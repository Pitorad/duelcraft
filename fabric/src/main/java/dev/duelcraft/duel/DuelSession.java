package dev.duelcraft.duel;

import dev.duelcraft.DuelCraft;
import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.gen.Events;
import dev.duelcraft.gen.MobDuelists;
import dev.duelcraft.gen.Payloads;
import dev.duelcraft.gen.Prompts;
import dev.duelcraft.gen.Settings;
import dev.duelcraft.item.CardDrops;
import dev.duelcraft.world.DuelBoard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * One duel in the world (systems: duel_session): two seats (players or a mob), the engine on the server
 * thread, prompts routed to the seat that must answer, state/events sent to both players, the board kept
 * in sync, rewards at the end. Seat index == engine team; team 0 moves first.
 */
public final class DuelSession {
	public sealed interface Seat permits PlayerSeat, MobSeat {
		String name();
	}

	public record PlayerSeat(UUID id, String name) implements Seat {
	}

	public record MobSeat(Mob mob, MobDuelists.MobDuelist row, String name) implements Seat {
	}

	private static int nextId = 1;
	public final int id = nextId++;
	public final Seat[] seats;
	private final List<Integer>[] deckCids;
	private final MinecraftServer server;
	private final ServerLevel level;
	private final DuelEngine engine;
	private final DuelAi[] ai = new DuelAi[2];
	private final DuelBoard board;
	private final CardDb db;
	private int promptSeq, sentSeq = -1, think, endTimer = -1;
	private boolean finished;
	private final List<String> log = new ArrayList<>();
	private final MobDuelist[] frozen = new MobDuelist[2];

	@SuppressWarnings("unchecked")
	public DuelSession(MinecraftServer server, ServerLevel level, Seat a, List<Integer>[] deckA, Seat b, List<Integer>[] deckB, Vec3 posA, Vec3 posB) {
		this.server = server;
		this.level = level;
		this.db = DuelCraftData.db();
		boolean swap = level.getRandom().nextBoolean(); // coin toss for who goes first
		seats = swap ? new Seat[] {b, a} : new Seat[] {a, b};
		List<Integer>[] d0 = swap ? deckB : deckA, d1 = swap ? deckA : deckB;
		deckCids = new List[] {concat(d0), concat(d1)};
		int[][] decks = {codes(d0[0]), codes(d0[1]), codes(d1[0]), codes(d1[1])};
		engine = new DuelEngine(DuelCraftData.core(), level.getRandom().nextLong(), decks, this::onEvent);
		board = new DuelBoard(level, swap ? posB : posA, swap ? posA : posB);
		for (int s = 0; s < 2; s++) {
			if (seats[s] instanceof MobSeat m) {
				ai[s] = new DuelAi(engine, db, s, level.getRandom().nextLong());
				frozen[s] = MobDuelist.freeze(m.mob(), board.seatPos(s), board.center());
			}
		}
		for (int s = 0; s < 2; s++) {
			ServerPlayer p = player(s);
			if (p != null) {
				ServerPlayNetworking.send(p, new Payloads.DuelStartPayload(id, s, seats[1 - s].name(), seats[1 - s] instanceof MobSeat, 0));
			}
		}
		say(Component.translatable("chat.duelcraft.duel_start", seats[0].name(), seats[1].name()).withStyle(ChatFormatting.GOLD));
		engine.run(Settings.MAX_STEPS_PER_TICK);
		broadcastState();
	}

	private static List<Integer> concat(List<Integer>[] d) {
		List<Integer> out = new ArrayList<>(d[0]);
		out.addAll(d[1]);
		return out;
	}

	private int[] codes(List<Integer> cids) {
		return cids.stream().map(db::byCid).filter(c -> c != null && c.playable()).mapToInt(CardDb.CardInfo::passcode).toArray();
	}

	public ServerPlayer player(int seat) {
		return seats[seat] instanceof PlayerSeat p ? server.getPlayerList().getPlayer(p.id()) : null;
	}

	public int seatOf(UUID player) {
		for (int s = 0; s < 2; s++) {
			if (seats[s] instanceof PlayerSeat p && p.id().equals(player)) {
				return s;
			}
		}
		return -1;
	}

	public boolean involves(Mob mob) {
		for (Seat s : seats) {
			if (s instanceof MobSeat m && m.mob() == mob) {
				return true;
			}
		}
		return false;
	}

	public boolean finished() {
		return finished;
	}

	/** Called every server tick by DuelManager. */
	public void tick() {
		board.tick();
		if (endTimer >= 0) {
			if (--endTimer <= 0) {
				cleanup();
			}
			return;
		}
		for (int s = 0; s < 2; s++) {
			if (seats[s] instanceof MobSeat m && (m.mob().isRemoved() || !m.mob().isAlive())) {
				end(1 - s, "forfeit");
				return;
			}
		}
		if (engine.ended()) {
			end(engine.winner, "duel");
			return;
		}
		if (engine.pending == null) {
			engine.run(Settings.MAX_STEPS_PER_TICK);
			broadcastState();
			return;
		}
		int who = engine.pendingPlayer();
		if (who < 0 || who > 1) {
			return;
		}
		if (ai[who] != null) {
			if (++think < Settings.AI_THINK_TICKS) {
				return;
			}
			think = 0;
			if (engine.retries >= 3) {
				DuelCraft.LOG.warn("AI answer rejected 3 times for {}; the AI concedes", engine.pending);
				end(1 - who, "concede");
				return;
			}
			engine.respond(ai[who].answer(engine.pending, engine.retries > 0));
			engine.run(Settings.MAX_STEPS_PER_TICK);
			broadcastState();
		} else if (sentSeq != promptSeqFor()) {
			ServerPlayer p = player(who);
			if (p == null) {
				end(1 - who, "forfeit");
				return;
			}
			sentSeq = promptSeqFor();
			ServerPlayNetworking.send(p, new Payloads.DuelPromptPayload(id, sentSeq, engine.pending.msgId(), engine.hint[who], engine.pendingRaw));
		}
	}

	private int promptSeqFor() {
		return promptSeq * 8 + Math.min(engine.retries, 7);
	}

	/** A player's answer to the prompt they were sent. */
	public void respond(ServerPlayer from, int seq, byte[] response) {
		int seat = seatOf(from.getUUID());
		if (endTimer >= 0 || engine.pending == null || seat != engine.pendingPlayer() || seq != sentSeq) {
			return;
		}
		promptSeq++;
		engine.respond(response);
		engine.run(Settings.MAX_STEPS_PER_TICK);
		broadcastState();
	}

	public void forfeit(int seat) {
		if (endTimer < 0) {
			end(1 - seat, "forfeit");
		}
	}

	private void onEvent(DuelEngine.LoggedEvent e) {
		String text = format(e);
		int player = -1, amount = 0, cid = 0;
		switch (e.event()) {
			case Events.Summoning s -> cid = cidOf(s.code());
			case Events.SpSummoning s -> cid = cidOf(s.code());
			case Events.FlipSummoning s -> cid = cidOf(s.code());
			case Events.Chaining c -> {
				cid = cidOf(c.code());
				board.highlight(c.where().con(), c.where().loc(), c.where().seq(), 30);
			}
			case Events.Attack a -> board.highlight(a.attacker().con(), a.attacker().loc(), a.attacker().seq(), 20);
			case Events.Damage d -> {
				player = d.player();
				amount = d.amount();
			}
			default -> {
			}
		}
		if (e.event() instanceof Events.Summoning || e.event() instanceof Events.SpSummoning || e.event() instanceof Events.FlipSummoning) {
			board.burst(board.center());
		}
		playSound(e.msgId());
		if (!text.isEmpty()) {
			log.add(text);
			for (int s = 0; s < 2; s++) {
				ServerPlayer p = player(s);
				if (p != null) {
					ServerPlayNetworking.send(p, new Payloads.DuelEventPayload(id, e.msgId(), text, cid, player, amount));
				}
			}
		}
	}

	private void playSound(int msgId) {
		String sound = Events.sound(msgId);
		if (sound == null || "none".equals(sound)) {
			return;
		}
		SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(sound));
		if (ev != null) {
			Vec3 c = board.center();
			level.playSound(null, c.x, c.y, c.z, ev, SoundSource.PLAYERS, 0.6F, 1.0F);
		}
	}

	private int cidOf(int passcode) {
		CardDb.CardInfo c = db.byPasscode(passcode);
		return c == null ? 0 : c.cid();
	}

	private String cardName(int passcode) {
		CardDb.CardInfo c = passcode == 0 ? null : db.byPasscode(passcode);
		return c == null ? "a face-down card" : c.name();
	}

	private String cardAt(MsgReader.Loc l) {
		DuelState.FieldCard c = engine.state == null ? null : engine.state.at(l.con(), l.loc(), l.seq());
		return c == null || c.empty() ? "directly" : cardName(c.code());
	}

	static String phaseName(int phase) {
		return switch (phase) {
			case 0x01 -> "Draw Phase";
			case 0x02 -> "Standby Phase";
			case 0x04 -> "Main Phase 1";
			case 0x08 -> "Battle Phase";
			case 0x10 -> "Battle Step";
			case 0x20 -> "Damage Step";
			case 0x40 -> "Damage Calculation";
			case 0x80 -> "End of Battle";
			case 0x100 -> "Main Phase 2";
			case 0x200 -> "End Phase";
			default -> "Phase " + phase;
		};
	}

	private String format(DuelEngine.LoggedEvent e) {
		String t = switch (e.event()) {
			case Events.Win x -> Events.Win.LOGTEXT.replace("{player}", x.player() < 2 ? seats[x.player()].name() : "Nobody");
			case Events.NewTurn x -> Events.NewTurn.LOGTEXT.replace("{count}", "" + engine.turn).replace("{player}", seats[x.player() & 1].name());
			case Events.NewPhase x -> phaseName(x.phase());
			case Events.SetCard x -> Events.SetCard.LOGTEXT.replace("{player}", seats[x.where().con() & 1].name());
			case Events.Summoning x -> Events.Summoning.LOGTEXT.replace("{player}", seats[x.where().con() & 1].name()).replace("{card}", cardName(x.code()));
			case Events.SpSummoning x -> Events.SpSummoning.LOGTEXT.replace("{player}", seats[x.where().con() & 1].name()).replace("{card}", cardName(x.code()));
			case Events.FlipSummoning x -> Events.FlipSummoning.LOGTEXT.replace("{player}", seats[x.where().con() & 1].name()).replace("{card}", cardName(x.code()));
			case Events.Chaining x -> Events.Chaining.LOGTEXT.replace("{player}", seats[x.con() & 1].name()).replace("{card}", cardName(x.code()))
				.replace("{count}", "" + x.chainSize());
			case Events.ChainNegated x -> Events.ChainNegated.LOGTEXT.replace("{count}", "" + x.chainLink());
			case Events.Draw x -> Events.Draw.LOGTEXT.replace("{player}", seats[x.player() & 1].name()).replace("{count}", "" + x.count());
			case Events.Damage x -> Events.Damage.LOGTEXT.replace("{player}", seats[x.player() & 1].name()).replace("{amount}", "" + x.amount());
			case Events.Recover x -> Events.Recover.LOGTEXT.replace("{player}", seats[x.player() & 1].name()).replace("{amount}", "" + x.amount());
			case Events.PayLpCost x -> Events.PayLpCost.LOGTEXT.replace("{player}", seats[x.player() & 1].name()).replace("{amount}", "" + x.amount());
			case Events.Attack x -> Events.Attack.LOGTEXT.replace("{card}", cardAt(x.attacker())).replace("{target}", x.target().loc() == 0 ? "directly" : cardAt(x.target()));
			case Events.TossCoin x -> Events.TossCoin.LOGTEXT.replace("{player}", seats[x.player() & 1].name());
			case Events.TossDice x -> Events.TossDice.LOGTEXT.replace("{player}", seats[x.player() & 1].name());
			default -> "";
		};
		return t;
	}

	private void broadcastState() {
		if (engine.state == null) {
			engine.refreshState();
		}
		DuelState st = engine.state;
		for (int s = 0; s < 2; s++) {
			ServerPlayer p = player(s);
			if (p != null) {
				DuelState v = st.forViewer(s);
				ServerPlayNetworking.send(p, new Payloads.DuelStatePayload(id, engine.turn, engine.turnPlayer, engine.phase, st.lp[0], st.lp[1], v.encodeCards()));
			}
		}
		board.update(st.forViewer(-1), new String[] {seats[0].name(), seats[1].name()});
	}

	private void end(int winner, String reason) {
		if (endTimer >= 0) {
			return;
		}
		finished = true;
		endTimer = 60;
		ItemStack[] reward = new ItemStack[2];
		if (winner == 0 || winner == 1) {
			int loser = 1 - winner;
			int count = seats[loser] instanceof MobSeat m ? m.row().rewardCards() : 1;
			ServerPlayer wp = player(winner);
			if (wp != null) {
				for (int i = 0; i < count; i++) {
					ItemStack card = CardDrops.fromDeck(deckCids[loser], level.getRandom());
					if (card != null) {
						reward[winner] = card;
						if (!wp.getInventory().add(card.copy())) {
							wp.spawnAtLocation(level, card.copy());
						}
					}
				}
			}
		}
		String winName = winner == 0 || winner == 1 ? seats[winner].name() : "Nobody";
		say(Component.translatable("chat.duelcraft.duel_end", winName, seats[1 - Math.max(0, Math.min(1, winner))].name(), reason).withStyle(ChatFormatting.GOLD));
		for (int s = 0; s < 2; s++) {
			ServerPlayer p = player(s);
			if (p != null) {
				int cid = reward[s] == null ? 0 : dev.duelcraft.item.CardItem.cid(reward[s]);
				ServerPlayNetworking.send(p, new Payloads.DuelEndPayload(id, winner, reason, cid));
			}
		}
		for (int s = 0; s < 2; s++) {
			if (frozen[s] != null) {
				frozen[s].restore();
				frozen[s] = null;
			}
		}
	}

	private void cleanup() {
		board.remove();
		engine.close();
		endTimer = -2;
	}

	public boolean cleaned() {
		return endTimer == -2;
	}

	private void say(Component c) {
		for (int s = 0; s < 2; s++) {
			ServerPlayer p = player(s);
			if (p != null) {
				p.sendSystemMessage(c);
			}
		}
	}

	public void shutdown() {
		for (int s = 0; s < 2; s++) {
			if (frozen[s] != null) {
				frozen[s].restore();
			}
		}
		board.remove();
		engine.close();
	}
}
