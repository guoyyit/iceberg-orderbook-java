package com.github.guoyyit.icebergorderbookjava.tests;

import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.api.NewOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.domain.Side;
import com.github.guoyyit.icebergorderbookjava.engine.SymbolEngine;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import com.github.guoyyit.icebergorderbookjava.wal.WalEvent;
import com.github.guoyyit.icebergorderbookjava.wal.WalReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Recovery must be deterministic: replaying the same WAL twice onto fresh
 * state yields identical depth, identical fills, and an identical stream of
 * follow-up trade sequences.
 */
class DeterministicReplayTest {

    @TempDir
    Path dir;

    private List<NewOrderRequest> script() {
        List<NewOrderRequest> reqs = new ArrayList<>();
        reqs.add(TestSupport.limit("r1", "s-ice", "AAA", "seller", Side.SELL, 100, 500, 100L));
        reqs.add(TestSupport.limit("r2", "s-a", "AAA", "sellerA", Side.SELL, 99, 100, 100L));
        reqs.add(TestSupport.limit("r3", "b-limit", "AAA", "buyerL", Side.BUY, 98, 100, 100L));
        reqs.add(TestSupport.market("r4", "m1", "AAA", "buyerM", Side.BUY, 200, 20_000L));
        reqs.add(TestSupport.market("r5", "m2", "AAA", "buyerM2", Side.BUY, 200, 20_000L));
        return reqs;
    }

    private void seedBalances(BalanceLedger l) {
        l.credit("seller", "AAA", 1_000_000);
        l.credit("sellerA", "AAA", 1_000_000);
        l.credit("buyerM", BalanceLedger.CASH, 1_000_000_000L);
        l.credit("buyerM2", BalanceLedger.CASH, 1_000_000_000L);
        l.credit("buyerL", BalanceLedger.CASH, 1_000_000_000L);
    }

    @Test
    void replayProducesIdenticalStateAndTradeSequence() throws Exception {
        MatchingProperties p1 = TestSupport.props(dir.resolve("run1"));
        BalanceLedger l1 = new BalanceLedger();
        seedBalances(l1);
        SymbolEngine e1 = TestSupport.engine("AAA", p1, l1);
        for (NewOrderRequest r : script()) {
            e1.newOrder(r);
        }
        DepthSnapshot snap1 = e1.snapshot();
        long lastTrade1 = snap1.lastTradeSeq();
        e1.close();

        // replay the WAL twice onto completely fresh state
        MatchingProperties pA = TestSupport.props(dir.resolve("replayA"));
        Files.createDirectories(Path.of(pA.getWalDir()));
        Files.copy(dir.resolve("run1").resolve("AAA.wal"),
                Path.of(pA.getWalDir()).resolve("AAA.wal"),
                StandardCopyOption.REPLACE_EXISTING);
        BalanceLedger la = new BalanceLedger();
        SymbolEngine ea = TestSupport.engine("AAA", pA, la);

        MatchingProperties pB = TestSupport.props(dir.resolve("replayB"));
        Files.createDirectories(Path.of(pB.getWalDir()));
        Files.copy(dir.resolve("run1").resolve("AAA.wal"),
                Path.of(pB.getWalDir()).resolve("AAA.wal"),
                StandardCopyOption.REPLACE_EXISTING);
        BalanceLedger lb = new BalanceLedger();
        SymbolEngine eb = TestSupport.engine("AAA", pB, lb);

        DepthSnapshot snapA = ea.snapshot();
        DepthSnapshot snapB = eb.snapshot();

        assertEquals(snap1.asks(), snapA.asks());
        assertEquals(snap1.bids(), snapA.bids());
        assertEquals(snapA.asks(), snapB.asks());
        assertEquals(snapA.bids(), snapB.bids());
        assertEquals(lastTrade1, snapA.lastTradeSeq());
        assertEquals(lastTrade1, snapB.lastTradeSeq());

        // frozen state identical across the two replays
        assertEquals(la.snapshot(), lb.snapshot());

        // a brand new aggressor after replay gets the same maker/seq on both
        seedBalances(la);
        seedBalances(lb);
        OrderResponse fa = ea.newOrder(TestSupport.market(
                "after", "mo-after", "AAA", "buyerM", Side.BUY, 100, 10_000L));
        OrderResponse fb = eb.newOrder(TestSupport.market(
                "after", "mo-after", "AAA", "buyerM", Side.BUY, 100, 10_000L));
        assertEquals(fa.fills().size(), fb.fills().size());
        assertEquals(fa.fills().get(0).makerOrderId(), fb.fills().get(0).makerOrderId());
        assertEquals(fa.fills().get(0).tradeSeq(), fb.fills().get(0).tradeSeq());
        ea.close();
        eb.close();
    }

    @Test
    void recoveredIdempotentOutcomesMatchOriginalFills() throws Exception {
        MatchingProperties p1 = TestSupport.props(dir.resolve("run1"));
        BalanceLedger l1 = new BalanceLedger();
        seedBalances(l1);
        SymbolEngine e1 = TestSupport.engine("AAA", p1, l1);
        List<OrderResponse> original = new ArrayList<>();
        for (NewOrderRequest r : script()) {
            original.add(e1.newOrder(r));
        }
        e1.close();

        MatchingProperties p2 = TestSupport.props(dir.resolve("run2"));
        Files.createDirectories(Path.of(p2.getWalDir()));
        Files.copy(dir.resolve("run1").resolve("AAA.wal"),
                Path.of(p2.getWalDir()).resolve("AAA.wal"),
                StandardCopyOption.REPLACE_EXISTING);
        BalanceLedger l2 = new BalanceLedger();
        SymbolEngine e2 = TestSupport.engine("AAA", p2, l2);

        for (int i = 0; i < script().size(); i++) {
            NewOrderRequest r = script().get(i);
            OrderResponse replayed = e2.newOrder(r); // same requestId -> stored outcome
            OrderResponse orig = original.get(i);
            assertEquals(orig.filledQty(), replayed.filledQty());
            assertEquals(orig.fills().size(), replayed.fills().size());
            for (int k = 0; k < orig.fills().size(); k++) {
                assertEquals(orig.fills().get(k).quantity(),
                        replayed.fills().get(k).quantity());
                assertEquals(orig.fills().get(k).makerOrderId(),
                        replayed.fills().get(k).makerOrderId());
            }
        }
        e2.close();
    }
}
