package dev.duelcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.duelcraft.DuelCraftData;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Card art from the player's Master Duel as GPU textures (systems: client_art): decoded/cached by
 * CardArtCache off the main thread, scaled to 256 px, uploaded on the render thread, and kept in an LRU of
 * 400 textures (~100 MB).
 */
public final class ArtTextures {
	private static final int SIZE = 256, MAX = 400;
	private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "DuelCraft art");
		t.setDaemon(true);
		return t;
	});
	private static final Map<Integer, Identifier> READY = new LinkedHashMap<>(64, 0.75f, true);
	private static final Set<Integer> LOADING = ConcurrentHashMap.newKeySet();
	private static final Set<Integer> NONE = ConcurrentHashMap.newKeySet();

	private ArtTextures() {
	}

	/** The art texture for a card, or null while it loads / when Master Duel has none for it. */
	public static Identifier get(int cid) {
		if (cid == 0) {
			return null;
		}
		Identifier id = READY.get(cid);
		if (id != null || NONE.contains(cid) || DuelCraftData.art() == null) {
			return id;
		}
		if (LOADING.add(cid)) {
			POOL.execute(() -> load(cid));
		}
		return null;
	}

	public static boolean missing(int cid) {
		return NONE.contains(cid);
	}

	private static void load(int cid) {
		try {
			Path png = DuelCraftData.art().png(cid);
			if (png == null) {
				NONE.add(cid);
				return;
			}
			BufferedImage src = ImageIO.read(png.toFile());
			BufferedImage small = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = small.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, SIZE, SIZE, null);
			g.dispose();
			int[] argb = small.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
			Minecraft.getInstance().execute(() -> upload(cid, argb));
		} catch (Exception e) {
			NONE.add(cid);
			dev.duelcraft.DuelCraft.LOG.warn("Card art {} failed: {}", cid, e.toString());
		} finally {
			LOADING.remove(cid);
		}
	}

	private static void upload(int cid, int[] argb) {
		NativeImage img = new NativeImage(SIZE, SIZE, false);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				img.setPixel(x, y, argb[y * SIZE + x]);
			}
		}
		Identifier id = Identifier.fromNamespaceAndPath("duelcraft", "art/" + cid);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "DuelCraft card " + cid, img));
		READY.put(cid, id);
		while (READY.size() > MAX) {
			Map.Entry<Integer, Identifier> eldest = READY.entrySet().iterator().next();
			READY.remove(eldest.getKey());
			Minecraft.getInstance().getTextureManager().release(eldest.getValue());
		}
	}
}
