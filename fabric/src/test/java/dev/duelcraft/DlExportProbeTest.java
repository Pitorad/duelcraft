package dev.duelcraft;

import dev.duelcraft.dl.DuelLinksData;
import dev.duelcraft.dl.DuelLinksVoice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Research only (-Ddl.export=regex): writes matching voice clips as Ogg to ../research/dl/ogg for local transcription. */
public class DlExportProbeTest {
	@Test
	void export() throws Exception {
		String re = System.getProperty("dl.export");
		if (re == null) {
			return;
		}
		Pattern p = Pattern.compile(re);
		DuelLinksData d = DuelLinksDataTest.data();
		Path out = Path.of("../research/dl/ogg");
		Files.createDirectories(out);
		AtomicInteger n = new AtomicInteger(), fail = new AtomicInteger();
		long t0 = System.currentTimeMillis();
		List<Integer> chars = d.characters();
		chars.parallelStream().forEach(ch -> {
			for (String cat : new String[] {"08", "09", "13"}) {
				for (DuelLinksData.Line l : d.lines(ch, cat, "*")) {
					for (String clip : l.clips()) {
						if (!p.matcher(clip).find() || Files.exists(out.resolve(clip + ".ogg"))) {
							continue;
						}
						try {
							Files.write(out.resolve(clip + ".ogg"), DuelLinksVoice.ogg(d.clipBundle(clip)));
							n.incrementAndGet();
						} catch (Exception e) {
							fail.incrementAndGet();
						}
					}
				}
			}
		});
		System.out.println("exported " + n + " clips, " + fail + " failed, " + (System.currentTimeMillis() - t0) + " ms");
	}
}
