package com.github.guoyyit.icebergorderbookjava.config;

import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
public class AppConfig {

    @Bean
    public BalanceLedger balanceLedger() {
        return new BalanceLedger();
    }
}
