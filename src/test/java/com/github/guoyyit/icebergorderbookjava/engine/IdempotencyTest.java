package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.dto.CancelRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceResult;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceRequest;
import com.github.guoyyit.icebergorderbookjava.model.ErrorCode;
import com.github.guoyyit.icebergorderbookjava.model.MatchingException;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyTest {

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
    void duplicatePlaceDoesNotDoubleExecuteOrDoubleFreeze(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        PlaceResult first = e.place(
                limit("dup", "A", Side.SELL, 100L, 100L, 10L));
        PlaceResult replay = e.place(
                limit("dup", "A", Side.SELL, 100L, 100L, 10L));

        // same committed outcome, no second order, frozen quantity unchanged
        assertEquals(first.status(), replay.status());
        assertEquals(10L, e.depth(10).frozenSellQty());
        assertEquals(1, e.depth(10).asks().get(0).orderCount());

        // one aggressive order matching once, then replayed -> only one fill
        e.place(limit("m1", "M", Side.BUY, 100L, 10L, null));
        e.place(limit("m1", "M", Side.BUY, 100L, 10L, null)); // replay
        assertEquals(90L, Views.remaining(e, "A")); // one real 10-fill
        e.close();
    }

    @Test
    void duplicateCancelReleasesOnlyOnce(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("p", "A", Side.SELL, 100L, 100L, 10L));
        e.cancel(new CancelRequest("c", "A", "BTC-USD", null));
        e.cancel(new CancelRequest("c", "A", "BTC-USD", null)); // replay
        assertEquals(0L, e.depth(10).frozenSellQty());
        assertEquals(0, e.depth(10).asks().size());
        e.close();
    }

    @Test
    void duplicateReplaceAppliesOnce(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("p", "A", Side.SELL, 100L, 100L, 10L));
        ReplaceRequest rr = new ReplaceRequest("am", "A", "BTC-USD",
                null, 90L, null, "KEEP", null);
        e.replace(rr);
        e.replace(rr);
        assertEquals(2L, Views.revision(e, "A"));   // bumped once
        assertEquals(90L, Views.total(e, "A"));
        e.close();
    }

    @Test
    void lateStaleRequestDoesNotOverrideNewState(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        e.place(limit("p", "A", Side.SELL, 100L, 100L, 10L));
        e.replace(new ReplaceRequest("new", "A", "BTC-USD",
                null, 50L, null, "KEEP", null)); // revision -> 2

        // an older in-flight cancel carrying revision 1 must not win
        MatchingException ex = assertThrows(MatchingException.class,
                () -> e.cancel(new CancelRequest("old", "A", "BTC-USD", 1L)));
        assertEquals(ErrorCode.REVISION_CONFLICT, ex.getErrorCode());
        assertEquals(2L, Views.revision(e, "A"));
        assertEquals(50L, Views.total(e, "A"));
        e.close();
    }

    @Test
    void requestIdRequired(@TempDir Path dir) {
        SymbolEngine e = engine(dir);
        MatchingException ex = assertThrows(MatchingException.class,
                () -> e.place(limit(null, "A", Side.SELL, 100L, 100L, 10L)));
        assertEquals(ErrorCode.MISSING_REQUEST_ID, ex.getErrorCode());
        assertEquals(0, e.depth(10).asks().size());
        e.close();
    }
}
