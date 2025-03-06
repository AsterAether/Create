package com.simibubi.create.content.logistics.stockTicker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.content.logistics.BigItemStack;

import net.createmod.catnip.codecs.stream.CatnipStreamCodecBuilders;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Package ordering context containing additional information of package orders.
 *
 * @param stacks
 * @param amounts
 */
public record PackageOrderCraftingContext(List<List<BigItemStack>> stacks, List<Integer> amounts) {

	public static final Codec<PackageOrderCraftingContext> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.list(BigItemStack.CODEC.listOf()).fieldOf("amounts").forGetter(PackageOrderCraftingContext::stacks),
		Codec.list(Codec.INT).fieldOf("amounts").forGetter(PackageOrderCraftingContext::amounts)
	).apply(instance, PackageOrderCraftingContext::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, PackageOrderCraftingContext> STREAM_CODEC = StreamCodec.composite(
		CatnipStreamCodecBuilders.list(CatnipStreamCodecBuilders.list(BigItemStack.STREAM_CODEC)), PackageOrderCraftingContext::stacks,
		CatnipStreamCodecBuilders.list(ByteBufCodecs.INT), PackageOrderCraftingContext::amounts,
		PackageOrderCraftingContext::new
	);

    public static boolean hasCraftingInformation(PackageOrderCraftingContext context) {
        if (context == null) {
            return false;
        }

		// Only a valid crafting packet if it contains exactly one recipe
        return context.stacks.size() == 1;
    }

    public static PackageOrderCraftingContext empty() {
        return new PackageOrderCraftingContext(List.of(), List.of());
    }

    public boolean isEmpty() {
        return stacks.isEmpty();
    }

}
