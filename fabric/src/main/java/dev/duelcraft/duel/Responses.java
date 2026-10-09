package dev.duelcraft.duel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Encodes answers in the formats of the duel_prompts sheet's response column (shared by the AI and the duel screen). */
public final class Responses {
	private Responses() {
	}

	public static byte[] i32(int v) {
		return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
	}

	public static byte[] i64(long v) {
		return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
	}

	/** MSG_SELECT_CARD / TRIBUTE / SUM: i32 0, i32 count, i32 indices. */
	public static byte[] cards(List<Integer> idx) {
		ByteBuffer b = ByteBuffer.allocate(8 + 4 * idx.size()).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0).putInt(idx.size());
		for (int i : idx) {
			b.putInt(i);
		}
		return b.array();
	}

	/** MSG_SELECT_UNSELECT_CARD: i32 1, i32 index. */
	public static byte[] unselect(int index) {
		return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(index).array();
	}

	/** MSG_SELECT_PLACE / DISFIELD: (player, location, sequence) per zone. */
	public static byte[] places(List<int[]> zones) {
		ByteBuffer b = ByteBuffer.allocate(3 * zones.size());
		for (int[] z : zones) {
			b.put((byte) z[0]).put((byte) z[1]).put((byte) z[2]);
		}
		return b.array();
	}

	/** MSG_SELECT_COUNTER: i16 per card. */
	public static byte[] counters(int[] perCard) {
		ByteBuffer b = ByteBuffer.allocate(2 * perCard.length).order(ByteOrder.LITTLE_ENDIAN);
		for (int c : perCard) {
			b.putShort((short) c);
		}
		return b.array();
	}

	/** MSG_SORT_CARD / SORT_CHAIN: -1 keeps the default order. */
	public static byte[] keepOrder() {
		return new byte[] {(byte) -1};
	}

	public static byte[] order(int[] positions) {
		byte[] r = new byte[positions.length];
		for (int i = 0; i < r.length; i++) {
			r[i] = (byte) positions[i];
		}
		return r;
	}
}
