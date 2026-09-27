package com.github.guoyyit.icebergorderbookjava.dto;

import com.github.guoyyit.icebergorderbookjava.model.Fill;
import com.github.guoyyit.icebergorderbookjava.model.OrderView;

import java.util.List;

/** Result of a replace/amend request. */
public record ReplaceResult(String orderId,
                            String status,
                            OrderView order,
                            List<Fill> fills) {
}
