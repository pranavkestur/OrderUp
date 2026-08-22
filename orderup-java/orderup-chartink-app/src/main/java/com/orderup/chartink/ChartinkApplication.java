package com.orderup.chartink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OrderUp Chartink webhook receiver.
 *
 * <p>Boots common's Kite auth + order-placement stack (no candle cache — the
 * webhook already tells us which symbol to buy). Scanning is entirely
 * absent; there's just a single POST endpoint that fans out over the
 * webhook's symbol list.
 */
@SpringBootApplication(scanBasePackages = "com.orderup")
@ConfigurationPropertiesScan("com.orderup")
@EntityScan("com.orderup")
@EnableJpaRepositories("com.orderup")
@EnableScheduling
public class ChartinkApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChartinkApplication.class, args);
    }
}

