package dev.duelcraft.world;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.duel.DuelState;
import dev.duelcraft.duel.OcgCore;
import dev.duelcraft.gen.Settings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The world board (systems: board): a mat between the two duelists and one CardEntity per occupied zone,
 * placed from the spectator view of DuelState (face-down cards show their back). Seat 0 sits at -forward.
 */
public final class DuelBoard {
	private final ServerLevel level;
	private final Vec3 center, forward, right;
	private final float yaw;
	private final Map<String, CardEntity> cards = new HashMap<>();
	private final CardEntity mat;
	private final CardEntity[] labels = new CardEntity[2];
	private final Map<String, Integer> glow = new HashMap<>();

	public DuelBoard(ServerLevel level, Vec3 seat0, Vec3 seat1) {
		this.level = level;
		Vec3 d = new Vec3(seat1.x - seat0.x, 0, seat1.z - seat0.z);
		forward = d.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : d.normalize();
		right = new Vec3(-forward.z, 0, forward.x);
		center = new Vec3((seat0.x + seat1.x) / 2, Math.min(seat0.y, seat1.y) + 0.02, (seat0.z + seat1.z) / 2);
		yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
		mat = spawn(0, 0, yaw, 0, CardEntity.MAT);
		for (int s = 0; s < 2; s++) {
			// LP labels float above the outer side of each half, out of the duelists' faces
			labels[s] = spawn((s == 0 ? 1 : -1) * 3.6, (s == 0 ? -1 : 1) * 0.9, yaw, 0, CardEntity.MAT | CardEntity.OPPONENT);
			labels[s].setPos(labels[s].position().add(0, 1.4, 0));
			labels[s].setCustomNameVisible(true);
		}
	}

	public Vec3 center() {
		return center;
	}

	/** Places a duelist at their end of the board, facing it. */
	public Vec3 seatPos(int seat) {
		double z = (seat == 0 ? -1 : 1) * Settings.BOARD_DISTANCE;
		return center.add(forward.scale(z));
	}

	private CardEntity spawn(double x, double z, float yRot, int cid, int flags) {
		CardEntity e = new CardEntity(CardEntity.TYPE, level);
		Vec3 p = center.add(right.scale(x)).add(forward.scale(z));
		e.setPos(p.x, p.y, p.z);
		e.setYRot(yRot);
		e.set(cid, flags);
		level.addFreshEntity(e);
		return e;
	}

	/** Board position (x across, z along) of a zone; null if the zone isn't shown. */
	static double[] zone(int seat, int loc, int seq) {
		double sign = seat == 0 ? -1 : 1, mirror = seat == 0 ? 1 : -1;
		return switch (loc) {
			case OcgCore.LOCATION_MZONE -> seq <= 4 ? new double[] {mirror * (seq - 2) * 0.8, sign * 0.6}
				: new double[] {(seq == 5 ? -0.8 : 0.8), 0};
			case OcgCore.LOCATION_SZONE -> seq <= 4 ? new double[] {mirror * (seq - 2) * 0.8, sign * 1.5}
				: seq == 5 ? new double[] {mirror * -2.4, sign * 0.6} : null;
			case OcgCore.LOCATION_GRAVE -> new double[] {mirror * 2.4, sign * 0.6};
			case OcgCore.LOCATION_DECK -> new double[] {mirror * 2.4, sign * 1.5};
			case OcgCore.LOCATION_EXTRA -> new double[] {mirror * -2.4, sign * 1.5};
			case OcgCore.LOCATION_REMOVED -> new double[] {mirror * 3.2, sign * 0.6};
			default -> null;
		};
	}

	public void update(DuelState spectator, String[] names) {
		CardDb db = DuelCraftData.db();
		Map<String, int[]> want = new HashMap<>();
		for (int seat = 0; seat < 2; seat++) {
			for (int loc : new int[] {OcgCore.LOCATION_MZONE, OcgCore.LOCATION_SZONE}) {
				for (DuelState.FieldCard c : spectator.at(seat, loc)) {
					if (!c.empty()) {
						want.put(seat + ":" + loc + ":" + c.seq(), new int[] {cidOf(db, c.code()), flagsOf(seat, c)});
					}
				}
			}
			// piles show their top card (deck/extra face down)
			for (int loc : new int[] {OcgCore.LOCATION_GRAVE, OcgCore.LOCATION_REMOVED, OcgCore.LOCATION_DECK, OcgCore.LOCATION_EXTRA}) {
				List<DuelState.FieldCard> pile = spectator.at(seat, loc);
				if (!pile.isEmpty()) {
					DuelState.FieldCard top = pile.getLast();
					boolean back = loc == OcgCore.LOCATION_DECK || loc == OcgCore.LOCATION_EXTRA || top.code() == 0;
					want.put(seat + ":" + loc + ":0", new int[] {back ? 0 : cidOf(db, top.code()), (back ? CardEntity.FACE_DOWN : 0) | (seat == 1 ? CardEntity.OPPONENT : 0)});
				}
			}
			labels[seat].setCustomName(Component.literal(names[seat] + "  " + spectator.lp[seat] + " LP"));
		}
		cards.entrySet().removeIf(en -> {
			if (!want.containsKey(en.getKey())) {
				en.getValue().discard();
				return true;
			}
			return false;
		});
		for (Map.Entry<String, int[]> w : want.entrySet()) {
			String[] k = w.getKey().split(":");
			int seat = Integer.parseInt(k[0]), loc = Integer.parseInt(k[1]), seq = Integer.parseInt(k[2]);
			double[] z = zone(seat, loc, seq);
			if (z == null) {
				continue;
			}
			int flags = w.getValue()[1] | (glow.containsKey(w.getKey()) ? CardEntity.GLOW : 0);
			CardEntity e = cards.get(w.getKey());
			if (e == null) {
				e = spawn(z[0], z[1], seat == 0 ? yaw : yaw + 180, w.getValue()[0], flags);
				cards.put(w.getKey(), e);
				if (loc == OcgCore.LOCATION_MZONE) {
					burst(e.position());
				}
			} else {
				e.set(w.getValue()[0], flags);
			}
		}
	}

	private static int cidOf(CardDb db, int passcode) {
		if (passcode == 0 || db == null) {
			return 0;
		}
		CardDb.CardInfo c = db.byPasscode(passcode);
		return c == null ? 0 : c.cid();
	}

	private static int flagsOf(int seat, DuelState.FieldCard c) {
		int f = seat == 1 ? CardEntity.OPPONENT : 0;
		if (c.faceDown() || c.code() == 0) {
			f |= CardEntity.FACE_DOWN;
		}
		if (c.defense()) {
			f |= CardEntity.DEFENSE;
		}
		return f;
	}

	/** Lifts a zone's card for a moment (attack lunge / activation glow). */
	public void highlight(int seat, int loc, int seq, int ticks) {
		glow.put(seat + ":" + loc + ":" + seq, ticks);
	}

	public void tick() {
		glow.replaceAll((k, v) -> v - 1);
		glow.entrySet().removeIf(en -> {
			if (en.getValue() <= 0) {
				CardEntity e = cards.get(en.getKey());
				if (e != null) {
					e.set(e.cid(), e.flags() & ~CardEntity.GLOW);
				}
				return true;
			}
			CardEntity e = cards.get(en.getKey());
			if (e != null) {
				e.set(e.cid(), e.flags() | CardEntity.GLOW);
			}
			return false;
		});
	}

	public void burst(Vec3 at) {
		level.sendParticles(ParticleTypes.ENCHANT, at.x, at.y + 0.3, at.z, 30, 0.3, 0.3, 0.3, 0.5);
		level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 0.2, at.z, 8, 0.2, 0.4, 0.2, 0.02);
	}

	public Vec3 zonePos(int seat, int loc, int seq) {
		double[] z = zone(seat, loc, seq);
		return z == null ? center : center.add(right.scale(z[0])).add(forward.scale(z[1]));
	}

	public void remove() {
		cards.values().forEach(Entity::discard);
		cards.clear();
		mat.discard();
		for (CardEntity l : labels) {
			l.discard();
		}
	}
}
