package dev.duelcraft.client;

import dev.duelcraft.cards.CardDb;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.FormattedCharSequence;

/** Card tooltip (systems: card_tooltip): Master Duel art, type line, stats and wrapped card text. */
public final class CardTooltip implements ClientTooltipComponent {
	private static final int ART = 56, WIDTH = 230, TEXT_LINES = 10;
	private final int cid;

	public CardTooltip(int cid) {
		this.cid = cid;
	}

	private List<FormattedCharSequence> text(Font font) {
		CardDb.CardInfo c = CardRender.info(cid);
		return c == null ? List.of() : CardRender.wrap(font, c.text(), WIDTH, TEXT_LINES);
	}

	@Override
	public int getHeight(Font font) {
		return ART + 4 + text(font).size() * (font.lineHeight + 1) + 2;
	}

	@Override
	public int getWidth(Font font) {
		return WIDTH;
	}

	@Override
	public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor g) {
		CardDb.CardInfo c = CardRender.info(cid);
		CardRender.art(g, cid, x, y, ART);
		if (c == null) {
			g.text(font, "Master Duel data loading...", x + ART + 6, y + 2, 0xFFAAAAAA);
			return;
		}
		int tx = x + ART + 6;
		g.text(font, CardRender.typeLine(c), tx, y + 2, 0xFFE0C060);
		g.text(font, CardRender.statsLine(c), tx, y + 14, 0xFFFFFFFF);
		if (!c.playable()) {
			g.text(font, "Not playable in duels yet", tx, y + 26, 0xFFFF6060);
		} else if (ArtTextures.missing(cid)) {
			g.text(font, "Art not downloaded in Master Duel", tx, y + 26, 0xFF909090);
		}
		int ty = y + ART + 4;
		for (FormattedCharSequence line : text(font)) {
			g.text(font, line, x, ty, 0xFFDDDDDD);
			ty += font.lineHeight + 1;
		}
	}
}
