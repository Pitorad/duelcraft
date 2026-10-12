package dev.duelcraft.cards;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What an activated effect does (systems: effect_hints). An activation prompt only carries aux.Stringid(id, n) =
 * (passcode &lt;&lt; 20) | n; md/effect_hints.tsv (tools/gen_effect_hints.py, from the card scripts) gives each one a
 * label ("Special Summon this card from GY") and tags (categories, kind, where, when). The duel screen shows them on
 * the action and lights up the sentence of the card text the effect most likely is.
 */
public final class EffectHints {
	public record Hint(int passcode, int n, String label, List<String> tags) {
	}

	private static Map<Long, Hint> hints;
	private static Map<Integer, List<Hint>> byCard;

	private EffectHints() {
	}

	private static synchronized void load() {
		if (hints != null) {
			return;
		}
		hints = new HashMap<>();
		byCard = new HashMap<>();
		try (InputStream in = EffectHints.class.getResourceAsStream("/data/duelcraft/md/effect_hints.tsv")) {
			if (in == null) {
				return;
			}
			for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (l.isBlank() || l.startsWith("#")) {
					continue;
				}
				String[] f = l.split("\t", -1);
				Hint h = new Hint(Integer.parseInt(f[0]), Integer.parseInt(f[1]), f.length > 2 ? f[2].trim() : "",
					f.length > 3 && !f[3].isBlank() ? List.of(f[3].trim().split(",")) : List.of());
				hints.put(key(h.passcode, h.n), h);
				byCard.computeIfAbsent(h.passcode, k -> new ArrayList<>()).add(h);
			}
		} catch (IOException | RuntimeException e) {
			// no hints: the duel screen falls back to "effect N"
		}
	}

	private static long key(int passcode, int n) {
		return ((long) passcode << 8) | (n & 0xFF);
	}

	/** The hint for a Stringid-style description ((passcode &lt;&lt; 20) | n), or null. */
	public static Hint of(long desc) {
		if ((desc >>> 20) == 0) {
			return null;
		}
		load();
		return hints.get(key((int) (desc >>> 20), (int) (desc & 0xFFFFF)));
	}

	public static List<Hint> forCard(int passcode) {
		load();
		return byCard.getOrDefault(passcode, List.of());
	}

	// ------------------------------------------------------------------ wording

	private static final Map<String, String> WHAT = new LinkedHashMap<>();
	private static final Map<String, String> KIND = Map.of("quick", "Quick Effect", "trigger", "Trigger");
	private static final Map<String, String> WHERE = new LinkedHashMap<>();
	private static final Map<String, String> WHEN = new LinkedHashMap<>();

	static {
		for (String[] w : new String[][] {{"special summon", "Special Summon"}, {"fusion summon", "Fusion Summon"}, {"normal summon", "Normal Summon"},
			{"add to hand", "Add to hand"}, {"destroy", "Destroy"}, {"banish", "Banish"}, {"send to gy", "Send to GY"}, {"return to deck", "Return to Deck"},
			{"to extra deck", "Return to Extra Deck"}, {"draw", "Draw"}, {"damage", "Inflict damage"}, {"gain lp", "Gain LP"}, {"change atk", "Change ATK"},
			{"change def", "Change DEF"}, {"change level", "Change Level"}, {"negate activation", "Negate activation"}, {"negate effect", "Negate effect"},
			{"negate summon", "Negate Summon"}, {"equip", "Equip"}, {"take control", "Take control"}, {"change position", "Change position"},
			{"set", "Set"}, {"discard", "Discard"}, {"mill", "Mill"}, {"tribute", "Tribute"}, {"token", "Token"}, {"counter", "Counters"},
			{"coin toss", "Coin toss"}, {"dice roll", "Dice roll"}}) {
			WHAT.put(w[0], w[1]);
		}
		for (String[] w : new String[][] {{"gy", "from GY"}, {"hand", "from hand"}, {"banished", "while banished"}, {"field zone", "Field Zone"},
			{"pendulum zone", "Pendulum Zone"}, {"deck", "from Deck"}}) {
			WHERE.put(w[0], w[1]);
		}
		for (String[] w : new String[][] {{"summoned", "when Summoned"}, {"flipped", "when flipped"}, {"destroyed", "when destroyed"},
			{"destroys by battle", "after destroying by battle"}, {"sent to gy", "when sent to GY"}, {"got banished", "when banished"},
			{"attack", "on an attack"}, {"activation", "in response to an activation"}, {"end phase", "End Phase"}, {"standby phase", "Standby Phase"},
			{"used as material", "when used as material"}, {"leaves the field", "when it leaves the field"}, {"added to hand", "when added to hand"},
			{"battle damage", "on battle damage"}}) {
			WHEN.put(w[0], w[1]);
		}
	}

	/** What the effect does: its label, else its categories ("Special Summon, Destroy"), else "". */
	public static String what(Hint h) {
		if (h == null) {
			return "";
		}
		if (!h.label.isEmpty()) {
			return h.label;
		}
		List<String> w = new ArrayList<>();
		for (String t : h.tags) {
			String s = WHAT.get(t);
			if (s != null) {
				w.add(s);
			}
		}
		return String.join(", ", w);
	}

	/** The tags' plain words ("Discard", "Send to GY"), in tag order. */
	public static List<String> whatWords(Hint h) {
		List<String> w = new ArrayList<>();
		if (h != null) {
			for (String t : h.tags) {
				if (WHAT.containsKey(t)) {
					w.add(WHAT.get(t));
				}
			}
		}
		return w;
	}

	/** Kind, where and when: "Quick Effect · from GY · when Summoned" (empty for a plain Ignition effect). */
	public static String details(Hint h) {
		if (h == null) {
			return "";
		}
		List<String> d = new ArrayList<>();
		for (String t : h.tags) {
			if (KIND.containsKey(t)) {
				d.add(KIND.get(t));
			}
		}
		for (String t : h.tags) {
			if (WHERE.containsKey(t)) {
				d.add(WHERE.get(t));
			}
		}
		for (String t : h.tags) {
			if (WHEN.containsKey(t)) {
				d.add(WHEN.get(t));
			}
		}
		return String.join(" · ", d);
	}

	// ------------------------------------------------------------------ finding the effect in the card text

	/** Words in the card text that go with a tag. */
	private static final Map<String, String[]> TEXT = new HashMap<>();

	static {
		Object[][] t = {{"special summon", "special summon"}, {"fusion summon", "fusion summon"}, {"normal summon", "normal summon"},
			{"add to hand", "to your hand", "add"}, {"search", "deck"}, {"destroy", "destroy"}, {"banish", "banish"}, {"send to gy", "send", "gy"},
			{"return to deck", "deck", "shuffle"}, {"draw", "draw"}, {"damage", "damage"}, {"gain lp", "gain", "lp"}, {"change atk", "atk"},
			{"change def", "def"}, {"change level", "level"}, {"negate activation", "negate the activation"}, {"negate effect", "negate"},
			{"negate summon", "negate the summon"}, {"equip", "equip"}, {"take control", "control"}, {"change position", "position"}, {"set", "set"},
			{"discard", "discard"}, {"mill", "send", "deck"}, {"tribute", "tribute"}, {"token", "token"}, {"counter", "counter"},
			{"coin toss", "coin"}, {"dice roll", "die"}, {"quick", "quick effect"}, {"gy", "gy"}, {"hand", "hand"}, {"banished", "banished"},
			{"summoned", "summoned"}, {"flipped", "flip"}, {"destroyed", "destroyed"}, {"destroys by battle", "destroys"}, {"sent to gy", "sent to the gy"},
			{"got banished", "banished"}, {"attack", "attack"}, {"activation", "activated"}, {"end phase", "end phase"},
			{"standby phase", "standby phase"}, {"used as material", "material"}, {"leaves the field", "leaves the field"}};
		for (Object[] row : t) {
			String[] words = new String[row.length - 1];
			for (int i = 1; i < row.length; i++) {
				words[i - 1] = (String) row[i];
			}
			TEXT.put((String) row[0], words);
		}
	}

	/** Sentences of the card text as [start, end) offsets. */
	static List<int[]> sentences(String text) {
		List<int[]> out = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean end = c == '\n' || (c == '.' && (i + 1 == text.length() || text.charAt(i + 1) == ' ' || text.charAt(i + 1) == '\n')
				&& !inQuotes(text, i));
			if (end || i + 1 == text.length()) {
				int e = c == '\n' ? i : i + 1;
				if (e > start && !text.substring(start, e).isBlank()) {
					out.add(new int[] {start, e});
				}
				start = i + 1;
				while (start < text.length() && text.charAt(start) == ' ') {
					start++;
				}
			}
		}
		return out;
	}

	private static boolean inQuotes(String text, int i) {
		int q = 0;
		for (int k = 0; k < i; k++) {
			if (text.charAt(k) == '"') {
				q++;
			}
		}
		return (q & 1) == 1;
	}

	private static boolean activatable(String s) {
		String l = s.toLowerCase(Locale.ROOT);
		return l.contains("you can") || l.contains("quick effect") || l.startsWith("activate") || l.contains(": ");
	}

	/**
	 * The [start, end) of the card-text sentence this effect most likely is, or null: the activatable sentences
	 * scored by the effect's tags, with its place among the card's effects as the tie-breaker.
	 */
	public static int[] sentenceFor(String text, Hint h) {
		if (text == null || h == null) {
			return null;
		}
		// Pendulum cards: Pendulum Zone effects are after "[Pendulum Effect]", the monster's own before it
		int pend = text.indexOf("[Pendulum Effect]");
		boolean inPend = h.tags.contains("pendulum zone");
		List<int[]> all = sentences(text), cands = new ArrayList<>();
		for (int[] s : all) {
			if (pend >= 0 && (s[0] > pend) != inPend) {
				continue;
			}
			if (activatable(text.substring(s[0], s[1]))) {
				cands.add(s);
			}
		}
		if (cands.isEmpty()) {
			return null;
		}
		List<Hint> card = new ArrayList<>();
		for (Hint o : forCard(h.passcode)) {
			if (pend < 0 || o.tags.contains("pendulum zone") == inPend) {
				card.add(o);
			}
		}
		int rank = 0;
		for (Hint o : card) {
			if (o.n < h.n) {
				rank++;
			}
		}
		int[] best = null;
		double bestScore = 0;
		for (int i = 0; i < cands.size(); i++) {
			String s = text.substring(cands.get(i)[0], cands.get(i)[1]).toLowerCase(Locale.ROOT);
			double score = 0;
			for (String t : h.tags) {
				for (String w : TEXT.getOrDefault(t, new String[0])) {
					if (s.contains(w)) {
						score += 1;
						break;
					}
				}
			}
			if (card.size() == cands.size() && i == rank) {
				score += 1.5; // as many effects as sentences: the order usually lines up
			} else if (i == rank) {
				score += 0.5;
			}
			if (score > bestScore) {
				bestScore = score;
				best = cands.get(i);
			}
		}
		return best;
	}
}
