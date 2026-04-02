package com.example.pgpclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.tls")
public record TlsProperties(
        String keyStorePath,
        String keyStorePassword,
        String keyStoreType,
        String trustStorePath,
        String trustStorePassword,
        String trustStoreType
) {
}
