package com.github.guoyyit.icebergorderbookjava.tests;

import com.github.guoyyit.icebergorderbookjava.api.NewOrderRequest;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.domain.OrderType;
import com.github.guoyyit.icebergorderbookjava.domain.Side;
import com.github.guoyyit.icebergorderbookjava.engine.SymbolEngine;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class TestSupport {

    private TestSupport() {
    }

    static MatchingProperties props(Path walDir) {
        MatchingProperties p = new MatchingProperties();
        p.setWalDir(walDir.toString());
        p.setDefaultPeakSize(100L);
        p.setMinPeakSize(1L);
        p.setMaxPeakSize(1_000_000L);
        p.setFsync(false);
        return p;
    }

    static SymbolEngine engine(String symbol, MatchingProperties p, BalanceLedger l) throws IOException {
        SymbolEngine e = new SymbolEngine(symbol, p, l);
        e.open();
        return e;
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var paths = Files.walk(p)) {
            paths.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // best effort on test cleanup
                }
            });
        }
    }

    static NewOrderRequest limit(String rid, String oid, String symbol, String account,
                                 Side side, long price, long qty, Long peak) {
        return new NewOrderRequest(rid, oid, symbol, account, side, OrderType.LIMIT,
                price, qty, peak, null);
    }

    static NewOrderRequest market(String rid, String oid, String symbol, String account,
                                  Side side, long qty, Long cashCap) {
        return new NewOrderRequest(rid, oid, symbol, account, side, OrderType.MARKET,
                null, qty, null, cashCap);
    }
}
