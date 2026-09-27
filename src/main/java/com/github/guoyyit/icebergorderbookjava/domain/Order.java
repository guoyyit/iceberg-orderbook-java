package com.github.guoyyit.icebergorderbookjava.domain;

/**
 * A live order instance held by the matching engine.
 *
 * <p>Visible depth only ever exposes {@link #visibleQty}; {@link #hiddenQty}
 * never leaves the matching core, so counterparties cannot infer hidden size
 * from book queries.
 */
public final class Order {

    private String orderId;
    private String symbol;
    private String account;
    private Side side;
    private OrderType type;
    private long price;          // LONG_PRICE for market orders
    private long totalQty;       // original submitted quantity
    private long filledQty;      // accumulated traded quantity
    private long visibleQty;     // currently displayed peak (resting only)
    private long hiddenQty;      // concealed reserve
    private long peakSize;       // replenishment granularity
    private long version;        // bumped on each successful replace
    private long seq;            // price-time priority sequence
    private boolean dead;        // lazy tombstone flag
    private long frozenQty;      // asset qty frozen on placement (SELL)
    private long frozenCash;     // cash frozen on placement (BUY, market cap or limit notional)

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }
    public OrderType getType() { return type; }
    public void setType(OrderType type) { this.type = type; }
    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }
    public long getTotalQty() { return totalQty; }
    public void setTotalQty(long totalQty) { this.totalQty = totalQty; }
    public long getFilledQty() { return filledQty; }
    public void setFilledQty(long filledQty) { this.filledQty = filledQty; }
    public long getVisibleQty() { return visibleQty; }
    public void setVisibleQty(long visibleQty) { this.visibleQty = visibleQty; }
    public long getHiddenQty() { return hiddenQty; }
    public void setHiddenQty(long hiddenQty) { this.hiddenQty = hiddenQty; }
    public long getPeakSize() { return peakSize; }
    public void setPeakSize(long peakSize) { this.peakSize = peakSize; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public boolean isDead() { return dead; }
    public void setDead(boolean dead) { this.dead = dead; }
    public long getFrozenQty() { return frozenQty; }
    public void setFrozenQty(long frozenQty) { this.frozenQty = frozenQty; }
    public long getFrozenCash() { return frozenCash; }
    public void setFrozenCash(long frozenCash) { this.frozenCash = frozenCash; }
}
