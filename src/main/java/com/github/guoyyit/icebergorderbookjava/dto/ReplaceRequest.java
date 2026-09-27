package com.github.guoyyit.icebergorderbookjava.dto;

/**
 * Amend/replace request. Null fields mean "unchanged" (where legal).
 * In KEEP mode only a non-increased total quantity is allowed, price
 * and peakSize must be unchanged; LOSE mode re-enters at the tail.
 */
public record ReplaceRequest(String requestId,
                             String orderId,
                             String symbol,
                             Long newPrice,
                             Long newTotalQty,
                             Long newPeakSize,
                             String priorityMode,
                             Long revision) {
}
