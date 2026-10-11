package dev.duelcraft.dl;

import dev.duelcraft.md.MasterDuelLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the player's Yu-Gi-Oh! Duel Links (Steam app 601510; systems: dl_locator). Melty passes the folder of the
 * installed game as DUELCRAFT_DL_DIR ({game:custom-yu-gi-oh-duel-links}); otherwise every Steam library is checked.
 */
public final class DuelLinksLocator {
	public static final String APP_ID = "601510";
	private static final Pattern INSTALL_DIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");

	public record Install(Path gameDir, Path bundleDir) {
	}

	private DuelLinksLocator() {
	}

	public static Install find() {
		String override = System.getenv("DUELCRAFT_DL_DIR");
		if (override != null && !override.isBlank()) {
			// Melty passes the companion game's folder; if it is not a Duel Links install, look in Steam as usual
			Install i = fromGameDir(Path.of(override));
			if (i != null) {
				return i;
			}
		}
		for (Path lib : MasterDuelLocator.steamLibraries()) {
			Path manifest = lib.resolve("steamapps").resolve("appmanifest_" + APP_ID + ".acf");
			String dirName = "Yu-Gi-Oh! Duel Links";
			try {
				if (Files.isRegularFile(manifest)) {
					Matcher m = INSTALL_DIR.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
					if (m.find()) {
						dirName = m.group(1);
					}
				}
			} catch (IOException ignored) {
				// default folder name
			}
			Install i = fromGameDir(lib.resolve("steamapps").resolve("common").resolve(dirName));
			if (i != null) {
				return i;
			}
		}
		return null;
	}

	/** LocalData/&lt;account hash&gt;/xx/&lt;bundle&gt;: the hash folder with the most recent data. */
	public static Install fromGameDir(Path game) {
		Path localData = game.resolve("LocalData");
		if (!Files.isDirectory(localData)) {
			return null;
		}
		try (Stream<Path> s = Files.list(localData)) {
			Path best = null;
			long bestTime = Long.MIN_VALUE;
			for (Path p : s.toList()) {
				if (Files.isDirectory(p) && Files.isDirectory(p.resolve("00"))) {
					long t = Files.getLastModifiedTime(p).toMillis();
					if (t > bestTime) {
						best = p;
						bestTime = t;
					}
				}
			}
			return best == null ? null : new Install(game, best);
		} catch (IOException e) {
			return null;
		}
	}
}
