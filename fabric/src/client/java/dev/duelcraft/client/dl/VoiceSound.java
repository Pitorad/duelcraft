package dev.duelcraft.client.dl;

import dev.duelcraft.gen.Settings;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * One Duel Links voice clip played through Minecraft's sound engine (systems: voice_sound). It resolves against the
 * duelcraft:voice event (sounds.json) and then points its Sound at duelcraft_dl:voice/&lt;clip&gt;, which
 * SoundBufferLibraryMixin serves from the player's Duel Links as Ogg.
 */
public final class VoiceSound extends AbstractSoundInstance {
	public static final Identifier EVENT = Identifier.fromNamespaceAndPath("duelcraft", "voice");
	public static final String NAMESPACE = "duelcraft_dl";
	private final String clip;

	public VoiceSound(String clip) {
		super(EVENT, SoundSource.VOICE, RandomSource.create());
		this.clip = clip;
		this.volume = Settings.VOICE_VOLUME;
		this.relative = true;
		this.attenuation = SoundInstance.Attenuation.NONE;
	}

	@Override
	public WeighedSoundEvents getOrResolve(SoundManager soundManager) {
		WeighedSoundEvents events = super.getOrResolve(soundManager);
		this.sound = new Sound(Identifier.fromNamespaceAndPath(NAMESPACE, "voice/" + clip), r -> 1.0F, r -> 1.0F, 1, Sound.Type.FILE, true, false, 16);
		return events;
	}

	public String clip() {
		return clip;
	}
}
