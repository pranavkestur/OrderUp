package com.orderup.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KiteClientConfig {

    @Bean
    public KiteConnect kiteConnect(KiteProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "KITE_API_KEY is not set. Populate it via environment variable or application.yml.");
        }
        KiteConnect kite = new KiteConnect(props.apiKey());
        if (props.userId() != null && !props.userId().isBlank()) {
            kite.setUserId(props.userId());
        }
        return kite;
    }
}

