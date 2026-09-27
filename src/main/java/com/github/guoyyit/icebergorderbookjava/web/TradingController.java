package com.github.guoyyit.icebergorderbookjava.web;

import com.github.guoyyit.icebergorderbookjava.api.CancelOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.api.NewOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.api.RejectedRequestException;
import com.github.guoyyit.icebergorderbookjava.api.ReplaceOrderRequest;
import com.github.guoyyit.icebergorderbookjava.service.TradingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TradingController {

    private final TradingService service;

    public TradingController(TradingService service) {
        this.service = service;
    }

    @PostMapping("/orders")
    public OrderResponse submit(@RequestBody NewOrderRequest req) throws Exception {
        return service.submit(req);
    }

    @PostMapping("/orders/cancel")
    public OrderResponse cancel(@RequestBody CancelOrderRequest req) throws Exception {
        return service.cancel(req);
    }

    @PostMapping("/orders/replace")
    public OrderResponse replace(@RequestBody ReplaceOrderRequest req) throws Exception {
        return service.replace(req);
    }

    @GetMapping("/depth/{symbol}")
    public DepthSnapshot depth(@PathVariable String symbol) throws Exception {
        return service.depth(symbol);
    }

    @ExceptionHandler(RejectedRequestException.class)
    public ResponseEntity<OrderResponse> rejected(RejectedRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(OrderResponse.rejected(ex.getRequestId(), ex.getOrderId(), ex.getCode().name()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(ex.getMessage())));
    }
}
