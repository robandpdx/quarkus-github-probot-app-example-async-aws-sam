package com.example.async.worker;

import java.io.IOException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.example.async.QueuedWebhook;

import io.quarkiverse.githubapp.GitHubClientProvider;
import software.amazon.awssdk.core.exception.SdkException;

@ApplicationScoped
public class CommentService {
    private static final Logger LOG = Logger.getLogger(CommentService.class);

    private final DeliveryStore deliveries;
    private final GitHubClientProvider clients;

    @Inject
    public CommentService(DeliveryStore deliveries, GitHubClientProvider clients) {
        this.deliveries = deliveries;
        this.clients = clients;
    }

    public void process(QueuedWebhook webhook) throws IOException {
        String owner = deliveries.acquire(webhook.deliveryId());
        if (owner == null) {
            LOG.infof("Skipping completed delivery %s", webhook.deliveryId());
            return;
        }

        try {
            var issue = clients.getInstallationClient(webhook.installationId())
                    .getRepository(webhook.repository())
                    .getIssue(webhook.issueNumber());
            String marker = "<!-- github-app-delivery:" + webhook.deliveryId() + " -->";
            boolean alreadyPosted = false;
            for (var comment : issue.listComments()) {
                if (comment.getBody() != null && comment.getBody().contains(marker)) {
                    alreadyPosted = true;
                    break;
                }
            }
            // Recover a successful GitHub write even if the previous worker died before completing its lease.
            if (!alreadyPosted) {
                issue.comment("Hello word!\n\n" + marker);
            }
            deliveries.complete(webhook.deliveryId(), owner);
            LOG.infof("Completed delivery %s for %s#%d%s", webhook.deliveryId(), webhook.repository(),
                    webhook.issueNumber(), alreadyPosted ? " (existing comment)" : "");
        } catch (IOException | SdkException | IllegalArgumentException | IllegalStateException failure) {
            LOG.errorf(failure, "Comment processing failed for GitHub delivery %s", webhook.deliveryId());
            try {
                deliveries.release(webhook.deliveryId(), owner);
            } catch (SdkException | IllegalStateException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                LOG.warnf("Could not release delivery %s (%s); lease will expire", webhook.deliveryId(),
                        cleanupFailure.getClass().getSimpleName());
            }
            throw failure;
        }
    }
}
