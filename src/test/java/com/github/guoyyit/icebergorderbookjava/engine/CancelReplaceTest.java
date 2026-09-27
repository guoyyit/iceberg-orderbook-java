package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.dto.CancelRequest;
import com.github.guoyyit.icebergorderbookjava.dto.CancelResult;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceResult;
import com.github.guoyyit.icebergorderbookjava.model.ErrorCode;
import com.github.guoyyit.icebergorderbookjava.model.MatchingException;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelReplaceTest {

    private SymbolEngine engine(Path dir) {
        SymbolEngine e = new SymbolEngine("BTC-USD",
                new BookConfig(1, 5, 1_000_000_000L), dir.toString());
        e.recover();
        return e;
    }

    private PlaceRequest limit(String rid, String oid, Side side,
                               long price, long qty, Long peak) {
        return new PlaceRequest(rid, oid, "BTC-USD", "owner", side,
                OrderType.LIMIT, price, qty, peak);
    }

    @Test
    void cancelImmediatelyReleasesFrozenAndDepth(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("p", "A", Side.SELL, 100L, 100L, 10L));
        assertEquals(10L, e.depth(10).frozenSellQty());

        CancelResult c = e.cancel(new CancelRequest("x", "A", "BTC-USD", null));
        assertEquals(10L, c.releasedQty());
        assertTrue(e.depth(10).asks().isEmpty());
        assertEquals(0L, e.depth(10).frozenSellQty());

        // cancelling again / operating on dead order rejected
        MatchingException ex = assertThrows(MatchingException.class,
                () -> e.cancel(new CancelRequest("x2", "A", "BTC-USD", null)));
        assertEquals(ErrorCode.ORDER_NOT_ACTIVE, ex.getErrorCode());
        e.close();
    }

    @Test
    void keepPriorityAmendShrinksAndKeepsPosition(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        e.place(limit("b", "B", Side.SELL, 100L, 5L, null));

        // KEEP reduce total to 50; position kept, visible peak unchanged
        ReplaceResult r = e.replace(new ReplaceRequest("am", "A", "BTC-USD",
                null, 50L, null, "KEEP", null));
        assertEquals("AMENDED_KEEP_PRIORITY", r.status());
        assertEquals(2L, Views.revision(e, "A"));

        // A still head-of-queue: buy 10 hits A not B
        var pr = e.place(limit("t", "T", Side.BUY, 100L, 10L, null));
        assertEquals("A", pr.fills().get(0).makerOrderId());
        e.close();
    }

    @Test
    void keepPriorityRejectsPricePeakAndIncrease(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));

        assertEquals(ErrorCode.INVALID_REPLACE, assertThrows(
                MatchingException.class,
                () -> e.replace(new ReplaceRequest("r1", "A", "BTC-USD",
                        99L, null, null, "KEEP", null))).getErrorCode());
        assertEquals(ErrorCode.INVALID_REPLACE, assertThrows(
                MatchingException.class,
                () -> e.replace(new ReplaceRequest("r2", "A", "BTC-USD",
                        null, null, 5L, "KEEP", null))).getErrorCode());
        assertEquals(ErrorCode.INVALID_REPLACE, assertThrows(
                MatchingException.class,
                () -> e.replace(new ReplaceRequest("r3", "A", "BTC-USD",
                        null, 120L, null, "KEEP", null))).getErrorCode());
        // nothing was written: still one level with 10 visible, revision 1
        assertEquals(10L, e.depth(10).asks().get(0).visibleQty());
        assertEquals(1L, Views.revision(e, "A"));
        e.close();
    }

    @Test
    void losePriorityAmendReentersAtTail(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        e.place(limit("b", "B", Side.SELL, 100L, 5L, null));

        e.replace(new ReplaceRequest("am", "A", "BTC-USD",
                null, 100L, 10L, "LOSE", null));
        // A re-entered behind B
        var pr = e.place(limit("t", "T", Side.BUY, 100L, 5L, null));
        assertEquals("B", pr.fills().get(0).makerOrderId());
        e.close();
    }

    @Test
    void losePriorityCanChangePriceAndPeak(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        ReplaceResult r = e.replace(new ReplaceRequest("am", "A", "BTC-USD",
                105L, 80L, 15L, "LOSE", null));
        assertEquals("AMENDED_LOST_PRIORITY", r.status());
        assertEquals(15L, Views.visible(e, "A"));
        assertEquals(15L, e.depth(10).asks().get(0).visibleQty());
        assertEquals(105L, e.depth(10).asks().get(0).price());
        e.close();
    }

    @Test
    void crossingAmendIsRejected(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        e.place(limit("bid", "BID", Side.BUY, 99L, 10L, null));
        // lowering the sell to <= best bid would cross -> rejected, no write
        MatchingException ex = assertThrows(MatchingException.class,
                () -> e.replace(new ReplaceRequest("am", "A", "BTC-USD",
                        99L, null, null, "LOSE", null)));
        assertEquals(ErrorCode.PRICE_CROSSES_MARKET, ex.getErrorCode());
        assertEquals(100L, e.depth(10).asks().get(0).price());
        e.close();
    }

    @Test
    void staleRevisionRejected(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        e.replace(new ReplaceRequest("am1", "A", "BTC-USD",
                null, 90L, null, "KEEP", null)); // revision -> 2
        MatchingException ex = assertThrows(MatchingException.class,
                () -> e.cancel(new CancelRequest("old", "A", "BTC-USD", 1L)));
        assertEquals(ErrorCode.REVISION_CONFLICT, ex.getErrorCode());
        // current revision works
        e.cancel(new CancelRequest("cur", "A", "BTC-USD", 2L));
        assertTrue(e.depth(10).asks().isEmpty());
        e.close();
    }
}
