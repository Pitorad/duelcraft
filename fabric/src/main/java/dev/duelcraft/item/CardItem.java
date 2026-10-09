package dev.duelcraft.item;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/**
 * One Yu-Gi-Oh! card from the player's Master Duel. The stack stores only the Master Duel card id; the
 * name, art, stats and text are read from Master Duel on each PC (items sheet: card).
 */
public final class CardItem extends Item {
	/** Tooltip payload: the client draws the art, stats and effect text for this cid. */
	public record CardTooltipData(int cid) implements TooltipComponent {
	}

	public CardItem(Properties properties) {
		super(properties);
	}

	public static ItemStack stack(int cid) {
		ItemStack s = new ItemStack(ModItems.CARD);
		s.set(ModItems.CARD_ID, cid);
		CardDb db = DuelCraftData.db();
		CardDb.CardInfo c = db == null ? null : db.byCid(cid);
		String frame = c == null ? "normal" : c.frame();
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(frame), List.of()));
		return s;
	}

	public static int cid(ItemStack s) {
		Integer c = s.get(ModItems.CARD_ID);
		return c == null ? 0 : c;
	}

	public static CardDb.CardInfo info(ItemStack s) {
		CardDb db = DuelCraftData.db();
		return db == null ? null : db.byCid(cid(s));
	}

	@Override
	public Component getName(ItemStack stack) {
		CardDb.CardInfo c = info(stack);
		if (c != null && !c.name().isEmpty()) {
			return Component.literal(c.name());
		}
		return cid(stack) == 0 ? super.getName(stack) : Component.translatable("item.duelcraft.card.unknown", cid(stack));
	}

	@Override
	public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
		int cid = cid(stack);
		return cid == 0 ? Optional.empty() : Optional.of(new CardTooltipData(cid));
	}
}
