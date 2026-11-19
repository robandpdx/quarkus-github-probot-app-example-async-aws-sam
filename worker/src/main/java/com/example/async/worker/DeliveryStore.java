package com.example.async.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@ApplicationScoped
public class DeliveryStore {
    private static final Logger LOG = Logger.getLogger(DeliveryStore.class);
    private static final long LEASE_SECONDS = Duration.ofSeconds(120).toSeconds();
    private static final long RETENTION_SECONDS = Duration.ofDays(7).toSeconds();
    private static final String OWNED_PROCESSING = "#owner = :owner AND #state = :processing";

    private final DynamoDbClient dynamo;
    private final String table;

    @Inject
    public DeliveryStore(DynamoDbClient dynamo, @ConfigProperty(name = "app.delivery-table") String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    public String acquire(String deliveryId) {
        long now = Instant.now().getEpochSecond();
        String owner = UUID.randomUUID().toString();
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(Map.of(
                            "deliveryId", string(deliveryId),
                            "state", string("PROCESSING"),
                            "owner", string(owner),
                            "leaseUntil", number(now + LEASE_SECONDS),
                            "expiresAt", number(now + RETENTION_SECONDS)))
                    .conditionExpression("attribute_not_exists(deliveryId) OR (#state = :processing AND leaseUntil <= :now)")
                    .expressionAttributeNames(Map.of("#state", "state"))
                    .expressionAttributeValues(Map.of(":processing", string("PROCESSING"), ":now", number(now)))
                    .build());
            return owner;
        } catch (ConditionalCheckFailedException contention) {
            var item = dynamo.getItem(GetItemRequest.builder()
                    .tableName(table)
                    .key(key(deliveryId))
                    .consistentRead(true)
                    .build()).item();
            if (string("COMPLETED").equals(item.get("state"))) {
                return null;
            }
            throw new IllegalStateException("Delivery is leased by another worker; retry required", contention);
        }
    }

    public void complete(String deliveryId, String owner) {
        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(table)
                .key(key(deliveryId))
                .conditionExpression(OWNED_PROCESSING)
                .updateExpression("SET #state = :completed, expiresAt = :expiresAt REMOVE #owner, leaseUntil")
                .expressionAttributeNames(Map.of("#state", "state", "#owner", "owner"))
                .expressionAttributeValues(Map.of(
                        ":owner", string(owner),
                        ":processing", string("PROCESSING"),
                        ":completed", string("COMPLETED"),
                        ":expiresAt", number(Instant.now().getEpochSecond() + RETENTION_SECONDS)))
                .build());
    }

    public void release(String deliveryId, String owner) {
        try {
            dynamo.deleteItem(DeleteItemRequest.builder()
                    .tableName(table)
                    .key(key(deliveryId))
                    .conditionExpression(OWNED_PROCESSING)
                    .expressionAttributeNames(Map.of("#state", "state", "#owner", "owner"))
                    .expressionAttributeValues(Map.of(":owner", string(owner), ":processing", string("PROCESSING")))
                    .build());
        } catch (ConditionalCheckFailedException lostOwnership) {
            LOG.warnf("Not releasing delivery %s: lease no longer belongs to this worker", deliveryId);
        }
    }

    private static Map<String, AttributeValue> key(String deliveryId) {
        return Map.of("deliveryId", string(deliveryId));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
