package dev.duelcraft.duel;

import dev.duelcraft.DuelCraft;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.cards.CardDb.CardInfo;
import dev.duelcraft.nativelib.NativeLib;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFM binding of ocgapi.h (EDOPro's duel engine, edo9300/ygopro-core, AGPL-3.0). Struct offsets follow the
 * x64 MSVC layout of ocgapi_types.h. Card data comes from CardDb (the player's Master Duel), scripts from
 * CardScripts. One OcgCore per world; duels are created with newDuel().
 */
public final class OcgCore {
	public static final int STATUS_END = 0, STATUS_AWAITING = 1, STATUS_CONTINUE = 2;
	public static final int LOCATION_DECK = 0x01, LOCATION_HAND = 0x02, LOCATION_MZONE = 0x04, LOCATION_SZONE = 0x08,
		LOCATION_GRAVE = 0x10, LOCATION_REMOVED = 0x20, LOCATION_EXTRA = 0x40, LOCATION_OVERLAY = 0x80;
	public static final int POS_FACEUP_ATTACK = 1, POS_FACEDOWN_ATTACK = 2, POS_FACEUP_DEFENSE = 4, POS_FACEDOWN_DEFENSE = 8;
	public static final int POS_FACEDOWN = POS_FACEDOWN_ATTACK | POS_FACEDOWN_DEFENSE;

	private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
	private static final java.lang.foreign.AddressLayout ADDR = ValueLayout.ADDRESS;

	private static OcgCore instance;
	private static final Map<Long, OcgCore> BY_PAYLOAD = new ConcurrentHashMap<>();

	private final CardDb db;
	private final CardScripts scripts;
	private final long payloadId;
	private final MethodHandle createDuel, destroyDuel, newCard, startDuel, process, getMessage, setResponse, loadScript, queryCount,
		queryLocation, queryField, getVersion;
	private final MemorySegment cardReaderStub, scriptReaderStub, logStub, cardDoneStub;
	private final Map<Integer, MemorySegment> setcodeArrays = new ConcurrentHashMap<>();

	public OcgCore(java.nio.file.Path dir, CardDb db, CardScripts scripts) {
		this.db = db;
		this.scripts = scripts;
		NativeLib.load(dir);
		createDuel = NativeLib.fn("OCG_CreateDuel", FunctionDescriptor.of(I32, ADDR, ADDR));
		destroyDuel = NativeLib.fn("OCG_DestroyDuel", FunctionDescriptor.ofVoid(ADDR));
		newCard = NativeLib.fn("OCG_DuelNewCard", FunctionDescriptor.ofVoid(ADDR, ADDR));
		startDuel = NativeLib.fn("OCG_StartDuel", FunctionDescriptor.ofVoid(ADDR));
		process = NativeLib.fn("OCG_DuelProcess", FunctionDescriptor.of(I32, ADDR));
		getMessage = NativeLib.fn("OCG_DuelGetMessage", FunctionDescriptor.of(ADDR, ADDR, ADDR));
		setResponse = NativeLib.fn("OCG_DuelSetResponse", FunctionDescriptor.ofVoid(ADDR, ADDR, I32));
		loadScript = NativeLib.fn("OCG_LoadScript", FunctionDescriptor.of(I32, ADDR, ADDR, I32, ADDR));
		queryCount = NativeLib.fn("OCG_DuelQueryCount", FunctionDescriptor.of(I32, ADDR, ValueLayout.JAVA_BYTE, I32));
		queryLocation = NativeLib.fn("OCG_DuelQueryLocation", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR));
		queryField = NativeLib.fn("OCG_DuelQueryField", FunctionDescriptor.of(ADDR, ADDR, ADDR));
		getVersion = NativeLib.fn("OCG_GetVersion", FunctionDescriptor.ofVoid(ADDR, ADDR));
		try {
			MethodHandles.Lookup l = MethodHandles.lookup();
			cardReaderStub = NativeLib.LINKER.upcallStub(l.findStatic(OcgCore.class, "cardReader",
				MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class)),
				FunctionDescriptor.ofVoid(ADDR, I32, ADDR), Arena.global());
			scriptReaderStub = NativeLib.LINKER.upcallStub(l.findStatic(OcgCore.class, "scriptReader",
				MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
				FunctionDescriptor.of(I32, ADDR, ADDR, ADDR), Arena.global());
			logStub = NativeLib.LINKER.upcallStub(l.findStatic(OcgCore.class, "logHandler",
				MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, int.class)),
				FunctionDescriptor.ofVoid(ADDR, ADDR, I32), Arena.global());
			cardDoneStub = NativeLib.LINKER.upcallStub(l.findStatic(OcgCore.class, "cardReaderDone",
				MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
				FunctionDescriptor.ofVoid(ADDR, ADDR), Arena.global());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		payloadId = System.identityHashCode(this) | 0x10000L;
		BY_PAYLOAD.put(payloadId, this);
		instance = this;
	}

	public static OcgCore get() {
		return instance;
	}

	public CardDb db() {
		return db;
	}

	public CardScripts scripts() {
		return scripts;
	}

	public int[] version() {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment ma = a.allocate(I32), mi = a.allocate(I32);
			getVersion.invokeExact(ma, mi);
			return new int[] {ma.get(I32, 0), mi.get(I32, 0)};
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	// ------------------------------------------------------------------ upcalls (engine -> Java)

	private static void cardReader(MemorySegment payload, int code, MemorySegment data) {
		try {
			cardReader0(payload, code, data);
		} catch (Throwable t) {
			DuelCraft.LOG.error("card reader failed for {}", code, t); // never let an exception unwind into native code
		}
	}

	private static void cardReader0(MemorySegment payload, int code, MemorySegment data) {
		OcgCore core = BY_PAYLOAD.get(payload.address());
		MemorySegment d = data.reinterpret(64);
		d.fill((byte) 0);
		d.set(I32, 0, code);
		CardInfo c = core == null ? null : core.db.byPasscode(code);
		if (c == null) {
			d.set(ADDR, 8, core == null ? MemorySegment.NULL : core.setcodes(code, new int[0]));
			return;
		}
		d.set(ADDR, 8, core.setcodes(code, c.setcodes()));
		d.set(I32, 16, c.type());
		d.set(I32, 20, c.level());
		d.set(I32, 24, c.attribute());
		d.set(I64, 32, c.race());
		d.set(I32, 40, c.atk());
		d.set(I32, 44, c.def());
		d.set(I32, 48, c.lscale());
		d.set(I32, 52, c.rscale());
		d.set(I32, 56, c.linkMarkers());
	}

	private static void cardReaderDone(MemorySegment payload, MemorySegment data) {
		// setcode arrays are cached for the life of the world; nothing to free
	}

	private static int scriptReader(MemorySegment payload, MemorySegment duel, MemorySegment name) {
		try {
			OcgCore core = BY_PAYLOAD.get(payload.address());
			if (core == null) {
				return 0;
			}
			String n = name.reinterpret(1024).getString(0);
			return core.loadScriptInto(duel, n) ? 1 : 0;
		} catch (Throwable t) {
			DuelCraft.LOG.error("script reader failed", t);
			return 0;
		}
	}

	private static void logHandler(MemorySegment payload, MemorySegment string, int type) {
		try {
			String s = string.reinterpret(1 << 16).getString(0);
			if (type == 0) {
				DuelCraft.LOG.warn("[ocgcore] {}", s);
			} else {
				DuelCraft.LOG.debug("[ocgcore:{}] {}", type, s);
			}
		} catch (Throwable ignored) {
			// logging must never unwind into native code
		}
	}

	private MemorySegment setcodes(int code, int[] sets) {
		return setcodeArrays.computeIfAbsent(code, k -> {
			MemorySegment m = Arena.global().allocate(2L * (sets.length + 1));
			for (int i = 0; i < sets.length; i++) {
				m.set(ValueLayout.JAVA_SHORT, 2L * i, (short) sets[i]);
			}
			return m;
		});
	}

	boolean loadScriptInto(MemorySegment duel, String name) {
		byte[] src = scripts.get(name);
		if (src == null) {
			return false;
		}
		try (Arena a = Arena.ofConfined()) {
			MemorySegment buf = a.allocate(src.length + 1L);
			MemorySegment.copy(src, 0, buf, ValueLayout.JAVA_BYTE, 0, src.length);
			MemorySegment nm = a.allocateFrom(name);
			return (int) loadScript.invokeExact(duel, buf, src.length, nm) != 0;
		} catch (Throwable t) {
			DuelCraft.LOG.error("loading script {} failed", name, t);
			return false;
		}
	}

	// ------------------------------------------------------------------ duel API (Java -> engine)

	/** A created duel. Not thread-safe: use from one thread. */
	public final class Duel implements AutoCloseable {
		private final MemorySegment ptr;
		private boolean closed;

		private Duel(MemorySegment ptr) {
			this.ptr = ptr;
		}

		public void newCard(int team, int duelist, int code, int con, int loc, int seq, int pos) {
			try (Arena a = Arena.ofConfined()) {
				MemorySegment info = a.allocate(24);
				info.set(ValueLayout.JAVA_BYTE, 0, (byte) team);
				info.set(ValueLayout.JAVA_BYTE, 1, (byte) duelist);
				info.set(I32, 4, code);
				info.set(ValueLayout.JAVA_BYTE, 8, (byte) con);
				info.set(I32, 12, loc);
				info.set(I32, 16, seq);
				info.set(I32, 20, pos);
				OcgCore.this.newCard.invokeExact(ptr, info);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		public void start() {
			try {
				startDuel.invokeExact(ptr);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		public int process() {
			try {
				return (int) OcgCore.this.process.invokeExact(ptr);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		public byte[] messages() {
			try (Arena a = Arena.ofConfined()) {
				MemorySegment len = a.allocate(I32);
				MemorySegment buf = (MemorySegment) getMessage.invokeExact(ptr, len);
				int n = len.get(I32, 0);
				return n == 0 ? new byte[0] : buf.reinterpret(n).toArray(ValueLayout.JAVA_BYTE);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		public void respond(byte[] response) {
			try (Arena a = Arena.ofConfined()) {
				MemorySegment buf = a.allocate(Math.max(1, response.length));
				MemorySegment.copy(response, 0, buf, ValueLayout.JAVA_BYTE, 0, response.length);
				setResponse.invokeExact(ptr, buf, response.length);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		public int count(int team, int loc) {
			try {
				return (int) queryCount.invokeExact(ptr, (byte) team, loc);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		/** OCG_DuelQueryLocation: u32 total size, then per slot (u16 0 = empty) or a card's query records. */
		public byte[] queryLocation(int flags, int con, int loc) {
			try (Arena a = Arena.ofConfined()) {
				MemorySegment info = a.allocate(20);
				info.set(I32, 0, flags);
				info.set(ValueLayout.JAVA_BYTE, 4, (byte) con);
				info.set(I32, 8, loc);
				MemorySegment len = a.allocate(I32);
				MemorySegment buf = (MemorySegment) queryLocation.invokeExact(ptr, len, info);
				int n = len.get(I32, 0);
				return n == 0 ? new byte[0] : buf.reinterpret(n).toArray(ValueLayout.JAVA_BYTE);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
		}

		@Override
		public void close() {
			if (!closed) {
				closed = true;
				try {
					destroyDuel.invokeExact(ptr);
				} catch (Throwable t) {
					throw new IllegalStateException(t);
				}
			}
		}
	}

	public record Player(int startingLp, int startingDraw, int drawPerTurn) {
	}

	public Duel newDuel(long[] seed, long flags, Player p1, Player p2) {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment o = a.allocate(136);
			for (int i = 0; i < 4; i++) {
				o.set(I64, 8L * i, seed[i]);
			}
			o.set(I64, 32, flags);
			o.set(I32, 40, p1.startingLp);
			o.set(I32, 44, p1.startingDraw);
			o.set(I32, 48, p1.drawPerTurn);
			o.set(I32, 52, p2.startingLp);
			o.set(I32, 56, p2.startingDraw);
			o.set(I32, 60, p2.drawPerTurn);
			MemorySegment payload = MemorySegment.ofAddress(payloadId);
			o.set(ADDR, 64, cardReaderStub);
			o.set(ADDR, 72, payload);
			o.set(ADDR, 80, scriptReaderStub);
			o.set(ADDR, 88, payload);
			o.set(ADDR, 96, logStub);
			o.set(ADDR, 104, payload);
			o.set(ADDR, 112, cardDoneStub);
			o.set(ADDR, 120, payload);
			o.set(ValueLayout.JAVA_BYTE, 128, (byte) 0);
			MemorySegment out = a.allocate(ADDR);
			int rc = (int) createDuel.invokeExact(out, o);
			if (rc != 0) {
				throw new IllegalStateException("OCG_CreateDuel failed: " + rc);
			}
			MemorySegment ptr = out.get(ADDR, 0);
			Duel d = new Duel(ptr);
			for (String base : new String[] {"constant.lua", "utility.lua"}) {
				if (!loadScriptInto(ptr, base)) {
					d.close();
					throw new IllegalStateException("could not load " + base);
				}
			}
			return d;
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	public static String utf8(byte[] b) {
		return new String(b, StandardCharsets.UTF_8);
	}
}
