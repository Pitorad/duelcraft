package dev.duelcraft.duel;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Freezes a challenged mob at its end of the board for the duel and restores it afterwards (systems: mob_duelist). */
public final class MobDuelist {
	private final Mob mob;
	private final boolean wasNoAi, wasInvulnerable, wasPersistent;

	private MobDuelist(Mob mob) {
		this.mob = mob;
		this.wasNoAi = mob.isNoAi();
		this.wasInvulnerable = mob.isInvulnerable();
		this.wasPersistent = mob.isPersistenceRequired();
	}

	public static MobDuelist freeze(Mob mob, Vec3 seat, Vec3 lookAt) {
		MobDuelist m = new MobDuelist(mob);
		mob.setNoAi(true);
		mob.setPermanentlyInvulnerable(true);
		mob.setPersistenceRequired();
		mob.setTarget(null);
		mob.getNavigation().stop();
		mob.teleportTo(seat.x, mob.getY(), seat.z);
		mob.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(lookAt.x, mob.getEyeY(), lookAt.z));
		mob.setYHeadRot(mob.getYRot());
		return m;
	}

	public void restore() {
		if (mob.isRemoved()) {
			return;
		}
		mob.setNoAi(wasNoAi);
		mob.setPermanentlyInvulnerable(wasInvulnerable);
		if (!wasPersistent) {
			// persistence can't be cleared through the API; a mob that was going to despawn now stays, which is harmless
		}
	}
}
