package dev.duelcraft.item;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.gen.Decks;
import dev.duelcraft.gen.HookIds;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Gives each player the starter deck (decks sheet: starter) once per world, as soon as the Master Duel data
 * is loaded, so the card frames and names are right from the start.
 */
public final class StarterDeck {
	private static final String TAG = "duelcraft.starter";

	private StarterDeck() {
	}

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> tryGive(handler.player));
		HookIds.PLAYER_JOIN.installed();
	}

	/** Called every second from the server tick for players still waiting (first index can take ~30 s). */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0 || DuelCraftData.db() == null) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			tryGive(p);
		}
	}

	static void tryGive(ServerPlayer p) {
		if (p.entityTags().contains(TAG) || DuelCraftData.db() == null) {
			return;
		}
		p.addTag(TAG);
		ItemStack box = DeckBoxItem.withDeck(Decks.STARTER.main(), Decks.STARTER.extra());
		box.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(Decks.STARTER.displayName()));
		if (!p.getInventory().add(box)) {
			p.spawnAtLocation((net.minecraft.server.level.ServerLevel) p.level(), box);
		}
		p.sendSystemMessage(Component.translatable("chat.duelcraft.starter").withStyle(ChatFormatting.GOLD));
	}
}
