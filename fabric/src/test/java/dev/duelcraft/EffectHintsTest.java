package dev.duelcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.duelcraft.cards.CardDb;
import dev.duelcraft.cards.EffectHints;
import org.junit.jupiter.api.Test;

/** systems: effect_hints. Labels from the scripts and the matching sentence of the Master Duel text. */
public class EffectHintsTest {
	private static String sentence(CardDb db, int passcode, int n) {
		CardDb.CardInfo c = db.byPasscode(passcode);
		assertNotNull(c, "card " + passcode);
		EffectHints.Hint h = EffectHints.of(((long) passcode << 20) | n);
		assertNotNull(h, "hint " + passcode + "/" + n);
		int[] s = EffectHints.sentenceFor(c.text(), h);
		String out = s == null ? "" : c.text().substring(s[0], s[1]);
		System.out.println(c.name() + " #" + n + " [" + EffectHints.what(h) + " | " + EffectHints.details(h) + "] -> " + out);
		return out;
	}

	@Test
	void labelsAndSentences() throws Exception {
		CardDb db = MasterDuelDataTest.db();
		// Stardust Dragon: negate (Quick Effect), then revive in the End Phase
		assertTrue(sentence(db, 44508094, 0).contains("negate the activation"));
		assertTrue(sentence(db, 44508094, 1).contains("End Phase"));
		assertEquals("Destroy, Negate activation", EffectHints.what(EffectHints.of((44508094L << 20))));
		assertEquals("Quick Effect · in response to an activation", EffectHints.details(EffectHints.of((44508094L << 20))));
		// Blue-Eyes Alternative White Dragon: the destroy effect, not the summon procedure
		assertTrue(sentence(db, 38517737, 0).contains("destroy it"));
		// Odd-Eyes Pendulum Dragon-style three effects
		sentence(db, 90036274, 0);
		sentence(db, 90036274, 1);
		sentence(db, 90036274, 2);
	}
}
