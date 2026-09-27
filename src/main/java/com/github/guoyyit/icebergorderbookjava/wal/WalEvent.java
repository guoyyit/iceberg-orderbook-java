package com.github.guoyyit.icebergorderbookjava.wal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.guoyyit.icebergorderbookjava.domain.OrderType;
import com.github.guoyyit.icebergorderbookjava.domain.Side;

/**
 * A single write-ahead log record. The log is an append-only, framed,
 * checksummed event stream. Every state mutation (including rejections and
 * idempotent request completion) is represented by one event type here;
 * recovery folds the stream back into engine state deterministically.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WalEvent {

    public enum Kind {
        ORDER_ADDED,
        TRADE,
        ENTER_BOOK,
        ORDER_CANCELLED,
        ORDER_REPLACED,
        REQUEST_REJECTED
    }

    // common
    public Kind kind;
    public String symbol;
    public String requestId;
    public long seq;             // global per-symbol event sequence

    // order identity / definition
    public String orderId;
    public String account;
    public Side side;
    public OrderType type;
    public Long price;
    public Long totalQty;
    public Long peakSize;
    public Long cashCap;
    public Long version;
    public Long prioritySeq;      // time-priority sequence (replaced orders)

    // trade
    public String makerOrderId;
    public String takerOrderId;
    public String makerAccount;
    public String takerAccount;
    public Long tradeQty;
    public Long tradePrice;
    public Long tradeSeq;
    public Long makerNewVisible;  // maker visible qty after the fill (0 if dead)
    public Long makerNewHidden;
    public Long makerFilledQty;
    public Long takerNewVisible;
    public Long takerNewHidden;
    public Long takerFilledQty;
    public Long takerFrozenCash; // taker remaining frozen cash (buy) after fill
    public Long takerFrozenQty;  // taker remaining frozen asset qty (sell) after fill
    public Long makerFrozenQty;  // maker remaining frozen base asset qty after fill
    public Long makerFrozenCash; // maker remaining frozen cash (buy) after fill

    // cancel / replace balance effects
    public Long releaseAssetQty; // freed frozen base asset (sells)
    public Long releaseCash;     // freed frozen cash (buys)
    public Long freezeAssetQty;  // frozen base asset on ORDER_ADDED (sells)
    public Long freezeCash;      // frozen cash on ORDER_ADDED (buys)
    public Long extraFreezeAsset;// additional asset freeze on ORDER_REPLACED
    public Long extraFreezeCash; // additional cash freeze on ORDER_REPLACED
    public String rejectCode;

    // enter book
    public Long visibleQty;
    public Long hiddenQty;
    public Boolean atFront;      // replenishment: true => front of its level
    public Boolean atSameSlot;   // true => reinsert at the same within-level slot
    public Long bookSeq;         // price-time priority key in the book

    public WalEvent() {
    }

    public static WalEvent orderAdded(String symbol, String requestId, long seq) {
        WalEvent e = new WalEvent();
        e.kind = Kind.ORDER_ADDED;
        e.symbol = symbol;
        e.requestId = requestId;
        e.seq = seq;
        return e;
    }

    public WalEvent withOrder(String orderId, String account, Side side,
                              OrderType type, Long price, Long totalQty,
                              Long peakSize, Long cashCap) {
        this.orderId = orderId;
        this.account = account;
        this.side = side;
        this.type = type;
        this.price = price;
        this.totalQty = totalQty;
        this.peakSize = peakSize;
        this.cashCap = cashCap;
        return this;
    }
}
