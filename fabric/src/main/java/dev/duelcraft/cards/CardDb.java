package dev.duelcraft.cards;

import dev.duelcraft.gen.MdEncodings;
import dev.duelcraft.md.MasterDuelCardTables;
import dev.duelcraft.md.MasterDuelCardTables.MdCard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every card the player's Master Duel knows, with its engine data. Names, text and stats come from Master
 * Duel; cards.tsv (shipped, numbers only) adds the EDOPro passcode, archetype setcodes and the few stat
 * overrides; the md encoding sheets turn Master Duel's kind/race/attribute/link codes into engine flags.
 */
public final class CardDb {
	public static final int TYPE_MONSTER = 0x1, TYPE_SPELL = 0x2, TYPE_TRAP = 0x4, TYPE_NORMAL = 0x10, TYPE_EFFECT = 0x20,
		TYPE_FUSION = 0x40, TYPE_SYNCHRO = 0x2000, TYPE_TOKEN = 0x4000, TYPE_XYZ = 0x800000, TYPE_PENDULUM = 0x1000000,
		TYPE_LINK = 0x4000000, TYPE_EXTRA = TYPE_FUSION | TYPE_SYNCHRO | TYPE_XYZ | TYPE_LINK;

	/** passcode 0 = not playable in duels yet (no rules script mapping). */
	public record CardInfo(int cid, String name, String text, int passcode, int type, int level, int attribute, long race, int atk, int def,
		int lscale, int rscale, int linkMarkers, int[] setcodes, String frame) {
		public boolean isMonster() {
			return (type & TYPE_MONSTER) != 0;
		}

		public boolean isExtraDeck() {
			return (type & TYPE_MONSTER) != 0 && (type & TYPE_EXTRA) != 0;
		}

		public boolean playable() {
			return passcode != 0;
		}
	}

	private final Map<Integer, CardInfo> byCid = new LinkedHashMap<>();
	private final Map<Integer, CardInfo> byPasscode = new HashMap<>();
	public final int masterDuelKey;

	public CardDb(MasterDuelCardTables tables) throws IOException {
		masterDuelKey = tables.key;
		Map<Integer, String[]> map = new HashMap<>();
		try (InputStream in = CardDb.class.getResourceAsStream("/data/duelcraft/md/cards.tsv")) {
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] f = line.split("\t", -1);
				map.put(Integer.parseInt(f[0]), f);
			}
		}
		for (MdCard c : tables.cards) {
			if (byCid.containsKey(c.cid())) {
				continue;
			}
			CardInfo info = convert(c, map.get(c.cid()));
			byCid.put(c.cid(), info);
			if (info.passcode != 0) {
				byPasscode.putIfAbsent(info.passcode, info);
			}
		}
	}

	static CardInfo convert(MdCard c, String[] row) {
		int a1 = c.a1(), a2 = c.a2();
		int kind = (a1 >>> 16) & 0x3F, icon = (a2 >>> 18) & 7;
		int[] k = MdEncodings.KINDS.get(kind << 8 | icon);
		if (k == null || (k[0] & TYPE_MONSTER) != 0) {
			int[] mk = MdEncodings.KINDS.get(kind << 8 | 0xFF);
			if (mk != null) {
				k = mk;
			}
		}
		int type = k == null ? 0 : k[0];
		String frame = k == null ? "normal" : MdEncodings.FRAMES[k[1]];
		int atk = 0, def = 0, level = 0, attribute = 0, lscale = 0, rscale = 0, link = 0;
		long race = 0;
		if ((type & TYPE_MONSTER) != 0) {
			int ra = a2 & 0x1FF, rd = (a2 >>> 9) & 0x1FF;
			atk = ra == 0x1FF ? -2 : ra * 10;
			level = (a1 >>> 26) & 0xF;
			race = MdEncodings.RACES.getOrDefault((a2 >>> 21) & 0x1F, 0L);
			attribute = MdEncodings.ATTRIBUTES.getOrDefault((a1 >>> 22) & 0xF, 0);
			if ((type & TYPE_LINK) != 0) {
				for (int b = 0; b < MdEncodings.LINK_MARKERS.length; b++) {
					if ((rd >>> b & 1) != 0) {
						link |= 1 << MdEncodings.LINK_MARKERS[b];
					}
				}
			} else {
				def = rd == 0x1FF ? -2 : rd * 10;
			}
			if ((type & TYPE_PENDULUM) != 0) {
				lscale = rscale = (a2 >>> 27) & 0xF;
			}
		}
		int passcode = 0;
		int[] setcodes = new int[0];
		if (row != null) {
			passcode = Integer.parseInt(row[1]);
			if (!row[2].isEmpty()) {
				String[] s = row[2].split(",");
				setcodes = new int[s.length];
				for (int i = 0; i < s.length; i++) {
					setcodes[i] = Integer.parseInt(s[i], 16);
				}
			}
			if (row.length > 3 && !row[3].isEmpty()) {
				for (String ov : row[3].split(",")) {
					String[] kv = ov.split("=");
					int v = Integer.parseInt(kv[1]);
					switch (kv[0]) {
						case "type" -> type = v;
						case "atk" -> atk = v;
						case "def" -> def = v;
						case "level" -> level = v;
						case "race" -> race = v;
						case "attribute" -> attribute = v;
						case "lscale" -> lscale = v;
						case "rscale" -> rscale = v;
						case "link" -> link = v;
						default -> {
						}
					}
				}
			}
		}
		return new CardInfo(c.cid(), c.name(), c.text(), passcode, type, level, attribute, race, atk, def, lscale, rscale, link, setcodes, frame);
	}

	public CardInfo byCid(int cid) {
		return byCid.get(cid);
	}

	/** Other Master Duel cids of the same card (alternate arts), by passcode or else by name. */
	public int[] alternates(int cid) {
		CardInfo c = byCid.get(cid);
		if (c == null) {
			return new int[0];
		}
		return byCid.values().stream().filter(o -> o.cid != cid && (c.passcode != 0 ? o.passcode == c.passcode : o.name.equals(c.name)))
			.mapToInt(CardInfo::cid).toArray();
	}

	public CardInfo byPasscode(int passcode) {
		return byPasscode.get(passcode);
	}

	public Collection<CardInfo> all() {
		return byCid.values();
	}

	public List<CardInfo> playable() {
		List<CardInfo> out = new ArrayList<>();
		for (CardInfo c : byCid.values()) {
			if (c.playable() && (c.type & TYPE_TOKEN) == 0) {
				out.add(c);
			}
		}
		return out;
	}

	public int size() {
		return byCid.size();
	}
}
