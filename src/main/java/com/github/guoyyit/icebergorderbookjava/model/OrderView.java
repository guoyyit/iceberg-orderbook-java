package com.github.guoyyit.icebergorderbookjava.model;

/** Owner view of one order, including hidden remainder (owner only). */
public record OrderView(String orderId,
                        String symbol,
                        String ownerId,
                        Side side,
                        OrderType type,
                        Long price,
                        long totalQty,
                        long filledQty,
                        long remainingQty,
                        long peakSize,
                        long visibleQty,
                        long hiddenQty,
                        long revision,
                        boolean active) {
}
