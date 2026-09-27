package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.dto.CancelRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceRequest;
import com.github.guoyyit.icebergorderbookjava.model.DepthView;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    private SymbolEngine start(Path dir) {
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
    void restoresDepthQtyPriorityFrozenAndRevisionAfterRestart(@TempDir Path dir) {
        SymbolEngine e1 = start(dir);
        e1.place(limit("a", "A", Side.SELL, 100L, 100L, 10L));
        e1.place(limit("b", "B", Side.SELL, 100L, 5L, null));
        e1.place(limit("c", "C", Side.SELL, 101L, 7L, null));
        e1.place(limit("t", "T", Side.BUY, 100L, 10L, null));
        e1.replace(new ReplaceRequest("am", "C", "BTC-USD",
                102L, 7L, null, "LOSE", null));
        DepthView before = e1.depth(50);
        assertEquals(List.of(100L, 102L),
                before.asks().stream().map(x -> x.price()).toList());
        e1.close();

        SymbolEngine e2 = start(dir);
        DepthView after = e2.depth(50);
        assertEquals(before, after);
        assertEquals(15L, after.asks().get(0).visibleQty());
        assertEquals(2, after.asks().get(0).orderCount());
        assertEquals(90L, Views.remaining(e2, "A"));
        assertEquals(10L, Views.visible(e2, "A"));
        assertEquals(80L, Views.hidden(e2, "A"));
        assertEquals(2L, Views.revision(e2, "C"));
        assertEquals(15L + 7L, after.frozenSellQty());

        var r = e2.place(limit("t2", "T2", Side.BUY, 100L, 5L, null));
        assertEquals("B", r.fills().get(0).makerOrderId());
        e2.close();
    }

    @Test
    void replayOfDuplicateRequestIdIsIdempotent(@TempDir Path dir) {
        SymbolEngine e1 = start(dir);
        e1.place(limit("dup", "A", Side.SELL, 100L, 100L, 10L));
        e1.close();

        SymbolEngine e2 = start(dir);
        e2.place(limit("dup", "A", Side.SELL, 100L, 100L, 10L));
        assertEquals(10L, e2.depth(10).frozenSellQty());
        assertEquals(1, e2.depth(10).asks().get(0).orderCount());
        e2.close();
    }

    @Test
    void committedFillsSurviveRestart(@TempDir Path dir) {
        SymbolEngine e1 = start(dir);
        e1.place(limit("m", "M", Side.SELL, 100L, 30L, null));
        e1.place(limit("t", "T", Side.BUY, 100L, 12L, null));
        assertEquals(18L, Views.remaining(e1, "M"));
        e1.close();

        SymbolEngine e2 = start(dir);
        assertEquals(18L, Views.remaining(e2, "M"));
        assertEquals(12L, Views.filled(e2, "M"));
        e2.close();
    }

    @Test
    void cancelledOrderDoesNotResurrect(@TempDir Path dir) {
        SymbolEngine e1 = start(dir);
        e1.place(limit("p", "A", Side.SELL, 100L, 100L, 10L));
        e1.cancel(new CancelRequest("x", "A", "BTC-USD", null));
        e1.close();

        SymbolEngine e2 = start(dir);
        assertTrue(e2.depth(10).asks().isEmpty());
        assertEquals(0L, e2.depth(10).frozenSellQty());
        e2.close();
    }

    @Test
    void tornTailFrameIsTruncatedNotReplayed(@TempDir Path dir) throws Exception {
        SymbolEngine e1 = start(dir);
        e1.place(limit("ok", "A", Side.SELL, 100L, 10L, null));
        e1.close();

        Path log = Files.list(dir).findFirst().orElseThrow();
        byte[] garbage = new byte[]{0, 0, 0, 42, 0, 0, 0, 0, 1, 2, 3};
        Files.write(log, garbage, StandardOpenOption.APPEND);

        SymbolEngine e2 = start(dir);
        assertEquals(10L, e2.depth(10).asks().get(0).visibleQty());
        e2.place(limit("ok2", "B", Side.SELL, 101L, 4L, null));
        e2.close();

        SymbolEngine e3 = start(dir);
        long q100 = e3.depth(10).asks().stream()
                .filter(l -> l.price() == 100L).findFirst().orElseThrow()
                .visibleQty();
        long q101 = e3.depth(10).asks().stream()
                .filter(l -> l.price() == 101L).findFirst().orElseThrow()
                .visibleQty();
        assertEquals(10L, q100);
        assertEquals(4L, q101);
        e3.close();
    }
}
