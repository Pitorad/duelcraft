package dev.duelcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.duelcraft.cards.CardDb;
import dev.duelcraft.duel.CardScripts;
import dev.duelcraft.duel.DuelAi;
import dev.duelcraft.duel.DuelEngine;
import dev.duelcraft.duel.OcgCore;
import dev.duelcraft.gen.Decks;
import dev.duelcraft.gen.Prompts;
import dev.duelcraft.md.CardArtCache;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** AI-vs-AI duels through the real engine (systems: native_lib, card_scripts, ocg_core, duel_messages, duel_state, duel_ai). */
public class DuelSoakTest {
	static OcgCore core;

	static synchronized OcgCore core() throws Exception {
		if (core == null) {
			CardDb db = MasterDuelDataTest.db();
			core = new OcgCore(MasterDuelDataTest.CACHE, db, new CardScripts(MasterDuelDataTest.CACHE));
		}
		return core;
	}

	static int[] codes(CardDb db, int[] cids) {
		return DuelEngine.passcodes(db, cids).stream().mapToInt(Integer::intValue).toArray();
	}

	@Test
	void version() throws Exception {
		int[] v = core().version();
		assertEquals(11, v[0]);
	}

	@Test
	void decodesArt() throws Exception {
		MasterDuelDataTest.db();
		CardArtCache art = new CardArtCache(MasterDuelDataTest.index, MasterDuelDataTest.CACHE.resolve("art")).alternates(MasterDuelDataTest.db()::alternates);
		core();
		BufferedImage img = art.decode(4064);
		assertNotNull(img);
		assertEquals(512, img.getWidth());
		BufferedImage ref = ImageIO.read(Path.of("../research/4064.png").toFile());
		long diff = 0;
		for (int y = 0; y < 512; y += 7) {
			for (int x = 0; x < 512; x += 7) {
				int a = img.getRGB(x, y), b = ref.getRGB(x, y);
				diff += Math.abs((a >> 16 & 255) - (b >> 16 & 255)) + Math.abs((a >> 8 & 255) - (b >> 8 & 255)) + Math.abs((a & 255) - (b & 255));
			}
		}
		System.out.println("art mean abs diff vs UnityPy: " + diff / (74.0 * 74 * 3));
		assertTrue(diff / (74.0 * 74 * 3) < 2.0);
		assertNotNull(art.png(4007)); // not downloaded here: falls back to alternate art 3801
		long missing = MasterDuelDataTest.db().all().stream().filter(cd -> !art.hasArt(cd.cid())).count();
		System.out.println("cards without any local art: " + missing);
	}

	@Test
	void soak() throws Exception {
		OcgCore c = core();
		CardDb db = c.db();
		Decks.Deck[] decks = {Decks.STARTER, Decks.DRAGONS, Decks.UNDEAD};
		Map<String, Integer> prompts = new TreeMap<>();
		int duels = Integer.getInteger("soak.duels", 30), finished = 0, maxTurns = 0, retriesTotal = 0, unknown = 0;
		long t0 = System.currentTimeMillis();
		for (int n = 0; n < duels; n++) {
			Decks.Deck a = decks[n % 3], b = decks[(n / 3 + n + 1) % 3];
			int[][] d = {codes(db, a.main()), codes(db, a.extra()), codes(db, b.main()), codes(db, b.extra())};
			try (DuelEngine e = new DuelEngine(c, 1000 + n, d, ev -> {
			})) {
				DuelAi[] ai = {new DuelAi(e, db, 0, n), new DuelAi(e, db, 1, n + 99)};
				int guard = 0;
				while (!e.ended() && guard++ < 20000 && e.turn < 200) {
					e.run(256);
					if (e.pending != null) {
						Prompts.Prompt p = e.pending;
						prompts.merge(Prompts.name(p.msgId()), 1, Integer::sum);
						if (e.retries >= 3) {
							throw new AssertionError("duel " + n + ": AI answer rejected 3 times for " + p);
						}
						e.respond(ai[e.pendingPlayer() & 1].answer(p, e.retries > 0));
						if (guard % 50 == 0) {
							for (int pl = 0; pl < 2; pl++) {
								for (int loc : dev.duelcraft.duel.DuelState.LOCATIONS) {
									long inState = e.state.at(pl, loc).stream().filter(fc -> !fc.empty()).count();
									assertEquals(e.duel().count(pl, loc), inState, "snapshot count p" + pl + " loc " + loc);
								}
							}
						}
					}
				}
				retriesTotal += e.retries;
				assertEquals(0, e.promptLeftovers, "prompt bytes left undecoded");
				unknown += e.unknownMessages;
				maxTurns = Math.max(maxTurns, e.turn);
				System.out.printf("duel %d %s vs %s: winner %d reason %d after %d turns, LP %d/%d%n", n, a.id(), b.id(), e.winner, e.winReason,
					e.turn, e.lp[0], e.lp[1]);
				if (e.ended()) {
					finished++;
				}
			}
		}
		System.out.println("prompts seen: " + prompts);
		System.out.printf("%d/%d finished, max turns %d, unknown msgs %d, %d ms%n", finished, duels, maxTurns, unknown, System.currentTimeMillis() - t0);
		assertEquals(duels, finished);
	}
}
