package com.bookmyseat.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Values from the 'app.security' block of application.yml. */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(String jwtSecret, String adminApiKey, Duration tokenTtl) {
}
