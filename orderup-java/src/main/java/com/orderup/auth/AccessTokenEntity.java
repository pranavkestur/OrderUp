package com.orderup.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.LocalDate;

@Entity
public class AccessTokenEntity {

    @Id
    private String userId;
    private String accessToken;
    private LocalDate obtainedOn;

    public AccessTokenEntity() {}

    public AccessTokenEntity(String userId, String accessToken, LocalDate obtainedOn) {
        this.userId = userId;
        this.accessToken = accessToken;
        this.obtainedOn = obtainedOn;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public LocalDate getObtainedOn() { return obtainedOn; }
    public void setObtainedOn(LocalDate obtainedOn) { this.obtainedOn = obtainedOn; }
}

