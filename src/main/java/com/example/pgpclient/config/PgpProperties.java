package com.example.pgpclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pgp")
public record PgpProperties(
        String clientPrivateKeyPath,
        String clientPublicKeyPath,
        String serverPublicKeyPath,
        String serverPrivateKeyPath
) {
}
