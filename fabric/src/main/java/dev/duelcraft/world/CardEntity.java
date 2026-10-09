package dev.duelcraft.world;

import dev.duelcraft.DuelCraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A card (or the duel mat) lying on the world board (systems: card_entity). Synced: Master Duel cid (0 =
 * card back) and flags. Never saved: boards are rebuilt by their duel.
 */
public final class CardEntity extends Entity {
	public static final int FACE_DOWN = 1, DEFENSE = 2, MAT = 4, GLOW = 8, OPPONENT = 16;
	public static final EntityDataAccessor<Integer> CID = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.INT);
	public static final EntityDataAccessor<Integer> FLAGS = SynchedEntityData.defineId(CardEntity.class, EntityDataSerializers.INT);

	public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DuelCraft.MOD_ID, "card"));
	public static final EntityType<CardEntity> TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY,
		EntityType.Builder.<CardEntity>of(CardEntity::new, MobCategory.MISC).sized(0.6F, 0.05F).noSave().noSummon().clientTrackingRange(8).build(KEY));

	/** Lunge animation: ticks left (client and server both count down). */
	public int lunge;

	public CardEntity(EntityType<?> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	public static void init() {
		// class load registers TYPE
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder b) {
		b.define(CID, 0);
		b.define(FLAGS, 0);
	}

	public int cid() {
		return entityData.get(CID);
	}

	public int flags() {
		return entityData.get(FLAGS);
	}

	public void set(int cid, int flags) {
		if (cid() != cid) {
			entityData.set(CID, cid);
		}
		if (flags() != flags) {
			entityData.set(FLAGS, flags);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (lunge > 0) {
			lunge--;
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public PushReaction getPistonPushReaction() {
		return PushReaction.IGNORE_ENTITY;
	}
}
