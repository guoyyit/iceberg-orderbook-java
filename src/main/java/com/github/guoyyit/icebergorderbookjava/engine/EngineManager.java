package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.config.BookProperties;
import com.github.guoyyit.icebergorderbookjava.dto.CancelRequest;
import com.github.guoyyit.icebergorderbookjava.dto.CancelResult;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceResult;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceResult;
import com.github.guoyyit.icebergorderbookjava.model.DepthView;
import com.github.guoyyit.icebergorderbookjava.model.ErrorCode;
import com.github.guoyyit.icebergorderbookjava.model.MatchingException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes by symbol to fully isolated engines. Each configured symbol
 * gets its own single-thread engine and its own durable log, so symbols
 * never share a lock or a thread: a slow symbol cannot block another.
 *
 * <p>Engines are created lazily and recover their log before serving the
 * first request. Symbols not present in configuration are rejected with
 * {@code UNKNOWN_SYMBOL} and never touch any book.
 */
@Component
public class EngineManager {

    private final BookProperties properties;
    private final Map<String, SymbolEngine> engines = new ConcurrentHashMap<>();

    public EngineManager(BookProperties properties) {
        this.properties = properties;
        validate();
    }

    private void validate() {
        properties.getSymbols().forEach((sym, cfg) -> {
            if (cfg.getMinVisibleQty() <= 0
                    || cfg.getReplenishGranularity() <= 0
                    || cfg.getMaxQty() <= 0
                    || cfg.getMinVisibleQty() > cfg.getMaxQty()) {
                throw new IllegalStateException(
                        "invalid orderbook config for symbol " + sym);
            }
        });
    }

    public SymbolEngine engine(String symbol) {
        if (symbol == null || !properties.getSymbols().containsKey(symbol)) {
            throw new MatchingException(ErrorCode.UNKNOWN_SYMBOL,
                    "unknown symbol: " + symbol);
        }
        return engines.computeIfAbsent(symbol, s -> {
            BookProperties.SymbolCfg cfg = properties.getSymbols().get(s);
            BookConfig bc = new BookConfig(cfg.getMinVisibleQty(),
                    cfg.getReplenishGranularity(), cfg.getMaxQty());
            SymbolEngine e = new SymbolEngine(s, bc, properties.getWalDir());
            e.recover();
            return e;
        });
    }

    public PlaceResult place(PlaceRequest req) {
        requireSymbol(req.symbol());
        return engine(req.symbol()).place(req);
    }

    public CancelResult cancel(CancelRequest req) {
        requireSymbol(req.symbol());
        return engine(req.symbol()).cancel(req);
    }

    public ReplaceResult replace(ReplaceRequest req) {
        requireSymbol(req.symbol());
        return engine(req.symbol()).replace(req);
    }

    public DepthView depth(String symbol, int maxLevels) {
        return engine(symbol).depth(maxLevels);
    }

    private void requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new MatchingException(ErrorCode.UNKNOWN_SYMBOL,
                    "symbol required");
        }
    }

    @PreDestroy
    public void shutdown() {
        engines.values().forEach(SymbolEngine::close);
    }
}
