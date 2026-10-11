package dev.duelcraft.client.mixin;

import dev.duelcraft.DuelCraft;
import dev.duelcraft.DuelCraftData;
import dev.duelcraft.client.dl.VoiceSound;
import dev.duelcraft.dl.DuelLinksVoice;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Serves duelcraft_dl:sounds/voice/<clip>.ogg from the player's Duel Links (hooks: sound_mixin). */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
	@Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
	private void duelcraft$voiceStream(Identifier location, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		if (!VoiceSound.NAMESPACE.equals(location.getNamespace())) {
			return;
		}
		String path = location.getPath(); // sounds/voice/v0101_02_00_00_0.ogg
		String clip = path.substring(path.lastIndexOf('/') + 1).replace(".ogg", "");
		cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
			try {
				Path bundle = DuelCraftData.dl() == null ? null : DuelCraftData.dl().clipBundle(clip);
				if (bundle == null) {
					throw new IllegalStateException("no Duel Links clip " + clip);
				}
				return (AudioStream) new JOrbisAudioStream(new ByteArrayInputStream(DuelLinksVoice.ogg(bundle)));
			} catch (Exception e) {
				DuelCraft.LOG.warn("Duel Links voice {} failed: {}", clip, e.toString());
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool()));
	}
}
