package com.github.guoyyit.icebergorderbookjava.api;

import java.util.List;

/**
 * Result of a new/cancel/replace request. {@code rejected} is true only for
 * rejected requests; {@code fills} lists the trades produced by this request
 * in deterministic sequence order.
 */
public record OrderResponse(
        String requestId,
        String orderId,
        boolean accepted,
        boolean rejected,
        String rejectCode,
        Long orderVersion,
        long remainingVisibleQty,
        long filledQty,
        boolean cancelled,
        List<TradeFill> fills
) {
    public static OrderResponse rejected(String requestId, String orderId, String code) {
        return new OrderResponse(requestId, orderId, false, true, code,
                null, 0L, 0L, false, List.of());
    }
}
