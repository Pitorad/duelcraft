package dev.duelcraft.md;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Unity serialized file (versions 17-22+, as in Unity 6 bundles) with embedded type trees. Objects are
 * read generically through their type tree into Maps/Lists/primitives, the way UnityPy does.
 */
public final class SerializedFile {
	public record TypeNode(String type, String name, int level, int metaFlag, List<TypeNode> children) {
	}

	public record ObjectInfo(long pathId, long start, int size, int typeIndex, int classId) {
	}

	private static Map<Integer, String> commonStrings;

	private final ByteBuffer data;
	private final List<TypeNode> types = new ArrayList<>();
	private final List<Integer> classIds = new ArrayList<>();
	public final List<ObjectInfo> objects = new ArrayList<>();

	public SerializedFile(byte[] bytes) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		b.getInt(); // metadata size
		b.getInt(); // file size
		int version = b.getInt();
		long dataOffset = b.getInt() & 0xFFFFFFFFL;
		boolean big = false;
		if (version >= 9) {
			big = b.get() != 0;
			b.position(b.position() + 3);
		}
		if (version >= 22) {
			b.getInt();
			b.getLong();
			dataOffset = b.getLong();
			b.getLong();
		}
		if (version < 17) {
			throw new IOException("serialized file version " + version + " is too old");
		}
		b.order(big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		UnityBundle.cstr(b); // unity version
		b.getInt(); // target platform
		boolean typeTree = b.get() != 0;
		if (!typeTree) {
			throw new IOException("bundle has no type trees");
		}
		int typeCount = b.getInt();
		for (int i = 0; i < typeCount; i++) {
			int classId = b.getInt();
			b.get(); // stripped
			short scriptIndex = b.getShort();
			if (classId == 114) {
				b.position(b.position() + 16); // script id
			}
			b.position(b.position() + 16); // old type hash
			types.add(readTypeTree(b, version));
			classIds.add(classId);
			if (version >= 21) {
				int deps = b.getInt();
				b.position(b.position() + 4 * deps);
			}
		}
		int objectCount = b.getInt();
		for (int i = 0; i < objectCount; i++) {
			b.position((b.position() + 3) & ~3);
			long pathId = b.getLong();
			long start = version >= 22 ? b.getLong() : b.getInt() & 0xFFFFFFFFL;
			int size = b.getInt();
			int typeIndex = b.getInt();
			objects.add(new ObjectInfo(pathId, start + dataOffset, size, typeIndex, classIds.get(typeIndex)));
		}
		this.data = ByteBuffer.wrap(bytes).order(big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
	}

	private static TypeNode readTypeTree(ByteBuffer b, int version) {
		int nodeCount = b.getInt();
		int stringSize = b.getInt();
		int[] level = new int[nodeCount], meta = new int[nodeCount], typeOff = new int[nodeCount], nameOff = new int[nodeCount];
		for (int i = 0; i < nodeCount; i++) {
			b.getShort(); // version
			level[i] = b.get() & 0xFF;
			b.get(); // type flags
			typeOff[i] = b.getInt();
			nameOff[i] = b.getInt();
			b.getInt(); // byte size
			b.getInt(); // index
			meta[i] = b.getInt();
			if (version >= 19) {
				b.getLong(); // ref type hash
			}
		}
		byte[] strings = new byte[stringSize];
		b.get(strings);
		// rebuild the tree from the flat, depth-first list
		List<TypeNode> stack = new ArrayList<>();
		TypeNode root = null;
		for (int i = 0; i < nodeCount; i++) {
			TypeNode n = new TypeNode(str(strings, typeOff[i]), str(strings, nameOff[i]), level[i], meta[i], new ArrayList<>());
			while (stack.size() > level[i]) {
				stack.removeLast();
			}
			if (stack.isEmpty()) {
				root = n;
			} else {
				stack.getLast().children.add(n);
			}
			stack.add(n);
		}
		return root;
	}

	private static String str(byte[] buf, int off) {
		if ((off & 0x80000000) != 0) {
			return common().getOrDefault(off & 0x7FFFFFFF, Integer.toString(off & 0x7FFFFFFF));
		}
		int end = off;
		while (end < buf.length && buf[end] != 0) {
			end++;
		}
		return new String(buf, off, end - off, StandardCharsets.UTF_8);
	}

	private static synchronized Map<Integer, String> common() {
		if (commonStrings == null) {
			Map<Integer, String> m = new HashMap<>();
			try (InputStream in = SerializedFile.class.getResourceAsStream("/data/duelcraft/unity/common_strings.tsv")) {
				for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
					int t = line.indexOf('\t');
					if (t > 0) {
						m.put(Integer.parseInt(line.substring(0, t)), line.substring(t + 1));
					}
				}
			} catch (IOException | NullPointerException e) {
				throw new IllegalStateException("missing Unity common strings table", e);
			}
			commonStrings = m;
		}
		return commonStrings;
	}

	public ObjectInfo find(int classId) {
		for (ObjectInfo o : objects) {
			if (o.classId == classId) {
				return o;
			}
		}
		return null;
	}

	/** Reads an object through its type tree. Values: Map, List, Number, Boolean, String, byte[]. */
	@SuppressWarnings("unchecked")
	public Map<String, Object> read(ObjectInfo o) {
		ByteBuffer b = data.duplicate().order(data.order());
		b.position(Math.toIntExact(o.start));
		return (Map<String, Object>) value(types.get(o.typeIndex), b);
	}

	private static boolean aligned(int metaFlag) {
		return (metaFlag & 0x4000) != 0;
	}

	private static Object value(TypeNode n, ByteBuffer b) {
		boolean align = aligned(n.metaFlag);
		Object v = switch (n.type) {
			case "SInt8" -> (int) b.get();
			case "UInt8", "char" -> b.get() & 0xFF;
			case "bool" -> b.get() != 0;
			case "short", "SInt16" -> (int) b.getShort();
			case "unsigned short", "UInt16" -> b.getShort() & 0xFFFF;
			case "int", "SInt32", "unsigned int", "UInt32", "Type*" -> b.getInt();
			case "long long", "SInt64", "unsigned long long", "UInt64", "FileSize" -> b.getLong();
			case "float" -> b.getFloat();
			case "double" -> b.getDouble();
			case "string" -> {
				int len = b.getInt();
				byte[] s = new byte[Math.max(len, 0)];
				b.get(s);
				align = true;
				// ISO-8859-1 keeps every byte (TextAsset.m_Script is binary); see bytes()/utf8()
				yield new String(s, StandardCharsets.ISO_8859_1);
			}
			case "TypelessData" -> {
				byte[] s = new byte[b.getInt()];
				b.get(s);
				yield s;
			}
			case "pair" -> List.of(value(n.children.get(0), b), value(n.children.get(1), b));
			default -> {
				if (!n.children.isEmpty() && n.children.getFirst().type.equals("Array")) {
					TypeNode arr = n.children.getFirst();
					if (aligned(arr.metaFlag)) {
						align = true;
					}
					int size = b.getInt();
					TypeNode elem = arr.children.get(1);
					if (elem.type.equals("UInt8") || elem.type.equals("char") || elem.type.equals("SInt8")) {
						byte[] s = new byte[size];
						b.get(s);
						if (aligned(elem.metaFlag)) {
							b.position((b.position() + 3) & ~3);
						}
						yield s;
					}
					List<Object> list = new ArrayList<>(size);
					for (int i = 0; i < size; i++) {
						list.add(value(elem, b));
					}
					yield list;
				}
				Map<String, Object> m = new LinkedHashMap<>();
				for (TypeNode c : n.children) {
					m.put(c.name, value(c, b));
				}
				yield m;
			}
		};
		if (align) {
			b.position((b.position() + 3) & ~3);
		}
		return v;
	}

	/** Raw bytes of a type-tree string value. */
	public static byte[] bytes(Object stringValue) {
		return ((String) stringValue).getBytes(StandardCharsets.ISO_8859_1);
	}

	/** A type-tree string value as text. */
	public static String utf8(Object stringValue) {
		return new String(bytes(stringValue), StandardCharsets.UTF_8);
	}

	/** The asset paths an AssetBundle object (class 142) lists in m_Container. */
	@SuppressWarnings("unchecked")
	public List<String> containerPaths() {
		ObjectInfo ab = find(142);
		if (ab == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (Object e : (List<Object>) read(ab).get("m_Container")) {
			out.add((String) ((List<Object>) e).getFirst());
		}
		return out;
	}
}
