package dev.duelcraft.dl;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.Info;
import dev.duelcraft.md.SerializedFile;
import dev.duelcraft.md.UnityBundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A Duel Links voice clip as Ogg Vorbis (systems: dl_voice). The clip is an FMOD FSB5 stream with the Vorbis
 * codebooks stripped (only a CRC32 of the encoder settings is kept); the matching setup header comes from
 * dl/vorbis_headers.bin (generated from libvorbis by tools/gen_vorbis_headers.py), granule positions from JOrbis'
 * block sizes. Nothing is cached on disk: the player's Duel Links is read each time.
 */
public final class DuelLinksVoice {
	private record SetupHeader(int channels, int rate, int blockShort, int blockLong, byte[] packet) {
	}

	private static Map<Integer, SetupHeader> headers;
	private static final int[] FREQ = {0, 8000, 11000, 11025, 16000, 22050, 24000, 32000, 44100, 48000};

	private DuelLinksVoice() {
	}

	private static synchronized Map<Integer, SetupHeader> headers() throws IOException {
		if (headers == null) {
			Map<Integer, SetupHeader> m = new HashMap<>();
			try (InputStream in = DuelLinksVoice.class.getResourceAsStream("/data/duelcraft/dl/vorbis_headers.bin")) {
				if (in == null) {
					throw new IOException("vorbis_headers.bin is missing from the mod jar");
				}
				ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
				int n = b.getInt();
				for (int i = 0; i < n; i++) {
					int crc = b.getInt(), ch = b.get() & 0xFF, rate = b.getInt(), bs = b.getShort() & 0xFFFF, bl = b.getShort() & 0xFFFF;
					byte[] p = new byte[b.getInt()];
					b.get(p);
					m.put(crc, new SetupHeader(ch, rate, bs, bl, p));
				}
			}
			headers = m;
		}
		return headers;
	}

	/** The FSB5 bytes of a clip, from its bundle's .resource stream. */
	public static byte[] fsb(Path bundle) throws IOException {
		UnityBundle b = new UnityBundle(bundle);
		SerializedFile sf = new SerializedFile(b.readNode(b.serializedNode()));
		SerializedFile.ObjectInfo clip = sf.find(83); // AudioClip
		if (clip == null) {
			throw new IOException("no AudioClip in " + bundle);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> res = (Map<String, Object>) sf.read(clip).get("m_Resource");
		String src = SerializedFile.utf8(res.get("m_Source"));
		UnityBundle.Node node = b.node(src.substring(src.lastIndexOf('/') + 1));
		long off = ((Number) res.get("m_Offset")).longValue();
		int size = ((Number) res.get("m_Size")).intValue();
		return b.read(node.offset() + off, size);
	}

	public static byte[] ogg(Path bundle) throws IOException {
		return fsbToOgg(fsb(bundle));
	}

	/** FSB5 (one Vorbis sample) to an Ogg Vorbis file. */
	public static byte[] fsbToOgg(byte[] fsb) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(fsb).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != 0x35425346) { // "FSB5"
			throw new IOException("not an FSB5 stream");
		}
		int version = b.getInt(4), sampleHeaders = b.getInt(12), nameTable = b.getInt(16), dataSize = b.getInt(20), mode = b.getInt(24);
		if (mode != 15) {
			throw new IOException("FSB5 codec " + mode + " is not Vorbis");
		}
		int headerSize = version == 0 ? 64 : 60;
		b.position(headerSize);
		long raw = b.getLong();
		boolean next = (raw & 1) != 0;
		int freq = FREQ[(int) (raw >> 1 & 0xF)];
		int channels = (int) (raw >> 5 & 1) + 1;
		long dataOffset = (raw >> 6 & 0x0FFFFFFFL) * 16;
		long samples = raw >> 34 & 0x3FFFFFFFL;
		int crc = 0;
		boolean haveCrc = false;
		while (next) {
			int c = b.getInt();
			next = (c & 1) != 0;
			int size = c >>> 1 & 0xFFFFFF, type = c >>> 25 & 0x7F;
			int start = b.position();
			switch (type) {
				case 1 -> channels = b.get(start) & 0xFF;
				case 2 -> freq = b.getInt(start);
				case 11 -> {
					crc = b.getInt(start);
					haveCrc = true;
				}
				default -> {
				}
			}
			b.position(start + size);
		}
		if (!haveCrc) {
			throw new IOException("FSB5 Vorbis sample without VORBISDATA");
		}
		SetupHeader sh = headers().get(crc);
		if (sh == null) {
			throw new IOException("no Vorbis setup header for crc " + Integer.toUnsignedString(crc));
		}
		int dataStart = headerSize + sampleHeaders + nameTable;
		int pos = (int) (dataStart + dataOffset), end = dataStart + dataSize;

		ByteArrayOutputStream out = new ByteArrayOutputStream(fsb.length + 8192);
		StreamState os = new StreamState();
		os.init(1);
		Page page = new Page();
		Info info = new Info();
		Comment comment = new Comment();
		info.init();
		comment.init();
		Packet id = packet(idHeader(channels, freq, sh.blockShort, sh.blockLong), 0, 0, true);
		Packet cm = packet(commentHeader(), 1, 0, false);
		Packet setup = packet(sh.packet, 2, 0, false);
		for (Packet p : new Packet[] {id, cm, setup}) {
			if (info.synthesis_headerin(comment, p) != 0) {
				throw new IOException("Vorbis header rejected (crc " + Integer.toUnsignedString(crc) + ")");
			}
			os.packetin(p);
		}
		while (os.flush(page) != 0) {
			write(out, page);
		}
		long granule = 0;
		int prevBs = 0, packetNo = 3;
		while (pos + 2 <= end) {
			int len = (fsb[pos] & 0xFF) | (fsb[pos + 1] & 0xFF) << 8;
			if (len == 0) {
				break;
			}
			byte[] data = new byte[len];
			System.arraycopy(fsb, pos + 2, data, 0, len);
			pos += 2 + len;
			boolean last = pos + 2 > end || ((fsb[pos] & 0xFF) | (fsb[pos + 1] & 0xFF) << 8) == 0;
			Packet p = packet(data, packetNo++, 0, false);
			int bs = info.blocksize(p);
			if (bs <= 0) {
				throw new IOException("bad Vorbis packet");
			}
			granule = prevBs == 0 ? 0 : granule + (bs + prevBs) / 4;
			prevBs = bs;
			p.granulepos = last && samples > 0 ? samples : granule;
			p.e_o_s = last ? 1 : 0;
			os.packetin(p);
			while (os.pageout(page) != 0) {
				write(out, page);
			}
		}
		while (os.flush(page) != 0) {
			write(out, page);
		}
		return out.toByteArray();
	}

	private static Packet packet(byte[] data, int no, long granule, boolean bos) {
		Packet p = new Packet();
		p.packet_base = data;
		p.packet = 0;
		p.bytes = data.length;
		p.b_o_s = bos ? 1 : 0;
		p.e_o_s = 0;
		p.granulepos = granule;
		p.packetno = no;
		return p;
	}

	private static void write(ByteArrayOutputStream out, Page page) {
		out.write(page.header_base, page.header, page.header_len);
		out.write(page.body_base, page.body, page.body_len);
	}

	private static int log2(int v) {
		return 31 - Integer.numberOfLeadingZeros(v);
	}

	private static byte[] idHeader(int channels, int rate, int bs0, int bs1) {
		ByteBuffer b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 1).put("vorbis".getBytes(StandardCharsets.US_ASCII)).putInt(0).put((byte) channels).putInt(rate).putInt(0).putInt(0).putInt(0);
		b.put((byte) (log2(bs0) | log2(bs1) << 4)).put((byte) 1);
		return b.array();
	}

	private static byte[] commentHeader() {
		byte[] vendor = "DuelCraft".getBytes(StandardCharsets.US_ASCII);
		ByteBuffer b = ByteBuffer.allocate(7 + 4 + vendor.length + 4 + 1).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 3).put("vorbis".getBytes(StandardCharsets.US_ASCII)).putInt(vendor.length).put(vendor).putInt(0).put((byte) 1);
		return b.array();
	}
}
