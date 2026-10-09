package dev.duelcraft.client;

import dev.duelcraft.duel.DuelState;
import dev.duelcraft.duel.MsgReader;
import dev.duelcraft.gen.Payloads;
import dev.duelcraft.gen.Prompts;
import java.util.ArrayList;
import java.util.List;

/** What this client knows about its current duel (fed by the s2c payloads). */
public final class ClientDuel {
	public static int duelId = -1, seat, turn, turnPlayer, phase;
	public static String opponent = "";
	public static boolean opponentIsMob;
	public static final int[] lp = {8000, 8000};
	public static List<DuelState.FieldCard> cards = List.of();
	public static final List<String> log = new ArrayList<>();
	public static Prompts.Prompt prompt;
	public static int promptSeq;
	public static long promptHint;
	public static String result = "";
	public static int rewardCid;
	public static long promptArrived;

	private ClientDuel() {
	}

	public static boolean active() {
		return duelId >= 0;
	}

	public static void start(Payloads.DuelStartPayload p) {
		duelId = p.duelId();
		seat = p.seat();
		opponent = p.opponentName();
		opponentIsMob = p.opponentIsMob();
		turn = 0;
		log.clear();
		prompt = null;
		result = "";
		rewardCid = 0;
		cards = List.of();
	}

	public static void state(Payloads.DuelStatePayload p) {
		if (p.duelId() != duelId) {
			return;
		}
		turn = p.turn();
		turnPlayer = p.turnPlayer();
		phase = p.phase();
		lp[0] = p.lp0();
		lp[1] = p.lp1();
		cards = DuelState.decodeCards(p.cards());
	}

	public static void event(Payloads.DuelEventPayload p) {
		if (p.duelId() == duelId) {
			log.add(p.text());
			while (log.size() > 60) {
				log.removeFirst();
			}
		}
	}

	public static void prompt(Payloads.DuelPromptPayload p) {
		if (p.duelId() != duelId) {
			return;
		}
		prompt = Prompts.decode(p.msgId(), new MsgReader(p.raw(), 0, p.raw().length));
		promptSeq = p.promptSeq();
		promptHint = p.hintDesc();
		promptArrived = System.currentTimeMillis();
	}

	public static void end(Payloads.DuelEndPayload p) {
		if (p.duelId() != duelId) {
			return;
		}
		prompt = null;
		result = p.winner() == seat ? "You win!" : p.winner() == 1 - seat ? "You lose..." : "Draw";
		rewardCid = p.rewardCid();
		log.add(result + (p.reason().equals("duel") ? "" : " (" + p.reason() + ")"));
	}

	public static void leave() {
		duelId = -1;
		prompt = null;
	}

	public static List<DuelState.FieldCard> at(int con, int loc) {
		return cards.stream().filter(c -> c.controller() == con && c.location() == loc).toList();
	}

	public static DuelState.FieldCard at(int con, int loc, int seq) {
		for (DuelState.FieldCard c : cards) {
			if (c.controller() == con && c.location() == loc && c.seq() == seq) {
				return c;
			}
		}
		return null;
	}
}
