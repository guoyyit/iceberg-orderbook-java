package com.github.guoyyit.icebergorderbookjava.model;

/**
 * Public trade report for the aggressor (taker). The resting (maker)
 * remaining quantity is deliberately absent so a counterparty cannot
 * infer hidden iceberg size.
 */
public record Fill(String symbol,
                   String takerOrderId,
                   String makerOrderId,
                   Side takerSide,
                   long price,
                   long qty,
                   long takerRemainingAfter) {
}
