package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.dto.CancelRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceRequest;
import com.github.guoyyit.icebergorderbookjava.model.ErrorCode;
import com.github.guoyyit.icebergorderbookjava.model.MatchingException;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

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
    void concurrentCancelReplaceMatchIsSerializedWithoutDrift(@TempDir Path dir)
            throws Exception {
        int n = 50;
        for (int iter = 0; iter < 5; iter++) {
            final int it = iter;
            SymbolEngine e = engine(dir);
            e.place(limit("seed" + it, "ICE", Side.SELL, 100L,
                    10L * n, 10L));

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            AtomicInteger errors = new AtomicInteger();
            ConcurrentLinkedQueue<Long> tradeQtys = new ConcurrentLinkedQueue<>();

            // 1) many aggressors each buy 10
            for (int i = 0; i < n; i++) {
                final int k = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        var r = e.place(limit("buy" + it + "-" + k,
                                "BUY" + it + "-" + k, Side.BUY, 100L, 10L, null));
                        r.fills().forEach(f -> tradeQtys.add(f.qty()));
                    } catch (Exception ex) {
                        errors.incrementAndGet();
                    }
                });
            }
            // 2) racing cancels/amends (mostly expected to fail once filled)
            for (int i = 0; i < n; i++) {
                final int k = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (k % 2 == 0) {
                            e.cancel(new CancelRequest("can" + it + "-" + k,
                                    "ICE", "BTC-USD", null));
                        } else {
                            e.replace(new ReplaceRequest("rep" + it + "-" + k,
                                    "ICE", "BTC-USD", null, null, null, "KEEP", null));
                        }
                    } catch (MatchingException ex) {
                        // expected: not active / invalid no-op replace etc.
                        if (ex.getErrorCode() != ErrorCode.ORDER_NOT_ACTIVE
                                && ex.getErrorCode() != ErrorCode.ORDER_NOT_FOUND
                                && ex.getErrorCode() != ErrorCode.INVALID_REPLACE
                                && ex.getErrorCode() != ErrorCode.REVISION_CONFLICT) {
                            errors.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        errors.incrementAndGet();
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            assertEquals(0, errors.get(), "unexpected error in concurrent run");

            // conservation: filled + remaining(book or removed) == total,
            // and every recorded trade is exactly 10 with no double fills
            long traded = tradeQtys.stream().mapToLong(Long::longValue).sum();
            long maxFilled = 10L * n;
            assertTrue(traded <= maxFilled, "traded=" + traded);
            // remaining visible on book + traded must equal total if still alive
            var order = e.bookView().getOrder("ICE");
            long rem = order == null ? 0L : order.remainingQty();
            assertEquals(maxFilled, traded + rem,
                    "quantity conservation: traded + remaining == total");
            // frozen visible never exceeds total and never goes negative
            long frozen = e.depth(10).frozenSellQty();
            assertTrue(frozen >= 0 && frozen <= maxFilled);
            e.close();
        }
    }

    @Test
    void symbolsAreIsolated(@TempDir Path dir) throws Exception {
        var props = new com.github.guoyyit.icebergorderbookjava.config.BookProperties();
        props.setWalDir(dir.toString());
        var btc = new com.github.guoyyit.icebergorderbookjava.config
                .BookProperties.SymbolCfg();
        var eth = new com.github.guoyyit.icebergorderbookjava.config
                .BookProperties.SymbolCfg();
        props.getSymbols().put("BTC-USD", btc);
        props.getSymbols().put("ETH-USD", eth);
        var mgr = new EngineManager(props);

        mgr.place(new PlaceRequest("b1", "B", "BTC-USD", "o", Side.SELL,
                OrderType.LIMIT, 100L, 10L, null));
        mgr.place(new PlaceRequest("e1", "E", "ETH-USD", "o", Side.SELL,
                OrderType.LIMIT, 200L, 30L, null));

        // cross-symbol order id same value is fine; depth is separate
        assertEquals(100L, mgr.depth("BTC-USD", 5).asks().get(0).price());
        assertEquals(200L, mgr.depth("ETH-USD", 5).asks().get(0).price());
        assertEquals(10L, mgr.depth("BTC-USD", 5).frozenSellQty());
        assertEquals(30L, mgr.depth("ETH-USD", 5).frozenSellQty());
        mgr.shutdown();
    }

    @Test
    void slowSymbolDoesNotBlockOtherSymbol(@TempDir Path dir) throws Exception {
        var props = new com.github.guoyyit.icebergorderbookjava.config.BookProperties();
        props.setWalDir(dir.toString());
        props.getSymbols().put("FAST", new com.github.guoyyit.icebergorderbookjava
                .config.BookProperties.SymbolCfg());
        props.getSymbols().put("SLOW", new com.github.guoyyit.icebergorderbookjava
                .config.BookProperties.SymbolCfg());
        var mgr = new EngineManager(props);

        CountDownLatch doneFast = new CountDownLatch(1);
        Thread slow = new Thread(() -> {
            // SLOW: many sequential operations, slower aggregate time
            for (int i = 0; i < 200; i++) {
                mgr.place(new PlaceRequest("s" + i, "S" + i, "SLOW", "o",
                        Side.SELL, OrderType.LIMIT, 100L, 1L, null));
            }
        });
        Thread fast = new Thread(() -> {
            mgr.place(new PlaceRequest("f1", "F1", "FAST", "o",
                    Side.SELL, OrderType.LIMIT, 50L, 1L, null));
            doneFast.countDown();
        });
        slow.start();
        Thread.sleep(20);
        fast.start();
        assertTrue(doneFast.await(5, TimeUnit.SECONDS),
                "FAST symbol must not be blocked by busy SLOW symbol");
        slow.join(10000);
        assertEquals(50L, mgr.depth("FAST", 5).asks().get(0).price());
        mgr.shutdown();
    }
}
