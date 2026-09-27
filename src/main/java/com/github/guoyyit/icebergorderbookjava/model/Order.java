package com.github.guoyyit.icebergorderbookjava.model;

/**
 * Mutable resting/aggressing order state, owned solely by its symbol's
 * single-threaded engine. For an iceberg order {@code peakSize} is the
 * current peak (replenishment granularity), {@code visibleQty} the
 * currently exposed quantity and hidden remainder =
 * remaining - visibleQty.
 */
public class Order {
    private String orderId;
    private String symbol;
    private String ownerId;
    private Side side;
    private OrderType type;
    private Long price;
    private long totalQty;
    private long filledQty;
    private long peakSize;
    private long visibleQty;
    private long revision;
    private boolean active;

    public Order() {
    }

    public long remainingQty() {
        return totalQty - filledQty;
    }

    public long hiddenQty() {
        return Math.max(0, remainingQty() - visibleQty);
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }
    public OrderType getType() { return type; }
    public void setType(OrderType type) { this.type = type; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public long getTotalQty() { return totalQty; }
    public void setTotalQty(long totalQty) { this.totalQty = totalQty; }
    public long getFilledQty() { return filledQty; }
    public void setFilledQty(long filledQty) { this.filledQty = filledQty; }
    public long getPeakSize() { return peakSize; }
    public void setPeakSize(long peakSize) { this.peakSize = peakSize; }
    public long getVisibleQty() { return visibleQty; }
    public void setVisibleQty(long visibleQty) { this.visibleQty = visibleQty; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
