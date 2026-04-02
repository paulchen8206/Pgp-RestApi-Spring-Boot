package com.example.pgpclient.service;

import com.example.pgpclient.config.KafkaTopicsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KafkaEmployeePublisher {

        private static final Schema DECRYPTED_EVENT_SCHEMA = new Schema.Parser().parse("""
                        {
                            "type": "record",
                            "name": "EmployeeDecryptedEvent",
                            "namespace": "com.example.pgpclient.avro",
                            "fields": [
                                {"name": "employeeId", "type": "string"},
                                {"name": "payload", "type": "string"},
                                {"name": "eventType", "type": "string"},
                                {"name": "eventVersion", "type": "string"}
                            ]
                        }
                        """);

        private static final Schema ENCRYPTED_EVENT_SCHEMA = new Schema.Parser().parse("""
                        {
                            "type": "record",
                            "name": "EmployeeEncryptedEvent",
                            "namespace": "com.example.pgpclient.avro",
                            "fields": [
                                {"name": "employeeId", "type": "string"},
                                {"name": "payload", "type": "string"},
                                {"name": "eventType", "type": "string"},
                                {"name": "eventVersion", "type": "string"}
                            ]
                        }
                        """);

    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

        private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicsProperties kafkaTopicsProperties;
    private final PgpCryptoService pgpCryptoService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public KafkaEmployeePublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                  KafkaTopicsProperties kafkaTopicsProperties,
                                  PgpCryptoService pgpCryptoService,
                                  ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicsProperties = kafkaTopicsProperties;
        this.pgpCryptoService = pgpCryptoService;
        this.objectMapper = objectMapper;
    }

    public void publish(String employeeId, String decryptedPayload) {
        String envelopeEncryptedPayload = envelopeEncryptEmployeeEvent(decryptedPayload);
        GenericRecord encryptedRecord = new GenericData.Record(ENCRYPTED_EVENT_SCHEMA);
        encryptedRecord.put("employeeId", employeeId);
        encryptedRecord.put("payload", envelopeEncryptedPayload);
        encryptedRecord.put("eventType", "employee.encrypted");
        encryptedRecord.put("eventVersion", "v1");

        GenericRecord decryptedRecord = new GenericData.Record(DECRYPTED_EVENT_SCHEMA);
        decryptedRecord.put("employeeId", employeeId);
        decryptedRecord.put("payload", decryptedPayload);
        decryptedRecord.put("eventType", "employee.decrypted");
        decryptedRecord.put("eventVersion", "v1");

        kafkaTemplate.send(kafkaTopicsProperties.employeeEncrypted(), employeeId, encryptedRecord).join();
        kafkaTemplate.send(kafkaTopicsProperties.employeeDecrypted(), employeeId, decryptedRecord).join();
    }

    @SuppressWarnings("unchecked")
    private String envelopeEncryptEmployeeEvent(String decryptedPayload) {
        try {
            Map<String, Object> event = objectMapper.readValue(decryptedPayload, new TypeReference<>() {
            });
            Object employeeObj = event.get("employee");
            if (!(employeeObj instanceof Map<?, ?> employeeMapRaw)) {
                throw new IllegalStateException("employee object not found in decrypted payload");
            }

            Map<String, Object> employee = (Map<String, Object>) employeeMapRaw;
            String ssn = asRequiredString(employee.get("ssn"), "employee.ssn");
            String salary = asRequiredString(employee.get("salary"), "employee.salary");

            byte[] dek = new byte[AES_KEY_BYTES];
            secureRandom.nextBytes(dek);
            SecretKeySpec key = new SecretKeySpec(dek, "AES");

            employee.put("ssn", encryptField(ssn, key));
            employee.put("salary", encryptField(salary, key));

            String encryptedDataKey = pgpCryptoService.encryptForServer(Base64.getEncoder().encodeToString(dek));

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("event", event);
            envelope.put("encryption", Map.of(
                    "type", "envelope",
                    "keyEncryption", "PGP",
                    "contentEncryption", "AES/GCM/NoPadding",
                    "encryptedDataKey", encryptedDataKey,
                    "encryptedFields", List.of("employee.ssn", "employee.salary")
            ));

            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse decrypted payload for envelope encryption", ex);
        }
    }

    private Map<String, String> encryptField(String value, SecretKeySpec key) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            Map<String, String> encryptedField = new LinkedHashMap<>();
            encryptedField.put("alg", "AES/GCM/NoPadding");
            encryptedField.put("iv", Base64.getEncoder().encodeToString(iv));
            encryptedField.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            return encryptedField;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt sensitive field", ex);
        }
    }

    private String asRequiredString(Object value, String fieldPath) {
        if (value == null) {
            throw new IllegalStateException(fieldPath + " is required in decrypted payload");
        }
        return String.valueOf(value);
    }
}
