package dev.duelcraft.client.dl;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.client.ClientDuel;
import dev.duelcraft.dl.DuelLinksData;
import dev.duelcraft.dl.VoiceTables;
import dev.duelcraft.gen.Payloads;
import dev.duelcraft.gen.Settings;
import dev.duelcraft.gen.VoiceTrigger;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;

/**
 * Turns duel events into Duel Links lines for the seat that acted (systems: voice_director): that seat's character
 * says its line for the card when it has one, else the generic line of the voice_categories trigger. One line at a
 * time (parts back to back); the seat's portrait shows the trigger's expression while it speaks.
 */
public final class VoiceDirector {
	private record Queued(int seat, DuelLinksData.Line line, int expression) {
	}

	private static final Deque<Queued> QUEUE = new ArrayDeque<>();
	private static final Deque<String> PARTS = new ArrayDeque<>();
	private static VoiceSound playing;
	private static int speakingSeat = -1;
	private static final int[] expression = {1, 1};
	private static final long[] expressionUntil = new long[2];
	private static final boolean[] lowLpSaid = new boolean[2];
	private static boolean drawPending;
	/** For the test bridge. */
	public static int played;
	public static String lastClip = "";

	private VoiceDirector() {
	}

	public static int expression(int seat) {
		return System.currentTimeMillis() < expressionUntil[seat & 1] ? expression[seat & 1] : (lowLpSaid[seat & 1] ? 4 : 1);
	}

	public static int speakingSeat() {
		return playing == null ? -1 : speakingSeat;
	}

	public static void reset() {
		QUEUE.clear();
		PARTS.clear();
		if (playing != null) {
			Minecraft.getInstance().getSoundManager().stop(playing);
		}
		playing = null;
		lowLpSaid[0] = lowLpSaid[1] = false;
	}

	public static void onStart() {
		reset();
		say(0, VoiceTrigger.DUEL_START, 0);
		say(1, VoiceTrigger.DUEL_START, 0);
	}

	public static void onEnd(int winner) {
		if (winner == 0 || winner == 1) {
			say(winner, VoiceTrigger.WIN, 0);
			say(1 - winner, VoiceTrigger.LOSE, 0);
		}
	}

	public static void onEvent(Payloads.DuelEventPayload e) {
		int seat = e.actor();
		if (seat < 0 || seat > 1) {
			return;
		}
		int t = e.cardType();
		boolean monster = (t & CardDb.TYPE_MONSTER) != 0, spell = (t & CardDb.TYPE_SPELL) != 0;
		switch (e.msgId()) {
			case 40 -> { // new turn
				say(seat, VoiceTrigger.MY_TURN, 0);
				drawPending = true;
			}
			case 90 -> { // draw: only the turn draw
				if (drawPending) {
					drawPending = false;
					say(seat, VoiceTrigger.DRAW, 0);
				}
			}
			case 41 -> { // phase
				if (e.amount() == 0x08) {
					say(seat, VoiceTrigger.BATTLE_PHASE, 0);
				} else if (e.amount() == 0x200) {
					say(seat, VoiceTrigger.END_TURN, 0);
				}
			}
			case 60, 62, 64 -> { // summons
				VoiceTrigger generic = (t & 0x40) != 0 ? VoiceTrigger.SUMMON_FUSION : (t & 0x80) != 0 ? VoiceTrigger.SUMMON_RITUAL
					: (t & 0x2000) != 0 ? VoiceTrigger.SUMMON_SYNCHRO : (t & 0x800000) != 0 ? VoiceTrigger.SUMMON_XYZ
					: (t & 0x4000000) != 0 ? VoiceTrigger.SUMMON_LINK : e.msgId() == 62 ? VoiceTrigger.SUMMON_SPECIAL : VoiceTrigger.SUMMON_ATTACK;
				sayCard(seat, VoiceTrigger.CARD_SUMMON, e.cid(), generic);
			}
			case 54 -> say(seat, e.amount() == 0x04 ? VoiceTrigger.SET_MONSTER : VoiceTrigger.SET_SPELL_TRAP, 0);
			case 70 -> { // activation
				VoiceTrigger generic;
				if (monster) {
					generic = VoiceTrigger.ACTIVATE_MONSTER_EFFECT;
				} else if (spell) {
					generic = (t & 0x10000) != 0 ? VoiceTrigger.ACTIVATE_QUICKPLAY : (t & 0x20000) != 0 ? VoiceTrigger.ACTIVATE_CONTINUOUS_SPELL
						: (t & 0x40000) != 0 ? VoiceTrigger.ACTIVATE_EQUIP : (t & 0x80000) != 0 ? VoiceTrigger.ACTIVATE_FIELD
						: (t & 0x80) != 0 ? VoiceTrigger.ACTIVATE_RITUAL_SPELL : VoiceTrigger.ACTIVATE_SPELL;
				} else if ((t & CardDb.TYPE_TRAP) != 0) {
					generic = (t & 0x20000) != 0 ? VoiceTrigger.ACTIVATE_CONTINUOUS_TRAP : (t & 0x100000) != 0 ? VoiceTrigger.ACTIVATE_COUNTER_TRAP
						: VoiceTrigger.ACTIVATE_TRAP;
				} else {
					generic = VoiceTrigger.ACTIVATE_OTHER;
				}
				sayCard(seat, monster ? VoiceTrigger.CARD_EFFECT : VoiceTrigger.CARD_ACTIVATE, e.cid(), generic);
			}
			case 110 -> sayCard(seat, VoiceTrigger.CARD_ATTACK, e.cid(), e.amount() == 1 ? VoiceTrigger.ATTACK_DIRECT : VoiceTrigger.ATTACK);
			case 91 -> { // damage to seat
				say(seat, e.amount() >= Settings.BIG_DAMAGE ? VoiceTrigger.DAMAGE_BIG : VoiceTrigger.DAMAGE_SMALL, 0);
				if (e.lp() > 0 && e.lp() <= Settings.LOW_LP && !lowLpSaid[seat]) {
					lowLpSaid[seat] = true;
					say(seat, VoiceTrigger.LOW_LP, 0);
				}
			}
			default -> {
			}
		}
	}

	private static int character(int seat) {
		return ClientDuel.character[seat & 1];
	}

	private static void sayCard(int seat, VoiceTrigger cardTrigger, int cid, VoiceTrigger generic) {
		DuelLinksData d = DuelCraftData.dl();
		VoiceTables v = DuelCraftData.voices();
		int ch = character(seat);
		if (d == null || v == null || ch <= 0) {
			return;
		}
		DuelLinksData.Line line = cid == 0 ? null : v.cardLine(d, DuelCraftData.db(), ch, cardTrigger.category, cid);
		if (line != null) {
			enqueue(seat, line, cardTrigger.expression, true);
		} else {
			say(seat, generic, 0);
		}
	}

	private static void say(int seat, VoiceTrigger trigger, int cid) {
		DuelLinksData d = DuelCraftData.dl();
		VoiceTables v = DuelCraftData.voices();
		int ch = character(seat);
		if (d == null || v == null || ch <= 0) {
			return;
		}
		DuelLinksData.Line line = v.generic(d, ch, trigger);
		if (line != null) {
			enqueue(seat, line, trigger.expression, trigger == VoiceTrigger.WIN || trigger == VoiceTrigger.LOSE || trigger == VoiceTrigger.DUEL_START);
		}
	}

	private static void enqueue(int seat, DuelLinksData.Line line, int expr, boolean important) {
		// keep the voice in step with the duel: unimportant lines are dropped when two are already waiting
		if (!important && QUEUE.size() >= 2) {
			return;
		}
		QUEUE.add(new Queued(seat, line, expr));
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		if (playing != null && mc.getSoundManager().isActive(playing)) {
			return;
		}
		playing = null;
		if (PARTS.isEmpty()) {
			Queued q = QUEUE.poll();
			if (q == null) {
				return;
			}
			speakingSeat = q.seat;
			PARTS.addAll(q.line.clips());
			expression[q.seat & 1] = q.expression;
			expressionUntil[q.seat & 1] = System.currentTimeMillis() + 2500;
		}
		String clip = PARTS.poll();
		if (clip != null) {
			playing = new VoiceSound(clip);
			played++;
			lastClip = clip;
			mc.getSoundManager().play(playing);
			expressionUntil[speakingSeat & 1] = Math.max(expressionUntil[speakingSeat & 1], System.currentTimeMillis() + 1500);
		}
	}
}
