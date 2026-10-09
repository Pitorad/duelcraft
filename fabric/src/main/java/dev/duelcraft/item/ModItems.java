package dev.duelcraft.item;

import com.mojang.serialization.Codec;
import dev.duelcraft.DuelCraft;
import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.gen.HookIds;
import dev.duelcraft.gen.ItemSpecs;
import java.util.Comparator;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Registers the items sheet (card, deck box), the card_id component, the deck box menu and the Cards creative tab. */
public final class ModItems {
	public static final DataComponentType<Integer> CARD_ID = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id("card_id"),
		DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	public static final CardItem CARD = register(ItemSpecs.CARD, CardItem::new);
	public static final DeckBoxItem DECK_BOX = register(ItemSpecs.DECK_BOX, DeckBoxItem::new);

	public static final MenuType<DeckBoxMenu> DECK_BOX_MENU = Registry.register(BuiltInRegistries.MENU, id("deck_box"),
		new MenuType<>(DeckBoxMenu::new, FeatureFlags.VANILLA_SET));

	public static final CreativeModeTab TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("cards"),
		CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
			.title(Component.translatable("itemGroup.duelcraft.cards"))
			.icon(() -> new ItemStack(DECK_BOX))
			.displayItems((params, out) -> {
				out.accept(new ItemStack(DECK_BOX));
				CardDb db = DuelCraftData.db();
				if (db != null) {
					db.all().stream().filter(c -> (c.type() & CardDb.TYPE_TOKEN) == 0 && !c.name().isEmpty())
						.sorted(Comparator.comparing(CardDb.CardInfo::name)).forEach(c -> out.accept(CardItem.stack(c.cid())));
				}
			}).build());

	private ModItems() {
	}

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DuelCraft.MOD_ID, path);
	}

	private static <T extends Item> T register(ItemSpecs.ItemSpec spec, java.util.function.Function<Item.Properties, T> factory) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(spec.id()));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key).stacksTo(spec.maxStack())));
	}

	public static void init() {
		HookIds.REGISTRIES.installed();
	}
}
