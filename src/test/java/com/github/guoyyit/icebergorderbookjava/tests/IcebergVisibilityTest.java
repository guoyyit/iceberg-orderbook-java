package com.github.guoyyit.icebergorderbookjava.tests;

import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.domain.Side;
import com.github.guoyyit.icebergorderbookjava.engine.SymbolEngine;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcebergVisibilityTest {

    @TempDir
    Path dir;
    private MatchingProperties props;
    private BalanceLedger ledger;
    private SymbolEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        props = TestSupport.props(dir);
        ledger = new BalanceLedger();
        ledger.credit("seller", "AAA", 10_000);
        ledger.credit("buyer", BalanceLedger.CASH, 10_000_000);
        engine = TestSupport.engine("AAA", props, ledger);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.close();
    }

    @Test
    void depthExposesOnlyVisiblePeakNeverHiddenReserve() {
        // iceberg sell: total 1000, peak 100 => visible 100, hidden 900
        OrderResponse r = engine.newOrder(TestSupport.limit(
                "r1", "o1", "AAA", "seller", Side.SELL, 100, 1000, 100L));
        assertTrue(r.accepted());
        assertEquals(100, r.remainingVisibleQty());

        DepthSnapshot snap = engine.snapshot();
        assertEquals(1, snap.asks().size());
        assertEquals(100, snap.asks().get(0).visibleQty());
        assertEquals(1, snap.asks().get(0).orderCount());
        assertTrue(snap.bids().isEmpty());
    }

    @Test
    void peakReplenishesFromHiddenReserveAfterPartialFill() {
        engine.newOrder(TestSupport.limit("r1", "iceberg", "AAA", "seller",
                Side.SELL, 100, 300, 100L));
        // buyer takes 100 (one peak)
        OrderResponse crossed = engine.newOrder(TestSupport.limit(
                "r2", "aggr", "AAA", "buyer", Side.BUY, 100, 100, 100L));
        assertEquals(1, crossed.fills().size());
        assertEquals(100, crossed.fills().get(0).quantity());
        assertEquals(0, crossed.remainingVisibleQty());
        assertEquals(100, crossed.filledQty());

        // iceberg replenished from hidden: depth still shows exactly 100
        DepthSnapshot snap = engine.snapshot();
        assertEquals(100, snap.asks().get(0).visibleQty());
    }

    @Test
    void replenishedPeakMatchesAgainWithinSameAggressor() {
        // iceberg sell 250 @100 peak 100
        engine.newOrder(TestSupport.limit("r1", "iceberg", "AAA", "seller",
                Side.SELL, 100, 250, 100L));
        // market buyer for 250, cash cap big enough
        OrderResponse m = engine.newOrder(TestSupport.market(
                "r2", "mo", "AAA", "buyer", Side.BUY, 250, 250_000L));
        assertEquals(250, m.filledQty());
        assertEquals(3, m.fills().size()); // 100 + 100 + 50
        assertEquals(100, m.fills().get(0).quantity());
        assertEquals(100, m.fills().get(1).quantity());
        assertEquals(50, m.fills().get(2).quantity());
        // exact trade sequencing
        assertEquals(1, m.fills().get(0).tradeSeq());
        assertEquals(3, m.fills().get(2).tradeSeq());

        assertTrue(engine.snapshot().asks().isEmpty());
    }

    @Test
    void plainOrderFullyVisibleWhenPeakCoversQuantity() {
        engine.newOrder(TestSupport.limit("r1", "plain", "AAA", "seller",
                Side.SELL, 50, 40, 100L));
        assertEquals(40, engine.snapshot().asks().get(0).visibleQty());
    }
}
