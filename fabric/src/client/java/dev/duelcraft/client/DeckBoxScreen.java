package dev.duelcraft.client;

import dev.duelcraft.item.DeckBoxItem;
import dev.duelcraft.item.DeckBoxMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Deck box editor (systems: deck_box_menu): 75 card slots drawn without textures, plus deck counts. */
public final class DeckBoxScreen extends AbstractContainerScreen<DeckBoxMenu> {
	public DeckBoxScreen(DeckBoxMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, 16 + DeckBoxMenu.COLS * 18, 18 + DeckBoxMenu.ROWS * 18 + 14 + 76 + 8);
		this.inventoryLabelX = 8 + (DeckBoxMenu.COLS * 18 - 9 * 18) / 2;
		this.inventoryLabelY = 18 + DeckBoxMenu.ROWS * 18 + 3;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractBackground(g, mx, my, a);
		int x = leftPos, y = topPos;
		g.fill(x, y, x + imageWidth, y + imageHeight, 0xFF2A2018);
		g.outline(x, y, imageWidth, imageHeight, 0xFFC09040);
		for (var slot : menu.slots) {
			int sx = x + slot.x - 1, sy = y + slot.y - 1;
			boolean deck = slot.index < DeckBoxItem.SLOTS;
			g.fill(sx, sy, sx + 18, sy + 18, deck ? 0xFF3C3428 : 0xFF404040);
			g.outline(sx, sy, 18, 18, 0xFF1A140E);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
		int[] n = menu.counts();
		g.text(font, title, titleLabelX, titleLabelY, 0xFFE0C080, false);
		String counts = "Main " + n[0] + "/40-60   Extra " + n[1] + "/15";
		int color = n[0] >= 40 && n[0] <= 60 && n[1] <= 15 ? 0xFF80FF80 : 0xFFFF8080;
		g.text(font, counts, imageWidth - 8 - font.width(counts), titleLabelY, color, false);
		g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFC0C0C0, false);
	}
}
