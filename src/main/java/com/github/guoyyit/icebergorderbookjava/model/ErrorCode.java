package com.github.guoyyit.icebergorderbookjava.model;

/** Distinguishable rejection codes. Never written into the book. */
public enum ErrorCode {
    UNKNOWN_SYMBOL,
    MISSING_REQUEST_ID,
    INVALID_REQUEST,
    INVALID_SIDE,
    INVALID_ORDER_TYPE,
    INVALID_PRICE,
    INVALID_QTY,
    INVALID_PEAK_SIZE,
    PEAK_BELOW_MIN_VISIBLE,
    PEAK_GRANULARITY,
    QTY_EXCEEDS_MAX,
    ORDER_ALREADY_EXISTS,
    ORDER_NOT_FOUND,
    ORDER_NOT_ACTIVE,
    REVISION_CONFLICT,
    INVALID_REPLACE,
    PRICE_CROSSES_MARKET,
    INVALID_PRIORITY_MODE,
    INTERNAL_ERROR
}
