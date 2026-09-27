package com.github.guoyyit.icebergorderbookjava.model;

/**
 * One visible price level. Only aggregated <em>visible</em> quantity is
 * exposed; hidden iceberg quantity is never present here and cannot be
 * inferred from this view.
 */
public record LevelView(long price, long visibleQty, int orderCount) {
}
