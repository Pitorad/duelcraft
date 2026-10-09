package dev.duelcraft.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** In-world duel overlay (systems: duel_hud): LP, turn, phase and whose move it is. */
public final class DuelHud {
	private DuelHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!ClientDuel.active() || DuelCraftClient.screen() != null) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth(), x = w / 2 - 110, y = 4;
		g.fill(x, y, x + 220, y + 34, 0xA0101420);
		int me = ClientDuel.seat;
		g.text(font, "You " + ClientDuel.lp[me] + " LP", x + 6, y + 4, 0xFF70B8FF);
		String opp = ClientDuel.opponent + " " + ClientDuel.lp[1 - me] + " LP";
		g.text(font, opp, x + 214 - font.width(opp), y + 4, 0xFFFF7070);
		String status;
		int color;
		if (!ClientDuel.result.isEmpty()) {
			status = ClientDuel.result;
			color = 0xFFFFFF60;
		} else if (ClientDuel.prompt != null) {
			status = Component.translatable("gui.duelcraft.your_turn", DuelCraftClient.DUEL_KEY.getTranslatedKeyMessage()).getString();
			color = (System.currentTimeMillis() / 500 % 2 == 0) ? 0xFFFFE070 : 0xFFFFFFFF;
		} else {
			status = "Turn " + ClientDuel.turn + " · " + DuelScreen.phaseName(ClientDuel.phase);
			color = 0xFFC0C0C0;
		}
		g.centeredText(font, status, w / 2, y + 20, color);
	}
}
