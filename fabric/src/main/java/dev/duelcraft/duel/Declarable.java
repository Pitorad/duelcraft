package dev.duelcraft.duel;

import dev.duelcraft.cards.CardDb;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Java port of ocgcore's is_declarable (playerop.cpp): which cards an MSG_ANNOUNCE_CARD opcode program allows. */
public final class Declarable {
	private static final long ADD = 0x4000000000000000L, SUB = 0x4000000100000000L, MUL = 0x4000000200000000L, DIV = 0x4000000300000000L,
		AND = 0x4000000400000000L, OR = 0x4000000500000000L, NEG = 0x4000000600000000L, NOT = 0x4000000700000000L,
		BAND = 0x4000000800000000L, BOR = 0x4000000900000000L, BNOT = 0x4000001000000000L, BXOR = 0x4000001100000000L,
		LSHIFT = 0x4000001200000000L, RSHIFT = 0x4000001300000000L, ALLOW_ALIASES = 0x4000001400000000L, ALLOW_TOKENS = 0x4000001500000000L,
		ISCODE = 0x4000010000000000L, ISSETCARD = 0x4000010100000000L, ISTYPE = 0x4000010200000000L, ISRACE = 0x4000010300000000L,
		ISATTRIBUTE = 0x4000010400000000L, GETCODE = 0x4000010500000000L, GETTYPE = 0x4000010700000000L, GETRACE = 0x4000010800000000L,
		GETATTRIBUTE = 0x4000010900000000L;
	private static final int MARINE_DOLPHIN = 78734254, TWINKLE_MOSS = 13857930;

	private Declarable() {
	}

	public static boolean check(CardDb.CardInfo cd, List<Long> ops) {
		Deque<Long> st = new ArrayDeque<>();
		boolean alias = false, token = false;
		for (long op : ops) {
			if (op == ADD || op == SUB || op == MUL || op == DIV || op == AND || op == OR || op == BAND || op == BOR || op == BXOR
				|| op == LSHIFT || op == RSHIFT) {
				if (st.size() >= 2) {
					long r = st.pop(), l = st.pop();
					st.push(op == ADD ? l + r : op == SUB ? l - r : op == MUL ? l * r : op == DIV ? (r == 0 ? 0 : l / r)
						: op == AND ? (l != 0 && r != 0 ? 1 : 0) : op == OR ? (l != 0 || r != 0 ? 1 : 0) : op == BAND ? l & r
						: op == BOR ? l | r : op == BXOR ? l ^ r : op == LSHIFT ? l << r : l >> r);
				}
			} else if (op == NEG || op == NOT || op == BNOT) {
				if (!st.isEmpty()) {
					long v = st.pop();
					st.push(op == NEG ? -v : op == NOT ? (v == 0 ? 1 : 0) : ~v);
				}
			} else if (op == ISCODE || op == ISTYPE || op == ISRACE || op == ISATTRIBUTE) {
				if (!st.isEmpty()) {
					long v = st.pop();
					st.push(op == ISCODE ? (cd.passcode() == (int) v ? 1L : 0L) : op == ISTYPE ? cd.type() & v
						: op == ISRACE ? cd.race() & v : cd.attribute() & v);
				}
			} else if (op == GETCODE) {
				st.push((long) cd.passcode());
			} else if (op == GETTYPE) {
				st.push((long) cd.type());
			} else if (op == GETRACE) {
				st.push(cd.race());
			} else if (op == GETATTRIBUTE) {
				st.push((long) cd.attribute());
			} else if (op == ISSETCARD) {
				if (!st.isEmpty()) {
					int set = (int) (long) st.pop();
					int settype = set & 0xFFF, sub = set & 0xF000;
					boolean res = false;
					for (int sc : cd.setcodes()) {
						if ((sc & 0xFFF) == settype && (sc & 0xF000 & sub) == sub) {
							res = true;
							break;
						}
					}
					st.push(res ? 1L : 0L);
				}
			} else if (op == ALLOW_ALIASES) {
				alias = true;
			} else if (op == ALLOW_TOKENS) {
				token = true;
			} else {
				st.push(op);
			}
		}
		if (st.size() != 1 || st.peek() == 0) {
			return false;
		}
		int mt = CardDb.TYPE_MONSTER | CardDb.TYPE_TOKEN;
		return cd.passcode() == MARINE_DOLPHIN || cd.passcode() == TWINKLE_MOSS || (token || (cd.type() & mt) != mt);
	}
}
