package dev.duelcraft.item;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/** A deck: up to 75 cards kept in the item (items sheet: deck_box). Use it to edit; right-click a mob or player to duel. */
public final class DeckBoxItem extends Item {
	public static final int SLOTS = 75;

	/** A deck split for the duel engine: Master Duel cids. */
	public record Deck(List<Integer> main, List<Integer> extra) {
	}

	public DeckBoxItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer sp) {
			sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new DeckBoxMenu(id, inv, hand), Component.translatable("container.duelcraft.deck_box")));
		}
		return InteractionResult.SUCCESS;
	}

	public static List<ItemStack> cards(ItemStack box) {
		NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
		ItemContainerContents c = box.get(DataComponents.CONTAINER);
		if (c != null) {
			c.copyInto(items);
		}
		return items;
	}

	public static void setCards(ItemStack box, List<ItemStack> cards) {
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(cards));
	}

	public static ItemStack withDeck(int[] main, int[] extra) {
		ItemStack box = new ItemStack(ModItems.DECK_BOX);
		List<ItemStack> cards = new ArrayList<>();
		for (int cid : main) {
			cards.add(CardItem.stack(cid));
		}
		for (int cid : extra) {
			cards.add(CardItem.stack(cid));
		}
		setCards(box, cards);
		return box;
	}

	public static Deck deck(ItemStack box) {
		CardDb db = DuelCraftData.db();
		List<Integer> main = new ArrayList<>(), extra = new ArrayList<>();
		for (ItemStack s : cards(box)) {
			if (s.getItem() instanceof CardItem) {
				int cid = CardItem.cid(s);
				CardDb.CardInfo c = db == null ? null : db.byCid(cid);
				for (int n = 0; n < s.getCount(); n++) {
					(c != null && c.isExtraDeck() ? extra : main).add(cid);
				}
			}
		}
		return new Deck(main, extra);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		Deck d = deck(stack);
		out.accept(Component.translatable("item.duelcraft.deck_box.count", d.main().size(), d.extra().size()));
		out.accept(Component.translatable("item.duelcraft.deck_box.hint"));
	}
}
