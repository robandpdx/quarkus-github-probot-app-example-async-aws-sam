package com.example.async.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.example.async.QueuedWebhook;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IssueWorkerTest {
    private static final QueuedWebhook WEBHOOK = new QueuedWebhook(
            "c656a55e-055a-4f4f-894b-ab900aa57b37", 123, "octo/example", 42);

    @Mock
    CommentService comments;
    private final ObjectMapper mapper = new ObjectMapper();
    private IssueWorker worker;

    @BeforeEach
    void setUp() {
        worker = new IssueWorker(mapper, comments);
    }

    @Test
    void successfulRecordIsAcknowledged() throws Exception {
        var response = worker.handleRequest(event(message("success", mapper.writeValueAsString(WEBHOOK))), null);

        assertEquals(List.of(), response.getBatchItemFailures());
        verify(comments).process(WEBHOOK);
    }

    @Test
    void onlyFailedRecordsAreRetriedAndLaterRecordsStillRun() throws Exception {
        var failedWebhook = new QueuedWebhook("f295c128-c42f-463a-a759-e2957faac7fe", 123, "octo/example", 43);
        doThrow(new IOException("GitHub unavailable")).when(comments).process(failedWebhook);

        var response = worker.handleRequest(event(
                message("github-error", mapper.writeValueAsString(failedWebhook)),
                message("invalid-json", "{bad"),
                message("json-null", "null"),
                message("success", mapper.writeValueAsString(WEBHOOK))), null);

        assertEquals(List.of("github-error", "invalid-json", "json-null"), response.getBatchItemFailures().stream()
                .map(failure -> failure.getItemIdentifier()).toList());
        verify(comments).process(WEBHOOK);
    }

    @Test
    void rejectsUnidentifiableRecordsBeforeAnySideEffects() throws Exception {
        for (String id : Arrays.asList(null, "", " \t")) {
            assertThrows(IllegalArgumentException.class, () -> worker.handleRequest(event(
                    message("valid", mapper.writeValueAsString(WEBHOOK)), message(id, "{}")), null));
        }
        assertThrows(IllegalArgumentException.class, () -> worker.handleRequest(event((SQSEvent.SQSMessage) null), null));
        verifyNoInteractions(comments);
    }

    private static SQSEvent.SQSMessage message(String id, String body) {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId(id);
        message.setBody(body);
        return message;
    }

    private static SQSEvent event(SQSEvent.SQSMessage... messages) {
        var event = new SQSEvent();
        event.setRecords(Arrays.asList(messages));
        return event;
    }
}
