package com.orderup.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WatchlistCandidateRepository extends JpaRepository<WatchlistCandidate, Long> {

    /** All rows for a symbol+side, most recent first — used to detect an existing row for today. */
    List<WatchlistCandidate> findBySymbolAndSideOrderByAddedAtDesc(String symbol, String side);

    /** All rows added within a time range — used by the /watchlist read endpoint. */
    List<WatchlistCandidate> findByAddedAtBetweenOrderByAddedAtDesc(Instant from, Instant to);

    /** Convenience: latest row for a symbol+side. */
    default Optional<WatchlistCandidate> latest(String symbol, String side) {
        List<WatchlistCandidate> rows = findBySymbolAndSideOrderByAddedAtDesc(symbol, side);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}

