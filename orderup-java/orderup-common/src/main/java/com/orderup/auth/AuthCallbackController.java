package com.orderup.auth;

import com.orderup.marketdata.InstrumentService;
import jakarta.servlet.http.HttpServletResponse;
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

    /**
     * Mobile-friendly entry page. This is the URL we push to Telegram — tapping it
     * from a phone (a) primes the ngrok-free browser-warning cookie by loading a
     * page from our origin first, and (b) meta-refresh redirects to Kite's OAuth.
     * When Kite bounces back with {@code request_token}, the browser already has
     * the ngrok skip cookie so the callback hits {@link #callback} without any
     * interstitial in the middle.
     *
     * <p>Explicitly setting the {@code abuse_interstitial} skip cookie on this
     * response is what makes subsequent same-origin requests from Kite's redirect
     * chain go straight through the tunnel.
     */
    @GetMapping("/start")
    public String start(HttpServletResponse resp) {
        // ngrok-free honours this cookie/query-param to suppress the "You are about
        // to visit …" warning. Setting it here means the callback (same origin,
        // hit by Kite's 302) inherits it.
        resp.addHeader("Set-Cookie",
                "abuse_interstitial=marbles-pamphlet-wages.ngrok-free.dev; Path=/; Max-Age=86400; SameSite=Lax");
        String kiteUrl = auth.loginUrl();
        // Small landing page so the user sees "opening Zerodha…" instead of a
        // silent redirect, and so any accidental back-button lands somewhere sane.
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta http-equiv=\"refresh\" content=\"1;url=" + kiteUrl + "\">"
                + "<title>OrderUp — Kite login</title></head>"
                + "<body style=\"font-family:-apple-system,system-ui,sans-serif;padding:24px;max-width:520px;margin:0 auto\">"
                + "<h2>🔐 Opening Zerodha login…</h2>"
                + "<p>If nothing happens, <a href=\"" + kiteUrl + "\">tap here</a>.</p>"
                + "<p style=\"color:#888;font-size:12px\">After you log in, Zerodha will redirect back to OrderUp automatically and today's access token will be saved.</p>"
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
            return "<html><body style=\"font-family:-apple-system,system-ui,sans-serif;padding:24px;max-width:520px;margin:0 auto\">"
                    + "<h2>✅ Login successful</h2>"
                    + "<p>OrderUp is now authenticated. You can close this tab.</p></body></html>";
        } catch (Throwable e) {
            log.error("Login failed", e);
            return "<html><body><h2>❌ Login failed</h2><pre>" + e.getMessage() + "</pre></body></html>";
        }
    }

    /**
     * Push the current Kite login URL to Telegram on demand. Handy for a bookmarked
     * curl or a phone shortcut: {@code curl -X POST http://<host>/kite/send-login}.
     * Response says whether Telegram is configured and whether the message was
     * actually sent (may be suppressed by the cooldown in
     * {@link KiteAuthService#pushLoginLinkToTelegram(String)}).
     */
    @PostMapping({"/send-login", "/notify-login"})
    public java.util.Map<String, Object> sendLogin() {
        // force=true — user explicitly asked, so bypass the anti-spam cooldown.
        boolean sent = auth.pushLoginLinkToTelegram("🔐 On-demand Kite login link:", true);
        return java.util.Map.of(
                "sent", sent,
                "authenticated", auth.isAuthenticated(),
                "loginUrl", auth.loginUrl()
        );
    }
}

