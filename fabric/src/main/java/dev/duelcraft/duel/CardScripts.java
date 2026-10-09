package dev.duelcraft.duel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EDOPro's Lua card scripts (Project Ignis CardScripts, AGPL-3.0), shipped as cardscripts.zip in the mod jar.
 * The engine asks for scripts by file name ("c55144522.lua", "proc_fusion.lua"); they are looked up in the
 * root, official/ and pre-release/ folders, in that order.
 */
public final class CardScripts {
	private static final String[] DIRS = {"", "official/", "pre-release/"};
	private final ZipFile zip;
	private final Map<String, ZipEntry> byName = new HashMap<>();
	private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

	public CardScripts(Path dir) throws IOException {
		Path file = dir.resolve("cardscripts.zip");
		try (InputStream in = CardScripts.class.getResourceAsStream("/cardscripts.zip")) {
			if (in == null) {
				throw new IOException("cardscripts.zip is missing from the mod jar");
			}
			byte[] bytes = in.readAllBytes();
			if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
				Files.createDirectories(dir);
				Path tmp = dir.resolve("cardscripts.zip.tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		zip = new ZipFile(file.toFile());
		for (String d : DIRS) {
			zip.stream().filter(e -> e.getName().startsWith(d) && e.getName().indexOf('/', d.length()) < 0)
				.forEach(e -> byName.putIfAbsent(e.getName().substring(d.length()), e));
		}
	}

	/** Script source by file name, or null. */
	public byte[] get(String name) {
		String key = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
		return cache.computeIfAbsent(key, k -> {
			ZipEntry e = byName.get(k);
			if (e == null) {
				return new byte[0];
			}
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			} catch (IOException ex) {
				return new byte[0];
			}
		}).length == 0 ? null : cache.get(key);
	}

	public boolean has(int passcode) {
		return byName.containsKey("c" + passcode + ".lua");
	}

	public int size() {
		return byName.size();
	}
}
