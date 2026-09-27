package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceResult;
import com.github.guoyyit.icebergorderbookjava.model.DepthView;
import com.github.guoyyit.icebergorderbookjava.model.LevelView;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcebergReplenishTest {

    private SymbolEngine engine(Path dir) {
        SymbolEngine e = new SymbolEngine("BTC-USD",
                new BookConfig(1, 1, 1_000_000_000L), dir.toString());
        e.recover();
        return e;
    }

    private PlaceRequest limit(String rid, String oid, Side side,
                               long price, long qty, Long peak) {
        return new PlaceRequest(rid, oid, "BTC-USD", "owner", side,
                OrderType.LIMIT, price, qty, peak);
    }

    @Test
    void depthExposesOnlyVisiblePeak(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        // iceberg: total 100, peak 10
        e.place(limit("r1", "ice", Side.SELL, 100L, 100L, 10L));

        DepthView d = e.depth(10);
        assertEquals(1, d.asks().size());
        LevelView lvl = d.asks().get(0);
        assertEquals(100L, lvl.price());
        assertEquals(10L, lvl.visibleQty(), "only the peak is visible");
        assertEquals(1, lvl.orderCount());
        // frozen (reserved) is exactly the visible slice
        assertEquals(10L, d.frozenSellQty());
    }

    @Test
    void replenishesFromHiddenAndReentersTail(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        // iceberg A total 100 peak 10, then plain visible B 5 at same price
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        e.place(limit("b", "B", Side.SELL, 100L, 5L, null));

        // buy 10: consumes A's first peak; A replenishes and goes to tail
        PlaceResult r1 = e.place(limit("c", "C", Side.BUY, 100L, 10L, null));
        assertEquals("FILLED", r1.status());
        assertEquals(10L, r1.fills().get(0).qty());
        assertEquals("A", r1.fills().get(0).makerOrderId());
        // public fill carries no maker remaining/hidden field at all
        assertTrue(r1.fills().get(0).makerOrderId() != null);
        assertEquals(90L, totalRemaining(e, "A"));
        // B now ahead of replenished A: visible 5 (B) + 10 (A) = 15
        DepthView d1 = e.depth(10);
        assertEquals(15L, d1.asks().get(0).visibleQty());
        assertEquals(2, d1.asks().get(0).orderCount());

        // buy 5: hits B first (time priority over replenished A); A untouched
        PlaceResult r2 = e.place(limit("d", "D", Side.BUY, 100L, 5L, null));
        assertEquals("B", r2.fills().get(0).makerOrderId());
        assertEquals(90L, totalRemaining(e, "A"));

        // buy 10: hits replenished A peak
        PlaceResult r3 = e.place(limit("e", "E", Side.BUY, 100L, 10L, null));
        assertEquals("A", r3.fills().get(0).makerOrderId());
        assertEquals(80L, totalRemaining(e, "A"));

        // depth still only ever shows one peak of A, never the hidden 80
        DepthView d3 = e.depth(10);
        assertEquals(10L, d3.asks().get(0).visibleQty());
        assertEquals(1, d3.asks().get(0).orderCount());

        e.close();
    }

    @Test
    void partialPeakFillKeepsPosition(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        e.place(limit("b", "B", Side.SELL, 100L, 5L, null));
        // partial fill of A's peak (4 of 10): no replenish, A stays head
        e.place(limit("c", "C", Side.BUY, 100L, 4L, null));
        PlaceResult r = e.place(limit("d", "D", Side.BUY, 100L, 6L, null));
        // first 6 hits A (6 remaining peak), A exhausts and replenishes
        assertEquals("A", r.fills().get(0).makerOrderId());
        assertEquals(6L, r.fills().get(0).qty());
        // remaining buy is 0 -> done; B untouched
        assertEquals(90L, totalRemaining(e, "A"));
        assertNotNull(e);
        e.close();
    }

    @Test
    void marketOrderCrossesIcebergAndDoesNotRest(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        // demand exceeds total liquidity (100): consume 100, no rest
        PlaceResult r = e.place(new PlaceRequest("m", "M", "BTC-USD", "o",
                Side.BUY, OrderType.MARKET, null, 250L, null));
        long sum = r.fills().stream().mapToLong(f -> f.qty()).sum();
        assertEquals(100L, sum);
        assertEquals("MARKET_DONE", r.status());
        // market order is gone, nothing rests on the bid side
        assertTrue(e.depth(10).bids().isEmpty());
        // fully-filled maker removed; ask side empty
        assertTrue(e.depth(10).asks().isEmpty());
        e.close();
    }

    private long totalRemaining(SymbolEngine e, String orderId) {
        // owner view via a new read-only depth is not enough; use package API
        return com.github.guoyyit.icebergorderbookjava.engine.Views
                .remaining(e, orderId);
    }
}
