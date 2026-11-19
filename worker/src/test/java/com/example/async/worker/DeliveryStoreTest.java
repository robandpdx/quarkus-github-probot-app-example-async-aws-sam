package com.example.async.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@ExtendWith(MockitoExtension.class)
class DeliveryStoreTest {
    @Mock
    DynamoDbClient dynamo;
    private DeliveryStore store;

    @BeforeEach
    void setUp() {
        store = new DeliveryStore(dynamo, "deliveries");
    }

    @Test
    void acquiresConditionalLeaseLongerThanWorkerTimeout() {
        long before = Instant.now().getEpochSecond();

        String owner = store.acquire("delivery");

        var request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(request.capture());
        assertNotNull(owner);
        assertEquals(owner, request.getValue().item().get("owner").s());
        assertTrue(Long.parseLong(request.getValue().item().get("leaseUntil").n()) > before + 90);
        assertTrue(request.getValue().conditionExpression().contains("attribute_not_exists(deliveryId)"));
        assertTrue(request.getValue().conditionExpression().contains("leaseUntil <= :now"));
    }

    @Test
    void completedDeliveryIsSkippedAfterConsistentRead() {
        conditionalFailureWithState("COMPLETED");

        assertNull(store.acquire("delivery"));

        var request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo).getItem(request.capture());
        assertTrue(request.getValue().consistentRead());
    }

    @Test
    void competingActiveLeaseMustRetry() {
        conditionalFailureWithState("PROCESSING");

        assertThrows(IllegalStateException.class, () -> store.acquire("delivery"));
    }

    @Test
    void ownershipLossCannotCompleteOrDeleteAnotherWorkersClaim() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(conditionFailed());
        when(dynamo.deleteItem(any(DeleteItemRequest.class))).thenThrow(conditionFailed());

        assertThrows(ConditionalCheckFailedException.class, () -> store.complete("delivery", "old-owner"));
        assertDoesNotThrow(() -> store.release("delivery", "old-owner"));

        var completion = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(completion.capture());
        assertTrue(completion.getValue().conditionExpression().contains("#owner = :owner"));
        assertEquals("owner", completion.getValue().expressionAttributeNames().get("#owner"));
        assertEquals("old-owner", completion.getValue().expressionAttributeValues().get(":owner").s());
        long expiry = Long.parseLong(completion.getValue().expressionAttributeValues().get(":expiresAt").n());
        assertTrue(expiry >= Instant.now().getEpochSecond() + 6 * 24 * 60 * 60);
        var release = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo).deleteItem(release.capture());
        assertTrue(release.getValue().conditionExpression().contains("#owner = :owner"));
        assertEquals("old-owner", release.getValue().expressionAttributeValues().get(":owner").s());
    }

    private void conditionalFailureWithState(String state) {
        when(dynamo.putItem(any(PutItemRequest.class))).thenThrow(conditionFailed());
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("state", AttributeValue.builder().s(state).build())).build());
    }

    private static ConditionalCheckFailedException conditionFailed() {
        return ConditionalCheckFailedException.builder().message("Condition failed").build();
    }
}
