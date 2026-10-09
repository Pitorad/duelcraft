package dev.duelcraft.client;

import dev.duelcraft.DuelCraftData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** One status line about the player's Master Duel data (systems: md_status): title screen and pause-free HUD. */
public final class MasterDuelStatus {
	private MasterDuelStatus() {
	}

	public static Component line() {
		return switch (DuelCraftData.status()) {
			case SEARCHING, INDEXING -> Component.translatable("gui.duelcraft.md_indexing", DuelCraftData.progress());
			case LOADING -> Component.translatable("gui.duelcraft.md_indexing", 99);
			case READY -> Component.translatable("gui.duelcraft.md_ready", DuelCraftData.db().size());
			case NO_MASTER_DUEL -> Component.translatable("gui.duelcraft.md_missing");
			case FAILED -> Component.translatable("gui.duelcraft.md_failed", DuelCraftData.error());
		};
	}

	public static int color() {
		return switch (DuelCraftData.status()) {
			case READY -> 0xFF80FF80;
			case NO_MASTER_DUEL, FAILED -> 0xFFFF7070;
			default -> 0xFFFFE070;
		};
	}

	public static void draw(GuiGraphicsExtractor g, Font font, int x, int y) {
		g.textWithWordWrap(font, line(), x, y, Math.max(100, g.guiWidth() - 2 * x), color());
	}
}
