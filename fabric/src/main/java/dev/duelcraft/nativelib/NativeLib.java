package dev.duelcraft.nativelib;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * duelcraft_native.dll (ocgcore + BC7 decoder) is shipped inside the mod jar; it is copied to
 * &lt;dir&gt;/native/duelcraft_native-&lt;sha&gt;.dll (a new name per build, so an old copy in use never blocks it)
 * and bound with the Java FFM API.
 */
public final class NativeLib {
	private static SymbolLookup lookup;
	public static final Linker LINKER = Linker.nativeLinker();

	private NativeLib() {
	}

	public static synchronized SymbolLookup load(Path dir) {
		if (lookup != null) {
			return lookup;
		}
		try (InputStream in = NativeLib.class.getResourceAsStream("/natives/duelcraft_native.dll")) {
			if (in == null) {
				throw new IllegalStateException("duelcraft_native.dll is missing from the mod jar");
			}
			byte[] bytes = in.readAllBytes();
			String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
			Path target = dir.resolve("native").resolve("duelcraft_native-" + sha + ".dll");
			if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
				Files.createDirectories(target.getParent());
				Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			lookup = SymbolLookup.libraryLookup(target, Arena.global());
			return lookup;
		} catch (IOException | java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("could not load duelcraft_native.dll", e);
		}
	}

	public static MethodHandle fn(String name, FunctionDescriptor fd) {
		MemorySegment addr = lookup.find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(addr, fd);
	}
}
