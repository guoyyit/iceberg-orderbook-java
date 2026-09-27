package com.github.guoyyit.icebergorderbookjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "matching.wal-dir=target/test-data/web",
        "matching.fsync=false"
})
@AutoConfigureMockMvc
class TradingControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    BalanceLedger ledger;
    @Autowired
    com.github.guoyyit.icebergorderbookjava.engine.MatchingEngine engine;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void endToEndPlaceDepthCancelAndDistinguishableReject() throws Exception {
        ledger.credit("w1", "BTC", 1_000_000);
        ledger.credit("c1", BalanceLedger.CASH, 100_000_000_000L);

        // place iceberg sell: total 500, peak 100
        String place = """
                {"requestId":"w-r1","orderId":"w-o1","symbol":"BTC","account":"w1",
                 "side":"SELL","type":"LIMIT","price":50000,"quantity":500,"peakSize":100}
                """;
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.remainingVisibleQty").value(100));

        // depth only exposes the peak
        mvc.perform(get("/api/depth/BTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asks[0].visibleQty").value(100))
                .andExpect(jsonPath("$.asks[0].orderCount").value(1));

        // invalid order -> distinguishable reject code, HTTP 400, not in book
        String bad = """
                {"requestId":"w-bad","orderId":"w-bad-o","symbol":"BTC","account":"w1",
                 "side":"SELL","type":"LIMIT","price":0,"quantity":10}
                """;
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejected").value(true))
                .andExpect(jsonPath("$.rejectCode").value("INVALID_PRICE"));
        mvc.perform(get("/api/depth/BTC"))
                .andExpect(jsonPath("$.asks[0].orderCount").value(1));

        // market buy crosses 100 (one peak)
        String mkt = """
                {"requestId":"w-r2","orderId":"w-m1","symbol":"BTC","account":"c1",
                 "side":"BUY","type":"MARKET","quantity":100,"cashCap":6000000}
                """;
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(mkt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fills[0].quantity").value(100))
                .andExpect(jsonPath("$.fills[0].price").value(50000));

        // iceberg replenished to 100 visible again, 300 hidden
        mvc.perform(get("/api/depth/BTC"))
                .andExpect(jsonPath("$.asks[0].visibleQty").value(100));

        // cancel
        mvc.perform(post("/api/orders/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-r3","symbol":"BTC","orderId":"w-o1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true));
        mvc.perform(get("/api/depth/BTC"))
                .andExpect(jsonPath("$.asks").isArray());

        // duplicate of the rejected request returns the stored rejection result
        // (idempotent replay: HTTP 200 carrying the original rejection body)
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(true))
                .andExpect(jsonPath("$.rejectCode").value("INVALID_PRICE"));

        engine.shutdownSymbol("BTC");
    }
}
