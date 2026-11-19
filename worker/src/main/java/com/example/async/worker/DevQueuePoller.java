package com.example.async.worker;

import java.util.HashSet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.scheduler.Scheduled;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@IfBuildProfile("dev")
@ApplicationScoped
public class DevQueuePoller {
    private static final Logger LOG = Logger.getLogger(DevQueuePoller.class);

    private final SqsClient sqs;
    private final IssueWorker worker;
    private final String queueUrl;

    @Inject
    public DevQueuePoller(SqsClient sqs, IssueWorker worker, @ConfigProperty(name = "app.queue-url") String queueUrl) {
        this.sqs = sqs;
        this.worker = worker;
        this.queueUrl = queueUrl;
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        try {
            var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(0).build()).messages();
            if (messages.isEmpty()) {
                return;
            }

            var event = new SQSEvent();
            event.setRecords(messages.stream().map(message -> {
                var record = new SQSEvent.SQSMessage();
                record.setMessageId(message.messageId());
                record.setBody(message.body());
                return record;
            }).toList());
            var failures = new HashSet<String>();
            worker.handleRequest(event, null).getBatchItemFailures()
                    .forEach(failure -> failures.add(failure.getItemIdentifier()));
            for (var message : messages) {
                if (!failures.contains(message.messageId())) {
                    try {
                        sqs.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
                    } catch (SdkException failure) {
                        LOG.warnf("Dev queue delete failed for message %s (%s); it will be redelivered",
                                message.messageId(), failure.getClass().getSimpleName());
                    }
                }
            }
        } catch (SdkException failure) {
            LOG.warnf("Dev queue receive failed (%s); retrying on the next poll", failure.getClass().getSimpleName());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.errorf("Dev queue batch invocation failed (%s); no remaining messages acknowledged",
                    failure.getClass().getSimpleName());
        }
    }
}
