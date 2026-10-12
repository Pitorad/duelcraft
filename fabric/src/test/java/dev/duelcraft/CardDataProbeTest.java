package dev.duelcraft;

import dev.duelcraft.cards.CardDb;
import org.junit.jupiter.api.Test;

/** Prints converted card data for named cards: -PprobeCards="A|B" (debugging rules questions). */
public class CardDataProbeTest {
	@Test
	void probe() throws Exception {
		String names = System.getProperty("probe.cards", "");
		if (names.isEmpty()) {
			return;
		}
		CardDb db = MasterDuelDataTest.db();
		for (String n : names.split(";")) {
			for (CardDb.CardInfo c : db.all()) {
				if (c.name().equalsIgnoreCase(n.trim())) {
					System.out.printf("PROBE %s cid=%d pass=%d type=0x%x level=%d attr=0x%x race=0x%x atk=%d def=%d | %s%n", c.name(), c.cid(), c.passcode(),
						c.type(), c.level(), c.attribute(), c.race(), c.atk(), c.def(), client(c));
				}
			}
		}
	}

	private static String client(CardDb.CardInfo c) {
		return dev.duelcraft.gen.MdEncodings.ATTRIBUTE_NAMES.getOrDefault(c.attribute(), "?") + " " + dev.duelcraft.gen.MdEncodings.RACE_NAMES.getOrDefault((long) c.race(), "?");
	}
}
