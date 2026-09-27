package com.github.guoyyit.icebergorderbookjava.dto;

/**
 * Cancel request. {@code revision} is optional optimistic ordering
 * guard; when present it must equal the order's current revision.
 */
public record CancelRequest(String requestId,
                            String orderId,
                            String symbol,
                            Long revision) {
}
