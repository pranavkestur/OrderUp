package com.orderup.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessTokenRepository extends JpaRepository<AccessTokenEntity, String> {
}

