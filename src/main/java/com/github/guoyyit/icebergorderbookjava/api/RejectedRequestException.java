package com.github.guoyyit.icebergorderbookjava.api;

import com.github.guoyyit.icebergorderbookjava.domain.RejectCode;

/**
 * Raised when a request fails validation or optimistic-concurrency checks.
 * Such requests are persisted (for idempotent replay) but never mutate the
 * order book.
 */
public class RejectedRequestException extends RuntimeException {

    private final RejectCode code;
    private final String requestId;
    private final String orderId;

    public RejectedRequestException(RejectCode code, String requestId, String orderId) {
        super(code.name());
        this.code = code;
        this.requestId = requestId;
        this.orderId = orderId;
    }

    public RejectCode getCode() { return code; }
    public String getRequestId() { return requestId; }
    public String getOrderId() { return orderId; }
}
