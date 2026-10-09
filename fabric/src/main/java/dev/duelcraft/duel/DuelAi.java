package dev.duelcraft.duel;

import dev.duelcraft.cards.CardDb;
import dev.duelcraft.duel.MsgReader.Loc;
import dev.duelcraft.gen.AiPolicies;
import dev.duelcraft.gen.Prompts.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The computer duelist: one method per ai_policies row (see design/ai_policies.json). */
public final class DuelAi implements AiPolicies {
	private final DuelEngine engine;
	private final CardDb db;
	private final int me;
	private final Random rng;
	private int actionsTurn = -1, actions;
	private final Map<Long, Integer> used = new HashMap<>();
	private final java.util.Set<Integer> attacked = new java.util.HashSet<>();

	public DuelAi(DuelEngine engine, CardDb db, int me, long seed) {
		this.engine = engine;
		this.db = db;
		this.me = me;
		this.rng = new Random(seed);
	}

	// ---------------------------------------------------------------- encoding helpers

	static byte[] i32(int v) {
		return Responses.i32(v);
	}

	static byte[] cards(List<Integer> idx) {
		return Responses.cards(idx);
	}

	private void newTurnCheck() {
		if (actionsTurn != engine.turn) {
			actionsTurn = engine.turn;
			actions = 0;
			used.clear();
			attacked.clear();
		}
	}

	private int atkAt(int con, int loc, int seq) {
		DuelState.FieldCard c = engine.state == null ? null : engine.state.at(con, loc, seq);
		return c == null || c.empty() ? 0 : c.atk();
	}

	private boolean isTrapCard(int code) {
		CardDb.CardInfo c = db.byPasscode(code);
		return c != null && (c.type() & CardDb.TYPE_TRAP) != 0;
	}

	private boolean hasFaceUpAttacker() {
		if (engine.state == null) {
			return false;
		}
		for (DuelState.FieldCard c : engine.state.at(me, OcgCore.LOCATION_MZONE)) {
			if (!c.empty() && !c.faceDown() && !c.defense()) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- policies

	@Override
	public byte[] aiIdle(SelectIdleCmd p, boolean fallback) {
		newTurnCheck();
		if (fallback || actions >= 12) {
			return i32(p.canEp() != 0 ? 7 : 6);
		}
		actions++;
		for (int i = 0; i < p.activatable().size(); i++) {
			var a = p.activatable().get(i);
			long key = (long) a.code() << 32 ^ a.desc();
			if (!isTrapCard(a.code()) && used.getOrDefault(key, 0) < 2 && rng.nextInt(100) < 60) {
				used.merge(key, 1, Integer::sum);
				return i32(i << 16 | 5);
			}
		}
		if (!p.summonable().isEmpty()) {
			int best = 0, bestAtk = -1;
			for (int i = 0; i < p.summonable().size(); i++) {
				CardDb.CardInfo c = db.byPasscode(p.summonable().get(i).code());
				int atk = c == null ? 0 : c.atk();
				if (atk > bestAtk) {
					best = i;
					bestAtk = atk;
				}
			}
			return i32(best << 16);
		}
		if (!p.spsummonable().isEmpty()) {
			return i32(1);
		}
		for (int i = 0; i < p.ssetable().size(); i++) {
			if (isTrapCard(p.ssetable().get(i).code())) {
				return i32(i << 16 | 4);
			}
		}
		if (p.canBp() != 0 && hasFaceUpAttacker()) {
			return i32(6);
		}
		return i32(p.canEp() != 0 ? 7 : 6);
	}

	@Override
	public byte[] aiBattle(SelectBattleCmd p, boolean fallback) {
		newTurnCheck();
		if (!fallback && actions++ < 20) {
			int best = -1, bestAtk = -1;
			for (int i = 0; i < p.attackers().size(); i++) {
				var a = p.attackers().get(i);
				int atk = atkAt(a.con(), a.loc(), a.seq());
				int key = a.code() * 8 + a.seq();
				if (!attacked.contains(key) && atk > bestAtk) {
					best = i;
					bestAtk = atk;
				}
			}
			if (best >= 0) {
				var a = p.attackers().get(best);
				attacked.add(a.code() * 8 + a.seq());
				return i32(best << 16 | 1);
			}
		}
		if (p.canM2() != 0 && !fallback) {
			return i32(2);
		}
		return i32(p.canEp() != 0 ? 3 : 2);
	}

	@Override
	public byte[] aiYes(SelectEffectYn p, boolean fallback) {
		return i32(fallback ? 0 : 1);
	}

	@Override
	public byte[] aiYes(SelectYesNo p, boolean fallback) {
		return i32(fallback ? 0 : 1);
	}

	@Override
	public byte[] aiFirst(SelectOption p, boolean fallback) {
		return i32(fallback && p.options().size() > 1 ? 1 : 0);
	}

	@Override
	public byte[] aiFirst(AnnounceNumber p, boolean fallback) {
		return i32(fallback && p.options().size() > 1 ? 1 : 0);
	}

	private byte[] pick(List<Loc> where, int min, int max, boolean cancelable, boolean fallback) {
		int want = Math.max(Math.min(Math.max(min, 1), max), min);
		List<Integer> idx = new ArrayList<>();
		if (!fallback) {
			for (int i = 0; i < where.size() && idx.size() < want; i++) {
				if (where.get(i).con() != me) {
					idx.add(i);
				}
			}
		}
		for (int i = 0; i < where.size() && idx.size() < want; i++) {
			if (!idx.contains(i)) {
				idx.add(i);
			}
		}
		if (idx.size() < min && cancelable) {
			return i32(-1);
		}
		return cards(idx);
	}

	@Override
	public byte[] aiPickMin(SelectCard p, boolean fallback) {
		return pick(p.cards().stream().map(SelectCard.CardsEntry::where).toList(), p.min(), p.max(), p.cancelable() != 0, fallback);
	}

	@Override
	public byte[] aiChain(SelectChain p, boolean fallback) {
		if (p.forced() != 0) {
			return i32(0);
		}
		if (fallback || p.chains().isEmpty()) {
			return i32(-1);
		}
		return i32(rng.nextBoolean() ? 0 : -1);
	}

	private static final int[] MZONE_ORDER = {2, 1, 3, 0, 4, 5, 6};
	private static final int[] SZONE_ORDER = {2, 1, 3, 0, 4, 5, 6, 7};

	private byte[] place(int count, int blocked, boolean opponentFirst, boolean fallback) {
		ByteBuffer b = ByteBuffer.allocate(3 * count);
		int flag = blocked;
		for (int n = 0; n < count; n++) {
			boolean found = false;
			int[] sides = opponentFirst ? new int[] {1 - me, me} : new int[] {me, 1 - me};
			outer:
			for (int side : sides) {
				int shift = side == me ? 0 : 16;
				for (int loc : new int[] {OcgCore.LOCATION_MZONE, OcgCore.LOCATION_SZONE}) {
					int[] order = loc == OcgCore.LOCATION_MZONE ? MZONE_ORDER : SZONE_ORDER;
					if (fallback) {
						order = loc == OcgCore.LOCATION_MZONE ? new int[] {0, 1, 2, 3, 4, 5, 6} : new int[] {0, 1, 2, 3, 4, 5, 6, 7};
					}
					for (int seq : order) {
						int bit = 1 << (seq + (loc == OcgCore.LOCATION_SZONE ? 8 : 0) + shift);
						if ((flag & bit) == 0) {
							flag |= bit;
							b.put((byte) side).put((byte) loc).put((byte) seq);
							found = true;
							break outer;
						}
					}
				}
			}
			if (!found) {
				b.put((byte) me).put((byte) OcgCore.LOCATION_MZONE).put((byte) 0);
			}
		}
		return b.array();
	}

	@Override
	public byte[] aiPlace(SelectPlace p, boolean fallback) {
		return place(p.count(), p.blocked(), false, fallback);
	}

	@Override
	public byte[] aiPlaceOpponent(SelectDisfield p, boolean fallback) {
		return place(p.count(), p.blocked(), true, fallback);
	}

	@Override
	public byte[] aiPosition(SelectPosition p, boolean fallback) {
		int[] order = fallback ? new int[] {1, 2, 4, 8} : new int[] {OcgCore.POS_FACEUP_ATTACK, OcgCore.POS_FACEUP_DEFENSE,
			OcgCore.POS_FACEDOWN_DEFENSE, OcgCore.POS_FACEDOWN_ATTACK};
		for (int pos : order) {
			if ((p.positions() & pos) != 0) {
				return i32(pos);
			}
		}
		return i32(OcgCore.POS_FACEUP_ATTACK);
	}

	@Override
	public byte[] aiTribute(SelectTribute p, boolean fallback) {
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < p.cards().size(); i++) {
			idx.add(i);
		}
		if (!fallback) {
			idx.sort((a, b) -> {
				var ca = p.cards().get(a);
				var cb = p.cards().get(b);
				return Integer.compare(atkAt(ca.con(), ca.loc(), ca.seq()), atkAt(cb.con(), cb.loc(), cb.seq()));
			});
		}
		List<Integer> chosen = new ArrayList<>();
		int total = 0;
		for (int i : idx) {
			if (total >= p.min()) {
				break;
			}
			chosen.add(i);
			total += p.cards().get(i).releaseParam();
		}
		if (total < p.min() && p.cancelable() != 0) {
			return i32(-1);
		}
		return cards(chosen);
	}

	@Override
	public byte[] aiSortDefault(SortChain p, boolean fallback) {
		return sortAnswer(p.cards().size(), fallback);
	}

	@Override
	public byte[] aiSortDefault(SortCard p, boolean fallback) {
		return sortAnswer(p.cards().size(), fallback);
	}

	private static byte[] sortAnswer(int n, boolean fallback) {
		if (!fallback) {
			return new byte[] {(byte) -1};
		}
		byte[] r = new byte[n];
		for (int i = 0; i < n; i++) {
			r[i] = (byte) i;
		}
		return r;
	}

	@Override
	public byte[] aiCounter(SelectCounter p, boolean fallback) {
		int n = p.cards().size();
		ByteBuffer b = ByteBuffer.allocate(2 * n).order(ByteOrder.LITTLE_ENDIAN);
		int left = p.count();
		short[] take = new short[n];
		for (int k = 0; k < n; k++) {
			int i = fallback ? n - 1 - k : k;
			int t = Math.min(left, p.cards().get(i).counters());
			take[i] = (short) t;
			left -= t;
		}
		for (short t : take) {
			b.putShort(t);
		}
		return b.array();
	}

	@Override
	public byte[] aiSum(SelectSum p, boolean fallback) {
		int target = p.target();
		int base1 = 0;
		for (var m : p.must()) {
			base1 += m.param() & 0xFFFF;
		}
		List<Integer> best = new ArrayList<>();
		if (!fallback && sumSearch(p, 0, new ArrayList<>(), base1, target, best)) {
			return cards(best);
		}
		List<Integer> all = new ArrayList<>();
		for (int i = 0; i < p.cards().size(); i++) {
			all.add(i);
		}
		return cards(all);
	}

	private boolean sumSearch(SelectSum p, int i, List<Integer> cur, int acc, int target, List<Integer> out) {
		boolean exact = p.mode() == 0;
		if (cur.size() >= Math.max(p.min(), 1) && (p.max() == 0 || cur.size() <= p.max())) {
			if (exact ? acc == target : acc >= target) {
				out.addAll(cur);
				return true;
			}
		}
		if (i >= p.cards().size() || (p.max() > 0 && cur.size() >= p.max()) || (exact && acc > target)) {
			return false;
		}
		int param = p.cards().get(i).param();
		for (int v : new int[] {param & 0xFFFF, param >>> 16}) {
			if (v == 0 && v != (param & 0xFFFF)) {
				continue;
			}
			cur.add(i);
			if (sumSearch(p, i + 1, cur, acc + v, target, out)) {
				return true;
			}
			cur.removeLast();
			if ((param >>> 16) == 0) {
				break;
			}
		}
		return sumSearch(p, i + 1, cur, acc, target, out);
	}

	@Override
	public byte[] aiUnselect(SelectUnselectCard p, boolean fallback) {
		if (p.finishable() != 0 && !fallback) {
			return i32(-1);
		}
		ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		int idx = fallback && !p.selected().isEmpty() ? p.selectable().size() : 0;
		if (p.selectable().isEmpty() && p.selected().isEmpty()) {
			return i32(-1);
		}
		if (p.selectable().isEmpty()) {
			idx = p.selectable().size();
		}
		return b.putInt(1).putInt(idx).array();
	}

	@Override
	public byte[] aiRandomRps(RockPaperScissors p, boolean fallback) {
		return i32(fallback ? 1 : 1 + rng.nextInt(3));
	}

	@Override
	public byte[] aiFlags(AnnounceRace p, boolean fallback) {
		return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(lowBits(p.available(), p.count(), fallback)).array();
	}

	@Override
	public byte[] aiFlags(AnnounceAttrib p, boolean fallback) {
		return i32((int) lowBits(p.available() & 0xFFFFFFFFL, p.count(), fallback));
	}

	private static long lowBits(long avail, int count, boolean high) {
		long out = 0;
		for (int k = 0; k < 64 && Long.bitCount(out) < count; k++) {
			int bit = high ? 63 - k : k;
			if ((avail >>> bit & 1) != 0) {
				out |= 1L << bit;
			}
		}
		return out;
	}

	@Override
	public byte[] aiDeclare(AnnounceCard p, boolean fallback) {
		List<Long> ops = p.opcodes().stream().map(AnnounceCard.OpcodesEntry::op).toList();
		boolean skip = fallback;
		for (CardDb.CardInfo c : db.playable()) {
			if (Declarable.check(c, ops)) {
				if (skip) {
					skip = false;
					continue;
				}
				return i32(c.passcode());
			}
		}
		return i32(0);
	}
}
