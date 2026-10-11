package dev.duelcraft.dl;

import dev.duelcraft.cards.CardDb;
import dev.duelcraft.gen.VoiceTrigger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Which Duel Links line a character says (systems: voice_tables). Card lines come from dl/card_lines.tsv (character,
 * category 08/09/13, sub, line index, Master Duel card id; numbers only, built by tools/gen_voice_tables.py); a monster's
 * attack call (19) shares its summon chant's index. Everything else picks a random line of the trigger's category.
 */
public final class VoiceTables {
	private record CardLine(String category, String sub, String index, int cid) {
	}

	private final Map<Integer, List<CardLine>> cards = new HashMap<>();
	private final Random rng = new Random();

	public VoiceTables() throws IOException {
		try (InputStream in = VoiceTables.class.getResourceAsStream("/data/duelcraft/dl/card_lines.tsv")) {
			if (in == null) {
				return;
			}
			for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (l.isBlank() || l.startsWith("#")) {
					continue;
				}
				String[] f = l.split("\t");
				cards.computeIfAbsent(Integer.parseInt(f[0]), k -> new ArrayList<>()).add(new CardLine(f[1], f[2], f[3], Integer.parseInt(f[4].trim())));
			}
		}
	}

	public int cardLineCount() {
		return cards.values().stream().mapToInt(List::size).sum();
	}

	private static boolean same(CardDb db, int a, int b) {
		if (a == b) {
			return true;
		}
		if (db == null) {
			return false;
		}
		CardDb.CardInfo x = db.byCid(a), y = db.byCid(b);
		return x != null && y != null && x.passcode() != 0 && x.passcode() == y.passcode();
	}

	/** The character's line for a card, or null: category 13 summon, 19 attack, 08 spell/trap, 09 monster effect. */
	public DuelLinksData.Line cardLine(DuelLinksData d, CardDb db, int character, String category, int cid) {
		String tableCat = category.equals("19") ? "13" : category;
		for (CardLine c : cards.getOrDefault(character, List.of())) {
			if (c.category.equals(tableCat) && same(db, c.cid, cid)) {
				DuelLinksData.Line l = d.line(String.format("v%04d_%s_%s_%s", character, category, c.sub, c.index));
				if (l != null) {
					return l;
				}
			}
		}
		return null;
	}

	/** A random generic line for a trigger, falling back to any sub of the category. */
	public DuelLinksData.Line generic(DuelLinksData d, int character, VoiceTrigger t) {
		List<DuelLinksData.Line> ls = d.lines(character, t.category, t.sub);
		if (ls.isEmpty() && !"*".equals(t.sub)) {
			ls = d.lines(character, t.category, "00");
		}
		return ls.isEmpty() ? null : ls.get(rng.nextInt(ls.size()));
	}
}
