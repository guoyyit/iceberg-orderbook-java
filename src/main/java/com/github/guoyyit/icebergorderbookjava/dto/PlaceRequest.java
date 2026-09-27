package com.github.guoyyit.icebergorderbookjava.dto;

import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;

/** New order request. peakSize null => fully visible limit order. */
public record PlaceRequest(String requestId,
                           String orderId,
                           String symbol,
                           String ownerId,
                           Side side,
                           OrderType type,
                           Long price,
                           Long qty,
                           Long peakSize) {
}
