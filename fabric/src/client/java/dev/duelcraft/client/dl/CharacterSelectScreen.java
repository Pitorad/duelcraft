package dev.duelcraft.client.dl;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.gen.Payloads;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * Pick the Duel Links character you duel as (systems: character_select): every character in the player's Duel
 * Links with their icon and name plate. Saved as character=&lt;id&gt; in config/duelcraft.properties.
 */
public final class CharacterSelectScreen extends Screen {
	private static final int CELL_W = 44, CELL_H = 54;
	private List<Integer> chars = List.of();
	private int scroll, hovered = -1;

	public CharacterSelectScreen() {
		super(Component.translatable("gui.duelcraft.pick_character"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		chars = DuelCraftData.dl() == null ? List.of() : DuelCraftData.dl().characters();
	}

	private int cols() {
		return Math.max(1, (width - 20) / CELL_W);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		g.fill(0, 0, width, height, 0xF0101420);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		super.extractRenderState(g, mx, my, a);
		int picked = picked();
		if (chars.isEmpty() && DuelCraftData.dl() != null) {
			init(); // opened while Duel Links was still being read
		}
		if (chars.isEmpty()) {
			g.centeredText(font, title, width / 2, 5, 0xFFFFE070);
			g.centeredText(font, Component.translatable(DuelCraftData.dlStatus().equals("missing") ? "gui.duelcraft.dl_missing" : "gui.duelcraft.dl_loading"),
				width / 2, height / 2, 0xFFFF9090);
			return;
		}
		int cols = cols(), x0 = (width - cols * CELL_W) / 2, y0 = 20 - scroll;
		hovered = -1;
		for (int i = -1; i < chars.size(); i++) {
			int slot = i + 1, x = x0 + (slot % cols) * CELL_W, y = y0 + (slot / cols) * CELL_H;
			if (y + CELL_H < 16 || y > height) {
				continue;
			}
			int ch = i < 0 ? 0 : chars.get(i);
			boolean hover = my >= 17 && my < height - 16 && mx >= x && mx < x + CELL_W - 4 && my >= y && my < y + CELL_H - 4;
			if (hover) {
				hovered = ch;
			}
			g.fill(x, y, x + CELL_W - 4, y + CELL_H - 4, ch == picked ? 0xFF3A6A3A : hover ? 0xFF3A4A70 : 0xFF202838);
			if (ch == 0) {
				g.centeredText(font, Component.translatable("gui.duelcraft.no_character"), x + (CELL_W - 4) / 2, y + (CELL_H - 4) / 2 - 4, 0xFFC0C0C0);
				continue;
			}
			PortraitTextures.Tex icon = PortraitTextures.get(ch, "icon");
			if (icon != null) {
				int h = CELL_H - 6, w = h * icon.width() / icon.height();
				g.blit(RenderPipelines.GUI_TEXTURED, icon.id(), x + (CELL_W - 4 - w) / 2, y + 1, 0, 0, w, h, w, h);
			}
			// the name plate over the bottom of the icon
			PortraitTextures.Tex name = PortraitTextures.get(ch, "name");
			if (name != null) {
				int w = CELL_W - 4, h = w * name.height() / name.width();
				g.blit(RenderPipelines.GUI_TEXTURED, name.id(), x, y + CELL_H - 4 - h, 0, 0, w, h, w, h);
			}
		}
		// title and hint bands stay on top of the scrolled grid
		g.fill(0, 0, width, 17, 0xFF101420);
		g.fill(0, height - 16, width, height, 0xFF101420);
		g.centeredText(font, title, width / 2, 5, 0xFFFFE070);
		g.centeredText(font, Component.translatable("gui.duelcraft.pick_hint"), width / 2, height - 12, 0xFFA0A0A0);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		int rows = (chars.size() + 1 + cols() - 1) / cols();
		scroll = Math.max(0, Math.min(Math.max(0, rows * CELL_H - (height - 40)), scroll - (int) (sy * 30)));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (hovered >= 0) {
			pick(hovered);
			onClose();
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	// ------------------------------------------------------------------ the saved pick

	static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("duelcraft.properties");
	}

	public static int picked() {
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
			p.load(r);
			return Integer.parseInt(p.getProperty("character", "0").trim());
		} catch (IOException | NumberFormatException e) {
			return 0;
		}
	}

	/** Saves the pick (keeping the file's other keys, such as Melty's join=) and tells the server. */
	public static void pick(int character) {
		Properties p = new Properties();
		Path f = file();
		try {
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					p.load(r);
				}
			}
			p.setProperty("character", Integer.toString(character));
			p.putIfAbsent("join", "");
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
				p.store(w, "DuelCraft: join= is set by Melty for join links; character= is your Duel Links character (K in game)");
			}
		} catch (IOException e) {
			dev.duelcraft.DuelCraft.LOG.warn("Saving the character pick failed: {}", e.toString());
		}
		sendPick();
	}

	public static void sendPick() {
		if (ClientPlayNetworking.canSend(Payloads.CharacterPickPayload.TYPE)) {
			ClientPlayNetworking.send(new Payloads.CharacterPickPayload(picked()));
		}
	}
}
