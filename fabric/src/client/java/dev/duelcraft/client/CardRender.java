package dev.duelcraft.client;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.gen.MdEncodings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/** Draws cards in GUIs: our frame texture, the Master Duel art in the art window, or the card back. */
public final class CardRender {
	public static final Identifier BACK = Identifier.fromNamespaceAndPath("duelcraft", "textures/entity/card_back.png");

	private CardRender() {
	}

	public static Identifier frame(String frame) {
		return Identifier.fromNamespaceAndPath("duelcraft", "textures/entity/frame_" + frame + ".png");
	}

	public static CardDb.CardInfo info(int cid) {
		CardDb db = DuelCraftData.db();
		return db == null ? null : db.byCid(cid);
	}

	public static CardDb.CardInfo byPasscode(int code) {
		CardDb db = DuelCraftData.db();
		return db == null || code == 0 ? null : db.byPasscode(code);
	}

	/** A card w x h at (x, y); cid 0 = card back. */
	public static void draw(GuiGraphicsExtractor g, int cid, int x, int y, int w, int h) {
		if (cid == 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, BACK, x, y, 0, 0, w, h, w, h);
			return;
		}
		CardDb.CardInfo c = info(cid);
		g.blit(RenderPipelines.GUI_TEXTURED, frame(c == null ? "normal" : c.frame()), x, y, 0, 0, w, h, w, h);
		Identifier art = ArtTextures.get(cid);
		// art window of the 64x92 frame: x 7..57, y 15..65
		int ax = x + w * 7 / 64, ay = y + h * 15 / 92, aw = w * 50 / 64, ah = h * 50 / 92;
		if (art != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, art, ax, ay, 0, 0, aw, ah, aw, ah);
		}
	}

	/** Square art only (tooltips, previews). */
	public static void art(GuiGraphicsExtractor g, int cid, int x, int y, int size) {
		Identifier art = ArtTextures.get(cid);
		if (art != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, art, x, y, 0, 0, size, size, size, size);
		} else {
			g.fill(x, y, x + size, y + size, 0xFF202030);
		}
	}

	/** "LIGHT Dragon / Normal  Lv 8" style type line. */
	public static String typeLine(CardDb.CardInfo c) {
		List<String> parts = new ArrayList<>();
		if (c.isMonster()) {
			parts.add(MdEncodings.ATTRIBUTE_NAMES.getOrDefault(c.attribute(), ""));
			parts.add(MdEncodings.RACE_NAMES.getOrDefault(c.race(), ""));
		}
		for (var e : new Object[][] {{0x2, "Spell"}, {0x4, "Trap"}, {0x10, "Normal"}, {0x20, "Effect"}, {0x40, "Fusion"}, {0x80, "Ritual"},
			{0x1000, "Tuner"}, {0x2000, "Synchro"}, {0x800000, "Xyz"}, {0x1000000, "Pendulum"}, {0x4000000, "Link"}, {0x10000, "Quick-Play"},
			{0x20000, "Continuous"}, {0x40000, "Equip"}, {0x80000, "Field"}, {0x100000, "Counter"}, {0x4000, "Token"}}) {
			if ((c.type() & (int) e[0]) != 0) {
				parts.add((String) e[1]);
			}
		}
		return String.join(" ", parts.stream().filter(s -> !s.isEmpty()).toList());
	}

	public static String statsLine(CardDb.CardInfo c) {
		if (!c.isMonster()) {
			return "";
		}
		String lv = (c.type() & CardDb.TYPE_LINK) != 0 ? "LINK-" + Integer.bitCount(c.linkMarkers())
			: ((c.type() & CardDb.TYPE_XYZ) != 0 ? "Rank " : "Level ") + c.level();
		String atk = c.atk() < 0 ? "?" : String.valueOf(c.atk());
		String def = (c.type() & CardDb.TYPE_LINK) != 0 ? "" : "  DEF " + (c.def() < 0 ? "?" : c.def());
		return lv + "   ATK " + atk + def;
	}

	public static List<FormattedCharSequence> wrap(Font font, String text, int width, int maxLines) {
		List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.literal(text.replace("\r", "")), width));
		if (lines.size() > maxLines) {
			lines = new ArrayList<>(lines.subList(0, maxLines));
		}
		return lines;
	}
}
