package com.github.guoyyit.icebergorderbookjava.tests;

import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.domain.Side;
import com.github.guoyyit.icebergorderbookjava.engine.SymbolEngine;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @TempDir
    Path dir;

    @Test
    void restartRebuildsQuantitiesDepthPriorityAndFreezes() throws Exception {
        MatchingProperties props = TestSupport.props(dir);
        BalanceLedger ledger = new BalanceLedger();
        ledger.credit("s1", "AAA", 10_000);
        ledger.credit("s2", "AAA", 10_000);
        ledger.credit("buyer", BalanceLedger.CASH, 10_000_000);

        SymbolEngine engine = TestSupport.engine("AAA", props, ledger);
        // resting iceberg sell 300 peak 100
        engine.newOrder(TestSupport.limit("req-1", "ice1", "AAA", "s1",
                Side.SELL, 100, 300, 100L));
        // ordinary sell behind it
        engine.newOrder(TestSupport.limit("req-2", "ice2", "AAA", "s2",
                Side.SELL, 100, 100, 100L));
        // partial fill of 150: peak1 (100) fully consumed & replenished to
        // peak2, then 50 of peak2 consumed => iceberg shows 50 visible and
        // keeps 100 hidden; the second seller still shows 100.
        OrderResponse before = engine.newOrder(TestSupport.market(
                "req-3", "buy1", "AAA", "buyer", Side.BUY, 150, 15_000L));
        assertEquals(150, before.filledQty());

        DepthSnapshot beforeSnap = engine.snapshot();
        // aggregated visible depth is 50 (iceberg remainder) + 100 (seller 2);
        // the iceberg's hidden 100 never contributes
        assertEquals(150, beforeSnap.asks().get(0).visibleQty());
        long frozenSellerBefore = ledger.frozen("s1", "AAA");
        engine.close();

        // ---- simulate process restart: fresh ledger + fresh engine ----
        BalanceLedger ledger2 = new BalanceLedger();
        ledger2.credit("buyer", BalanceLedger.CASH, 10_000_000);
        SymbolEngine restarted = TestSupport.engine("AAA", props, ledger2);

        DepthSnapshot after = restarted.snapshot();
        assertEquals(beforeSnap.asks().size(), after.asks().size());
        assertEquals(beforeSnap.asks().get(0).visibleQty(), after.asks().get(0).visibleQty());
        assertEquals(150, after.asks().get(0).visibleQty());

        // frozen asset of the partially filled iceberg is restored (300-150)
        assertEquals(frozenSellerBefore, ledger2.frozen("s1", "AAA"));
        assertEquals(150, ledger2.frozen("s1", "AAA"));

        // priority restored: next buyer must hit the replenished iceberg first
        OrderResponse next = restarted.newOrder(TestSupport.market(
                "req-4", "buy2", "AAA", "buyer", Side.BUY, 100, 10_000L));
        assertEquals("ice1", next.fills().get(0).makerOrderId());
        restarted.close();
    }

    @Test
    void replayedRequestAfterRestartIsIdempotent() throws Exception {
        MatchingProperties props = TestSupport.props(dir);
        BalanceLedger ledger = new BalanceLedger();
        ledger.credit("s1", "AAA", 10_000);
        ledger.credit("buyer", BalanceLedger.CASH, 10_000_000);
        SymbolEngine engine = TestSupport.engine("AAA", props, ledger);
        engine.newOrder(TestSupport.limit("req-1", "ice1", "AAA", "s1",
                Side.SELL, 100, 300, 100L));
        OrderResponse first = engine.newOrder(TestSupport.market(
                "req-2", "buy1", "AAA", "buyer", Side.BUY, 50, 5_000L));
        assertEquals(50, first.filledQty());
        engine.close();

        BalanceLedger ledger2 = new BalanceLedger();
        ledger2.credit("buyer", BalanceLedger.CASH, 10_000_000);
        SymbolEngine restarted = TestSupport.engine("AAA", props, ledger2);
        OrderResponse replay = restarted.newOrder(TestSupport.market(
                "req-2", "buy1", "AAA", "buyer", Side.BUY, 50, 5_000L));
        assertEquals(1, replay.fills().size());
        assertEquals(50, replay.fills().get(0).quantity());
        // no extra trade happened: seller still has 250 frozen
        assertEquals(250, ledger2.frozen("s1", "AAA"));
        restarted.close();
    }

    @Test
    void tornTailIsIgnoredButCommittedRecordsReplay() throws Exception {
        MatchingProperties props = TestSupport.props(dir);
        BalanceLedger ledger = new BalanceLedger();
        ledger.credit("s1", "AAA", 10_000);
        ledger.credit("buyer", BalanceLedger.CASH, 10_000_000);
        SymbolEngine engine = TestSupport.engine("AAA", props, ledger);
        engine.newOrder(TestSupport.limit("req-1", "ice1", "AAA", "s1",
                Side.SELL, 100, 100, 100L));
        engine.close();

        // append garbage (simulates a torn partial write at crash)
        var wal = dir.resolve("AAA.wal");
        java.nio.file.Files.write(wal,
                new byte[]{1, 2, 3, 4, 5, 6, 7},
                java.nio.file.StandardOpenOption.APPEND);

        BalanceLedger ledger2 = new BalanceLedger();
        SymbolEngine restarted = TestSupport.engine("AAA", props, ledger2);
        assertEquals(100, restarted.snapshot().asks().get(0).visibleQty());
        assertTrue(ledger2.frozen("s1", "AAA") >= 100);
        restarted.close();
    }
}
