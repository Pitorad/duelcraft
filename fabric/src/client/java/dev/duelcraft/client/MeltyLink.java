package dev.duelcraft.client;

import dev.duelcraft.DuelCraft;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.MinecraftServer;

/**
 * Melty multiplayer (systems: multiplayer_link). Hosting: when DUELCRAFT_LAN_PORT is set, the world is
 * opened to LAN on that port as soon as it loads; e4mc then logs "Domain assigned: &lt;address&gt;" so friends
 * can join through its relay. Joining: config/duelcraft.properties join=&lt;address&gt; connects from the title screen.
 */
public final class MeltyLink {
	private static boolean joinTried;

	private MeltyLink() {
	}

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			String port = System.getenv("DUELCRAFT_LAN_PORT");
			MinecraftServer server = mc.getSingleplayerServer();
			if (port == null || port.isBlank() || server == null || server.isPublished()) {
				return;
			}
			mc.execute(() -> {
				if (System.getenv("DUELCRAFT_LAN_OFFLINE") != null) {
					server.setUsesAuthentication(false);
				}
				boolean ok = server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, Integer.parseInt(port.trim()));
				DuelCraft.LOG.info("DuelCraft: world opened to LAN on port {} ({})", port.trim(), ok ? "ok" : "FAILED");
			});
		});
	}

	static Path file(Minecraft mc) {
		return mc.gameDirectory.toPath().resolve("config").resolve("duelcraft.properties");
	}

	/** The join= address from config/duelcraft.properties (Melty writes it for a join link), or null. */
	static String joinAddress(Minecraft mc) {
		Path f = file(mc);
		Properties p = new Properties();
		try {
			if (!Files.exists(f)) {
				Files.createDirectories(f.getParent());
				try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
					w.write("# DuelCraft: to play in a friend's world, put their address after join= (Melty does this for join links)\njoin=\n");
				}
				return null;
			}
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				p.load(r);
			}
		} catch (IOException e) {
			return null;
		}
		String j = p.getProperty("join", "").trim();
		return j.isEmpty() ? null : j;
	}

	/** Called every client tick: the first time the title screen shows, join the friend's world if asked to. */
	public static void tick(Minecraft mc) {
		if (joinTried || !(DuelCraftClient.screen() instanceof TitleScreen title)) {
			return;
		}
		joinTried = true;
		String join = joinAddress(mc);
		if (join != null) {
			DuelCraft.LOG.info("DuelCraft: joining {}", join);
			ConnectScreen.startConnecting(title, mc, ServerAddress.parseString(join), new ServerData("DuelCraft", join, ServerData.Type.OTHER), false, null);
		}
	}
}
