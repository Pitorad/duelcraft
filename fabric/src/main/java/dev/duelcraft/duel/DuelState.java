package dev.duelcraft.duel;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The field after a processing step, read with OCG_DuelQueryLocation for both players and every location.
 * forViewer() hides what that player may not see (opponent's hand and face-down cards, both decks); the
 * result is what DuelStatePayload carries.
 */
public final class DuelState {
	public static final int QUERY_CODE = 0x1, QUERY_POSITION = 0x2, QUERY_TYPE = 0x8, QUERY_LEVEL = 0x10, QUERY_RANK = 0x20,
		QUERY_ATTACK = 0x100, QUERY_DEFENSE = 0x200, QUERY_OVERLAY_CARD = 0x10000, QUERY_COUNTERS = 0x20000, QUERY_OWNER = 0x40000,
		QUERY_IS_PUBLIC = 0x100000, QUERY_LINK = 0x800000, QUERY_END = 0x80000000;
	public static final int FLAGS = QUERY_CODE | QUERY_POSITION | QUERY_TYPE | QUERY_LEVEL | QUERY_RANK | QUERY_ATTACK | QUERY_DEFENSE
		| QUERY_OVERLAY_CARD | QUERY_COUNTERS | QUERY_OWNER | QUERY_IS_PUBLIC | QUERY_LINK;
	public static final int[] LOCATIONS = {OcgCore.LOCATION_DECK, OcgCore.LOCATION_HAND, OcgCore.LOCATION_MZONE, OcgCore.LOCATION_SZONE,
		OcgCore.LOCATION_GRAVE, OcgCore.LOCATION_REMOVED, OcgCore.LOCATION_EXTRA};

	/** One card slot. code 0 = hidden from this viewer; empty = no card in this zone. */
	public record FieldCard(int controller, int location, int seq, boolean empty, int code, int position, int type, int level, int rank,
		int link, int atk, int def, int overlays, int counters, boolean isPublic) {
		public boolean faceDown() {
			return (position & OcgCore.POS_FACEDOWN) != 0;
		}

		public boolean defense() {
			return (position & (OcgCore.POS_FACEUP_DEFENSE | OcgCore.POS_FACEDOWN_DEFENSE)) != 0;
		}

		FieldCard hidden() {
			return new FieldCard(controller, location, seq, empty, 0, position, 0, 0, 0, 0, 0, 0, overlays, 0, false);
		}
	}

	public int turn, turnPlayer, phase;
	public final int[] lp = new int[2];
	public final List<FieldCard> cards = new ArrayList<>();

	public static DuelState query(OcgCore.Duel duel, int turn, int turnPlayer, int phase, int lp0, int lp1) {
		DuelState s = new DuelState();
		s.turn = turn;
		s.turnPlayer = turnPlayer;
		s.phase = phase;
		s.lp[0] = lp0;
		s.lp[1] = lp1;
		for (int p = 0; p < 2; p++) {
			for (int loc : LOCATIONS) {
				byte[] q = duel.queryLocation(FLAGS, p, loc);
				parse(s.cards, q, p, loc);
			}
		}
		return s;
	}

	static void parse(List<FieldCard> out, byte[] q, int con, int loc) {
		if (q.length < 4) {
			return;
		}
		ByteBuffer b = ByteBuffer.wrap(q).order(ByteOrder.LITTLE_ENDIAN);
		int total = b.getInt();
		int end = Math.min(q.length, 4 + total);
		int seq = 0;
		while (b.position() < end) {
			int size = b.getShort() & 0xFFFF;
			if (size == 0) {
				out.add(new FieldCard(con, loc, seq++, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false));
				continue;
			}
			int code = 0, position = 0, type = 0, level = 0, rank = 0, link = 0, atk = 0, def = 0, overlays = 0, counters = 0;
			boolean pub = false;
			b.position(b.position() - 2);
			while (true) {
				int sz = b.getShort() & 0xFFFF;
				int flag = b.getInt();
				int valStart = b.position();
				switch (flag) {
					case QUERY_CODE -> code = b.getInt();
					case QUERY_POSITION -> position = b.getInt();
					case QUERY_TYPE -> type = b.getInt();
					case QUERY_LEVEL -> level = b.getInt();
					case QUERY_RANK -> rank = b.getInt();
					case QUERY_ATTACK -> atk = b.getInt();
					case QUERY_DEFENSE -> def = b.getInt();
					case QUERY_LINK -> link = b.getInt();
					case QUERY_OVERLAY_CARD -> overlays = b.getInt();
					case QUERY_COUNTERS -> {
						int n = b.getInt();
						for (int i = 0; i < n; i++) {
							counters += b.getInt() >>> 16;
						}
					}
					case QUERY_IS_PUBLIC -> pub = b.get() != 0;
					default -> {
					}
				}
				b.position(valStart + sz - 4);
				if (flag == QUERY_END) {
					break;
				}
			}
			out.add(new FieldCard(con, loc, seq++, false, code, position, type, level, rank, link, atk, def, overlays, counters, pub));
		}
	}

	public List<FieldCard> at(int con, int loc) {
		return cards.stream().filter(c -> c.controller == con && c.location == loc).toList();
	}

	public FieldCard at(int con, int loc, int seq) {
		for (FieldCard c : cards) {
			if (c.controller == con && c.location == loc && c.seq == seq) {
				return c;
			}
		}
		return null;
	}

	/** Copy with everything the viewer may not see hidden. viewer -1 = spectator. */
	public DuelState forViewer(int viewer) {
		DuelState s = new DuelState();
		s.turn = turn;
		s.turnPlayer = turnPlayer;
		s.phase = phase;
		s.lp[0] = lp[0];
		s.lp[1] = lp[1];
		for (FieldCard c : cards) {
			boolean visible = !c.empty && switch (c.location) {
				case OcgCore.LOCATION_DECK -> false;
				case OcgCore.LOCATION_HAND -> c.controller == viewer || c.isPublic;
				case OcgCore.LOCATION_EXTRA -> c.controller == viewer || !c.faceDown();
				case OcgCore.LOCATION_MZONE, OcgCore.LOCATION_SZONE, OcgCore.LOCATION_REMOVED -> !c.faceDown() || c.controller == viewer;
				default -> true;
			};
			s.cards.add(visible || c.empty ? c : c.hidden());
		}
		return s;
	}

	public byte[] encodeCards() {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try (DataOutputStream o = new DataOutputStream(bo)) {
			o.writeShort(cards.size());
			for (FieldCard c : cards) {
				o.writeByte(c.controller);
				o.writeByte(c.location);
				o.writeByte(c.seq);
				o.writeBoolean(c.empty);
				if (!c.empty) {
					o.writeInt(c.code);
					o.writeByte(c.position);
					o.writeInt(c.type);
					o.writeByte(c.level);
					o.writeByte(c.rank);
					o.writeByte(c.link);
					o.writeInt(c.atk);
					o.writeInt(c.def);
					o.writeByte(c.overlays);
					o.writeShort(c.counters);
					o.writeBoolean(c.isPublic);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bo.toByteArray();
	}

	public static List<FieldCard> decodeCards(byte[] data) {
		List<FieldCard> out = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
			int n = in.readUnsignedShort();
			for (int i = 0; i < n; i++) {
				int con = in.readUnsignedByte(), loc = in.readUnsignedByte(), seq = in.readUnsignedByte();
				boolean empty = in.readBoolean();
				if (empty) {
					out.add(new FieldCard(con, loc, seq, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false));
				} else {
					out.add(new FieldCard(con, loc, seq, false, in.readInt(), in.readUnsignedByte(), in.readInt(), in.readUnsignedByte(),
						in.readUnsignedByte(), in.readUnsignedByte(), in.readInt(), in.readInt(), in.readUnsignedByte(), in.readUnsignedShort(),
						in.readBoolean()));
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return out;
	}
}
