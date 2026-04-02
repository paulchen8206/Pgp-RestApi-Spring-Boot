package com.example.pgpclient.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

class ComposeStackIT {

    private static final String PROJECT_DIR = System.getProperty("user.dir");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void shouldReturnDecryptedEmployeeContentFromComposeStack() throws Exception {
        runCommand("docker compose down -v --remove-orphans");
        runCommand("docker compose up --build -d");

        String body = waitForSuccessfulEmployeeResponse();
        Map<String, Object> outerResponse = OBJECT_MAPPER.readValue(body, Map.class);
        String decryptedResponse = (String) outerResponse.get("decryptedResponse");
        Map<String, Object> decryptedJson = OBJECT_MAPPER.readValue(decryptedResponse, Map.class);
        Map<String, Object> employee = (Map<String, Object>) decryptedJson.get("employee");
        Map<String, Object> encryptionInTransit = (Map<String, Object>) decryptedJson.get("encryptionInTransit");

        Assertions.assertEquals("E1002", decryptedJson.get("employeeId"), "employeeId mismatch");
        Assertions.assertEquals("987-65-4321", employee.get("ssn"), "ssn mismatch");
        Assertions.assertEquals(136500, ((Number) employee.get("salary")).intValue(), "salary mismatch");
        Assertions.assertEquals("PGP", encryptionInTransit.get("payload"), "PGP transit marker missing");
    }

    @AfterAll
    static void cleanup() throws Exception {
        runCommand("docker compose down -v --remove-orphans");
    }

    private static String waitForSuccessfulEmployeeResponse() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(4).toMillis();
        Exception lastException = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:18080/api/employee?employeeId=E1002"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                lastException = new IllegalStateException("Unexpected status code: " + response.statusCode() + " body=" + response.body());
            } catch (Exception ex) {
                lastException = ex;
            }

            Thread.sleep(2000);
        }

        String diagnostics = runCommandAllowFailure("docker compose ps -a && docker compose logs --no-color --tail=120");
        throw new IllegalStateException(
            "Service did not return successful response within timeout. Diagnostics:\n" + diagnostics,
            lastException
        );
    }

    private static void runCommand(String command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", command)
                .directory(new java.io.File(PROJECT_DIR))
                .redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("Command failed (" + code + "): " + command + System.lineSeparator() + output);
        }
    }

    private static String runCommandAllowFailure(String command) {
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command)
                    .directory(new java.io.File(PROJECT_DIR))
                    .redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            process.waitFor();
            return output;
        } catch (Exception ex) {
            return "Failed to collect diagnostics: " + ex.getMessage();
        }
    }
}
