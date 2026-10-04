package com.lowdragmc.kilagraph.util;

import com.lowdragmc.lowdraglib2.utils.fluids.FluidAction;
import com.lowdragmc.lowdraglib2.utils.fluids.IFluidHandler;
import com.lowdragmc.lowdraglib2.utils.items.IItemHandler;
import dev.architectury.fluid.FluidStack;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapters between fabric's transfer API ({@link Storage}) and the LDLib2-vendored
 * {@link IItemHandler}/{@link IFluidHandler} shapes the node ports expose. Port types stay
 * identical to the upstream (neoforge-shaped) signatures; only the world lookup changes:
 * {@code Capabilities.*.getCapability} becomes {@code ItemStorage/FluidStorage.SIDED.find}
 * plus these wrappers.
 */
public final class KGCapabilityAdapters {

    private KGCapabilityAdapters() {}

    /** {@code Capabilities.ItemHandler.BLOCK.getCapability(world, pos, null, null, side)} */
    @Nullable
    public static IItemHandler itemHandlerAt(@NotNull Level level, @NotNull BlockPos pos, @Nullable Direction side) {
        var storage = ItemStorage.SIDED.find(level, pos, null, null, side);
        return storage == null ? null : new ItemStorageHandler(storage);
    }

    /** {@code Capabilities.FluidHandler.BLOCK.getCapability(world, pos, null, null, side)} */
    @Nullable
    public static IFluidHandler fluidHandlerAt(@NotNull Level level, @NotNull BlockPos pos, @Nullable Direction side) {
        var storage = FluidStorage.SIDED.find(level, pos, null, null, side);
        return storage == null ? null : new FluidStorageHandler(storage);
    }

    /**
     * {@code entity.getCapability(Capabilities.ItemHandler.ENTITY)}. Fabric has no generic
     * entity item lookup — players expose their inventory, everything else reports none.
     */
    @Nullable
    public static IItemHandler entityItemHandler(@Nullable Entity entity) {
        if (entity instanceof Player player) {
            return new ItemStorageHandler(InventoryStorage.of(player.getInventory(), null));
        }
        return null;
    }

    /** {@code new InvWrapper(container)} — a vanilla {@link Container} as an {@link IItemHandler}. */
    public static IItemHandler containerHandler(@NotNull Container container) {
        return new ItemStorageHandler(InventoryStorage.of(container, null));
    }

    private static List<StorageView<ItemVariant>> itemSlots(Storage<ItemVariant> storage) {
        List<StorageView<ItemVariant>> slots = new ArrayList<>();
        storage.forEach(slots::add);
        return slots;
    }

    private static List<StorageView<FluidVariant>> fluidTanks(Storage<FluidVariant> storage) {
        List<StorageView<FluidVariant>> tanks = new ArrayList<>();
        storage.forEach(tanks::add);
        return tanks;
    }

    private static final class ItemStorageHandler implements IItemHandler {
        private final Storage<ItemVariant> storage;

        private ItemStorageHandler(Storage<ItemVariant> storage) {
            this.storage = storage;
        }

        @Override
        public int getSlots() {
            return itemSlots(storage).size();
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            var views = itemSlots(storage);
            if (slot < 0 || slot >= views.size()) return ItemStack.EMPTY;
            var view = views.get(slot);
            return view.getResource().toStack((int) Math.min(Integer.MAX_VALUE, view.getAmount()));
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return stack;
            var views = itemSlots(storage);
            if (slot < 0 || slot >= views.size()) return stack;
            // StorageView is read-only on fabric; insert goes to the owning storage, which routes
            // the item to its slots internally (per-slot insert like neoforge's is not expressible).
            try (Transaction tx = Transaction.openOuter()) {
                long leftover = stack.getCount() - storage.insert(ItemVariant.of(stack), stack.getCount(), tx);
                if (!simulate) tx.commit();
                return stack.copyWithCount((int) Math.min(Integer.MAX_VALUE, leftover));
            }
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0) return ItemStack.EMPTY;
            var views = itemSlots(storage);
            if (slot < 0 || slot >= views.size()) return ItemStack.EMPTY;
            var view = views.get(slot);
            try (Transaction tx = Transaction.openOuter()) {
                long extracted = view.extract(view.getResource(), amount, tx);
                if (!simulate) tx.commit();
                return view.getResource().toStack((int) Math.min(Integer.MAX_VALUE, extracted));
            }
        }

        @Override
        public int getSlotLimit(int slot) {
            var views = itemSlots(storage);
            if (slot < 0 || slot >= views.size()) return 0;
            return (int) Math.min(Integer.MAX_VALUE, views.get(slot).getCapacity());
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            var views = itemSlots(storage);
            if (slot < 0 || slot >= views.size()) return false;
            try (Transaction tx = Transaction.openOuter()) {
                return storage.insert(ItemVariant.of(stack), stack.getCount(), tx) > 0;
            }
        }
    }

    private static final class FluidStorageHandler implements IFluidHandler {
        private final Storage<FluidVariant> storage;

        private FluidStorageHandler(Storage<FluidVariant> storage) {
            this.storage = storage;
        }

        @Override
        public int getTanks() {
            return fluidTanks(storage).size();
        }

        @Override
        public @NotNull FluidStack getFluidInTank(int tank) {
            var views = fluidTanks(storage);
            if (tank < 0 || tank >= views.size()) return FluidStack.empty();
            var view = views.get(tank);
            // TransferVariant.getComponents() is DataComponentPatch; architectury's create() takes it directly.
            return FluidStack.create(view.getResource().getFluid(), view.getAmount(),
                    view.getResource().getComponents());
        }

        @Override
        public int getTankCapacity(int tank) {
            var views = fluidTanks(storage);
            if (tank < 0 || tank >= views.size()) return 0;
            return (int) Math.min(Integer.MAX_VALUE, views.get(tank).getCapacity());
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            var views = fluidTanks(storage);
            if (tank < 0 || tank >= views.size()) return false;
            try (Transaction tx = Transaction.openOuter()) {
                return storage.insert(toVariant(stack), stack.getAmount(), tx) > 0;
            }
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return 0;
            try (Transaction tx = Transaction.openOuter()) {
                long filled = storage.insert(toVariant(resource), resource.getAmount(), tx);
                if (action.execute()) tx.commit();
                return (int) Math.min(Integer.MAX_VALUE, filled);
            }
        }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) return FluidStack.empty();
            try (Transaction tx = Transaction.openOuter()) {
                long drained = storage.extract(toVariant(resource), resource.getAmount(), tx);
                if (action.execute()) tx.commit();
                return drained <= 0
                        ? FluidStack.empty()
                        : FluidStack.create(resource.getFluid(), drained, resource.getComponents().asPatch());
            }
        }

        @Override
        public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            if (maxDrain <= 0) return FluidStack.empty();
            for (var view : fluidTanks(storage)) {
                if (view.isResourceBlank()) continue;
                try (Transaction tx = Transaction.openOuter()) {
                    var variant = view.getResource();
                    long drained = view.extract(variant, maxDrain, tx);
                    if (drained > 0) {
                        if (action.execute()) tx.commit();
                        return FluidStack.create(variant.getFluid(), drained, variant.getComponents());
                    }
                }
            }
            return FluidStack.empty();
        }

        private static FluidVariant toVariant(FluidStack stack) {
            return FluidVariant.of(stack.getFluid(), stack.getComponents().asPatch());
        }
    }
}
