package dev.duelcraft.md;

import dev.duelcraft.nativelib.NativeLib;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Card illustrations from the player's Master Duel (md_assets: card_illust): BC7 textures decoded with the
 * native helper and cached as PNG in &lt;dir&gt;/art/&lt;cid&gt;.png. Nothing is shipped; each player decodes their own.
 */
public final class CardArtCache {
	private static final int BC7 = 25, DXT1 = 10, DXT5 = 12, RGBA32 = 4, ARGB32 = 5, RGB24 = 3;
	private final MasterDuelIndex index;
	private final Path dir;
	private java.util.function.IntFunction<int[]> alternates = cid -> new int[0];
	private MethodHandle decodeBc7;

	public CardArtCache(MasterDuelIndex index, Path dir) {
		this.index = index;
		this.dir = dir;
	}

	/** Other cids of the same card (alternate arts): used when the player's Master Duel hasn't downloaded this art. */
	public CardArtCache alternates(java.util.function.IntFunction<int[]> alternates) {
		this.alternates = alternates;
		return this;
	}

	public boolean hasArt(int cid) {
		if (bundleFor(cid) != null) {
			return true;
		}
		for (int alt : alternates.apply(cid)) {
			if (bundleFor(alt) != null) {
				return true;
			}
		}
		return false;
	}

	/** Cached PNG for a card, extracting it on first use; null if Master Duel has no art for it. */
	public synchronized Path png(int cid) throws IOException {
		Path out = dir.resolve(cid + ".png");
		if (Files.isRegularFile(out)) {
			return out;
		}
		BufferedImage img = decode(cid);
		for (int alt : img == null ? alternates.apply(cid) : new int[0]) {
			img = decode(alt);
			if (img != null) {
				break;
			}
		}
		if (img == null) {
			return null;
		}
		Files.createDirectories(dir);
		Path tmp = dir.resolve(cid + ".png.tmp");
		ImageIO.write(img, "png", tmp.toFile());
		Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		return out;
	}

	private Path bundleFor(int cid) {
		String sub = String.format("%02d", cid / 1000);
		for (String p : List.of("assets/resources/card/images/illust/tcg/" + sub + "/" + cid + ".bmp",
			"assets/resourcesassetbundle/card/images/illust/tcg/" + cid + ".bmp",
			"assets/resources/card/images/illust/common/" + sub + "/" + cid + ".bmp")) {
			Path b = index.bundle(p);
			if (b != null) {
				return b;
			}
		}
		return null;
	}

	public BufferedImage decode(int cid) throws IOException {
		Path bundle = bundleFor(cid);
		if (bundle == null) {
			return null;
		}
		UnityBundle b = new UnityBundle(bundle);
		SerializedFile sf = new SerializedFile(b.readNode(b.serializedNode()));
		SerializedFile.ObjectInfo tex = sf.find(28); // Texture2D
		if (tex == null) {
			return null;
		}
		Map<String, Object> t = sf.read(tex);
		int w = (Integer) t.get("m_Width"), h = (Integer) t.get("m_Height"), format = (Integer) t.get("m_TextureFormat");
		byte[] data;
		@SuppressWarnings("unchecked")
		Map<String, Object> stream = (Map<String, Object>) t.get("m_StreamData");
		String path = stream == null ? "" : SerializedFile.utf8(stream.get("path"));
		if (!path.isEmpty()) {
			UnityBundle.Node res = b.node(path.substring(path.lastIndexOf('/') + 1));
			long off = ((Number) stream.get("offset")).longValue();
			int size = ((Number) stream.get("size")).intValue();
			data = b.read(res.offset() + off, size);
		} else {
			data = (byte[]) t.get("image data");
		}
		byte[] rgba = switch (format) {
			case BC7, DXT1, DXT5 -> bc(format, data, w, h);
			case RGBA32 -> data;
			case ARGB32 -> argbToRgba(data);
			case RGB24 -> rgbToRgba(data);
			default -> throw new IOException("card art format " + format + " not supported (cid " + cid + ")");
		};
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			int row = (h - 1 - y) * w * 4; // Unity stores rows bottom-up
			for (int x = 0; x < w; x++) {
				int i = row + x * 4;
				img.setRGB(x, y, (rgba[i + 3] & 0xFF) << 24 | (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8 | (rgba[i + 2] & 0xFF));
			}
		}
		return img;
	}

	private byte[] bc(int format, byte[] src, int w, int h) throws IOException {
		if (decodeBc7 == null) {
			decodeBc7 = NativeLib.fn("dc_decode_bc", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		}
		try (Arena a = Arena.ofConfined()) {
			MemorySegment in = a.allocate(src.length);
			MemorySegment.copy(src, 0, in, ValueLayout.JAVA_BYTE, 0, src.length);
			MemorySegment out = a.allocate((long) w * h * 4);
			int rc = (int) decodeBc7.invokeExact(format, in, src.length, w, h, out);
			if (rc != 0) {
				throw new IOException("BC" + format + " decode failed");
			}
			return out.toArray(ValueLayout.JAVA_BYTE);
		} catch (IOException e) {
			throw e;
		} catch (Throwable e) {
			throw new IOException(e);
		}
	}

	private static byte[] argbToRgba(byte[] d) {
		byte[] o = new byte[d.length];
		for (int i = 0; i < d.length; i += 4) {
			o[i] = d[i + 1];
			o[i + 1] = d[i + 2];
			o[i + 2] = d[i + 3];
			o[i + 3] = d[i];
		}
		return o;
	}

	private static byte[] rgbToRgba(byte[] d) {
		byte[] o = new byte[d.length / 3 * 4];
		for (int i = 0, j = 0; i + 2 < d.length; i += 3, j += 4) {
			o[j] = d[i];
			o[j + 1] = d[i + 1];
			o[j + 2] = d[i + 2];
			o[j + 3] = (byte) 255;
		}
		return o;
	}
}
