package com.example.async.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@ExtendWith(MockitoExtension.class)
class DevQueuePollerTest {
    @Mock
    SqsClient sqs;
    @Mock
    IssueWorker worker;
    private DevQueuePoller poller;

    @BeforeEach
    void setUp() {
        poller = new DevQueuePoller(sqs, worker, "http://localhost:9324/queue/github-webhooks");
    }

    @Test
    void routesThroughWorkerAndDeletesOnlySuccessfulMessages() {
        messagesAvailable();
        when(worker.handleRequest(any(SQSEvent.class), isNull())).thenReturn(
                new SQSBatchResponse(List.of(new SQSBatchResponse.BatchItemFailure("retry"))));

        poller.poll();

        var event = ArgumentCaptor.forClass(SQSEvent.class);
        verify(worker).handleRequest(event.capture(), isNull());
        assertEquals(List.of("success", "retry"), event.getValue().getRecords().stream()
                .map(SQSEvent.SQSMessage::getMessageId).toList());
        assertEquals("success-body", event.getValue().getRecords().getFirst().getBody());
        var deletion = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(deletion.capture());
        assertEquals("success-receipt", deletion.getValue().receiptHandle());
    }

    @Test
    void transientReceiveFailureDoesNotPreventNextScheduledAttempt() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(SdkClientException.create("Unavailable"));

        assertDoesNotThrow(poller::poll);
        verifyNoInteractions(worker);

        messagesAvailable();
        when(worker.handleRequest(any(SQSEvent.class), isNull())).thenReturn(
                new SQSBatchResponse(List.of(new SQSBatchResponse.BatchItemFailure("retry"))));
        assertDoesNotThrow(poller::poll);
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    private void messagesAvailable() {
        doReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("success").body("success-body")
                                .receiptHandle("success-receipt").build(),
                        Message.builder().messageId("retry").body("retry-body").receiptHandle("retry-receipt").build())
                .build()).when(sqs).receiveMessage(any(ReceiveMessageRequest.class));
    }
}
