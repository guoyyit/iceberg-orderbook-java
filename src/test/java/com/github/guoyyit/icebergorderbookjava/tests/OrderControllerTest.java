package com.github.guoyyit.icebergorderbookjava.tests;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = {
        "orderbook.wal-dir=target/test-wal-web",
        "orderbook.symbols.TST.min-visible-qty=5",
        "orderbook.symbols.TST.replenish-granularity=5",
        "orderbook.symbols.TST.max-qty=1000000"
})
class OrderControllerTest {

    @Autowired
    private WebApplicationContext ctx;

    private MockMvc mvc;

    @PostConstruct
    void init() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    void placeThenDepthHidesHiddenQuantity() throws Exception {
        String body = """
                {
                  "requestId":"w1","orderId":"WICE","symbol":"TST",
                  "ownerId":"o","side":"SELL","type":"LIMIT",
                  "price":100,"qty":100,"peakSize":10
                }
                """;
        mvc.perform(post("/api/orderbook/place")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.status").value("RESTING"));

        mvc.perform(get("/api/orderbook/depth/TST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asks[?(@.price==100)].visibleQty")
                        .value(org.hamcrest.Matchers.hasItem(10)));
    }

    @Test
    void unknownSymbolReturnsDistinguishableCode() throws Exception {
        String body = """
                {
                  "requestId":"x","orderId":"Z","symbol":"NOPE",
                  "ownerId":"o","side":"BUY","type":"LIMIT",
                  "price":1,"qty":1
                }
                """;
        mvc.perform(post("/api/orderbook/place")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_SYMBOL"));
    }

    @Test
    void peakBelowMinAndGranularityReturnDistinguishableCode() throws Exception {
        String bad = """
                {
                  "requestId":"g1","orderId":"G1","symbol":"TST",
                  "ownerId":"o","side":"SELL","type":"LIMIT",
                  "price":200,"qty":100,"peakSize":4
                }
                """;
        mvc.perform(post("/api/orderbook/place")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode")
                        .value("PEAK_BELOW_MIN_VISIBLE"));

        String bad2 = """
                {
                  "requestId":"g2","orderId":"G2","symbol":"TST",
                  "ownerId":"o","side":"SELL","type":"LIMIT",
                  "price":201,"qty":100,"peakSize":7
                }
                """;
        mvc.perform(post("/api/orderbook/place")
                        .contentType(MediaType.APPLICATION_JSON).content(bad2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("PEAK_GRANULARITY"));
    }
}
