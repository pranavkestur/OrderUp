package com.orderup.pnl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistent lookup for {@link PriceCacheEntry}. Range queries used by the
 * price-journey drawer; the top-by-date query drives "do we need to fetch
 * fresh data?" decisions in {@link PriceHistoryService}.
 */
public interface PriceCacheEntryRepository extends JpaRepository<PriceCacheEntry, String> {

    List<PriceCacheEntry> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
            String symbol, LocalDate from, LocalDate to);

    Optional<PriceCacheEntry> findTopBySymbolOrderByTradeDateDesc(String symbol);
}

