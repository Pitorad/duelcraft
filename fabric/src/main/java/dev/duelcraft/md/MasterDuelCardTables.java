package dev.duelcraft.md;

import dev.duelcraft.gen.MdAssets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Master Duel's own card tables (md_assets sheet: card_prop, card_indx, card_name, card_desc, en-us),
 * decrypted the way the game stores them: out[i] ^= ((i + k + 0x23D) * k ^ (i % 7)), then zlib. The key k
 * is found by trying all 256 values, so a game update that changes it keeps working.
 */
public final class MasterDuelCardTables {
	/** One Master Duel card: raw property words a1/a2 (see MODLOG) plus its English name and text. */
	public record MdCard(int cid, String name, String text, int a1, int a2) {
	}

	public final List<MdCard> cards;
	public final int key;

	private MasterDuelCardTables(List<MdCard> cards, int key) {
		this.cards = cards;
		this.key = key;
	}

	public static MasterDuelCardTables load(MasterDuelIndex index) throws IOException {
		int[] key = {-1};
		byte[] prop = table(index, MdAssets.CARD_PROP, key);
		byte[] indx = table(index, MdAssets.CARD_INDX, key);
		byte[] name = table(index, MdAssets.CARD_NAME, key);
		byte[] desc = table(index, MdAssets.CARD_DESC, key);
		ByteBuffer p = ByteBuffer.wrap(prop).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer x = ByteBuffer.wrap(indx).order(ByteOrder.LITTLE_ENDIAN);
		List<MdCard> out = new ArrayList<>(prop.length / 8);
		for (int i = 0; i < prop.length / 8; i++) {
			int a1 = p.getInt(i * 8), a2 = p.getInt(i * 8 + 4);
			int cid = a1 & 0xFFFF;
			if (cid == 0) {
				continue;
			}
			out.add(new MdCard(cid, cstr(name, x.getInt(i * 8)), cstr(desc, x.getInt(i * 8 + 4)), a1, a2));
		}
		return new MasterDuelCardTables(List.copyOf(out), key[0]);
	}

	private static String cstr(byte[] b, int off) {
		if (off < 0 || off >= b.length) {
			return "";
		}
		int end = off;
		while (end < b.length && b[end] != 0) {
			end++;
		}
		return new String(b, off, end - off, StandardCharsets.UTF_8);
	}

	/** Finds the en-us table in whichever data folder hash the current game build uses. */
	private static byte[] table(MasterDuelIndex index, String pattern, int[] key) throws IOException {
		String file = pattern.substring(pattern.lastIndexOf('/') + 1);
		String prefix = pattern.substring(0, pattern.indexOf("<hash>"));
		List<String> hits = index.paths(s -> s.startsWith(prefix) && s.endsWith("/en-us/" + file));
		if (hits.isEmpty()) {
			throw new IOException("Master Duel table not found: " + file);
		}
		Path bundle = index.bundle(hits.getLast());
		UnityBundle b = new UnityBundle(bundle);
		SerializedFile sf = new SerializedFile(b.readNode(b.serializedNode()));
		SerializedFile.ObjectInfo text = sf.find(49); // TextAsset
		if (text == null) {
			throw new IOException("no TextAsset in " + bundle);
		}
		Map<String, Object> obj = sf.read(text);
		byte[] enc = SerializedFile.bytes(obj.get("m_Script"));
		if (key[0] >= 0) {
			byte[] d = decrypt(enc, key[0]);
			if (d != null) {
				return d;
			}
		}
		for (int k = 0; k < 256; k++) {
			byte[] d = decrypt(enc, k);
			if (d != null) {
				key[0] = k;
				return d;
			}
		}
		throw new IOException("could not decrypt " + file);
	}

	static byte[] decrypt(byte[] enc, int k) {
		byte[] b = enc.clone();
		for (int i = 0; i < b.length; i++) {
			int v = (i + k + 0x23D) * k ^ (i % 7);
			b[i] ^= (byte) v;
		}
		Inflater inf = new Inflater();
		try {
			inf.setInput(b);
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(b.length * 4);
			byte[] buf = new byte[1 << 16];
			while (!inf.finished()) {
				int n = inf.inflate(buf);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
					return null;
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} catch (DataFormatException e) {
			return null;
		} finally {
			inf.end();
		}
	}
}
