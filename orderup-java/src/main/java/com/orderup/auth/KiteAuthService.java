package com.orderup.auth;

import com.orderup.config.KiteProperties;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.User;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Holds the current Kite access token, persists it to the DB, and knows when re-auth is required.
 *
 * Kite access tokens expire daily around 06:00 IST. We consider a token valid only if it was
 * obtained today (same calendar date in IST).
 */
@Service
public class KiteAuthService {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

    private final KiteConnect kite;
    private final KiteProperties props;
    private final AccessTokenRepository repo;

    private volatile boolean authenticated = false;

    public KiteAuthService(KiteConnect kite, KiteProperties props, AccessTokenRepository repo) {
        this.kite = kite;
        this.props = props;
        this.repo = repo;
    }

    @PostConstruct
    void initFromDb() {
        String userId = effectiveUserId();
        if (userId == null) { logLoginNeeded(); return; }

        Optional<AccessTokenEntity> row = repo.findById(userId);
        if (row.isEmpty()) { logLoginNeeded(); return; }

        AccessTokenEntity e = row.get();
        if (!LocalDate.now().equals(e.getObtainedOn())) {
            log.warn("Persisted access token is stale ({}). Fresh login required.", e.getObtainedOn());
            logLoginNeeded();
            return;
        }
        try {
            kite.setAccessToken(e.getAccessToken());
            kite.setUserId(e.getUserId());
            // Verify via profile call
            kite.getProfile();
            authenticated = true;
            log.info("✅ Restored valid Kite access token for user {}", e.getUserId());
        } catch (Throwable ex) {
            log.warn("Persisted token failed verification: {}", ex.getMessage());
            logLoginNeeded();
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
        repo.save(new AccessTokenEntity(user.userId, user.accessToken, LocalDate.now()));
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

