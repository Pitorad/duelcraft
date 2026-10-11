package dev.duelcraft;

import dev.duelcraft.md.MasterDuelIndex;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Research: index the Duel Links bundles with the same reader (writes build/test-cache/dl_index.tsv). */
public class DuelLinksIndexProbeTest {
	@Test
	void index() throws Exception {
		Path dl = Path.of("C:/Program Files (x86)/Steam/steamapps/common/Yu-Gi-Oh! Duel Links/LocalData/4475f1bc");
		long t0 = System.currentTimeMillis();
		MasterDuelIndex i = MasterDuelIndex.load(dl, Path.of("build/test-cache/dl_index.tsv"), p -> {
		});
		System.out.println("DL index: " + i.size() + " assets in " + (System.currentTimeMillis() - t0) + " ms");
	}
}
