package com.example.pgpclient.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.pgpclient.config.RemoteApiProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RemotePgpApiService {

    private final HttpClient httpClient;
    private final RemoteApiProperties remoteApiProperties;
    private final PgpCryptoService pgpCryptoService;
    private final KafkaEmployeePublisher kafkaEmployeePublisher;
    private final ObjectMapper objectMapper;

    public RemotePgpApiService(HttpClient remoteHttpClient,
                               RemoteApiProperties remoteApiProperties,
                               PgpCryptoService pgpCryptoService,
                               KafkaEmployeePublisher kafkaEmployeePublisher,
                               ObjectMapper objectMapper) {
        this.httpClient = remoteHttpClient;
        this.remoteApiProperties = remoteApiProperties;
        this.pgpCryptoService = pgpCryptoService;
        this.kafkaEmployeePublisher = kafkaEmployeePublisher;
        this.objectMapper = objectMapper;
    }

    public String requestData(String employeeId) {
        String requestPayload = toRequestJson(employeeId);
        String requestSignature = pgpCryptoService.signPayload(requestPayload);
        String plainRequestEnvelope = toEnvelopeJson(requestPayload, requestSignature);
        String encryptedRequest = pgpCryptoService.encryptForServer(plainRequestEnvelope);

        Map<String, Object> response = postEncryptedRequest(encryptedRequest);

        if (response == null || !response.containsKey("encryptedData")) {
            throw new IllegalStateException("Missing encryptedData in response");
        }

        Object encryptedResponseValue = response.get("encryptedData");
        if (!(encryptedResponseValue instanceof String encryptedResponse)) {
            throw new IllegalStateException("encryptedData in response must be a string");
        }
        String decryptedEnvelope = pgpCryptoService.decryptFromServer(encryptedResponse);
        String decryptedPayload = verifyAndExtractPayload(decryptedEnvelope);
        kafkaEmployeePublisher.publish(employeeId, decryptedPayload);
        return decryptedPayload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postEncryptedRequest(String encryptedRequest) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("encryptedData", encryptedRequest));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(remoteApiProperties.baseUrl() + "/pgp/data"))
                    .timeout(Duration.ofMillis(remoteApiProperties.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Remote API returned status " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to call remote PGP API", ex);
        }
    }

    private String toRequestJson(String employeeId) {
        try {
            return objectMapper.writeValueAsString(Map.of("employeeId", employeeId));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to build request payload", ex);
        }
    }

    private String toEnvelopeJson(String payload, String signature) {
        try {
            Map<String, String> envelope = new LinkedHashMap<>();
            envelope.put("payload", payload);
            envelope.put("signature", signature);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to build signed envelope", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private String verifyAndExtractPayload(String decryptedEnvelope) {
        try {
            Map<String, String> envelope = objectMapper.readValue(decryptedEnvelope, Map.class);
            String payload = envelope.get("payload");
            String signature = envelope.get("signature");
            if (payload == null || signature == null) {
                throw new IllegalStateException("Missing payload/signature in response envelope");
            }

            pgpCryptoService.verifyServerSignature(payload, signature);
            return payload;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse decrypted envelope", ex);
        }
    }
}
