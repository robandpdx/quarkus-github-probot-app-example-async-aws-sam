package com.example.async;

import java.net.URI;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.LaunchMode;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@ApplicationScoped
public class AwsClients {
    @ConfigProperty(name = "app.aws.region", defaultValue = "${AWS_REGION:us-west-2}")
    String region;

    @ConfigProperty(name = "app.aws.sqs-endpoint")
    Optional<URI> sqsEndpoint;

    @ConfigProperty(name = "app.aws.dynamodb-endpoint")
    Optional<URI> dynamodbEndpoint;

    @Produces
    @ApplicationScoped
    SqsClient sqs() {
        return configure(SqsClient.builder(), sqsEndpoint).build();
    }

    @Produces
    @ApplicationScoped
    DynamoDbClient dynamodb() {
        return configure(DynamoDbClient.builder(), dynamodbEndpoint).build();
    }

    private <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> B configure(B builder,
            Optional<URI> endpoint) {
        builder.region(Region.of(region)).httpClientBuilder(UrlConnectionHttpClient.builder());
        endpoint.ifPresent(builder::endpointOverride);
        if (LaunchMode.current().isDevOrTest() && endpoint.isPresent()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder;
    }

    void closeSqs(@Disposes SqsClient client) {
        client.close();
    }

    void closeDynamodb(@Disposes DynamoDbClient client) {
        client.close();
    }
}
