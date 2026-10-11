package dev.duelcraft.client.dl;

import com.mojang.blaze3d.platform.NativeImage;
import dev.duelcraft.DuelCraft;
import dev.duelcraft.DuelCraftData;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Duel Links character images as textures (systems: character_portrait): kind "icon", "name" or "face1".."face6",
 * decoded from the player's Duel Links off the main thread, uploaded on the render thread. Returns null until ready.
 */
public final class PortraitTextures {
	public record Tex(Identifier id, int width, int height) {
	}

	private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "DuelCraft portraits");
		t.setDaemon(true);
		return t;
	});
	private static final Map<String, Tex> READY = new ConcurrentHashMap<>();
	private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
	private static final Set<String> NONE = ConcurrentHashMap.newKeySet();

	private PortraitTextures() {
	}

	public static Tex get(int character, String kind) {
		if (character <= 0 || DuelCraftData.dl() == null) {
			return null;
		}
		String key = character + "_" + kind;
		Tex t = READY.get(key);
		if (t != null || NONE.contains(key)) {
			return t;
		}
		if (LOADING.add(key)) {
			POOL.execute(() -> {
				try {
					BufferedImage img = DuelCraftData.dl().image(character, kind);
					if (img == null) {
						NONE.add(key);
						return;
					}
					int w = img.getWidth(), h = img.getHeight();
					int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
					Minecraft.getInstance().execute(() -> {
						NativeImage n = new NativeImage(w, h, false);
						for (int y = 0; y < h; y++) {
							for (int x = 0; x < w; x++) {
								n.setPixel(x, y, argb[y * w + x]);
							}
						}
						Identifier id = Identifier.fromNamespaceAndPath("duelcraft", "dl/" + key);
						Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "DuelCraft " + key, n));
						READY.put(key, new Tex(id, w, h));
					});
				} catch (Exception e) {
					NONE.add(key);
					DuelCraft.LOG.warn("Duel Links image {} failed: {}", key, e.toString());
				} finally {
					LOADING.remove(key);
				}
			});
		}
		return null;
	}
}
