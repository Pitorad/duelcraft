package dev.duelcraft.md;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Probe: what Master Duel's dlg_indx/dlg_text and word_indx/word_text tables hold (-PprobeCards=dlg). */
public class DlgTextProbeTest {
	@Test
	void dump() throws Exception {
		if (!"dlg".equals(System.getProperty("probe.cards"))) {
			return;
		}
		dev.duelcraft.MasterDuelDataTest.db();
		MasterDuelIndex index = dev.duelcraft.MasterDuelDataTest.index;
		int[] key = {-1};
		for (String t : new String[] {"dlg", "word"}) {
			byte[] indx = MasterDuelCardTables.table(index, "assets/resourcesassetbundle/card/data/<hash>/en-us/" + t + "_indx.bytes", key);
			byte[] text = MasterDuelCardTables.table(index, "assets/resourcesassetbundle/card/data/<hash>/en-us/" + t + "_text.bytes", key);
			System.out.println("PROBE " + t + " indx " + indx.length + " text " + text.length);
			ByteBuffer b = ByteBuffer.wrap(indx).order(ByteOrder.LITTLE_ENDIAN);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(48, indx.length / 4); i++) {
				sb.append(b.getInt(i * 4)).append(' ');
			}
			System.out.println("PROBE " + t + " indx words: " + sb);
			String all = new String(text, StandardCharsets.UTF_8);
			String[] parts = all.split("\0");
			System.out.println("PROBE " + t + " strings " + parts.length);
			for (int i = 0; i < Math.min(30, parts.length); i++) {
				System.out.println("PROBE " + t + " [" + i + "] " + parts[i]);
			}
			for (int i = 0; i < parts.length; i++) {
				if (parts[i].contains("ncrease its Level") || parts[i].contains("ecrease its Level") || parts[i].contains("Level by 1")) {
					System.out.println("PROBE " + t + " hit [" + i + "] " + parts[i]);
				}
			}
		}
	}
}
