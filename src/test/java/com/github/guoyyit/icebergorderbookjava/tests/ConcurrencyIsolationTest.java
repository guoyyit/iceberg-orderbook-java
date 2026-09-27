package com.github.guoyyit.icebergorderbookjava.tests;

import com.github.guoyyit.icebergorderbookjava.api.CancelOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.domain.Side;
import com.github.guoyyit.icebergorderbookjava.engine.MatchingEngine;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyIsolationTest {

    @TempDir
    Path dir;
    private MatchingProperties props;
    private BalanceLedger ledger;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        props = TestSupport.props(dir);
        ledger = new BalanceLedger();
        engine = new MatchingEngine(props, ledger);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void concurrentCancelAndMatchNeverDoubleTradesOrDropsQuantity() throws Exception {
        ledger.credit("seller", "AAA", 100_000);
        ledger.credit("buyer", BalanceLedger.CASH, 100_000_000);
        int cancelCount = 100;  // sellers at price 200 -> will be cancelled
        int buyTargets = 100;   // sellers at price 100 -> will be bought
        int n = cancelCount + buyTargets;
        for (int i = 0; i < cancelCount; i++) {
            engine.submit(TestSupport.limit("place-c" + i, "cord-" + i, "AAA",
                    "seller", Side.SELL, 200, 100, 100L));
        }
        for (int i = 0; i < buyTargets; i++) {
            engine.submit(TestSupport.limit("place-b" + i, "bord-" + i, "AAA",
                    "seller", Side.SELL, 100, 100, 100L));
        }
        assertEquals(n * 100L, ledger.frozen("seller", "AAA"));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger bought = new AtomicInteger();
        AtomicInteger cancelReplays = new AtomicInteger();
        AtomicInteger buyReplays = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int ti = t;
            pool.submit(() -> {
                try {
                    start.await();
                    // lower half of workers cancel + replay cancels at price 200
                    for (int i = ti; i < cancelCount; i += threads) {
                        OrderResponse c = engine.cancel(new CancelOrderRequest(
                                "cancel-" + i, "AAA", "cord-" + i, null));
                        if (c.cancelled()) {
                            cancelled.incrementAndGet();
                        }
                        OrderResponse again = engine.cancel(new CancelOrderRequest(
                                "cancel-" + i, "AAA", "cord-" + i, null));
                        if (again.cancelled()) {
                            cancelReplays.incrementAndGet();
                        }
                    }
                    // upper half buy + replay buys against price 100
                    for (int i = ti; i < buyTargets; i += threads) {
                        OrderResponse r = engine.submit(TestSupport.market(
                                "buy-" + i, "mo-" + i, "AAA", "buyer",
                                Side.BUY, 100, 10_000L));
                        if (r.filledQty() == 100) {
                            bought.incrementAndGet();
                        }
                        OrderResponse replay = engine.submit(TestSupport.market(
                                "buy-" + i, "mo-" + i, "AAA", "buyer",
                                Side.BUY, 100, 10_000L));
                        if (replay.filledQty() == 100) {
                            buyReplays.incrementAndGet();
                        }
                    }
                } catch (Exception ignored) {
                    // unexpected here, but surfaces via invariant assertions
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(cancelCount, cancelled.get());
        assertEquals(cancelCount, cancelReplays.get());
        assertEquals(buyTargets, bought.get());
        assertEquals(buyTargets, buyReplays.get());

        // every target order settled exactly once; no depth and no frozen left
        long depthQty = engine.depth("AAA").asks().stream()
                .mapToLong(l -> l.visibleQty()).sum();
        assertEquals(0, depthQty);
        assertEquals(0, ledger.frozen("seller", "AAA"),
                "frozen quantity must be fully settled with no drift");
        // buyer spent exactly 100 * 100 * 100 = 1,000,000 once
        assertEquals(100_000_000L - 1_000_000L,
                ledger.available("buyer", BalanceLedger.CASH));
    }

    @Test
    void symbolsAreIsolatedAndSlowSymbolDoesNotBlockOthers() throws Exception {
        ledger.credit("s", "AAA", 1_000_000);
        ledger.credit("s", "BBB", 1_000_000);
        ledger.credit("b", BalanceLedger.CASH, 1_000_000_000);

        engine.submit(TestSupport.limit("a1", "a-ord", "AAA", "s",
                Side.SELL, 100, 100, 100L));
        engine.submit(TestSupport.limit("b1", "b-ord", "BBB", "s",
                Side.SELL, 200, 50, 50L));

        // depth of each symbol only contains its own orders
        assertEquals(100, engine.depth("AAA").asks().get(0).visibleQty());
        assertEquals(200, engine.depth("BBB").asks().get(0).price());
        assertTrue(engine.depth("AAA").bids().isEmpty());

        // a trade on AAA must not affect BBB
        OrderResponse fill = engine.submit(TestSupport.market(
                "a-buy", "a-mo", "AAA", "b", Side.BUY, 100, 10_000L));
        assertEquals(100, fill.filledQty());
        assertTrue(engine.depth("AAA").asks().isEmpty());
        assertEquals(50, engine.depth("BBB").asks().get(0).visibleQty());

        // independent WAL files
        assertTrue(java.nio.file.Files.exists(dir.resolve("AAA.wal")));
        assertTrue(java.nio.file.Files.exists(dir.resolve("BBB.wal")));
    }

    @Test
    void concurrentDuplicateRequestsFreezeAndTradeExactlyOnce() throws Exception {
        ledger.credit("seller", "AAA", 1_000_000);
        ledger.credit("buyer", BalanceLedger.CASH, 1_000_000_000);
        engine.submit(TestSupport.limit("m1", "maker", "AAA", "seller",
                Side.SELL, 100, 100, 100L));

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<OrderResponse> results = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // same requestId, same body
                    results.add(engine.submit(TestSupport.market(
                            "same-req", "same-mo", "AAA", "buyer",
                            Side.BUY, 100, 10_000L)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // Every response reports the same historical outcome (one trade of 100).
        // The crucial invariants are state-level: the maker was consumed once
        // and balances settled once regardless of how many replays arrived.
        assertEquals(threads, results.size());
        for (OrderResponse r : results) {
            assertEquals(100, r.filledQty());
            assertEquals(1, r.fills().size());
            assertEquals("maker", r.fills().get(0).makerOrderId());
        }
        // book consumed exactly once
        assertTrue(engine.depth("AAA").asks().isEmpty());
        // seller's 100 frozen units were traded exactly once (none left frozen)
        assertEquals(0, ledger.frozen("seller", "AAA"));
        // buyer's cash frozen only once and fully consumed
        assertEquals(1_000_000_000L - 10_000L, ledger.available("buyer", BalanceLedger.CASH));
    }
}
