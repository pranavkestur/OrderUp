package com.orderup.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OrderUp App entry point (WACE + legacy multi-indicator scanner).
 *
 * <p>Now lives under {@code com.orderup.app}. We widen scans back to
 * {@code com.orderup} so shared beans / JPA entities / config-properties in
 * the {@code orderup-common} module are picked up.
 */
@SpringBootApplication(scanBasePackages = "com.orderup")
@ConfigurationPropertiesScan("com.orderup")
@EntityScan("com.orderup")
@EnableJpaRepositories("com.orderup")
@EnableScheduling
public class OrderUpApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderUpApplication.class, args);
    }
}

