package com.github.guoyyit.icebergorderbookjava.tests;

import com.github.guoyyit.icebergorderbookjava.api.NewOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.api.RejectedRequestException;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.domain.OrderType;
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

class ValidationTest {

    @TempDir
    Path dir;
    private MatchingProperties props;
    private BalanceLedger ledger;
    private SymbolEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        props = TestSupport.props(dir);
        props.setMinPeakSize(10L);
        props.setMaxPeakSize(500L);
        ledger = new BalanceLedger();
        ledger.credit("s", "AAA", 100_000);
        ledger.credit("b", BalanceLedger.CASH, 100_000_000);
        engine = TestSupport.engine("AAA", props, ledger);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.close();
    }

    private RejectedRequestException submitExpectReject(NewOrderRequest r) {
        return assertThrows(RejectedRequestException.class, () -> engine.newOrder(r));
    }

    @Test
    void rejectsInvalidQuantity() {
        assertEquals("INVALID_QTY", submitExpectReject(
                TestSupport.limit("r", "o", "AAA", "s", Side.SELL, 100, 0, null)).getCode().name());
    }

    @Test
    void rejectsInvalidPrice() {
        assertEquals("INVALID_PRICE", submitExpectReject(
                TestSupport.limit("r", "o", "AAA", "s", Side.SELL, 0, 10, null)).getCode().name());
    }

    @Test
    void rejectsMarketWithPrice() {
        assertEquals("MARKET_PRICE_NOT_ALLOWED", submitExpectReject(
                new NewOrderRequest("r", "o", "AAA", "b", Side.BUY,
                        OrderType.MARKET, 100L, 10, null, 1000L)).getCode().name());
    }

    @Test
    void rejectsMarketBuyWithoutCashCap() {
        assertEquals("MARKET_CASH_REQUIRED", submitExpectReject(
                TestSupport.market("r", "o", "AAA", "b", Side.BUY, 10, null)).getCode().name());
    }

    @Test
    void rejectsPeakBelowMinimumAndAboveMaximum() {
        assertEquals("PEAK_BELOW_MIN", submitExpectReject(
                TestSupport.limit("r1", "o1", "AAA", "s", Side.SELL, 100, 100, 5L)).getCode().name());
        assertEquals("PEAK_ABOVE_MAX", submitExpectReject(
                TestSupport.limit("r2", "o2", "AAA", "s", Side.SELL, 100, 100, 600L)).getCode().name());
    }

    @Test
    void rejectedOrderIsNeverWrittenToBookAndRejectionIsIdempotent() {
        RejectedRequestException ex = submitExpectReject(
                TestSupport.limit("dup", "o", "AAA", "s", Side.SELL, 100, -5, null));
        assertEquals("INVALID_QTY", ex.getCode().name());
        assertTrue(engine.snapshot().asks().isEmpty());
        // replaying the same rejected request id returns the stored rejection
        // result (idempotent) without touching the book or balances
        OrderResponse again = engine.newOrder(
                TestSupport.limit("dup", "o", "AAA", "s", Side.SELL, 100, -5, null));
        assertTrue(again.rejected());
        assertEquals("INVALID_QTY", again.rejectCode());
        assertTrue(engine.snapshot().asks().isEmpty());
        assertEquals(100_000, ledger.available("s", "AAA")); // nothing frozen
    }

    @Test
    void insufficientBalanceRejected() {
        RejectedRequestException ex = submitExpectReject(TestSupport.limit(
                "r", "o", "AAA", "poor", Side.SELL, 100, 10, 10L));
        assertEquals("INSUFFICIENT_BALANCE", ex.getCode().name());
        assertTrue(engine.snapshot().asks().isEmpty());
    }
}
