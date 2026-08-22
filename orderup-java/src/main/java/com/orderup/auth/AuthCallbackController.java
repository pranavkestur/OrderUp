package com.orderup.auth;

import com.orderup.marketdata.InstrumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kite")
public class AuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(AuthCallbackController.class);

    private final KiteAuthService auth;
    private final InstrumentService instruments;

    public AuthCallbackController(KiteAuthService auth, InstrumentService instruments) {
        this.auth = auth;
        this.instruments = instruments;
    }

    /** Convenience redirect: hit /kite/login in a browser to start OAuth. */
    @GetMapping("/login")
    public String login() {
        return "<html><body>"
                + "<h2>OrderUp — Kite Login</h2>"
                + "<p><a href=\"" + auth.loginUrl() + "\">Click here to log in to Zerodha Kite</a></p>"
                + "</body></html>";
    }

    /** Kite redirects the user here with ?request_token=...&action=login&status=success */
    @GetMapping("/callback")
    public String callback(@RequestParam(value = "request_token", required = false) String requestToken,
                           @RequestParam(value = "status", required = false) String status) {
        if (requestToken == null || requestToken.isBlank()) {
            return "<html><body><h2>Missing request_token</h2><p>status=" + status + "</p></body></html>";
        }
        try {
            auth.completeLogin(requestToken);
            // Refresh instrument list once we're authenticated
            instruments.refreshInstruments();
            return "<html><body><h2>✅ Login successful</h2>"
                    + "<p>OrderUp is now authenticated. You can close this tab.</p></body></html>";
        } catch (Throwable e) {
            log.error("Login failed", e);
            return "<html><body><h2>❌ Login failed</h2><pre>" + e.getMessage() + "</pre></body></html>";
        }
    }
}

