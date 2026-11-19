package com.example.async;

import java.util.UUID;

public record QueuedWebhook(String deliveryId, long installationId, String repository, int issueNumber) {
    public QueuedWebhook {
        UUID.fromString(deliveryId);
        if (installationId <= 0 || issueNumber <= 0 || repository == null
                || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid GitHub issue webhook");
        }
    }
}
