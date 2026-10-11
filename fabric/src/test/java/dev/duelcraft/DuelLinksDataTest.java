package dev.duelcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import dev.duelcraft.dl.DuelLinksData;
import dev.duelcraft.dl.DuelLinksLocator;
import dev.duelcraft.dl.DuelLinksVoice;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs against this PC's Duel Links (systems: dl_locator, dl_data, dl_voice). */
public class DuelLinksDataTest {
	static DuelLinksData data;

	static synchronized DuelLinksData data() throws Exception {
		if (data == null) {
			DuelLinksLocator.Install dl = DuelLinksLocator.find();
			assertNotNull(dl, "Duel Links not found");
			data = DuelLinksData.load(dl, Path.of("build/test-cache/dl_index.tsv"), p -> {
			});
		}
		return data;
	}

	/** systems: voice_tables. Yugi's summon chant for Dark Magician and the generic fallback for a card he has no line for. */
	@Test
	void voiceTables() throws Exception {
		DuelLinksData d = data();
		dev.duelcraft.dl.VoiceTables v = new dev.duelcraft.dl.VoiceTables();
		System.out.println("card lines: " + v.cardLineCount());
		assertTrue(v.cardLineCount() > 1000, "card lines " + v.cardLineCount());
		DuelLinksData.Line dm = v.cardLine(d, null, 1, "13", 3863);
		assertNotNull(dm, "Yugi summons Dark Magician");
		assertEquals("13", dm.category());
		assertNotNull(v.cardLine(d, null, 1, "19", 3863), "Yugi attacks with Dark Magician (19 shares 13's index)");
		assertEquals(null, v.cardLine(d, null, 1, "13", 4007), "no Yugi line for Blue-Eyes");
		for (dev.duelcraft.gen.VoiceTrigger t : dev.duelcraft.gen.VoiceTrigger.values()) {
			if (!t.category.equals("08") && !t.category.equals("09") && !t.category.equals("13") && !t.category.equals("19")) {
				assertNotNull(v.generic(d, 1, t), "Yugi generic " + t);
			}
		}
	}

	@Test
	void characters() throws Exception {
		DuelLinksData d = data();
		List<Integer> chars = d.characters();
		System.out.println("characters: " + chars.size() + ", clips: " + d.clipCount());
		assertTrue(chars.size() > 150, "characters " + chars.size());
		assertTrue(chars.contains(101));
		for (int e = 1; e <= 6; e++) {
			assertTrue(d.hasFace(101, e), "Jaden face " + e);
		}
		assertFalse(d.lines(101, "02", "*").isEmpty(), "Jaden has DUEL! lines");
		assertEquals(2, d.line("v0101_13_00_18").clips().size(), "two-part Neos chant");
		for (String kind : new String[] {"icon", "name", "face1", "face6"}) {
			BufferedImage img = d.image(101, kind);
			assertNotNull(img, kind);
			System.out.println(kind + " " + img.getWidth() + "x" + img.getHeight());
		}
	}

	@Test
	void voiceToOgg() throws Exception {
		DuelLinksData d = data();
		int checked = 0;
		for (String clip : new String[] {"v0101_02_00_00_0", "v0101_13_00_18_0", "v0101_13_00_18_1", "v0003_02_00_00_0", "v0201_28_00_00_0"}) {
			Path bundle = d.clipBundle(clip);
			if (bundle == null) {
				continue;
			}
			byte[] fsb = DuelLinksVoice.fsb(bundle);
			long expected = ByteBuffer.wrap(fsb).order(ByteOrder.LITTLE_ENDIAN).getLong(60) >> 34 & 0x3FFFFFFFL;
			byte[] ogg = DuelLinksVoice.fsbToOgg(fsb);
			long decoded = decode(ogg);
			System.out.println(clip + ": fsb " + fsb.length + " B -> ogg " + ogg.length + " B, samples " + decoded + " (fsb says " + expected + ")");
			assertTrue(Math.abs(decoded - expected) <= 2048, clip);
			Files.createDirectories(Path.of("build/test-cache/voice"));
			Files.write(Path.of("build/test-cache/voice/" + clip + ".ogg"), ogg);
			checked++;
		}
		assertTrue(checked >= 3);
	}

	/** Decodes Ogg Vorbis with JOrbis (Minecraft's decoder) and returns the PCM sample count per channel. */
	static long decode(byte[] ogg) {
		SyncState sync = new SyncState();
		StreamState stream = new StreamState();
		Page page = new Page();
		Packet packet = new Packet();
		Info info = new Info();
		Comment comment = new Comment();
		DspState dsp = new DspState();
		Block block = new Block(dsp);
		info.init();
		comment.init();
		sync.init();
		int index = sync.buffer(ogg.length);
		System.arraycopy(ogg, 0, sync.data, index, ogg.length);
		sync.wrote(ogg.length);
		int headers = 0;
		long samples = 0;
		boolean started = false;
		while (sync.pageout(page) == 1) {
			if (!started) {
				stream.init(page.serialno());
				started = true;
			}
			stream.pagein(page);
			while (stream.packetout(packet) == 1) {
				if (headers < 3) {
					if (info.synthesis_headerin(comment, packet) < 0) {
						throw new AssertionError("bad header " + headers);
					}
					if (++headers == 3) {
						dsp.synthesis_init(info);
						block.init(dsp);
					}
					continue;
				}
				if (block.synthesis(packet) == 0) {
					dsp.synthesis_blockin(block);
				}
				float[][][] pcm = new float[1][][];
				int[] off = new int[info.channels];
				int n;
				while ((n = dsp.synthesis_pcmout(pcm, off)) > 0) {
					samples += n;
					dsp.synthesis_read(n);
				}
			}
		}
		return samples;
	}
}
