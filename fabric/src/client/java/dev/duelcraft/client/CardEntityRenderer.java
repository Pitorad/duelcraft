package dev.duelcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.world.CardEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** Draws CardEntity (systems: card_entity_renderer): a card lying on the board with its Master Duel art, or the mat. */
public final class CardEntityRenderer extends EntityRenderer<CardEntity, CardEntityRenderer.State> {
	private static final Identifier MAT = Identifier.fromNamespaceAndPath("duelcraft", "textures/entity/mat.png");
	private static final float W = 0.6F, H = 0.87F;

	public static final class State extends EntityRenderState {
		int cid, flags;
		float yRot;
	}

	public CardEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(CardEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.cid = entity.cid();
		state.flags = entity.flags();
		state.yRot = entity.getYRot();
	}

	@Override
	protected boolean affectedByCulling(CardEntity entity) {
		return (entity.flags() & CardEntity.MAT) == 0;
	}

	@Override
	public void submit(State s, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
		boolean mat = (s.flags & CardEntity.MAT) != 0;
		if (mat && (s.flags & CardEntity.OPPONENT) != 0) {
			super.submit(s, pose, out, camera); // LP label: name tag only
			return;
		}
		pose.pushPose();
		pose.rotateDegrees(Axis.YP, -s.yRot);
		int light = s.lightCoords;
		if (mat) {
			quad(pose, out, MAT, 7.6F, 4.0F, 0.0F, light);
			pose.popPose();
			return;
		}
		if ((s.flags & CardEntity.GLOW) != 0) {
			pose.translate(0, 0.25F + 0.05F * (float) Math.sin(s.ageInTicks * 0.4F), 0);
			pose.rotateDegrees(Axis.XP, -20);
		}
		if ((s.flags & CardEntity.DEFENSE) != 0) {
			pose.rotateDegrees(Axis.YP, 90);
		}
		boolean back = s.cid == 0 || (s.flags & CardEntity.FACE_DOWN) != 0;
		if (back) {
			quad(pose, out, CardRender.BACK, W, H, 0.02F, light);
		} else {
			CardDb.CardInfo c = CardRender.info(s.cid);
			quad(pose, out, CardRender.frame(c == null ? "normal" : c.frame()), W, H, 0.02F, light);
			Identifier art = ArtTextures.get(s.cid);
			if (art != null) {
				// art window of the 64x92 frame: x 7..57 (centered), y 15..65 from the top
				float aw = W * 50 / 64F, ah = H * 50 / 92F, cz = H / 2 - H * 40 / 92F;
				pose.pushPose();
				pose.translate(0, 0, cz);
				quad(pose, out, art, aw, ah, 0.025F, light);
				pose.popPose();
			}
		}
		pose.popPose();
		super.submit(s, pose, out, camera);
	}

	/** A flat textured rectangle w (x) by h (z) at height y; the texture's top edge faces +z. */
	private static void quad(PoseStack pose, SubmitNodeCollector out, Identifier tex, float w, float h, float y, int light) {
		out.submitCustomGeometry(pose, RenderTypes.entityCutout(tex), (p, buf) -> {
			float x0 = -w / 2, x1 = w / 2, z0 = -h / 2, z1 = h / 2;
			vertex(p, buf, x0, y, z0, 1, 1, light);
			vertex(p, buf, x1, y, z0, 0, 1, light);
			vertex(p, buf, x1, y, z1, 0, 0, light);
			vertex(p, buf, x0, y, z1, 1, 0, light);
		});
	}

	private static void vertex(PoseStack.Pose p, VertexConsumer buf, float x, float y, float z, float u, float v, int light) {
		buf.addVertex(p, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
	}
}
