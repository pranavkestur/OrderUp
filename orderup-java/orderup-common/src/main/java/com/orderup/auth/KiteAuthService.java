package com.orderup.auth;

import com.orderup.config.KiteProperties;
import com.orderup.notify.TelegramNotifier;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.User;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Holds the current Kite access token, persists it, and knows when re-auth is required.
 *
 * <p>Kite access tokens expire daily around 06:00 IST. A token is valid only if
 * it was obtained on today's IST calendar date.
 *
 * <p><b>Storage precedence on startup:</b>
 * <ol>
 *   <li>{@link FileAccessTokenStore} — shared file at {@code kite.token-store}
 *       (if configured). Lets multiple OrderUp apps on the same box share one
 *       daily login.</li>
 *   <li>{@link AccessTokenRepository} (H2/JPA) — legacy per-app store, kept for
 *       audit history and as a fallback when no file store is configured.</li>
 * </ol>
 * On successful login both stores are written so downstream apps see the update
 * immediately (file) and the JPA audit log stays complete (row-per-userId).
 */
@Service
public class KiteAuthService {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

    private final KiteConnect kite;
    private final KiteProperties props;
    private final AccessTokenRepository repo;
    private final ObjectProvider<FileAccessTokenStore> fileStoreProvider;
    private final ObjectProvider<TelegramNotifier> telegramProvider;

    private volatile boolean authenticated = false;

    /** Rate-limit repeated Telegram login pushes so a burst of failed orders can't spam. */
    private static final Duration LOGIN_PUSH_COOLDOWN = Duration.ofMinutes(10);
    private volatile Instant lastLoginPushAt = Instant.EPOCH;

    public KiteAuthService(KiteConnect kite, KiteProperties props,
                           AccessTokenRepository repo,
                           ObjectProvider<FileAccessTokenStore> fileStoreProvider,
                           ObjectProvider<TelegramNotifier> telegramProvider) {
        this.kite = kite;
        this.props = props;
        this.repo = repo;
        this.fileStoreProvider = fileStoreProvider;
        this.telegramProvider = telegramProvider;
    }

    @PostConstruct
    void initFromStores() {
        // 1. Try the shared file first.
        FileAccessTokenStore fileStore = fileStoreProvider.getIfAvailable();
        if (fileStore != null) {
            Optional<FileAccessTokenStore.Token> row = fileStore.read();
            if (row.isPresent() && applyIfFresh(row.get().userId(), row.get().accessToken(), row.get().obtainedOn(),
                    "file store " + fileStore)) {
                return;
            }
        }

        // 2. Fall back to JPA.
        String userId = effectiveUserId();
        if (userId == null) { logLoginNeeded(); return; }
        Optional<AccessTokenEntity> row = repo.findById(userId);
        if (row.isEmpty()) { logLoginNeeded(); return; }
        AccessTokenEntity e = row.get();
        if (!applyIfFresh(e.getUserId(), e.getAccessToken(), e.getObtainedOn(), "JPA store")) {
            // applyIfFresh already logged; nothing else to do.
        }
    }

    /**
     * Fires once when the Spring context is fully ready (Tomcat listening, all
     * beans initialised). Pings Telegram with the current auth status and, if
     * the token is stale/missing, pushes the mobile-friendly login link. This
     * runs from {@code orderup-common} so <b>every</b> OrderUp Spring Boot app
     * (WACE, Chartink, future modules) gets the reminder automatically — no
     * per-app scheduler wiring needed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        TelegramNotifier tg = telegramProvider.getIfAvailable();
        String appName = System.getProperty("spring.application.name",
                System.getenv().getOrDefault("SPRING_APPLICATION_NAME", "OrderUp"));
        String status = authenticated ? "authenticated" : "NOT authenticated";
        log.info("🤖 {} started ({}).", appName, status);
        if (tg != null && tg.isConfigured()) {
            tg.send("🤖 " + appName + " started (" + status + ").");
        }
        if (!authenticated) {
            // Force the push through even if some earlier code path already
            // pinged Telegram inside this JVM (cooldown resets on JVM restart
            // anyway, but be defensive so startup is always noisy).
            lastLoginPushAt = Instant.EPOCH;
            pushLoginLinkToTelegram("🔐 Kite login required (startup):");
        }
    }

    /** Set the token on the KiteConnect client and verify via /profile. Returns true iff verified. */
    private boolean applyIfFresh(String userId, String token, LocalDate obtainedOn, String source) {
        if (!LocalDate.now().equals(obtainedOn)) {
            log.warn("Persisted access token from {} is stale ({}). Fresh login required.", source, obtainedOn);
            logLoginNeeded();
            return false;
        }
        try {
            kite.setAccessToken(token);
            kite.setUserId(userId);
            kite.getProfile();
            authenticated = true;
            log.info("✅ Restored valid Kite access token for user {} (from {})", userId, source);
            return true;
        } catch (Throwable ex) {
            log.warn("Token from {} failed verification: {}", source, ex.getMessage());
            logLoginNeeded();
            return false;
        }
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String loginUrl() {
        return kite.getLoginURL();
    }

    /** Called by the OAuth callback controller once Kite redirects back with a request_token. */
    public synchronized void completeLogin(String requestToken) throws Throwable {
        User user = kite.generateSession(requestToken, props.apiSecret());
        kite.setAccessToken(user.accessToken);
        kite.setUserId(user.userId);
        LocalDate today = LocalDate.now();
        repo.save(new AccessTokenEntity(user.userId, user.accessToken, today));
        FileAccessTokenStore fileStore = fileStoreProvider.getIfAvailable();
        if (fileStore != null) {
            fileStore.write(new FileAccessTokenStore.Token(user.userId, user.accessToken, today));
        }
        authenticated = true;
        log.info("✅ Kite login successful for user {}", user.userId);
        TelegramNotifier tg = telegramProvider.getIfAvailable();
        if (tg != null) {
            tg.send("✅ Kite login successful for user " + user.userId + ". OrderUp is now authenticated for "
                    + today + ".");
        }
    }

    /**
     * Called by any component that detects the current in-memory token is no longer accepted
     * by Kite (typically a 403 on an order or profile call). Flips {@link #authenticated} to
     * false and pushes a fresh login link to Telegram (rate-limited).
     */
    public synchronized void markUnauthenticated(String reason) {
        if (authenticated) {
            log.warn("Marking Kite session unauthenticated: {}", reason);
        }
        authenticated = false;
        pushLoginLinkToTelegram("🔐 Kite session invalid (" + reason + "). Login required:");
    }

    /**
     * Push the current Kite login URL to Telegram. Rate-limited to once per
     * {@link #LOGIN_PUSH_COOLDOWN} to avoid spamming when many orders fail in a row.
     * Returns true if the message was actually sent.
     *
     * <p>The URL we send is not Kite's OAuth URL directly — it's OrderUp's own
     * {@code /kite/start} on the same origin as the redirect URL. That entry page
     * primes the ngrok-free browser-warning cookie and then meta-refreshes to
     * Kite, so tapping the link on a phone completes the full OAuth round trip
     * without hitting the interstitial that would otherwise swallow the
     * {@code request_token}.
     */
    public boolean pushLoginLinkToTelegram(String prefix) {
        return pushLoginLinkToTelegram(prefix, false);
    }

    /**
     * Same as {@link #pushLoginLinkToTelegram(String)} but with an explicit
     * {@code force} flag that bypasses the 10-minute cooldown. Use {@code true}
     * for user-initiated on-demand pushes (e.g. {@code POST /kite/send-login})
     * where spam isn't a concern.
     */
    public boolean pushLoginLinkToTelegram(String prefix, boolean force) {
        TelegramNotifier tg = telegramProvider.getIfAvailable();
        if (tg == null || !tg.isConfigured()) {
            log.debug("Telegram not configured — skipping login-link push.");
            return false;
        }
        Instant now = Instant.now();
        if (!force) {
            synchronized (this) {
                if (Duration.between(lastLoginPushAt, now).compareTo(LOGIN_PUSH_COOLDOWN) < 0) {
                    log.debug("Skipping login-link push (last sent {} ago, cooldown {}).",
                            Duration.between(lastLoginPushAt, now), LOGIN_PUSH_COOLDOWN);
                    return false;
                }
                lastLoginPushAt = now;
            }
        } else {
            lastLoginPushAt = now;
        }
        String head = (prefix == null || prefix.isBlank())
                ? "🔐 Kite login required."
                : prefix;
        tg.send(head + "\n" + mobileEntryUrl()
                + "\n\nTap the link on your phone → log in to Zerodha → you'll be redirected back and today's access token will be saved automatically.");
        return true;
    }

    /**
     * Derive the mobile-friendly entry URL {@code https://<host>/kite/start} from
     * the configured {@code kite.redirect-url}. Falls back to the raw Kite login
     * URL if the redirect URL isn't a well-formed http(s) URL.
     */
    private String mobileEntryUrl() {
        String r = props.redirectUrl();
        if (r == null || r.isBlank()) return loginUrl();
        try {
            java.net.URI uri = java.net.URI.create(r);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) return loginUrl();
            StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
            if (port > 0 && !(("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80))) {
                sb.append(':').append(port);
            }
            sb.append("/kite/start");
            return sb.toString();
        } catch (Throwable t) {
            return loginUrl();
        }
    }

    private String effectiveUserId() {
        if (props.userId() != null && !props.userId().isBlank()) return props.userId();
        return repo.findAll().stream().map(AccessTokenEntity::getUserId).findFirst().orElse(null);
    }

    private void logLoginNeeded() {
        authenticated = false;
        log.warn("---------------------------------------------------------------");
        log.warn("Kite login required. Open this URL in a browser:");
        log.warn("  {}", loginUrl());
        log.warn("After login, Kite will redirect back to {} and finish setup.", props.redirectUrl());
        log.warn("---------------------------------------------------------------");
    }
}

