package com.orderup.auth;

import com.orderup.config.KiteProperties;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.User;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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

    private volatile boolean authenticated = false;

    public KiteAuthService(KiteConnect kite, KiteProperties props,
                           AccessTokenRepository repo,
                           ObjectProvider<FileAccessTokenStore> fileStoreProvider) {
        this.kite = kite;
        this.props = props;
        this.repo = repo;
        this.fileStoreProvider = fileStoreProvider;
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

