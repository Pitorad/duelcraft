package dev.duelcraft.net;

import dev.duelcraft.duel.DuelManager;
import dev.duelcraft.duel.DuelSession;
import dev.duelcraft.gen.HookIds;
import dev.duelcraft.gen.Payloads;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Registers the payloads sheet and the server-side receivers (systems: network). */
public final class DuelNet {
	private DuelNet() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Payloads.DuelStartPayload.TYPE, Payloads.DuelStartPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.DuelStatePayload.TYPE, Payloads.DuelStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.DuelEventPayload.TYPE, Payloads.DuelEventPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.DuelPromptPayload.TYPE, Payloads.DuelPromptPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.DuelEndPayload.TYPE, Payloads.DuelEndPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.DuelResponsePayload.TYPE, Payloads.DuelResponsePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.DuelForfeitPayload.TYPE, Payloads.DuelForfeitPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Payloads.DuelResponsePayload.TYPE, (p, ctx) -> {
			DuelSession s = DuelManager.byId(p.duelId());
			if (s != null) {
				s.respond(ctx.player(), p.promptSeq(), p.response());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.DuelForfeitPayload.TYPE, (p, ctx) -> DuelManager.forfeit(ctx.player()));
		HookIds.PAYLOAD_TYPES.installed();
	}
}
