package com.orderup.auth;

import com.orderup.config.KiteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File-based shared access-token store. When {@code kite.token-store} is
 * configured, every app in the OrderUp monorepo (WACE scanner, Chartink
 * webhook, future) reads/writes the same JSON file so a single Kite login
 * covers whichever app is currently running.
 *
 * <p>Layout (JSON, one row, atomic {@code .tmp} + rename write):
 * <pre>
 *   {"userId":"AB1234","accessToken":"…","obtainedOn":"2026-08-23"}
 * </pre>
 *
 * <p>Deliberately hand-rolled parsing (no Jackson dep required from callers).
 * On any IO/parse failure we degrade to "no token" so callers fall back to the
 * JPA store or a fresh login — never a hard failure.
 *
 * <p>Enabled iff {@code kite.token-store} is non-blank. Bean is conditionally
 * omitted otherwise; {@link KiteAuthService} handles the absence gracefully.
 */
@Component
@ConditionalOnProperty(prefix = "kite", name = "token-store")
public class FileAccessTokenStore {

    private static final Logger log = LoggerFactory.getLogger(FileAccessTokenStore.class);

    private final Path path;

    public FileAccessTokenStore(KiteProperties props) {
        this.path = Paths.get(props.tokenStore());
    }

    /** Immutable snapshot; obtainedOn may be {@link LocalDate#MIN} if legacy row. */
    public record Token(String userId, String accessToken, LocalDate obtainedOn) {}

    public Optional<Token> read() {
        if (!Files.isReadable(path)) return Optional.empty();
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String userId = matchField(raw, "userId");
            String accessToken = matchField(raw, "accessToken");
            String obtainedOn = matchField(raw, "obtainedOn");
            if (userId == null || accessToken == null) return Optional.empty();
            LocalDate day = obtainedOn == null ? LocalDate.MIN : LocalDate.parse(obtainedOn);
            return Optional.of(new Token(userId, accessToken, day));
        } catch (Exception e) {
            log.warn("Failed to read kite token file {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public void write(Token t) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            String json = "{"
                    + "\"userId\":"      + quote(t.userId())      + ","
                    + "\"accessToken\":" + quote(t.accessToken()) + ","
                    + "\"obtainedOn\":"  + quote(t.obtainedOn().toString())
                    + "}";
            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Wrote shared kite token to {} (obtainedOn={})", path, t.obtainedOn());
        } catch (IOException e) {
            log.warn("Failed to write kite token file {}: {}", path, e.getMessage());
        }
    }

    private static String matchField(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

