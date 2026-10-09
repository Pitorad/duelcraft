package dev.duelcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.duelcraft.gen.HookIds;
import dev.duelcraft.gen.Payloads;
import dev.duelcraft.item.CardItem;
import dev.duelcraft.item.ModItems;
import dev.duelcraft.world.CardEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;


/** Client wiring: the client-side rows of the hooks sheet. */
public final class DuelCraftClient implements ClientModInitializer {
	public static final KeyMapping DUEL_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.duelcraft.duel_screen", InputConstants.Type.KEYBOARD,
		InputConstants.KEY_V, new KeyMapping.Category(Identifier.fromNamespaceAndPath("duelcraft", "duelcraft"))));

	public static Screen screen() {
		return Minecraft.getInstance().gui.screen();
	}

	public static void open(Screen s) {
		Minecraft.getInstance().gui.setScreen(s);
	}

	@Override
	public void onInitializeClient() {
		HookIds.KEYBIND.installed();
		EntityRendererRegistry.register(CardEntity.TYPE, CardEntityRenderer::new);
		HookIds.ENTITY_RENDERER.installed();
		ClientTooltipComponentCallback.EVENT.register(data -> data instanceof CardItem.CardTooltipData d ? new CardTooltip(d.cid()) : null);
		HookIds.TOOLTIP_COMPONENT.installed();
		MenuScreens.register(ModItems.DECK_BOX_MENU, DeckBoxScreen::new);
		HookIds.MENU_SCREEN.installed();
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("duelcraft", "duel"), DuelHud::render);
		HookIds.HUD.installed();

		ClientPlayNetworking.registerGlobalReceiver(Payloads.DuelStartPayload.TYPE, (p, ctx) -> {
			ClientDuel.start(p);
			open(new DuelScreen());
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.DuelStatePayload.TYPE, (p, ctx) -> ClientDuel.state(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.DuelEventPayload.TYPE, (p, ctx) -> ClientDuel.event(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.DuelPromptPayload.TYPE, (p, ctx) -> ClientDuel.prompt(p));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.DuelEndPayload.TYPE, (p, ctx) -> ClientDuel.end(p));
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> ClientDuel.leave());

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			MeltyLink.tick(mc);
			TestBridge.tick(mc);
			while (DUEL_KEY.consumeClick()) {
				if (ClientDuel.active() && screen() == null) {
					open(new DuelScreen());
				}
			}
			// a prompt for us opens the duel screen (unless another screen is up)
			if (ClientDuel.prompt != null && screen() == null && System.currentTimeMillis() - ClientDuel.promptArrived < 400) {
				open(new DuelScreen());
			}
		});
		HookIds.CLIENT_TICK.installed();
		MeltyLink.init();
		HookIds.WORLD_JOIN.installed();
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof TitleScreen) {
				ScreenEvents.afterExtract(screen).register((s, g, mx, my, a) -> MasterDuelStatus.draw(g, Minecraft.getInstance().font, 4, 4));
			}
		});
		HookIds.TITLE_SCREEN.installed();
	}
}
