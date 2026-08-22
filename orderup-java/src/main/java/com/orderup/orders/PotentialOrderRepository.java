package com.orderup.orders;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PotentialOrderRepository extends JpaRepository<PotentialOrder, Long> {
}

