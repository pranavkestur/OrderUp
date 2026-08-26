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

    /**
     * Total attempts per {@code send(...)} call. Telegram's HTTP/2 edge frequently
     * RSTs the first pooled connection after it's been idle for a while, so a
     * single-shot send that happens right after a lull (e.g. after several
     * minutes of no signals, then a BUY fires) tends to fail with
     * {@code "Connection reset"}. Retrying once with a fresh WebClient in a new
     * event-loop cycle reliably fixes it.
     */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(250);

    private final TelegramProperties props;
    private final WebClient client;

    public TelegramNotifier(TelegramProperties props) {
        this.props = props;
        this.client = WebClient.builder().baseUrl("https://api.telegram.org").build();
    }

    public void send(String message) {
        if (!isConfigured()) return;
        Throwable last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                client.post()
                        .uri("/bot{token}/sendMessage", props.botToken())
                        .bodyValue(Map.of("chat_id", props.chatId(), "text", message))
                        .retrieve()
                        .toBodilessEntity()
                        .block(Duration.ofSeconds(5));
                if (attempt > 1) {
                    log.info("Telegram notify succeeded on attempt {}/{}.", attempt, MAX_ATTEMPTS);
                }
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    log.debug("Telegram notify attempt {}/{} failed ({}), retrying in {}ms.",
                            attempt, MAX_ATTEMPTS, e.getMessage(), RETRY_BACKOFF.toMillis() * attempt);
                    try {
                        Thread.sleep(RETRY_BACKOFF.toMillis() * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.warn("Telegram notify failed after {} attempts: {}", MAX_ATTEMPTS,
                last == null ? "unknown" : last.getMessage());
    }

    public boolean isConfigured() {
        return props.botToken() != null && !props.botToken().isBlank()
                && props.chatId() != null && !props.chatId().isBlank();
    }
}

