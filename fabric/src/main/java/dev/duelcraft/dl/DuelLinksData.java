package dev.duelcraft.dl;

import dev.duelcraft.md.CardArtCache;
import dev.duelcraft.md.MasterDuelIndex;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player's Yu-Gi-Oh! Duel Links data (systems: dl_data): its asset index (same reader as Master Duel), the
 * characters that have both a face portrait and voice clips, each character's clips by category, and their images.
 */
public final class DuelLinksData {
	private static final Pattern VOICE = Pattern.compile("/v(\\d{4})_(\\d{2})_(\\d{2})_(\\d{2})_(\\d)\\.wav$");
	private static final Pattern FACE = Pattern.compile("characters/sn(\\d{4})/images/en-us/chara007_([1-6])\\.png$");

	/** One spoken line: one or more clip parts played back to back. */
	public record Line(int character, String category, String sub, String index, List<String> clips) {
		public String key() {
			return String.format("v%04d_%s_%s_%s", character, category, sub, index);
		}
	}

	private final MasterDuelIndex index;
	private final Map<String, String> clipPaths = new TreeMap<>();
	/** character -> "cat_sub" -> lines. */
	private final Map<Integer, Map<String, List<Line>>> lines = new TreeMap<>();
	private final Map<Integer, boolean[]> faces = new TreeMap<>();

	private DuelLinksData(MasterDuelIndex index) {
		this.index = index;
		Map<String, List<String>> parts = new TreeMap<>();
		for (String p : index.paths(s -> s.contains("/audioclip/voice/") || s.contains("characters/sn"))) {
			Matcher v = VOICE.matcher(p);
			if (v.find() && (p.contains("/en-us/") || p.startsWith("assets/en-us/"))) {
				String clip = p.substring(p.lastIndexOf('/') + 1, p.length() - 4);
				clipPaths.putIfAbsent(clip, p);
				parts.computeIfAbsent(clip.substring(0, clip.length() - 2), k -> new ArrayList<>()).add(clip);
				continue;
			}
			Matcher f = FACE.matcher(p);
			if (f.find()) {
				faces.computeIfAbsent(Integer.parseInt(f.group(1)), k -> new boolean[7])[Integer.parseInt(f.group(2))] = true;
			}
		}
		for (Map.Entry<String, List<String>> e : parts.entrySet()) {
			String[] k = e.getKey().split("_"); // v0101, 13, 00, 18
			int ch = Integer.parseInt(k[0].substring(1));
			List<String> clips = new ArrayList<>(e.getValue());
			clips.sort(null);
			lines.computeIfAbsent(ch, x -> new TreeMap<>()).computeIfAbsent(k[1] + "_" + k[2], x -> new ArrayList<>())
				.add(new Line(ch, k[1], k[2], k[3], List.copyOf(clips)));
		}
	}

	public static DuelLinksData load(DuelLinksLocator.Install install, Path cache, IntConsumer progress) throws IOException {
		return new DuelLinksData(MasterDuelIndex.load(install.bundleDir(), cache, progress));
	}

	/** Characters with a normal face (expression 1) and at least one voice line, by id. */
	public List<Integer> characters() {
		List<Integer> out = new ArrayList<>();
		for (Map.Entry<Integer, boolean[]> e : faces.entrySet()) {
			if (e.getValue()[1] && lines.containsKey(e.getKey())) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	public boolean hasFace(int character, int expression) {
		boolean[] f = faces.get(character);
		return f != null && expression >= 1 && expression <= 6 && f[expression];
	}

	/** Lines of a character in category/sub; sub "*" = every sub of the category. */
	public List<Line> lines(int character, String category, String sub) {
		Map<String, List<Line>> m = lines.getOrDefault(character, Map.of());
		if (!"*".equals(sub)) {
			return m.getOrDefault(category + "_" + sub, List.of());
		}
		List<Line> out = new ArrayList<>();
		m.forEach((k, v) -> {
			if (k.startsWith(category + "_")) {
				out.addAll(v);
			}
		});
		return out;
	}

	public Line line(String key) {
		String[] k = key.split("_");
		int ch = Integer.parseInt(k[0].substring(1));
		for (Line l : lines(ch, k[1], k[2])) {
			if (l.index().equals(k[3])) {
				return l;
			}
		}
		return null;
	}

	public Path clipBundle(String clip) {
		String p = clipPaths.get(clip);
		return p == null ? null : index.bundle(p);
	}

	/** kind: "icon" (chara001), "name" (charaname002) or "face1".."face6" (chara007_N). */
	public BufferedImage image(int character, String kind) throws IOException {
		String file = switch (kind) {
			case "icon" -> "chara001.png";
			case "name" -> "charaname002.png";
			default -> "chara007_" + kind.substring(4) + ".png";
		};
		Path b = index.bundle(String.format("assets/resources/characters/sn%04d/images/en-us/%s", character, file));
		return b == null ? null : CardArtCache.decodeBundle(b);
	}

	public int clipCount() {
		return clipPaths.size();
	}
}
