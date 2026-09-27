package com.github.guoyyit.icebergorderbookjava.api;

import com.github.guoyyit.icebergorderbookjava.domain.OrderType;
import com.github.guoyyit.icebergorderbookjava.domain.Side;

/**
 * New order request.
 *
 * @param requestId client supplied idempotency key (mandatory)
 * @param orderId   client supplied order identifier (mandatory, unique)
 * @param symbol    trading instrument (mandatory)
 * @param account   owning account (mandatory)
 * @param side      BUY / SELL
 * @param type      LIMIT / MARKET
 * @param price     limit price (mandatory for LIMIT; forbidden for MARKET)
 * @param quantity  total order quantity including the concealed reserve
 * @param peakSize  iceberg peak/replenishment granularity; null => default.
 *                  If peakSize >= quantity the order behaves as a plain
 *                  (fully visible) order.
 * @param cashCap   for BUY market orders: maximum cash spend; remaining
 *                  quantity is cancelled once reached
 */
public record NewOrderRequest(
        String requestId,
        String orderId,
        String symbol,
        String account,
        Side side,
        OrderType type,
        Long price,
        long quantity,
        Long peakSize,
        Long cashCap
) {
}
