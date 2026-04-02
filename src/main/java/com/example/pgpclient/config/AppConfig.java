package com.example.pgpclient.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
    RemoteApiProperties.class,
    TlsProperties.class,
    PgpProperties.class,
    KafkaTopicsProperties.class,
    StreamingProperties.class
})
public class AppConfig {

    @Bean
    public HttpClient remoteHttpClient(RemoteApiProperties remoteApiProperties, TlsProperties tlsProperties) throws Exception {
        SSLContext sslContext = buildSslContext(tlsProperties);
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofMillis(remoteApiProperties.timeoutMs()))
                .build();
    }

    private SSLContext buildSslContext(TlsProperties tlsProperties) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(tlsProperties.keyStoreType());
        try (FileInputStream keyStoreInput = new FileInputStream(tlsProperties.keyStorePath())) {
            keyStore.load(keyStoreInput, tlsProperties.keyStorePassword().toCharArray());
        }

        KeyStore trustStore = KeyStore.getInstance(tlsProperties.trustStoreType());
        try (FileInputStream trustStoreInput = new FileInputStream(tlsProperties.trustStorePath())) {
            trustStore.load(trustStoreInput, tlsProperties.trustStorePassword().toCharArray());
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, tlsProperties.keyStorePassword().toCharArray());

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
        return sslContext;
    }
}
