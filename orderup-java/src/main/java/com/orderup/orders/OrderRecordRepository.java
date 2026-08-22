package com.orderup.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {
    List<OrderRecord> findByPlacedAtAfterOrderByPlacedAtDesc(Instant after);
}

