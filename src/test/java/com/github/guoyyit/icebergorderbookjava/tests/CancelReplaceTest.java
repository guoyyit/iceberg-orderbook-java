package com.github.guoyyit.icebergorderbookjava.tests;

import com.github.guoyyit.icebergorderbookjava.api.CancelOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.api.RejectedRequestException;
import com.github.guoyyit.icebergorderbookjava.api.ReplaceOrderRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelReplaceTest {

    @TempDir
    Path dir;
    private MatchingProperties props;
    private BalanceLedger ledger;
    private SymbolEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        props = TestSupport.props(dir);
        ledger = new BalanceLedger();
        ledger.credit("s1", "AAA", 10_000);
        ledger.credit("s2", "AAA", 10_000);
        ledger.credit("b", BalanceLedger.CASH, 10_000_000);
        engine = TestSupport.engine("AAA", props, ledger);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.close();
    }

    @Test
    void cancelImmediatelyReleasesFrozenQuantity() {
        engine.newOrder(TestSupport.limit("r1", "o1", "AAA", "s1",
                Side.SELL, 100, 300, 300L));
        assertEquals(300, ledger.frozen("s1", "AAA"));
        assertEquals(10_000 - 300, ledger.available("s1", "AAA"));

        OrderResponse c = engine.cancel(new CancelOrderRequest("r2", "AAA", "o1", null));
        assertTrue(c.cancelled());
        assertEquals(0, ledger.frozen("s1", "AAA"));
        assertEquals(10_000, ledger.available("s1", "AAA"));
        assertTrue(engine.snapshot().asks().isEmpty());
    }

    @Test
    void cancelSameOrderTwiceSecondRejectedUnknownOrDead() {
        engine.newOrder(TestSupport.limit("r1", "o1", "AAA", "s1",
                Side.SELL, 100, 300, 300L));
        engine.cancel(new CancelOrderRequest("r2", "AAA", "o1", null));
        RejectedRequestException ex = assertThrows(RejectedRequestException.class,
                () -> engine.cancel(new CancelOrderRequest("r3", "AAA", "o1", null)));
        assertEquals("UNKNOWN_ORDER", ex.getCode().name());
    }

    @Test
    void replaceKeepPriorityStaysAheadAtSamePrice() {
        // two sellers at 100: o1 first, o2 second
        engine.newOrder(TestSupport.limit("r1", "o1", "AAA", "s1",
                Side.SELL, 100, 100, 100L));
        engine.newOrder(TestSupport.limit("r2", "o2", "AAA", "s2",
                Side.SELL, 100, 100, 100L));

        // modify o1 keeping priority (qty stays 100)
        OrderResponse rep = engine.replace(new ReplaceOrderRequest(
                "r3", "AAA", "o1", null, null, 100L, 100L, true));
        assertEquals(2, rep.orderVersion());

        // aggressor buys 100 -> must hit o1 (kept its time priority)
        OrderResponse buy = engine.newOrder(TestSupport.limit(
                "r4", "buy", "AAA", "b", Side.BUY, 100, 100, 100L));
        assertEquals("o1", buy.fills().get(0).makerOrderId());
    }

    @Test
    void replaceLosePriorityGoesToBackOfQueue() {
        engine.newOrder(TestSupport.limit("r1", "o1", "AAA", "s1",
                Side.SELL, 100, 100, 100L));
        engine.newOrder(TestSupport.limit("r2", "o2", "AAA", "s2",
                Side.SELL, 100, 100, 100L));

        OrderResponse rep = engine.replace(new ReplaceOrderRequest(
                "r3", "AAA", "o1", null, null, 100L, 100L, false));
        assertEquals(2, rep.orderVersion());

        OrderResponse buy = engine.newOrder(TestSupport.limit(
                "r4", "buy", "AAA", "b", Side.BUY, 100, 100, 100L));
        assertEquals("o2", buy.fills().get(0).makerOrderId());
    }

    @Test
    void staleExpectedVersionIsRejectedAndDoesNotMutate() {
        engine.newOrder(TestSupport.limit("r1", "o1", "AAA", "s1",
                Side.SELL, 100, 100, 100L));
        engine.replace(new ReplaceOrderRequest("r2", "AAA", "o1",
                1L, null, 200L, 100L, false)); // version becomes 2

        RejectedRequestException ex = assertThrows(RejectedRequestException.class,
                () -> engine.replace(new ReplaceOrderRequest(
                        "r3", "AAA", "o1", 1L, null, 300L, 100L, false)));
        assertEquals("VERSION_CONFLICT", ex.getCode().name());

        // book depth must still reflect the accepted replace: total 200 with a
        // peak of 100 => visible depth is exactly 100 (the hidden 100 never
        // leaks), and the full 200 remains tradable.
        assertEquals(100, engine.snapshot().asks().get(0).visibleQty());
        OrderResponse buyAll = engine.newOrder(TestSupport.market(
                "r4", "mo", "AAA", "b", Side.BUY, 200, 20_000L));
        assertEquals(200, buyAll.filledQty());
    }

    @Test
    void keepPriorityWithPriceChangeIsRejected() {
        engine.newOrder(TestSupport.limit("r1", "o1", "AAA", "s1",
                Side.SELL, 100, 100, 100L));
        RejectedRequestException ex = assertThrows(RejectedRequestException.class,
                () -> engine.replace(new ReplaceOrderRequest(
                        "r2", "AAA", "o1", 1L, 99L, 100L, 100L, true)));
        assertEquals("KEEP_PRIORITY_PRICE_CHANGE", ex.getCode().name());
    }

    @Test
    void duplicateCancelRequestIsIdempotent() {
        engine.newOrder(TestSupport.limit("r1", "o1", "AAA", "s1",
                Side.SELL, 100, 100, 100L));
        OrderResponse first = engine.cancel(new CancelOrderRequest("dup", "AAA", "o1", null));
        OrderResponse again = engine.cancel(new CancelOrderRequest("dup", "AAA", "o1", null));
        assertEquals(first.cancelled(), again.cancelled());
        assertEquals(10_000, ledger.available("s1", "AAA"));
        assertEquals(0, ledger.frozen("s1", "AAA"));
    }
}
