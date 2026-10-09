package dev.duelcraft;

import dev.duelcraft.duel.DuelManager;
import dev.duelcraft.item.CardDrops;
import dev.duelcraft.item.ModItems;
import dev.duelcraft.item.StarterDeck;
import dev.duelcraft.net.DuelNet;
import dev.duelcraft.world.CardEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DuelCraft: Minecraft x Yu-Gi-Oh! Master Duel. The cards (names, text, stats, art) come from the player's
 * own Master Duel; duels run on EDOPro's engine. Design: design/*.json; journal: MODLOG.md.
 */
public final class DuelCraft implements ModInitializer {
	public static final String MOD_ID = "duelcraft";
	public static final Logger LOG = LoggerFactory.getLogger("DuelCraft");

	@Override
	public void onInitialize() {
		ModItems.init();
		CardEntity.init();
		DuelNet.init();
		DuelManager.init();
		StarterDeck.init();
		CardDrops.init();
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer p) {
				DuelManager.forfeit(p);
			}
		});
		DuelCraftData.startLoading();
		LOG.info("DuelCraft starting; reading Yu-Gi-Oh! Master Duel's cards in the background");
	}
}
