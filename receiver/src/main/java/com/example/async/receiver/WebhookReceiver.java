package com.example.async.receiver;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.example.async.QueuedWebhook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.GitHubEvent;
import io.vertx.core.json.JsonObject;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Singleton
public class WebhookReceiver {
    private static final Logger LOG = Logger.getLogger(WebhookReceiver.class);

    @Inject
    SqsClient sqs;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "app.queue-url")
    String queueUrl;

    void receive(@Observes GitHubEvent event) throws JsonProcessingException {
        if (!"issues".equals(event.getEvent()) || !"opened".equals(event.getAction())) {
            return;
        }
        try {
            JsonObject issue = event.getParsedPayload().getJsonObject("issue");
            Long installationId = event.getInstallationId();
            if (installationId == null || issue == null || issue.getInteger("number") == null) {
                throw new IllegalArgumentException("Issue webhook is missing installation or issue details");
            }
            QueuedWebhook job = new QueuedWebhook(event.getDeliveryId(), installationId,
                    event.getRepositoryOrThrow(), issue.getInteger("number"));
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                    .messageBody(mapper.writeValueAsString(job)).build());
            LOG.infof("Queued GitHub delivery %s for %s#%d", job.deliveryId(), job.repository(), job.issueNumber());
        } catch (SdkException | IllegalArgumentException | JsonProcessingException e) {
            LOG.errorf(e, "Unable to enqueue GitHub delivery %s", event.getDeliveryId());
            throw e;
        }
    }
}
