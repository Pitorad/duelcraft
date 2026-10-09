package dev.duelcraft.duel;

import dev.duelcraft.gen.Events;
import dev.duelcraft.gen.Prompts;
import dev.duelcraft.gen.Settings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One duel inside ocgcore, independent of Minecraft: feeds decks, runs OCG_DuelProcess, splits the
 * length-prefixed message buffer, decodes prompts/events (generated from the duel_prompts/duel_events
 * sheets), tracks turn/phase/LP and the field snapshot. Team 0 always moves first.
 */
public final class DuelEngine implements AutoCloseable {
	/** A decoded info message with its raw body. */
	public record LoggedEvent(int msgId, Events.Event event, byte[] raw) {
	}

	private final OcgCore.Duel duel;
	public int turn, turnPlayer, phase;
	public final int[] lp = {Settings.STARTING_LP, Settings.STARTING_LP};
	public final long[] hint = new long[2];
	public int winner = -1, winReason = -1;
	public Prompts.Prompt pending;
	public byte[] pendingRaw;
	public int retries;
	public DuelState state;
	public int unknownMessages, promptLeftovers;
	private Prompts.Prompt lastPrompt;
	private byte[] lastRaw;
	private final Consumer<LoggedEvent> events;

	public DuelEngine(OcgCore core, long seed, int[][] decks, Consumer<LoggedEvent> events) {
		this.events = events;
		long[] s = {seed, seed * 6364136223846793005L + 1442695040888963407L, ~seed, seed ^ 0x9E3779B97F4A7C15L};
		OcgCore.Player pl = new OcgCore.Player(Settings.STARTING_LP, Settings.STARTING_HAND, Settings.DRAW_PER_TURN);
		duel = core.newDuel(s, Settings.DUEL_FLAGS, pl, pl);
		// decks[team*2] = main passcodes, decks[team*2+1] = extra passcodes. ocgcore doesn't shuffle the
		// starting decks (EDOPro's host does), so shuffle here with the duel's seed.
		java.util.Random rng = new java.util.Random(seed);
		for (int team = 0; team < 2; team++) {
			int[] main = decks[team * 2].clone();
			for (int i = main.length - 1; i > 0; i--) {
				int j = rng.nextInt(i + 1), t = main[i];
				main[i] = main[j];
				main[j] = t;
			}
			for (int code : main) {
				duel.newCard(team, 0, code, team, OcgCore.LOCATION_DECK, 0, OcgCore.POS_FACEDOWN_DEFENSE);
			}
			for (int code : decks[team * 2 + 1]) {
				duel.newCard(team, 0, code, team, OcgCore.LOCATION_EXTRA, 0, OcgCore.POS_FACEDOWN_DEFENSE);
			}
		}
		duel.start();
	}

	public boolean ended() {
		return winner >= 0 || winReason >= 0;
	}

	/** Runs the engine until it waits for an answer or the duel ends (at most maxSteps process calls). */
	public void run(int maxSteps) {
		for (int i = 0; i < maxSteps && pending == null && !ended(); i++) {
			int status = duel.process();
			handle(duel.messages());
			if (status == OcgCore.STATUS_END) {
				if (winner < 0) {
					winReason = 0xFF;
				}
				break;
			}
			if (status == OcgCore.STATUS_AWAITING && pending == null && !ended()) {
				throw new IllegalStateException("engine is waiting but sent no prompt");
			}
		}
		refreshState();
	}

	public void refreshState() {
		state = DuelState.query(duel, turn, turnPlayer, phase, lp[0], lp[1]);
	}

	/** The player who must answer the pending prompt. */
	public int pendingPlayer() {
		return pendingRaw == null ? -1 : pendingRaw[0] & 0xFF;
	}

	public void respond(byte[] response) {
		pending = null;
		pendingRaw = null;
		duel.respond(response);
	}

	void handle(byte[] buf) {
		ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
		while (b.remaining() >= 4) {
			int len = b.getInt();
			int start = b.position();
			if (len <= 0 || start + len > buf.length) {
				break;
			}
			int id = buf[start] & 0xFF;
			byte[] body = new byte[len - 1];
			System.arraycopy(buf, start + 1, body, 0, len - 1);
			b.position(start + len);
			MsgReader r = new MsgReader(body, 0, body.length);
			if (id == 1) { // MSG_RETRY: the last answer was invalid; ask again
				retries++;
				pending = lastPrompt;
				pendingRaw = lastRaw;
				continue;
			}
			Prompts.Prompt p = Prompts.decode(id, r);
			if (p != null) {
				if (r.remaining() != 0) {
					promptLeftovers++; // the sheet's layout didn't consume the whole message
				}
				retries = 0;
				pending = lastPrompt = p;
				pendingRaw = lastRaw = body;
				continue;
			}
			Events.Event e = body.length == 0 && id != 1 ? null : decodeEvent(id, body);
			if (e == null) {
				unknownMessages++;
				continue;
			}
			track(e);
			events.accept(new LoggedEvent(id, e, body));
		}
	}

	private int lastPromptId() {
		return lastPrompt == null ? -1 : lastPrompt.msgId();
	}

	private static Events.Event decodeEvent(int id, byte[] body) {
		try {
			return Events.decode(id, new MsgReader(body, 0, body.length));
		} catch (RuntimeException ex) {
			return null; // shorter than the sheet's layout: shown only as a field refresh
		}
	}

	private void track(Events.Event e) {
		switch (e) {
			case Events.NewTurn t -> {
				turn++;
				turnPlayer = t.player();
			}
			case Events.NewPhase ph -> phase = ph.phase();
			case Events.Damage d -> lp[d.player() & 1] = Math.max(0, lp[d.player() & 1] - d.amount());
			case Events.PayLpCost d -> lp[d.player() & 1] = Math.max(0, lp[d.player() & 1] - d.amount());
			case Events.Recover r -> lp[r.player() & 1] += r.amount();
			case Events.LpUpdate u -> lp[u.player() & 1] = u.lp();
			case Events.Hint h -> {
				if (h.type() == 3 && h.player() < 2) {
					hint[h.player()] = h.desc();
				}
			}
			case Events.Win w -> {
				winner = w.player();
				winReason = w.reason();
			}
			default -> {
			}
		}
	}

	public OcgCore.Duel duel() {
		return duel;
	}

	public static List<Integer> passcodes(dev.duelcraft.cards.CardDb db, int[] cids) {
		List<Integer> out = new ArrayList<>();
		for (int cid : cids) {
			var c = db.byCid(cid);
			if (c != null && c.playable()) {
				out.add(c.passcode());
			}
		}
		return out;
	}

	@Override
	public void close() {
		duel.close();
	}
}
