package dev.duelcraft.md;

/** LZ4 block-format decompression (no frame), as used by UnityFS block and directory info. */
public final class Lz4 {
	private Lz4() {
	}

	public static byte[] decompress(byte[] src, int off, int len, int outLen) {
		byte[] dst = new byte[outLen];
		int sp = off, end = off + len, dp = 0;
		while (sp < end) {
			int token = src[sp++] & 0xFF;
			int lit = token >>> 4;
			if (lit == 15) {
				int b;
				do {
					b = src[sp++] & 0xFF;
					lit += b;
				} while (b == 255);
			}
			System.arraycopy(src, sp, dst, dp, lit);
			sp += lit;
			dp += lit;
			if (sp >= end) {
				break; // last sequence has literals only
			}
			int offset = (src[sp] & 0xFF) | (src[sp + 1] & 0xFF) << 8;
			sp += 2;
			int match = token & 15;
			if (match == 15) {
				int b;
				do {
					b = src[sp++] & 0xFF;
					match += b;
				} while (b == 255);
			}
			match += 4;
			int from = dp - offset;
			if (offset <= 0 || from < 0) {
				throw new IllegalStateException("corrupt LZ4 data");
			}
			for (int i = 0; i < match; i++) {
				dst[dp++] = dst[from + i]; // overlapping copies are intended
			}
		}
		if (dp != outLen) {
			throw new IllegalStateException("LZ4 size mismatch: " + dp + " != " + outLen);
		}
		return dst;
	}
}
