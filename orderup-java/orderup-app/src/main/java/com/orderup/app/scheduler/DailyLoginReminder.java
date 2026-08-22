package com.orderup.app.scheduler;

import com.orderup.auth.KiteAuthService;
import com.orderup.config.TradingProperties;
import com.orderup.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Once every trading morning at 08:45 IST, ping the user via Telegram with a login link
 * if the Kite access token is stale. Also fires a one‑time startup notification so the
 * user knows the service actually came up.
 */
@Component
public class DailyLoginReminder {

    private static final Logger log = LoggerFactory.getLogger(DailyLoginReminder.class);

    private final KiteAuthService auth;
    private final TelegramNotifier telegram;
    private final TradingProperties trading;

    public DailyLoginReminder(KiteAuthService auth, TelegramNotifier telegram, TradingProperties trading) {
        this.auth = auth;
        this.telegram = telegram;
        this.trading = trading;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        String status = auth.isAuthenticated() ? "authenticated" : "NOT authenticated";
        String msg = "🤖 OrderUp started (" + status + ")."
                + (auth.isAuthenticated() ? "" : "\nLogin: " + auth.loginUrl());
        log.info(msg);
        telegram.send(msg);
    }

    /** 08:45 Mon–Fri IST. */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "${trading.scheduler-zone}")
    public void morningReminder() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(trading.schedulerZone()));
        LocalDate d = now.toLocalDate();
        if (trading.holidays() != null && trading.holidays().contains(d)) {
            log.info("Holiday {} — skipping login reminder.", d);
            return;
        }

        if (auth.isAuthenticated()) {
            log.info("Morning check: token still valid, no reminder needed.");
            return;
        }
        String msg = "☀️ Good morning! OrderUp needs a Kite login for today.\n"
                + "Tap: " + auth.loginUrl();
        log.info("Sending morning login reminder.");
        telegram.send(msg);
    }
}

