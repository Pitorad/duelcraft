package dev.duelcraft;

import dev.duelcraft.cards.CardDb;
import dev.duelcraft.duel.CardScripts;
import dev.duelcraft.duel.OcgCore;
import dev.duelcraft.md.CardArtCache;
import dev.duelcraft.md.MasterDuelCardTables;
import dev.duelcraft.md.MasterDuelIndex;
import dev.duelcraft.md.MasterDuelLocator;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads the player's Master Duel data once per game start, off the main thread: locate -> index -> card
 * tables -> CardDb -> art cache -> card scripts -> duel engine. Everything else asks status() first.
 */
public final class DuelCraftData {
	public enum Status { SEARCHING, INDEXING, LOADING, READY, NO_MASTER_DUEL, FAILED }

	private static volatile Status status = Status.SEARCHING;
	private static volatile int progress;
	private static volatile String error = "";
	private static volatile CardDb db;
	private static volatile CardArtCache art;
	private static volatile OcgCore core;
	private static volatile MasterDuelLocator.Install install;
	private static Thread loader;

	private DuelCraftData() {
	}

	public static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("duelcraft");
	}

	public static synchronized void startLoading() {
		if (loader != null) {
			return;
		}
		loader = new Thread(DuelCraftData::load, "DuelCraft Master Duel loader");
		loader.setDaemon(true);
		loader.start();
	}

	private static void load() {
		try {
			install = MasterDuelLocator.find();
			if (install == null) {
				status = Status.NO_MASTER_DUEL;
				DuelCraft.LOG.warn("Yu-Gi-Oh! Master Duel was not found. Install it on Steam (free) and start it once.");
				return;
			}
			DuelCraft.LOG.info("Master Duel found at {}", install.gameDir());
			status = Status.INDEXING;
			MasterDuelIndex index = MasterDuelIndex.load(install.bundleDir(), dir().resolve("md_index.tsv"), p -> progress = p);
			status = Status.LOADING;
			MasterDuelCardTables tables = MasterDuelCardTables.load(index);
			CardDb cards = new CardDb(tables);
			art = new CardArtCache(index, dir().resolve("art")).alternates(cards::alternates);
			db = cards;
			core = new OcgCore(dir(), cards, new CardScripts(dir()));
			int[] v = core.version();
			DuelCraft.LOG.info("DuelCraft ready: {} Master Duel cards ({} playable), duel engine {}.{}", cards.size(), cards.playable().size(), v[0], v[1]);
			status = Status.READY;
		} catch (Throwable t) {
			error = String.valueOf(t.getMessage());
			status = Status.FAILED;
			DuelCraft.LOG.error("Loading Master Duel data failed", t);
		}
	}

	public static Status status() {
		return status;
	}

	public static int progress() {
		return progress;
	}

	public static String error() {
		return error;
	}

	public static boolean ready() {
		return status == Status.READY;
	}

	/** Card data (names/text/stats); available from LOADING's end even before the engine is up. */
	public static CardDb db() {
		return db;
	}

	public static CardArtCache art() {
		return art;
	}

	public static OcgCore core() {
		return core;
	}

	public static MasterDuelLocator.Install install() {
		return install;
	}
}
