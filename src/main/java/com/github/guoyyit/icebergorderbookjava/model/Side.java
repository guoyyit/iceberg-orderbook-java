package com.github.guoyyit.icebergorderbookjava.model;

/** Order side. */
public enum Side {
    BUY, SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
