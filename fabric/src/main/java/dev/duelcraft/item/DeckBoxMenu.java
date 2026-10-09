package dev.duelcraft.item;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Deck box editor: 75 card slots (5 rows of 15) above the player's inventory. Slots take one card each.
 * Saved back into the deck box in the player's hand whenever it changes.
 */
public final class DeckBoxMenu extends AbstractContainerMenu {
	public static final int COLS = 15, ROWS = 5;
	private final Container deck;
	private final InteractionHand hand;
	private final Player player;

	/** Client side (the server syncs the slots). */
	public DeckBoxMenu(int id, Inventory inv) {
		this(id, inv, null);
	}

	public DeckBoxMenu(int id, Inventory inv, InteractionHand hand) {
		super(ModItems.DECK_BOX_MENU, id);
		this.hand = hand;
		this.player = inv.player;
		deck = new SimpleContainer(DeckBoxItem.SLOTS) {
			@Override
			public void setChanged() {
				super.setChanged();
				save();
			}
		};
		if (hand != null) {
			List<ItemStack> cards = DeckBoxItem.cards(player.getItemInHand(hand));
			for (int i = 0; i < cards.size(); i++) {
				deck.setItem(i, cards.get(i));
			}
		}
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				addSlot(new Slot(deck, r * COLS + c, 8 + c * 18, 18 + r * 18) {
					@Override
					public boolean mayPlace(ItemStack stack) {
						return stack.getItem() instanceof CardItem;
					}

					@Override
					public int getMaxStackSize() {
						return 1;
					}
				});
			}
		}
		int invX = 8 + (COLS * 18 - 9 * 18) / 2, invY = 18 + ROWS * 18 + 14;
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 9; c++) {
				addSlot(new Slot(inv, 9 + r * 9 + c, invX + c * 18, invY + r * 18));
			}
		}
		for (int c = 0; c < 9; c++) {
			addSlot(new Slot(inv, c, invX + c * 18, invY + 58) {
				@Override
				public boolean mayPickup(Player p) {
					// the open deck box itself can't be moved while editing it
					return hand != InteractionHand.MAIN_HAND || getContainerSlot() != inv.getSelectedSlot();
				}
			});
		}
	}

	private void save() {
		if (hand == null || player.level().isClientSide()) {
			return;
		}
		ItemStack box = player.getItemInHand(hand);
		if (box.getItem() instanceof DeckBoxItem) {
			List<ItemStack> cards = new ArrayList<>();
			for (int i = 0; i < deck.getContainerSize(); i++) {
				cards.add(deck.getItem(i).copy());
			}
			DeckBoxItem.setCards(box, cards);
		}
	}

	@Override
	public ItemStack quickMoveStack(Player p, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack before = stack.copy();
		int deckEnd = DeckBoxItem.SLOTS;
		if (index < deckEnd) {
			if (!moveItemStackTo(stack, deckEnd, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			if (!(stack.getItem() instanceof CardItem)) {
				return ItemStack.EMPTY;
			}
			// one card per deck slot
			for (int i = 0; i < deckEnd && !stack.isEmpty(); i++) {
				Slot s = slots.get(i);
				if (!s.hasItem()) {
					s.set(stack.split(1));
				}
			}
		}
		if (stack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return stack.getCount() == before.getCount() ? ItemStack.EMPTY : before;
	}

	@Override
	public boolean stillValid(Player p) {
		return hand == null || p.getItemInHand(hand).getItem() instanceof DeckBoxItem;
	}

	public int[] counts() {
		int main = 0, extra = 0;
		var db = dev.duelcraft.DuelCraftData.db();
		for (int i = 0; i < DeckBoxItem.SLOTS; i++) {
			ItemStack s = slots.get(i).getItem();
			if (s.getItem() instanceof CardItem) {
				var c = db == null ? null : db.byCid(CardItem.cid(s));
				if (c != null && c.isExtraDeck()) {
					extra++;
				} else {
					main++;
				}
			}
		}
		return new int[] {main, extra};
	}
}
