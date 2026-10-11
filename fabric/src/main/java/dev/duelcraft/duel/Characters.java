package dev.duelcraft.duel;

import dev.duelcraft.gen.HookIds;
import dev.duelcraft.gen.Payloads;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Each player's chosen Duel Links character (systems: characters_server); 0 = none. Sent by the client on join and on pick. */
public final class Characters {
	private static final Map<UUID, Integer> PICKS = new ConcurrentHashMap<>();

	private Characters() {
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(Payloads.CharacterPickPayload.TYPE, (p, ctx) -> {
			int c = Math.max(0, Math.min(9999, p.character()));
			PICKS.put(ctx.player().getUUID(), c);
		});
		HookIds.PICK_ON_JOIN.installed();
	}

	public static int of(UUID player) {
		return PICKS.getOrDefault(player, 0);
	}
}
