package com.simibubi.create.content.logistics.packager.repackager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageItem.PackageOrderData;
import com.simibubi.create.content.logistics.stockTicker.PackageOrder;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderCraftingContext;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.items.ItemStackHandler;

public class PackageRepackageHelper {

	protected Map<Integer, List<ItemStack>> collectedPackages = new HashMap<>();

	public void clear() {
		collectedPackages.clear();
	}

	public boolean isFragmented(ItemStack box) {
		if (!box.has(AllDataComponents.PACKAGE_ORDER_DATA))
			return false;

		PackageOrderData data = box.get(AllDataComponents.PACKAGE_ORDER_DATA);

		return !(data.linkIndex() == 0 && data.isFinalLink() && data.fragmentIndex() == 0 && data.isFinal());
	}

	public int addPackageFragment(ItemStack box) {
		int collectedOrderId = PackageItem.getOrderId(box);
		if (collectedOrderId == -1)
			return -1;

		List<ItemStack> collectedOrder = collectedPackages.computeIfAbsent(collectedOrderId, $ -> Lists.newArrayList());
		collectedOrder.add(box);

		if (!isOrderComplete(collectedOrderId))
			return -1;

		return collectedOrderId;
	}

	public List<ItemStack> repack(int orderId) {
		List<ItemStack> exportingPackages = new ArrayList<>();
		String address = "";
		PackageOrder orderContext = null;
		PackageOrderCraftingContext orderCraftingContext = null;
		List<BigItemStack> allItems = new ArrayList<>();

		for (ItemStack box : collectedPackages.get(orderId)) {
			address = PackageItem.getAddress(box);
			if (box.has(AllDataComponents.PACKAGE_ORDER_DATA)) {
				PackageOrder context = box.get(AllDataComponents.PACKAGE_ORDER_DATA).orderContext();
				if (context != null && !context.isEmpty())
					orderContext = context;
				PackageOrderCraftingContext craftingContext = box.get(AllDataComponents.PACKAGE_ORDER_DATA).craftingContext();
				if (craftingContext != null && !craftingContext.isEmpty())
					orderCraftingContext = craftingContext;
			}


			ItemStackHandler contents = PackageItem.getContents(box);
			Slots: for (int slot = 0; slot < contents.getSlots(); slot++) {
				ItemStack stackInSlot = contents.getStackInSlot(slot);
				for (BigItemStack existing : allItems) {
					if (!ItemStack.isSameItemSameComponents(stackInSlot, existing.stack))
						continue;
					existing.count += stackInSlot.getCount();
					continue Slots;
				}
				allItems.add(new BigItemStack(stackInSlot, stackInSlot.getCount()));
			}
		}

		List<BigItemStack> orderedStacks = new ArrayList<>();
		if (orderContext != null) {
			for (BigItemStack stack : orderContext.stacks()) {
				orderedStacks.add(new BigItemStack(stack.stack, stack.count));
			}
		}

		List<ItemStack> outputSlots = new ArrayList<>();

		Repack:
		while (true) {
			allItems.removeIf(e -> e.count == 0);
			if (allItems.isEmpty())
				break;

			BigItemStack targetedEntry = null;
			if (!orderedStacks.isEmpty())
				targetedEntry = orderedStacks.remove(0);

			ItemSearch:
			for (BigItemStack entry : allItems) {
				int targetAmount = entry.count;
				if (targetAmount == 0)
					continue;
				if (targetedEntry != null) {
					targetAmount = targetedEntry.count;
					if (!ItemStack.isSameItemSameComponents(entry.stack, targetedEntry.stack))
						continue;
				}

				while (targetAmount > 0) {
					int removedAmount = Math.min(Math.min(targetAmount, entry.stack.getMaxStackSize()), entry.count);
					if (removedAmount == 0)
						continue ItemSearch;

					ItemStack output = entry.stack.copyWithCount(removedAmount);
					targetAmount -= removedAmount;
					if (targetedEntry != null)
						targetedEntry.count = targetAmount;
					entry.count -= removedAmount;
					outputSlots.add(output);
				}

				continue Repack;
			}
		}

		int currentSlot = 0;
		ItemStackHandler target = new ItemStackHandler(PackageItem.SLOTS);

		for (ItemStack item : outputSlots) {
			target.setStackInSlot(currentSlot++, item);
			if (currentSlot < PackageItem.SLOTS)
				continue;
			exportingPackages.add(PackageItem.containing(target));
			target = new ItemStackHandler(PackageItem.SLOTS);
			currentSlot = 0;
		}

		for (int slot = 0; slot < target.getSlots(); slot++)
			if (!target.getStackInSlot(slot)
				.isEmpty()) {
				exportingPackages.add(PackageItem.containing(target));
				break;
			}

		for (ItemStack box : exportingPackages)
			PackageItem.addAddress(box, address);

		for (int i = 0; i < exportingPackages.size(); i++) {
			ItemStack box = exportingPackages.get(i);
			boolean isfinal = i == exportingPackages.size() - 1;
			PackageOrder outboundOrderContext = isfinal && orderContext != null ? new PackageOrder(orderContext.stacks()) : null;
			PackageOrderCraftingContext outboundCraftingContext = isfinal && orderCraftingContext != null ? new PackageOrderCraftingContext(orderCraftingContext.stacks(), orderCraftingContext.amounts()) : null;
			PackageItem.setOrder(box, orderId, 0, true, 0, true, outboundOrderContext, outboundCraftingContext);
		}

		return exportingPackages;
	}


	private boolean isOrderComplete(int orderId) {
		boolean finalLinkReached = false;
		Links:
		for (int linkCounter = 0; linkCounter < 1000; linkCounter++) {
			if (finalLinkReached)
				break;
			Packages:
			for (int packageCounter = 0; packageCounter < 1000; packageCounter++) {
				for (ItemStack box : collectedPackages.get(orderId)) {
					PackageOrderData data = box.get(AllDataComponents.PACKAGE_ORDER_DATA);
					if (linkCounter != data.linkIndex())
						continue;
					if (packageCounter != data.fragmentIndex())
						continue;
					finalLinkReached = data.isFinalLink();
					if (data.isFinal())
						continue Links;
					continue Packages;
				}
				return false;
			}
		}
		return true;
	}

	protected boolean shouldSplit(ItemStack box) {
		if (!box.has(AllDataComponents.PACKAGE_ORDER_DATA))
			return false;

		PackageOrderCraftingContext orderContext = PackageItem.getOrderCraftingContext(box);

		// If stacks are >= 2, there is at least two crafting recipes in the request, need to split
		// If the amount of any request is higher than one, need to split
		return orderContext.stacks().size() >= 2 || orderContext.amounts().stream().anyMatch(a -> a > 1);
	}

	protected List<ItemStack> split(ItemStack box) {
		PackageOrderCraftingContext orderCraftingContext = PackageItem.getOrderCraftingContext(box);
		int orderId = PackageItem.getOrderId(box);
		String address = PackageItem.getAddress(box);
		ItemStackHandler contents = PackageItem.getContents(box);
		// Get all available items
		List<BigItemStack> allItems = new ArrayList<>();
		AllItems:
		for (int slot = 0; slot < contents.getSlots(); slot++) {
			ItemStack stackInSlot = contents.getStackInSlot(slot);
			if (stackInSlot.isEmpty())
				continue;
			for (BigItemStack existing : allItems) {
				if (!ItemStack.isSameItemSameComponents(stackInSlot, existing.stack))
					continue;
				existing.count += stackInSlot.getCount();
				continue AllItems;
			}
			allItems.add(new BigItemStack(stackInSlot.copy(), stackInSlot.getCount()));
		}

		List<ItemStack> packages = new ArrayList<>();
		// Each context list is a unique crafting recipe
		for (int i = 0; i < orderCraftingContext.stacks().size(); i++) {
			int amount = orderCraftingContext.amounts().get(i);
			List<BigItemStack> packRequest = orderCraftingContext.stacks().get(i);
			// Create a package per requested amount of the recipe
			for (int j = 0; j < amount; j++) {
				allItems.removeIf(e -> e.count == 0);
				List<BigItemStack> packRequestInstance = new ArrayList<>();
				List<ItemStack> outputSlots = new ArrayList<>();

				PackRequestFill:
				for (BigItemStack bis : packRequest) {
					if (bis.stack.isEmpty())
						continue;
					for (BigItemStack existing : packRequestInstance) {
						if (!ItemStack.isSameItemSameComponents(bis.stack, existing.stack))
							continue;
						existing.count += bis.count;
						continue PackRequestFill;
					}
					packRequestInstance.add(new BigItemStack(bis.stack, bis.count));
				}

				for (BigItemStack targetedEntry : packRequestInstance) {
					int targetAmount = targetedEntry.count;
					ItemSearch:
					for (BigItemStack entry : allItems) {
						if (!ItemStack.isSameItemSameComponents(targetedEntry.stack, entry.stack))
							continue;
						while (targetAmount > 0) {
							int removedAmount = Math.min(Math.min(targetAmount, entry.stack.getMaxStackSize()), entry.count);
							if (removedAmount == 0)
								continue ItemSearch;

							ItemStack output = entry.stack.copyWithCount(removedAmount);
							targetAmount -= removedAmount;
							entry.count -= removedAmount;
							outputSlots.add(output);
						}
					}
					// Sanity check, if there is a targetAmount left the package didn't contain enough items to fulfill everything
					// Shouldn't happen with just Create itself, but can happen with malformed packages
					if (targetAmount > 0) {
						Create.LOGGER.error("Package splitting failed because package did not contain enough items to fulfill all requests");
						return null;
					}
				}

				ItemStackHandler target = new ItemStackHandler(PackageItem.SLOTS);
				for (int k = 0; k < outputSlots.size(); k++)
					target.setStackInSlot(k, outputSlots.get(k));
				ItemStack packageItem = PackageItem.containing(target);
				PackageItem.addAddress(packageItem, address);
				List<List<BigItemStack>> newCraftContext = new ArrayList<>();
				// Second is the actual crafting recipe
				newCraftContext.add(packRequest);
				PackageItem.setOrder(packageItem, orderId, 0, true, 0, true, new PackageOrder(packRequest), new PackageOrderCraftingContext(newCraftContext, List.of(1)));
				packages.add(packageItem);
			}
		}
		return packages;
	}

}
