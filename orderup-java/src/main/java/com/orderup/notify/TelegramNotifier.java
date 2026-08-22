package com.orderup.notify;

import com.orderup.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final TelegramProperties props;
    private final WebClient client;

    public TelegramNotifier(TelegramProperties props) {
        this.props = props;
        this.client = WebClient.builder().baseUrl("https://api.telegram.org").build();
    }

    public void send(String message) {
        if (!isConfigured()) return;
        try {
            client.post()
                    .uri("/bot{token}/sendMessage", props.botToken())
                    .bodyValue(Map.of("chat_id", props.chatId(), "text", message))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Telegram notify failed: {}", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return props.botToken() != null && !props.botToken().isBlank()
                && props.chatId() != null && !props.chatId().isBlank();
    }
}

