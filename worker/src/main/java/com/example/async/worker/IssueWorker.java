package com.example.async.worker;

import java.io.IOException;
import java.util.ArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.jboss.logging.Logger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.example.async.QueuedWebhook;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;
import software.amazon.awssdk.core.exception.SdkException;

@Named("issue-worker")
@ApplicationScoped
@RegisterForReflection(targets = QueuedWebhook.class)
public class IssueWorker implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger LOG = Logger.getLogger(IssueWorker.class);

    private final ObjectMapper mapper;
    private final CommentService comments;

    @Inject
    public IssueWorker(ObjectMapper mapper, CommentService comments) {
        this.mapper = mapper;
        this.comments = comments;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            throw new IllegalArgumentException("SQS event records are required");
        }
        // Validate the entire batch before performing side effects: an unidentified failure cannot be retried selectively.
        for (var record : event.getRecords()) {
            if (record == null || record.getMessageId() == null || record.getMessageId().isBlank()) {
                throw new IllegalArgumentException("Every SQS record must have a nonblank message ID");
            }
        }

        var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
        for (var record : event.getRecords()) {
            QueuedWebhook webhook = null;
            try {
                webhook = mapper.readValue(record.getBody(), QueuedWebhook.class);
                if (webhook == null) {
                    throw new IllegalArgumentException("Queued webhook must not be null");
                }
                comments.process(webhook);
            } catch (IOException | SdkException | IllegalArgumentException | IllegalStateException failure) {
                // Do not log exception messages or JSON bodies: parse and HTTP errors can contain sensitive input.
                LOG.errorf("SQS message %s delivery %s failed (%s); leaving it for retry",
                        record.getMessageId(), webhook == null ? "unknown" : webhook.deliveryId(),
                        failure.getClass().getSimpleName());
                failures.add(new SQSBatchResponse.BatchItemFailure(record.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }
}
