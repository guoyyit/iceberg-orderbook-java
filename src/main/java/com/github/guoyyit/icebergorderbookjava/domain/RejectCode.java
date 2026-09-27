package com.github.guoyyit.icebergorderbookjava.domain;

/**
 * Distinguishable rejection error codes. Rejected requests are never written
 * into the order book.
 */
public enum RejectCode {
    INVALID_SYMBOL,
    INVALID_ACCOUNT,
    INVALID_QTY,
    INVALID_PRICE,
    INVALID_PEAK_SIZE,
    PEAK_BELOW_MIN,
    PEAK_ABOVE_MAX,
    MARKET_PRICE_NOT_ALLOWED,
    LIMIT_PRICE_REQUIRED,
    MARKET_CASH_REQUIRED,
    INVALID_CASH_CAP,
    INVALID_REQUEST_ID,
    INVALID_ORDER_ID,
    INVALID_SIDE,
    INVALID_TYPE,
    UNKNOWN_ORDER,
    ORDER_DEAD,
    VERSION_CONFLICT,
    INSUFFICIENT_BALANCE,
    BAD_REPLACE,
    KEEP_PRIORITY_PRICE_CHANGE
}
