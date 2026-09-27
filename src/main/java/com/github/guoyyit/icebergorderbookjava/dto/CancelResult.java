package com.github.guoyyit.icebergorderbookjava.dto;

import com.github.guoyyit.icebergorderbookjava.model.OrderView;

/** Result of a cancel request. releasedQty is freed frozen quantity. */
public record CancelResult(String orderId,
                           String status,
                           long releasedQty,
                           OrderView order) {
}
