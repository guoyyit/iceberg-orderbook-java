package com.github.guoyyit.icebergorderbookjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration under {@code orderbook.*}: WAL directory and per-symbol
 * parameters. Symbols absent from the map are rejected as
 * {@code UNKNOWN_SYMBOL}.
 */
@Component
@ConfigurationProperties(prefix = "orderbook")
public class BookProperties {

    private String walDir = "data/orderbook-wal";
    private int depthLevels = 20;
    private Map<String, SymbolCfg> symbols = new LinkedHashMap<>();

    public static class SymbolCfg {
        private long minVisibleQty = 1L;
        private long replenishGranularity = 1L;
        private long maxQty = 1_000_000_000L;

        public long getMinVisibleQty() { return minVisibleQty; }
        public void setMinVisibleQty(long v) { this.minVisibleQty = v; }
        public long getReplenishGranularity() { return replenishGranularity; }
        public void setReplenishGranularity(long v) { this.replenishGranularity = v; }
        public long getMaxQty() { return maxQty; }
        public void setMaxQty(long v) { this.maxQty = v; }
    }

    public String getWalDir() { return walDir; }
    public void setWalDir(String walDir) { this.walDir = walDir; }
    public int getDepthLevels() { return depthLevels; }
    public void setDepthLevels(int depthLevels) { this.depthLevels = depthLevels; }
    public Map<String, SymbolCfg> getSymbols() { return symbols; }
    public void setSymbols(Map<String, SymbolCfg> symbols) { this.symbols = symbols; }
}
