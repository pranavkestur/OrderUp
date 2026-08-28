package com.orderup.pnl;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
public interface ExitPerformanceRepository extends JpaRepository<ExitPerformance, Long> {
    List<ExitPerformance> findByExitAtAfter(Instant after);
}
