package com.github.guoyyit.icebergorderbookjava.api;

/**
 * Replace (modify) request.
 *
 * <p>Only the non-null fields are changed: {@code newPrice} null keeps the
 * price; {@code newPeakSize} null keeps the peak. {@code newTotalQty} is
 * mandatory and must cover the already filled quantity.
 *
 * @param keepPriority true  => retain time priority (only permitted at an
 *                                unchanged price)
 *                     false => order goes to the back of the queue and
 *                              receives a new sequence number
 */
public record ReplaceOrderRequest(
        String requestId,
        String symbol,
        String orderId,
        Long expectedVersion,
        Long newPrice,
        Long newTotalQty,
        Long newPeakSize,
        boolean keepPriority
) {
}
