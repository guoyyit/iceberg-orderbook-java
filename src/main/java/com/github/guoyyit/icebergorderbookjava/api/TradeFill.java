package com.github.guoyyit.icebergorderbookjava.api;

/**
 * One trade produced by a match. Fills are reported from the perspective of
 * the incoming (aggressing) request; resting order ids are exposed so clients
 * can correlate, but no hidden quantity is ever reported.
 */
public record TradeFill(
        long tradeSeq,
        String makerOrderId,
        String takerOrderId,
        String symbol,
        long price,
        long quantity,
        long takerRemainingQty,
        String buyAccount,
        String sellAccount
) {
}
