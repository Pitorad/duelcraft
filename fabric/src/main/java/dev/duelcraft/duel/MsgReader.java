package dev.duelcraft.duel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Little-endian reader for ocgcore message bodies, used by the generated Prompts/Events decoders. */
public final class MsgReader {
	/** ocgcore loc_info: controller, location, sequence, position. */
	public record Loc(int con, int loc, int seq, int pos) {
	}

	private final ByteBuffer buf;

	public MsgReader(byte[] data, int offset, int length) {
		buf = ByteBuffer.wrap(data, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
	}

	public int u8() {
		return buf.get() & 0xFF;
	}

	public int u16() {
		return buf.getShort() & 0xFFFF;
	}

	public int u32() {
		return buf.getInt();
	}

	public int i32() {
		return buf.getInt();
	}

	public long u64() {
		return buf.getLong();
	}

	public Loc loc() {
		return new Loc(u8(), u8(), u32(), u32());
	}

	public <T> List<T> list(int count, Function<MsgReader, T> read) {
		List<T> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			out.add(read.apply(this));
		}
		return List.copyOf(out);
	}

	public int remaining() {
		return buf.remaining();
	}
}
