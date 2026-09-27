package com.github.guoyyit.icebergorderbookjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable matching / iceberg parameters.
 */
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    /** Default replenishment peak size used when an iceberg order omits it. */
    private long defaultPeakSize = 100L;

    /** Minimum visible (display) quantity allowed for an iceberg peak. */
    private long minPeakSize = 1L;

    /** Maximum allowed peak size (sanity bound). */
    private long maxPeakSize = 1_000_000_000L;

    /** Directory holding per-symbol write-ahead log files. */
    private String walDir = "data/wal";

    /** Fsync every committed batch (false = OS flush only; tests may disable). */
    private boolean fsync = true;

    public long getDefaultPeakSize() { return defaultPeakSize; }
    public void setDefaultPeakSize(long defaultPeakSize) { this.defaultPeakSize = defaultPeakSize; }
    public long getMinPeakSize() { return minPeakSize; }
    public void setMinPeakSize(long minPeakSize) { this.minPeakSize = minPeakSize; }
    public long getMaxPeakSize() { return maxPeakSize; }
    public void setMaxPeakSize(long maxPeakSize) { this.maxPeakSize = maxPeakSize; }
    public String getWalDir() { return walDir; }
    public void setWalDir(String walDir) { this.walDir = walDir; }
    public boolean isFsync() { return fsync; }
    public void setFsync(boolean fsync) { this.fsync = fsync; }
}
