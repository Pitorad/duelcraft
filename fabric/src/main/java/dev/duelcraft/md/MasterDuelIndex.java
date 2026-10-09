package dev.duelcraft.md;

import dev.duelcraft.DuelCraft;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Asset path -> bundle file for the player's Master Duel data. Bundle names are hashes, so the first run
 * opens every bundle once and reads its AssetBundle container; the result is cached in
 * &lt;game dir&gt;/duelcraft/md_index.tsv and rebuilt when Master Duel's data folder changes.
 */
public final class MasterDuelIndex {
	private final Path bundleDir;
	private final Map<String, String> byPath;

	private MasterDuelIndex(Path bundleDir, Map<String, String> byPath) {
		this.bundleDir = bundleDir;
		this.byPath = byPath;
	}

	public Path bundle(String assetPath) {
		String f = byPath.get(assetPath);
		return f == null ? null : bundleDir.resolve(f);
	}

	public List<String> paths(java.util.function.Predicate<String> filter) {
		return byPath.keySet().stream().filter(filter).sorted().toList();
	}

	public int size() {
		return byPath.size();
	}

	/** Loads the cached index if it still matches the data folder, else rebuilds it. progress gets 0-100. */
	public static MasterDuelIndex load(Path bundleDir, Path cacheFile, IntConsumer progress) throws IOException {
		List<Path> files = listBundles(bundleDir);
		String stamp = bundleDir.toAbsolutePath() + "\t" + files.size() + "\t" + newestDir(bundleDir);
		if (Files.isRegularFile(cacheFile)) {
			List<String> lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
			if (!lines.isEmpty() && lines.getFirst().equals("#" + stamp)) {
				Map<String, String> m = new HashMap<>(lines.size() * 2);
				for (int i = 1; i < lines.size(); i++) {
					String l = lines.get(i);
					int t = l.indexOf('\t');
					if (t > 0) {
						m.put(l.substring(t + 1), l.substring(0, t));
					}
				}
				progress.accept(100);
				return new MasterDuelIndex(bundleDir, m);
			}
		}
		long t0 = System.nanoTime();
		Map<String, String> m = new HashMap<>();
		AtomicInteger done = new AtomicInteger();
		int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
		ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "DuelCraft Master Duel index");
			t.setDaemon(true);
			return t;
		});
		try {
			List<Future<String[]>> futures = new ArrayList<>(files.size());
			for (Path f : files) {
				futures.add(pool.submit(() -> {
					String rel = bundleDir.relativize(f).toString().replace('\\', '/');
					List<String> out = new ArrayList<>();
					try {
						UnityBundle b = new UnityBundle(f);
						UnityBundle.Node n = b.serializedNode();
						if (n != null) {
							for (String p : new SerializedFile(b.readNode(n)).containerPaths()) {
								out.add(rel + "\t" + p);
							}
						}
					} catch (Exception e) {
						// not every file is a bundle we can read; skip it
					}
					int d = done.incrementAndGet();
					if (d % 400 == 0) {
						progress.accept(d * 100 / files.size());
					}
					return out.toArray(String[]::new);
				}));
			}
			StringBuilder sb = new StringBuilder("#").append(stamp).append('\n');
			for (Future<String[]> fu : futures) {
				for (String line : fu.get()) {
					int t = line.indexOf('\t');
					m.put(line.substring(t + 1), line.substring(0, t));
					sb.append(line).append('\n');
				}
			}
			Files.createDirectories(cacheFile.getParent());
			Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
			Files.writeString(tmp, sb, StandardCharsets.UTF_8);
			Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
			throw new IOException("indexing Master Duel failed", e);
		} finally {
			pool.shutdownNow();
		}
		DuelCraft.LOG.info("Indexed {} Master Duel assets from {} bundles in {} s", m.size(), files.size(), (System.nanoTime() - t0) / 1_000_000_000);
		progress.accept(100);
		return new MasterDuelIndex(bundleDir, m);
	}

	private static List<Path> listBundles(Path dir) throws IOException {
		try (Stream<Path> s = Files.walk(dir, 2)) {
			return s.filter(Files::isRegularFile).sorted().toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private static long newestDir(Path dir) throws IOException {
		long newest = Files.getLastModifiedTime(dir).toMillis();
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.toList()) {
				newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
			}
		}
		return newest;
	}
}
