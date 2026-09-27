package com.github.guoyyit.icebergorderbookjava.api;

import java.util.List;

/**
 * Visible order book depth. Only displayed peak quantities are aggregated;
 * hidden reserves never appear anywhere in this structure.
 */
public record DepthSnapshot(
        String symbol,
        long lastTradeSeq,
        List<Level> bids,
        List<Level> asks
) {
    public record Level(long price, long visibleQty, int orderCount) {
    }
}
