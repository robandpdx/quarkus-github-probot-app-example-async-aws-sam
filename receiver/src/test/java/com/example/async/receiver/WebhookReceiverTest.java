package com.example.async.receiver;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.example.async.QueuedWebhook;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

@QuarkusTest
@GitHubAppTest
class WebhookReceiverTest {
    @InjectMock
    SqsClient sqs;

    @Inject
    ObjectMapper mapper;

    @Test
    void acknowledgesOnlyAfterQueueAcceptsTheEvent() throws IOException {
        Mockito.when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        UUID delivery = UUID.randomUUID();

        assertEquals(200, when().deliveryId(delivery).payloadFromClasspath("/issue-opened.json")
                .event(org.kohsuke.github.GHEvent.ISSUES).statusCode());

        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertEquals(new QueuedWebhook(delivery.toString(), 2311213, "octocat/Hello-World", 1),
                mapper.readValue(request.getValue().messageBody(), QueuedWebhook.class));
        verifyNoMoreInteractions(sqs);
    }

    @Test
    void doesNotAcknowledgeAQueueFailure() throws IOException {
        Mockito.when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SqsException.builder().message("Queue unavailable").statusCode(503).build());

        assertEquals(500, when().payloadFromClasspath("/issue-opened.json")
                .event(org.kohsuke.github.GHEvent.ISSUES, true).statusCode());
    }

    @Test
    void ignoresOtherActionsAndEventTypes() throws IOException {
        when().payloadFromClasspath("/issue-closed.json").event(org.kohsuke.github.GHEvent.ISSUES);
        when().payloadFromClasspath("/issue-opened.json").event(org.kohsuke.github.GHEvent.PULL_REQUEST);
        verifyNoInteractions(sqs);
    }
}
