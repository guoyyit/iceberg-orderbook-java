package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.model.ErrorCode;
import com.github.guoyyit.icebergorderbookjava.model.MatchingException;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationTest {

    // min visible 5, granularity 5, max 1000
    private SymbolEngine engine(Path dir) {
        SymbolEngine e = new SymbolEngine("BTC-USD",
                new BookConfig(5, 5, 1000L), dir.toString());
        e.recover();
        return e;
    }

    private PlaceRequest req(String rid, String oid, Side side,
                             OrderType type, Long price, Long qty, Long peak) {
        return new PlaceRequest(rid, oid, "BTC-USD", "o", side,
                type, price, qty, peak);
    }

    private MatchingException expect(Runnable r) {
        return assertThrows(MatchingException.class, r::run);
    }

    @Test
    void rejectsOutOfBoundsAndIllegalOrdersWithoutWriting(@TempDir Path dir) {
        SymbolEngine e = engine(dir);

        assertEquals(ErrorCode.INVALID_QTY, expect(() ->
                e.place(req("r1", "A", Side.BUY, OrderType.LIMIT, 100L, 0L, null)))
                .getErrorCode());
        assertEquals(ErrorCode.INVALID_QTY, expect(() ->
                e.place(req("r2", "A", Side.BUY, OrderType.LIMIT, 100L, -5L, null)))
                .getErrorCode());
        assertEquals(ErrorCode.INVALID_PRICE, expect(() ->
                e.place(req("r3", "A", Side.BUY, OrderType.LIMIT, null, 10L, null)))
                .getErrorCode());
        assertEquals(ErrorCode.INVALID_PRICE, expect(() ->
                e.place(req("r4", "A", Side.BUY, OrderType.LIMIT, 0L, 10L, null)))
                .getErrorCode());
        assertEquals(ErrorCode.INVALID_SIDE, expect(() ->
                e.place(new PlaceRequest("r5", "A", "BTC-USD", "o",
                        null, OrderType.LIMIT, 100L, 10L, null))).getErrorCode());
        assertEquals(ErrorCode.INVALID_ORDER_TYPE, expect(() ->
                e.place(new PlaceRequest("r6", "A", "BTC-USD", "o",
                        Side.BUY, null, 100L, 10L, null))).getErrorCode());

        assertEquals(ErrorCode.PEAK_BELOW_MIN_VISIBLE, expect(() ->
                e.place(req("r7", "A", Side.SELL, OrderType.LIMIT, 100L, 100L, 4L)))
                .getErrorCode());
        assertEquals(ErrorCode.PEAK_GRANULARITY, expect(() ->
                e.place(req("r8", "A", Side.SELL, OrderType.LIMIT, 100L, 100L, 7L)))
                .getErrorCode());
        assertEquals(ErrorCode.INVALID_PEAK_SIZE, expect(() ->
                e.place(req("r9", "A", Side.SELL, OrderType.LIMIT, 100L, 10L, 20L)))
                .getErrorCode());
        assertEquals(ErrorCode.QTY_EXCEEDS_MAX, expect(() ->
                e.place(req("r10", "A", Side.SELL, OrderType.LIMIT, 100L, 1001L, null)))
                .getErrorCode());
        assertEquals(ErrorCode.INVALID_PEAK_SIZE, expect(() ->
                e.place(req("r11", "A", Side.BUY, OrderType.MARKET, null, 10L, 5L)))
                .getErrorCode());
        assertEquals(ErrorCode.INVALID_PRICE, expect(() ->
                e.place(req("r12", "A", Side.BUY, OrderType.MARKET, 99L, 10L, null)))
                .getErrorCode());

        // nothing rejected ever reached the book
        assertEquals(0, e.depth(10).bids().size() + e.depth(10).asks().size());
        e.close();
    }

    @Test
    void rejectsDuplicateOrderId(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(req("r1", "A", Side.SELL, OrderType.LIMIT, 100L, 10L, null));
        assertEquals(ErrorCode.ORDER_ALREADY_EXISTS, expect(() ->
                e.place(req("different-request", "A", Side.SELL,
                        OrderType.LIMIT, 100L, 10L, null))).getErrorCode());
        e.close();
    }

    @Test
    void unknownSymbolRejectedByManager(@TempDir Path dir) {
        var props = new com.github.guoyyit.icebergorderbookjava.config.BookProperties();
        props.setWalDir(dir.toString());
        var mgr = new com.github.guoyyit.icebergorderbookjava.engine.EngineManager(props);
        MatchingException ex = assertThrows(MatchingException.class,
                () -> mgr.depth("DOES-NOT-EXIST", 10));
        assertEquals(ErrorCode.UNKNOWN_SYMBOL, ex.getErrorCode());
        mgr.shutdown();
    }
}
