package dev.duelcraft.md;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a UnityFS asset bundle (format 6-8, Unity 6) from the player's Master Duel install: the header,
 * the LZ4 block/directory info and the nodes (a serialized file plus its .resS/.resource streams).
 * Blocks are decompressed lazily and only the ones a node needs.
 */
public final class UnityBundle {
	public record Node(long offset, long size, int flags, String path) {
	}

	private record Block(int uncompressed, int compressed, int flags, long fileOffset, long dataOffset) {
	}

	private final Path file;
	private final List<Block> blocks = new ArrayList<>();
	public final List<Node> nodes = new ArrayList<>();
	private final byte[][] cache;
	private final int[] valid;

	public UnityBundle(Path file) throws IOException {
		this.file = file;
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			byte[] head = new byte[(int) Math.min(raf.length(), 256)];
			raf.readFully(head);
			ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
			if (!"UnityFS".equals(cstr(b))) {
				throw new IOException("not a UnityFS bundle: " + file);
			}
			int version = b.getInt();
			cstr(b); // player version "5.x.x"
			cstr(b); // engine version
			b.getLong(); // total size
			int compressedInfo = b.getInt();
			int uncompressedInfo = b.getInt();
			int flags = b.getInt();
			long pos = b.position();
			if (version >= 7) {
				pos = align16(pos);
			}
			byte[] info = new byte[compressedInfo];
			long infoAt = (flags & 0x80) != 0 ? raf.length() - compressedInfo : pos;
			raf.seek(infoAt);
			raf.readFully(info);
			if ((flags & 0x80) == 0) {
				pos += compressedInfo;
			}
			byte[] dir = switch (flags & 0x3F) {
				case 0 -> info;
				case 2, 3 -> Lz4.decompress(info, 0, info.length, uncompressedInfo);
				default -> throw new IOException("unsupported directory compression " + (flags & 0x3F));
			};
			ByteBuffer d = ByteBuffer.wrap(dir).order(ByteOrder.BIG_ENDIAN);
			d.position(16); // uncompressed data hash
			int blockCount = d.getInt();
			if ((flags & 0x200) != 0) {
				pos = align16(pos);
			}
			long dataOffset = 0;
			for (int i = 0; i < blockCount; i++) {
				int u = d.getInt(), c = d.getInt(), f = d.getShort() & 0xFFFF;
				blocks.add(new Block(u, c, f, pos, dataOffset));
				pos += c;
				dataOffset += u;
			}
			int nodeCount = d.getInt();
			for (int i = 0; i < nodeCount; i++) {
				long off = d.getLong(), size = d.getLong();
				int f = d.getInt();
				nodes.add(new Node(off, size, f, cstr(d)));
			}
		}
		cache = new byte[blocks.size()][];
		valid = new int[blocks.size()];
	}

	private static long align16(long p) {
		return (p + 15) & ~15L;
	}

	static String cstr(ByteBuffer b) {
		int start = b.position();
		while (b.get() != 0) {
			// scan to NUL
		}
		byte[] s = new byte[b.position() - start - 1];
		b.get(start, s);
		return new String(s, StandardCharsets.UTF_8);
	}

	public Node node(String suffix) {
		for (Node n : nodes) {
			if (n.path.endsWith(suffix)) {
				return n;
			}
		}
		return null;
	}

	/** The first node that is a serialized file (not a .resS/.resource stream). */
	public Node serializedNode() {
		for (Node n : nodes) {
			if (!n.path.endsWith(".resS") && !n.path.endsWith(".resource")) {
				return n;
			}
		}
		return nodes.isEmpty() ? null : nodes.getFirst();
	}

	/** Bytes [start, start+len) of the bundle's decompressed data stream. */
	public byte[] read(long start, int len) throws IOException {
		byte[] out = new byte[len];
		int done = 0;
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			for (int i = 0; i < blocks.size() && done < len; i++) {
				Block bl = blocks.get(i);
				long bStart = bl.dataOffset, bEnd = bStart + bl.uncompressed;
				long want = start + done;
				if (want >= bEnd || want < bStart) {
					continue;
				}
				int from = (int) (want - bStart);
				int n = (int) Math.min(len - done, bl.uncompressed - from);
				byte[] data = block(raf, i, from + n);
				System.arraycopy(data, from, out, done, n);
				done += n;
			}
		}
		if (done != len) {
			throw new IOException("read past end of bundle " + file);
		}
		return out;
	}

	public byte[] readNode(Node n) throws IOException {
		return read(n.offset, Math.toIntExact(n.size));
	}

	/** Unity LZMA block: 5 property bytes (lc/lp/pb, dictionary size) then raw LZMA data. */
	private static byte[] lzma(byte[] comp, int outLen, int need) throws IOException {
		int props = comp[0] & 0xFF;
		int dict = (comp[1] & 0xFF) | (comp[2] & 0xFF) << 8 | (comp[3] & 0xFF) << 16 | (comp[4] & 0xFF) << 24;
		try (org.tukaani.xz.LZMAInputStream in = new org.tukaani.xz.LZMAInputStream(
			new java.io.ByteArrayInputStream(comp, 5, comp.length - 5), -1, (byte) props, dict)) { // -1: Unity writes an end marker; a known size makes xz-java reject it
			byte[] out = new byte[outLen];
			int n = 0;
			while (n < need) {
				int r = in.read(out, n, need - n);
				if (r < 0) {
					throw new IOException("short LZMA block");
				}
				n += r;
			}
			return out;
		}
	}

	/** Block i decompressed at least up to byte 'need' (LZMA blocks are decoded only as far as needed). */
	private byte[] block(RandomAccessFile raf, int i, int need) throws IOException {
		if (cache[i] != null && valid[i] >= need) {
			return cache[i];
		}
		Block bl = blocks.get(i);
		byte[] comp = new byte[bl.compressed];
		raf.seek(bl.fileOffset);
		raf.readFully(comp);
		byte[] data = switch (bl.flags & 0x3F) {
			case 0 -> comp;
			case 1 -> lzma(comp, bl.uncompressed, need);
			case 2, 3 -> Lz4.decompress(comp, 0, comp.length, bl.uncompressed);
			default -> throw new IOException("unsupported block compression " + (bl.flags & 0x3F) + " in " + file);
		};
		cache[i] = data;
		valid[i] = (bl.flags & 0x3F) == 1 ? need : bl.uncompressed;
		return data;
	}
}
