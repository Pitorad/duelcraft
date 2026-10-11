package dev.duelcraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.duelcraft.DuelCraft;
import dev.duelcraft.DuelCraftData;
import dev.duelcraft.client.dl.CharacterSelectScreen;
import dev.duelcraft.client.dl.PortraitTextures;
import dev.duelcraft.client.dl.VoiceDirector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FileUtil;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Melty's test runs (recipe test.checks): while %LOCALAPPDATA%\Melty\test\duelcraft.json exists, DuelCraft opens a
 * fresh world from the test's seed, duels a villager as a Duel Links character with every prompt answered by the
 * duel AI, and appends "pass|fail &lt;check&gt;" lines to the file the test names. Checks: cards-loaded,
 * characters-loaded, duel-starts, voice-plays, portrait-shows, duel-finishes.
 */
public final class MeltyTest {
	private static final long DEADLINE_MS = 7 * 60_000L;
	private static JsonObject test;
	private static Path checks;
	private static final Set<String> REPORTED = new HashSet<>();
	private static long seed, started, duelSeenAt;
	private static int step, ticks, character;

	private MeltyTest() {
	}

	public static boolean active() {
		return test != null;
	}

	public static void init() {
		String la = System.getenv("LOCALAPPDATA"), rehearse = System.getenv("DUELCRAFT_MELTY_TEST");
		if (la == null && rehearse == null) {
			return;
		}
		Path dir = la == null ? Path.of(".") : Path.of(la, "Melty", "test");
		Path f = rehearse != null ? Path.of(rehearse) : dir.resolve("duelcraft.json"); // DUELCRAFT_MELTY_TEST: a local rehearsal
		if (!Files.isRegularFile(f) && Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				f = s.filter(p -> p.getFileName().toString().toLowerCase().contains("duelcraft") && p.toString().endsWith(".json")).findFirst().orElse(null);
			} catch (IOException e) {
				return;
			}
		}
		if (f == null || !Files.isRegularFile(f)) {
			return;
		}
		try {
			test = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			checks = Path.of(test.get("checks").getAsString());
			seed = test.has("seed") ? test.get("seed").getAsLong() : 1;
			started = System.currentTimeMillis();
			DuelCraft.LOG.info("DuelCraft: Melty test run {} of {} (seed {})", test.has("run") ? test.get("run") : "?", test.has("runs") ? test.get("runs") : "?", seed);
		} catch (Exception e) {
			DuelCraft.LOG.warn("DuelCraft: unreadable Melty test file {}: {}", f, e.toString());
			test = null;
		}
	}

	private static void report(String check, boolean pass, String why) {
		if (!REPORTED.add(check)) {
			return;
		}
		String line = (pass ? "pass " : "fail ") + check + (pass || why == null ? "" : ": " + why) + "\n";
		DuelCraft.LOG.info("DuelCraft test: {}", line.trim());
		try {
			Files.writeString(checks, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			DuelCraft.LOG.warn("DuelCraft: writing test check failed: {}", e.toString());
		}
	}

	private static void failRest(String why) {
		for (String c : new String[] {"cards-loaded", "characters-loaded", "duel-starts", "voice-plays", "portrait-shows", "duel-finishes"}) {
			report(c, false, why);
		}
		step = 99;
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		if (test == null || step == 99) {
			return;
		}
		ticks++;
		if (System.currentTimeMillis() - started > DEADLINE_MS) {
			failRest("still at step " + step + " after " + DEADLINE_MS / 60_000 + " minutes");
			return;
		}
		switch (step) {
			case 0 -> { // the data: Master Duel cards, then Duel Links characters
				DuelCraftData.Status st = DuelCraftData.status();
				if (st == DuelCraftData.Status.NO_MASTER_DUEL || st == DuelCraftData.Status.FAILED) {
					failRest("Master Duel: " + st);
				} else if (st == DuelCraftData.Status.READY) {
					report("cards-loaded", true, null);
					step = 1;
				}
			}
			case 1 -> {
				String st = DuelCraftData.dlStatus();
				if (DuelCraftData.dl() != null) {
					List<Integer> chars = DuelCraftData.dl().characters();
					report("characters-loaded", !chars.isEmpty(), "no characters");
					character = chars.isEmpty() ? 0 : chars.get((int) Math.floorMod(seed, (long) chars.size()));
					step = 2;
				} else if (st.equals("missing") || st.startsWith("failed")) {
					report("characters-loaded", false, "Duel Links: " + st);
					step = 2;
				}
			}
			case 2 -> { // a fresh peaceful world from the test's seed
				if (!(DuelCraftClient.screen() instanceof TitleScreen title)) {
					return;
				}
				CharacterSelectScreen.pick(character);
				try {
					String name = "DuelCraft Test";
					LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
						WorldDataConfiguration.DEFAULT);
					String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), name, "");
					mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(seed, false, false), WorldPresets::createNormalWorldDimensions, title);
					step = 3;
				} catch (IOException e) {
					failRest("world creation: " + e);
				}
			}
			case 3 -> { // in the world: send the pick, a villager beside us, and the duel
				IntegratedServer server = mc.getSingleplayerServer();
				if (mc.player == null || server == null || mc.player.tickCount < 60) {
					return;
				}
				CharacterSelectScreen.sendPick();
				server.execute(() -> {
					ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst();
					if (p == null) {
						return;
					}
					var op = server.createCommandSourceStack().withPosition(p.position()).withLevel(p.level()).withSuppressedOutput();
					server.getCommands().performPrefixedCommand(op, "time set day");
					server.getCommands().performPrefixedCommand(op, "summon minecraft:villager ~2 ~ ~ {NoAI:1b}");
					server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "duel nearest starter");
				});
				step = 4;
				ticks = 0;
			}
			case 4 -> { // the duel, every prompt answered by the duel AI
				if (!ClientDuel.active()) {
					if (ticks > 20 * 30) {
						failRest("the duel did not start");
					}
					return;
				}
				if (duelSeenAt == 0) {
					duelSeenAt = System.currentTimeMillis();
					report("duel-starts", true, null);
				}
				if (!(DuelCraftClient.screen() instanceof DuelScreen)) {
					DuelCraftClient.open(new DuelScreen());
					return;
				}
				if (character > 0 && PortraitTextures.get(character, "face1") != null) {
					report("portrait-shows", true, null);
				}
				if (VoiceDirector.played > 0) {
					report("voice-plays", true, null);
				}
				if (!ClientDuel.result.isEmpty()) {
					report("duel-finishes", true, null);
					report("voice-plays", character > 0 && VoiceDirector.played > 0, character > 0 ? "no voice line played" : "no Duel Links character");
					report("portrait-shows", character > 0 && PortraitTextures.get(character, "face1") != null, "no portrait");
					DuelCraft.LOG.info("DuelCraft test: duel over ({}) after {} turns, {} voice lines", ClientDuel.result, ClientDuel.turn, VoiceDirector.played);
					step = 99; // the duel screen stays up for the picture
					return;
				}
				if (ClientDuel.prompt != null && ticks % 3 == 0 && DuelCraftClient.screen() instanceof DuelScreen d) {
					d.autoStep();
				}
			}
			default -> {
			}
		}
	}
}
