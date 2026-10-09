package dev.duelcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.duelcraft.cards.CardDb;
import dev.duelcraft.md.MasterDuelCardTables;
import dev.duelcraft.md.MasterDuelIndex;
import dev.duelcraft.md.MasterDuelLocator;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Runs against this PC's real Master Duel install (systems: md_locator, md_index, md_card_tables, card_db). */
public class MasterDuelDataTest {
	static final Path CACHE = Path.of("build", "test-cache");
	static CardDb db;
	static MasterDuelIndex index;

	static synchronized CardDb db() throws Exception {
		if (db == null) {
			MasterDuelLocator.Install md = MasterDuelLocator.find();
			assertNotNull(md, "Master Duel not found");
			long t0 = System.currentTimeMillis();
			index = MasterDuelIndex.load(md.bundleDir(), CACHE.resolve("md_index.tsv"), p -> {
			});
			System.out.println("index: " + index.size() + " assets in " + (System.currentTimeMillis() - t0) + " ms");
			t0 = System.currentTimeMillis();
			MasterDuelCardTables t = MasterDuelCardTables.load(index);
			db = new CardDb(t);
			System.out.println("tables: " + t.cards.size() + " cards, key " + t.key + ", " + (System.currentTimeMillis() - t0) + " ms");
		}
		return db;
	}

	@Test
	void locatesMasterDuel() {
		MasterDuelLocator.Install md = MasterDuelLocator.find();
		assertNotNull(md);
		assertTrue(md.gameDir().toString().contains("Master Duel"), md.toString());
	}

	@Test
	void readsCards() throws Exception {
		CardDb d = db();
		assertTrue(d.size() > 14000, "cards: " + d.size());
		CardDb.CardInfo bewd = d.byCid(4007);
		assertEquals("Blue-Eyes White Dragon", bewd.name());
		assertEquals(89631139, bewd.passcode());
		assertEquals(3000, bewd.atk());
		assertEquals(2500, bewd.def());
		assertEquals(8, bewd.level());
		assertEquals(0x11, bewd.type());
		assertEquals(16, bewd.attribute());
		assertEquals(8192L, bewd.race());
		assertTrue(bewd.text().length() > 20, bewd.text());
		CardDb.CardInfo pot = d.byPasscode(55144522);
		assertEquals("Pot of Greed", pot.name());
		assertEquals(0x2, pot.type());
		System.out.println("playable: " + d.playable().size());
	}
}
