package dev.duelcraft.client;

import dev.duelcraft.DuelCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Test-only automation, active only when DUELCRAFT_TEST_DIR is set (never in a player's install): reads
 * commands from &lt;dir&gt;/cmd.txt and writes results to &lt;dir&gt;/out.txt, so an agent can drive menus and duels
 * without OS input. Commands: wait N | widgets | press &lt;label&gt; | click x y (GUI px) | chat &lt;text&gt; |
 * duel &lt;button label&gt; | duelcard con loc seq | screen | close | esc | type &lt;text&gt;.
 */
public final class TestBridge {
	private static final Path DIR = System.getenv("DUELCRAFT_TEST_DIR") == null ? null : Path.of(System.getenv("DUELCRAFT_TEST_DIR"));
	private static final Deque<String> QUEUE = new ArrayDeque<>();
	private static int wait;

	private TestBridge() {
	}

	public static boolean enabled() {
		return DIR != null;
	}

	public static void tick(Minecraft mc) {
		if (DIR == null) {
			return;
		}
		try {
			Path cmd = DIR.resolve("cmd.txt");
			if (Files.exists(cmd)) {
				List<String> lines = Files.readAllLines(cmd, StandardCharsets.UTF_8);
				Files.delete(cmd);
				QUEUE.addAll(lines);
			}
		} catch (IOException e) {
			return;
		}
		while (!QUEUE.isEmpty()) {
			if (wait > 0) {
				wait--;
				return;
			}
			String line = QUEUE.poll().trim();
			if (line.isEmpty()) {
				continue;
			}
			try {
				out("> " + line + "\n" + run(mc, line));
			} catch (Exception e) {
				out("> " + line + "\nERROR " + e);
			}
		}
	}

	private static String run(Minecraft mc, String line) {
		String[] a = line.split(" ", 2);
		String arg = a.length > 1 ? a[1] : "";
		Screen s = DuelCraftClient.screen();
		switch (a[0]) {
			case "wait" -> {
				wait = Integer.parseInt(arg);
				return "ok";
			}
			case "screen" -> {
				return s == null ? "none" : s.getClass().getName() + " " + s.width + "x" + s.height;
			}
			case "widgets" -> {
				StringBuilder sb = new StringBuilder();
				if (s != null) {
					for (GuiEventListener l : s.children()) {
						if (l instanceof AbstractWidget w) {
							sb.append(w.getMessage().getString()).append(" @ ").append(w.getX() + w.getWidth() / 2).append(',')
								.append(w.getY() + w.getHeight() / 2).append(w.active ? "" : " (inactive)").append('\n');
						}
					}
				}
				if (s instanceof DuelScreen d) {
					sb.append(d.describe());
				}
				return sb.toString();
			}
			case "press" -> {
				for (GuiEventListener l : s.children()) {
					if (l instanceof AbstractWidget w && w.getMessage().getString().equalsIgnoreCase(arg)) {
						if (w instanceof net.minecraft.client.gui.components.AbstractButton b) {
							b.onPress(new MouseButtonEvent(w.getX() + 1, w.getY() + 1, new MouseButtonInfo(0, 0)));
						} else {
							click(s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
						}
						return "pressed";
					}
				}
				return "no widget " + arg;
			}
			case "click" -> {
				String[] xy = arg.split(" ");
				click(s, Double.parseDouble(xy[0]), Double.parseDouble(xy[1]));
				return "clicked";
			}
			case "type" -> {
				for (char c : arg.toCharArray()) {
					s.charTyped(new net.minecraft.client.input.CharacterEvent(c));
				}
				return "typed";
			}
			case "chat" -> {
				if (arg.startsWith("/")) {
					mc.player.connection.sendCommand(arg.substring(1));
				} else {
					mc.player.connection.sendChat(arg);
				}
				return "sent";
			}
			case "duel" -> {
				return s instanceof DuelScreen d ? d.pressLabel(arg) : "not in duel screen";
			}
			case "duelcard" -> {
				String[] k = arg.split(" ");
				return s instanceof DuelScreen d ? d.clickCard(Integer.parseInt(k[0]), Integer.parseInt(k[1]), Integer.parseInt(k[2])) : "not in duel screen";
			}
			case "duelauto" -> {
				// answer up to N prompts, waiting for each to arrive
				int n = arg.isEmpty() ? 1 : Integer.parseInt(arg);
				if (!(s instanceof DuelScreen d)) {
					return "not in duel screen";
				}
				if (ClientDuel.prompt == null || !ClientDuel.result.isEmpty()) {
					if (n > 1 && ClientDuel.result.isEmpty()) {
						QUEUE.addFirst("duelauto " + n);
						wait = 5;
					}
					return ClientDuel.result.isEmpty() ? "waiting" : "duel over: " + ClientDuel.result;
				}
				String r = "[" + ClientDuel.prompt.getClass().getSimpleName() + "] " + d.autoStep();
				if (n > 1) {
					QUEUE.addFirst("duelauto " + (n - 1));
					wait = 3;
				}
				return r;
			}
			case "inv" -> {
				DuelCraftClient.open(mc.player.isCreative() ? new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(mc.player,
					mc.player.connection.enabledFeatures(), true) : new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
				return "inventory";
			}
			case "use" -> {
				mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
				return "used";
			}
			case "slot" -> {
				mc.player.getInventory().setSelectedSlot(Integer.parseInt(arg));
				return "slot " + arg;
			}
			case "mouse" -> {
				// move the real cursor (GUI px -> window px) so hover tooltips show
				String[] xy = arg.split(" ");
				double sc = mc.getWindow().getGuiScale();
				mc.mouseHandler.onMove(mc.getWindow().handle(), Double.parseDouble(xy[0]) * sc, Double.parseDouble(xy[1]) * sc, 0, 0);
				return "mouse";
			}
			case "duelopen" -> {
				DuelCraftClient.open(new DuelScreen());
				return "opened";
			}
			case "close" -> {
				DuelCraftClient.open(null);
				return "closed";
			}
			case "esc" -> {
				if (s != null) {
					s.onClose();
				}
				return "esc";
			}
			default -> {
				return "unknown command";
			}
		}
	}

	private static void click(Screen s, double x, double y) {
		MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
		s.mouseClicked(e, false);
		s.mouseReleased(e);
	}

	private static void out(String text) {
		try {
			Files.writeString(DIR.resolve("out.txt"), text + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			DuelCraft.LOG.warn("test bridge: {}", e.toString());
		}
	}
}
