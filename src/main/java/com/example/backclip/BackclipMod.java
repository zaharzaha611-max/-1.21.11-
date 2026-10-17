package com.example.backclip;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class BackclipMod implements ModInitializer {
	public static final String MOD_ID = "backclip";

	public static Item CLIP_WAND;

	@Override
	public void onInitialize() {
		Identifier itemId = Identifier.of(MOD_ID, "clip_wand");
		CLIP_WAND = Registry.register(Registries.ITEM, itemId, new ClipWandItem(
				new Item.Settings()
						.registryKey(RegistryKey.of(RegistryKeys.ITEM, itemId))
						.maxCount(1)));

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(CLIP_WAND));
	}
}
