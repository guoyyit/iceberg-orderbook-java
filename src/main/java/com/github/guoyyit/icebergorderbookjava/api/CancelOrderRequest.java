package com.github.guoyyit.icebergorderbookjava.api;

/**
 * Cancel request. {@code expectedVersion} is optional optimistic
 * concurrency control: when non-null it must match the order's current
 * version, otherwise a late/duplicate client request is rejected.
 */
public record CancelOrderRequest(
        String requestId,
        String symbol,
        String orderId,
        Long expectedVersion
) {
}
