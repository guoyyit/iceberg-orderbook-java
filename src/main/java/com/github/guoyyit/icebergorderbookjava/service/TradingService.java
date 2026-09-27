package com.github.guoyyit.icebergorderbookjava.service;

import com.github.guoyyit.icebergorderbookjava.api.CancelOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.api.NewOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.api.ReplaceOrderRequest;
import com.github.guoyyit.icebergorderbookjava.engine.MatchingEngine;
import org.springframework.stereotype.Service;

/**
 * Thin application service in front of the matching engine.
 */
@Service
public class TradingService {

    private final MatchingEngine engine;

    public TradingService(MatchingEngine engine) {
        this.engine = engine;
    }

    public OrderResponse submit(NewOrderRequest req) throws Exception {
        return engine.submit(req);
    }

    public OrderResponse cancel(CancelOrderRequest req) throws Exception {
        return engine.cancel(req);
    }

    public OrderResponse replace(ReplaceOrderRequest req) throws Exception {
        return engine.replace(req);
    }

    public DepthSnapshot depth(String symbol) throws Exception {
        return engine.depth(symbol);
    }
}
