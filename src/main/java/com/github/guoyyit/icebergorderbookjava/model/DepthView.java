package com.github.guoyyit.icebergorderbookjava.model;

import java.util.List;

/** Visible depth snapshot (bids descending, asks ascending). */
public record DepthView(String symbol,
                        List<LevelView> bids,
                        List<LevelView> asks,
                        long frozenBuyQty,
                        long frozenSellQty) {
}
