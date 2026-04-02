package com.example.pgpclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "remote")
public record RemoteApiProperties(String baseUrl, int timeoutMs) {
}
