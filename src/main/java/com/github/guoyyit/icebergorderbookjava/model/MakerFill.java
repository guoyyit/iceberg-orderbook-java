package com.github.guoyyit.icebergorderbookjava.model;

/**
 * Internal fill against one resting order produced by the deterministic
 * matcher. {@code replenished} indicates the maker peak was exhausted
 * and a new slice was appended at the tail of its price level (classic
 * iceberg semantics). {@code price} is the trade price (maker price).
 */
public record MakerFill(String makerOrderId,
                        Side makerSide,
                        long price,
                        long qty,
                        boolean replenished) {
}
