package com.github.guoyyit.icebergorderbookjava.config;

/**
 * Immutable per-symbol matching configuration.
 *
 * @param minVisibleQty  minimum allowed peak/display quantity
 * @param replenishGranularity replenishment granularity; every peak size
 *                       must be a positive multiple of this value
 * @param maxQty         hard cap on order total quantity
 */
public record BookConfig(long minVisibleQty,
                         long replenishGranularity,
                         long maxQty) {

    public static BookConfig defaultConfig() {
        return new BookConfig(1L, 1L, 1_000_000_000L);
    }
}
