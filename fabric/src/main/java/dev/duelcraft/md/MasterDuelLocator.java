package dev.duelcraft.md;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the player's own Yu-Gi-Oh! Master Duel (Steam app 1449850). Master Duel isn't in Melty's catalog,
 * so the mod looks for it itself: DUELCRAFT_MD_DIR, else every Steam library listed in libraryfolders.vdf.
 */
public final class MasterDuelLocator {
	public static final String APP_ID = "1449850";
	private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
	private static final Pattern INSTALL_DIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");

	public record Install(Path gameDir, Path bundleDir) {
	}

	private MasterDuelLocator() {
	}

	/** The install, or null when Master Duel isn't installed (or never started, so it has no data yet). */
	public static Install find() {
		String override = System.getenv("DUELCRAFT_MD_DIR");
		if (override != null && !override.isBlank()) {
			return fromGameDir(Path.of(override));
		}
		for (Path lib : steamLibraries()) {
			Path manifest = lib.resolve("steamapps").resolve("appmanifest_" + APP_ID + ".acf");
			String dirName = "Yu-Gi-Oh!  Master Duel";
			try {
				if (Files.isRegularFile(manifest)) {
					Matcher m = INSTALL_DIR.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
					if (m.find()) {
						dirName = m.group(1);
					}
				}
			} catch (IOException ignored) {
				// fall back to the default folder name
			}
			Install i = fromGameDir(lib.resolve("steamapps").resolve("common").resolve(dirName));
			if (i != null) {
				return i;
			}
		}
		return null;
	}

	public static Install fromGameDir(Path game) {
		Path localData = game.resolve("LocalData");
		if (!Files.isDirectory(localData)) {
			return null;
		}
		try (Stream<Path> s = Files.list(localData)) {
			// LocalData/<account hash>/0000/xx/<bundle>; pick the folder with the most recent data
			Path best = null;
			long bestTime = Long.MIN_VALUE;
			for (Path p : s.toList()) {
				Path b = p.resolve("0000");
				if (Files.isDirectory(b)) {
					long t = Files.getLastModifiedTime(b).toMillis();
					if (t > bestTime) {
						best = b;
						bestTime = t;
					}
				}
			}
			return best == null ? null : new Install(game, best);
		} catch (IOException e) {
			return null;
		}
	}

	static List<Path> steamLibraries() {
		Set<Path> roots = new LinkedHashSet<>();
		String steamPath = registrySteamPath();
		if (steamPath != null) {
			roots.add(Path.of(steamPath));
		}
		for (String env : new String[] {"ProgramFiles(x86)", "ProgramFiles"}) {
			String pf = System.getenv(env);
			if (pf != null) {
				roots.add(Path.of(pf, "Steam"));
			}
		}
		Set<Path> libs = new LinkedHashSet<>();
		for (Path root : roots) {
			Path vdf = root.resolve("steamapps").resolve("libraryfolders.vdf");
			libs.add(root);
			try {
				if (Files.isRegularFile(vdf)) {
					Matcher m = VDF_PATH.matcher(Files.readString(vdf, StandardCharsets.UTF_8));
					while (m.find()) {
						libs.add(Path.of(m.group(1).replace("\\\\", "\\")));
					}
				}
			} catch (IOException ignored) {
				// unreadable library list: keep the default library
			}
		}
		return new ArrayList<>(libs);
	}

	private static String registrySteamPath() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
			return null;
		}
		try {
			Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor();
			Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
			return m.find() ? m.group(1).trim().replace('/', '\\') : null;
		} catch (IOException | InterruptedException e) {
			return null;
		}
	}
}
