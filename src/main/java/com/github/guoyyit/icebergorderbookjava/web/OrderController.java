package com.github.guoyyit.icebergorderbookjava.web;

import com.github.guoyyit.icebergorderbookjava.dto.ApiResponse;
import com.github.guoyyit.icebergorderbookjava.dto.CancelRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceRequest;
import com.github.guoyyit.icebergorderbookjava.engine.EngineManager;
import com.github.guoyyit.icebergorderbookjava.model.DepthView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Local REST endpoints: place / cancel / replace / visible depth. */
@RestController
@RequestMapping("/api/orderbook")
public class OrderController {

    private final EngineManager manager;

    public OrderController(EngineManager manager) {
        this.manager = manager;
    }

    @PostMapping("/place")
    public ApiResponse<?> place(@RequestBody PlaceRequest req) {
        return ApiResponse.success(manager.place(req), req.requestId());
    }

    @PostMapping("/cancel")
    public ApiResponse<?> cancel(@RequestBody CancelRequest req) {
        return ApiResponse.success(manager.cancel(req), req.requestId());
    }

    @PostMapping("/replace")
    public ApiResponse<?> replace(@RequestBody ReplaceRequest req) {
        return ApiResponse.success(manager.replace(req), req.requestId());
    }

    @GetMapping("/depth/{symbol}")
    public ApiResponse<DepthView> depth(@PathVariable String symbol,
                                        @RequestParam(defaultValue = "20") int levels) {
        return ApiResponse.success(manager.depth(symbol, levels), null);
    }
}
