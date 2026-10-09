package dev.duelcraft.item;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.gen.HookIds;
import dev.duelcraft.gen.Settings;
import java.util.List;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Survival collecting: hostile mobs killed by a player sometimes drop a random playable card. */
public final class CardDrops {
	private static List<CardDb.CardInfo> pool;

	private CardDrops() {
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Enemy && source.getEntity() instanceof Player && entity.level() instanceof ServerLevel level
				&& level.getRandom().nextDouble() < Settings.MOB_CARD_DROP_CHANCE) {
				ItemStack card = randomCard(level.getRandom());
				if (card != null) {
					entity.spawnAtLocation(level, card);
				}
			}
		});
		HookIds.MOB_DEATH.installed();
	}

	public static ItemStack randomCard(RandomSource rng) {
		CardDb db = DuelCraftData.db();
		if (db == null) {
			return null;
		}
		if (pool == null) {
			pool = db.playable().stream().filter(c -> !c.name().isEmpty()).toList();
		}
		return pool.isEmpty() ? null : CardItem.stack(pool.get(rng.nextInt(pool.size())).cid());
	}

	/** A random card from a deck (reward for winning a duel). */
	public static ItemStack fromDeck(List<Integer> cids, RandomSource rng) {
		return cids.isEmpty() ? null : CardItem.stack(cids.get(rng.nextInt(cids.size())));
	}

	static boolean isDying(LivingEntity e) {
		return e.isDeadOrDying();
	}
}
