package dev.duelcraft.duel;

import com.mojang.brigadier.Command;
import dev.duelcraft.DuelCraftData;
import dev.duelcraft.gen.HookIds;
import dev.duelcraft.gen.MobDuelists;
import dev.duelcraft.gen.Settings;
import dev.duelcraft.item.DeckBoxItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Challenges, running duels, forfeits and the /duel command (systems: duel_manager). */
public final class DuelManager {
	private record Challenge(UUID from, UUID to, int expires) {
	}

	private static final List<DuelSession> SESSIONS = new ArrayList<>();
	private static final Map<UUID, Challenge> CHALLENGES = new HashMap<>();
	private static MinecraftServer server;

	private DuelManager() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			DuelCraftData.startLoading();
		});
		HookIds.SERVER_STARTED.installed();
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			SESSIONS.forEach(DuelSession::shutdown);
			SESSIONS.clear();
			CHALLENGES.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(DuelManager::tick);
		HookIds.SERVER_TICK.installed();
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			ItemStack held = player.getItemInHand(hand);
			if (!(held.getItem() instanceof DeckBoxItem)) {
				return InteractionResult.PASS;
			}
			if (player instanceof ServerPlayer sp) {
				challenge(sp, held, entity);
			}
			return InteractionResult.SUCCESS;
		});
		HookIds.USE_ENTITY.installed();
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> forfeit(handler.player));
		HookIds.PLAYER_LEAVE.installed();
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> dispatcher.register(Commands.literal("duel")
			.then(Commands.literal("accept").executes(c -> accept(c.getSource().getPlayerOrException()) ? 1 : 0))
			.then(Commands.literal("decline").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				CHALLENGES.values().removeIf(ch -> ch.to.equals(p.getUUID()));
				p.sendSystemMessage(Component.translatable("chat.duelcraft.declined"));
				return 1;
			}))
			.then(Commands.literal("challenge").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player");
				ItemStack box = deckBoxOf(p);
				if (box == null || target == p) {
					p.sendSystemMessage(Component.translatable("chat.duelcraft.nearest_none"));
					return 0;
				}
				challenge(p, box, target);
				return 1;
			})))
			.then(Commands.literal("forfeit").executes(c -> forfeit(c.getSource().getPlayerOrException()) ? 1 : 0))
			.then(Commands.literal("status").executes(c -> {
				c.getSource().sendSystemMessage(Component.literal("DuelCraft: Master Duel " + DuelCraftData.status()
					+ (DuelCraftData.status() == DuelCraftData.Status.INDEXING ? " " + DuelCraftData.progress() + "%" : "")
					+ ", duels running: " + SESSIONS.size()));
				return 1;
			}))
			.then(Commands.literal("nearest").executes(c -> nearest(c.getSource().getPlayerOrException(), null))
				// test decks from the decks sheet, e.g. /duel nearest prompts
				.then(Commands.argument("deck", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c ->
					nearest(c.getSource().getPlayerOrException(), dev.duelcraft.gen.Decks.byId(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "deck"))))))));
		HookIds.COMMANDS.installed();
	}

	private static int nearest(ServerPlayer p, dev.duelcraft.gen.Decks.Deck deck) {
		ItemStack box = deck != null ? DeckBoxItem.withDeck(deck.main(), deck.extra()) : deckBoxOf(p);
		Mob nearest = null;
		for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(12))) {
			if (m.isAlive() && (nearest == null || m.distanceToSqr(p) < nearest.distanceToSqr(p))) {
				nearest = m;
			}
		}
		if (box == null || nearest == null) {
			p.sendSystemMessage(Component.translatable("chat.duelcraft.nearest_none"));
			return 0;
		}
		challenge(p, box, nearest);
		return Command.SINGLE_SUCCESS;
	}

	static ItemStack deckBoxOf(Player p) {
		for (ItemStack s : new ItemStack[] {p.getMainHandItem(), p.getOffhandItem()}) {
			if (s.getItem() instanceof DeckBoxItem) {
				return s;
			}
		}
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			if (p.getInventory().getItem(i).getItem() instanceof DeckBoxItem) {
				return p.getInventory().getItem(i);
			}
		}
		return null;
	}

	public static DuelSession sessionOf(UUID player) {
		for (DuelSession s : SESSIONS) {
			if (!s.finished() && s.seatOf(player) >= 0) {
				return s;
			}
		}
		return null;
	}

	public static DuelSession byId(int id) {
		for (DuelSession s : SESSIONS) {
			if (s.id == id) {
				return s;
			}
		}
		return null;
	}

	private static boolean busy(Mob mob) {
		for (DuelSession s : SESSIONS) {
			if (!s.cleaned() && s.involves(mob)) {
				return true;
			}
		}
		return false;
	}

	private static boolean ready(ServerPlayer p, ItemStack box) {
		if (!DuelCraftData.ready()) {
			p.sendSystemMessage(Component.translatable(switch (DuelCraftData.status()) {
				case NO_MASTER_DUEL -> "chat.duelcraft.no_master_duel";
				case FAILED -> "chat.duelcraft.load_failed";
				default -> "chat.duelcraft.loading";
			}, DuelCraftData.progress()).withStyle(ChatFormatting.RED));
			return false;
		}
		if (sessionOf(p.getUUID()) != null) {
			p.sendSystemMessage(Component.translatable("chat.duelcraft.already").withStyle(ChatFormatting.RED));
			return false;
		}
		DeckBoxItem.Deck d = DeckBoxItem.deck(box);
		if (d.main().size() < Settings.MIN_DECK || d.main().size() > 60 || d.extra().size() > 15) {
			p.sendSystemMessage(Component.translatable("chat.duelcraft.bad_deck", d.main().size(), d.extra().size()).withStyle(ChatFormatting.RED));
			return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	static void challenge(ServerPlayer p, ItemStack box, Entity target) {
		if (!ready(p, box)) {
			return;
		}
		if (target instanceof ServerPlayer other) {
			CHALLENGES.put(p.getUUID(), new Challenge(p.getUUID(), other.getUUID(), server.getTickCount() + Settings.CHALLENGE_TIMEOUT_TICKS));
			p.sendSystemMessage(Component.translatable("chat.duelcraft.challenge_sent", other.getName()));
			other.sendSystemMessage(Component.translatable("chat.duelcraft.challenged", p.getName())
				.append(" ")
				.append(Component.translatable("chat.duelcraft.accept").withStyle(s -> s.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.RunCommand("/duel accept"))))
				.append(" ")
				.append(Component.translatable("chat.duelcraft.decline").withStyle(s -> s.withColor(ChatFormatting.RED).withClickEvent(new ClickEvent.RunCommand("/duel decline")))));
			return;
		}
		if (target instanceof Mob mob && !(mob instanceof EnderDragon) && !(mob instanceof WitherBoss) && mob.isAlive()) {
			if (busy(mob)) {
				p.sendSystemMessage(Component.translatable("chat.duelcraft.mob_busy"));
				return;
			}
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
			MobDuelists.MobDuelist row = MobDuelists.forEntity(type);
			DeckBoxItem.Deck mine = DeckBoxItem.deck(box);
			List<Integer>[] myDeck = new List[] {mine.main(), mine.extra()};
			List<Integer>[] mobDeck = new List[] {toList(row.deck().main()), toList(row.deck().extra())};
			String name = mob.hasCustomName() ? mob.getCustomName().getString() : row.title();
			SESSIONS.add(new DuelSession(server, (ServerLevel) p.level(), new DuelSession.PlayerSeat(p.getUUID(), p.getName().getString()), myDeck,
				new DuelSession.MobSeat(mob, row, name), mobDeck, p.position(), mob.position()));
		}
	}

	private static List<Integer> toList(int[] a) {
		List<Integer> l = new ArrayList<>(a.length);
		for (int x : a) {
			l.add(x);
		}
		return l;
	}

	@SuppressWarnings("unchecked")
	static boolean accept(ServerPlayer p) {
		Challenge ch = null;
		for (Challenge c : CHALLENGES.values()) {
			if (c.to.equals(p.getUUID())) {
				ch = c;
			}
		}
		if (ch == null) {
			p.sendSystemMessage(Component.translatable("chat.duelcraft.no_challenge"));
			return false;
		}
		CHALLENGES.remove(ch.from);
		ServerPlayer from = server.getPlayerList().getPlayer(ch.from);
		ItemStack boxA = from == null ? null : deckBoxOf(from), boxB = deckBoxOf(p);
		if (from == null || boxA == null || boxB == null || !ready(from, boxA) || !ready(p, boxB)) {
			p.sendSystemMessage(Component.translatable("chat.duelcraft.challenge_failed"));
			return false;
		}
		DeckBoxItem.Deck a = DeckBoxItem.deck(boxA), b = DeckBoxItem.deck(boxB);
		SESSIONS.add(new DuelSession(server, (ServerLevel) from.level(), new DuelSession.PlayerSeat(from.getUUID(), from.getName().getString()),
			new List[] {a.main(), a.extra()}, new DuelSession.PlayerSeat(p.getUUID(), p.getName().getString()), new List[] {b.main(), b.extra()},
			from.position(), p.position()));
		return true;
	}

	public static boolean forfeit(ServerPlayer p) {
		DuelSession s = sessionOf(p.getUUID());
		if (s == null) {
			return false;
		}
		s.forfeit(s.seatOf(p.getUUID()));
		return true;
	}

	private static void tick(MinecraftServer s) {
		CHALLENGES.values().removeIf(c -> c.expires < s.getTickCount());
		for (DuelSession d : List.copyOf(SESSIONS)) {
			try {
				d.tick();
			} catch (RuntimeException e) {
				dev.duelcraft.DuelCraft.LOG.error("Duel {} failed; ending it", d.id, e);
				d.shutdown();
				SESSIONS.remove(d);
				continue;
			}
			if (d.cleaned()) {
				SESSIONS.remove(d);
			}
		}
		dev.duelcraft.item.StarterDeck.tick(s);
	}
}
