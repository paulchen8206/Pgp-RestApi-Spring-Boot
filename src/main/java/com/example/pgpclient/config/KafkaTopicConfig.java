package com.example.pgpclient.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic employeeDecryptedTopic(KafkaTopicsProperties topics) {
        return TopicBuilder.name(topics.employeeDecrypted())
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic employeeEncryptedTopic(KafkaTopicsProperties topics) {
        return TopicBuilder.name(topics.employeeEncrypted())
                .partitions(1)
                .replicas(1)
                .build();
    }
}
