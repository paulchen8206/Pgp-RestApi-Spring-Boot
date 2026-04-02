package com.example.pgpclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.streaming")
public record StreamingProperties(
        boolean enabled,
        String master,
        String appName,
        String checkpointLocation,
        long triggerIntervalSeconds,
        long rowsPerSecond,
        List<String> employeeIds
) {
}