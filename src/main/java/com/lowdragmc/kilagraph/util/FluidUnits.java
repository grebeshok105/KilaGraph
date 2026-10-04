package com.lowdragmc.kilagraph.util;

import dev.architectury.fluid.FluidStack;

/**
 * Conversion between the graph-facing fluid unit and the unit architectury's
 * {@link FluidStack} actually stores.
 *
 * <p>Graph nodes promise millibuckets (a bucket is 1000). On fabric a {@code FluidStack}
 * carries droplets instead ({@code FluidStack.bucketAmount()} is 81000, so 81 droplets
 * per mB); on neoforge a stack carries mB directly and the scale is 1. All
 * conversion happens at the boundary — port inputs, port outputs, and the capability
 * adapter — never inside a stack, so a stack's {@code getAmount()} stays in droplets.</p>
 */
public final class FluidUnits {

    private static final long SCALE = FluidStack.bucketAmount() / 1000;

    private FluidUnits() {}

    /** Graph-facing millibuckets to the stack-internal droplets. */
    public static long mbToDroplets(long mb) {
        return mb * SCALE;
    }

    /** Stack-internal droplets to graph-facing millibuckets (truncates, like any unit cast). */
    public static long dropletsToMb(long droplets) {
        return droplets / SCALE;
    }

    /** {@link #dropletsToMb} clamped to int for the int-typed port surfaces. */
    public static int dropletsToMbInt(long droplets) {
        return (int) Math.min(Integer.MAX_VALUE, droplets / SCALE);
    }
}
