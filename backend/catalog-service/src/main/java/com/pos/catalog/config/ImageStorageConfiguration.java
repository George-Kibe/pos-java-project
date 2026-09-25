package com.pos.catalog.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** The S3 client for product images. */
@Configuration
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client imageStorageClient(ImageStorageProperties properties) {
        AwsCredentialsProvider credentials =
                properties.getAccessKey().isBlank()
                        ? DefaultCredentialsProvider.builder().build()
                        : StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        properties.getAccessKey(), properties.getSecretKey()));
        var builder =
                S3Client.builder()
                        .region(Region.of(properties.getRegion()))
                        .credentialsProvider(credentials)
                        .httpClient(UrlConnectionHttpClient.create())
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(properties.isPathStyle())
                                        .build());
        if (!properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }
}
