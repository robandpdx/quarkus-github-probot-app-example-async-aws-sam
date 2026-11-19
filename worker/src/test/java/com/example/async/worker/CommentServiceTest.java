package com.example.async.worker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.PagedIterator;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.async.QueuedWebhook;

import io.quarkiverse.githubapp.GitHubClientProvider;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {
    private static final QueuedWebhook WEBHOOK = new QueuedWebhook(
            "c656a55e-055a-4f4f-894b-ab900aa57b37", 123, "octo/example", 42);
    private static final String MARKER = "<!-- github-app-delivery:" + WEBHOOK.deliveryId() + " -->";

    @Mock
    DeliveryStore deliveries;
    @Mock
    GitHubClientProvider clients;
    @Mock
    GitHub github;
    @Mock
    GHRepository repository;
    @Mock
    GHIssue issue;
    @Mock
    PagedIterable<GHIssueComment> comments;
    @Mock
    PagedIterator<GHIssueComment> iterator;

    private CommentService service;

    @BeforeEach
    void setUp() {
        service = new CommentService(deliveries, clients);
    }

    @Test
    void postsGreetingWithMarkerThenCompletesOwnedDelivery() throws Exception {
        ownedIssue("An unrelated comment", null);

        service.process(WEBHOOK);

        var order = org.mockito.Mockito.inOrder(issue, deliveries);
        order.verify(deliveries).acquire(WEBHOOK.deliveryId());
        order.verify(issue).listComments();
        order.verify(issue).comment("Hello word!\n\n" + MARKER);
        order.verify(deliveries).complete(WEBHOOK.deliveryId(), "owner");
        verify(deliveries, never()).release(anyString(), anyString());
    }

    @Test
    void completedDuplicateDoesNotCallGitHub() throws Exception {
        when(deliveries.acquire(WEBHOOK.deliveryId())).thenReturn(null);

        service.process(WEBHOOK);

        verifyNoInteractions(clients);
        verify(deliveries, never()).complete(anyString(), anyString());
    }

    @Test
    void recoversExistingMarkerWithoutPostingAnotherComment() throws Exception {
        ownedIssue("Hello word!\n\n" + MARKER);

        service.process(WEBHOOK);

        verify(issue, never()).comment(anyString());
        verify(deliveries).complete(WEBHOOK.deliveryId(), "owner");
    }

    @Test
    void leaseContentionPropagatesWithoutTouchingGitHubOrReleasingAnotherLease() {
        var failure = new IllegalStateException("Competing lease");
        when(deliveries.acquire(WEBHOOK.deliveryId())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.process(WEBHOOK)));

        verifyNoInteractions(clients);
        verify(deliveries, never()).release(anyString(), anyString());
    }

    @Test
    void failedCommentReleasesItsClaimAndPreservesOriginalErrorWhenCleanupFails() throws Exception {
        ownedIssue();
        var failure = new IOException("GitHub unavailable");
        var cleanupFailure = new IllegalStateException("DynamoDB unavailable");
        when(issue.comment(anyString())).thenThrow(failure);
        doThrow(cleanupFailure).when(deliveries).release(WEBHOOK.deliveryId(), "owner");

        assertSame(failure, assertThrows(IOException.class, () -> service.process(WEBHOOK)));
        assertSame(cleanupFailure, failure.getSuppressed()[0]);
        verify(deliveries, never()).complete(anyString(), anyString());
    }

    @Test
    void completionFailureIsRetriedRatherThanAcknowledged() throws Exception {
        ownedIssue();
        var failure = new IllegalStateException("Completion failed");
        doThrow(failure).when(deliveries).complete(WEBHOOK.deliveryId(), "owner");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.process(WEBHOOK)));

        verify(issue).comment("Hello word!\n\n" + MARKER);
        verify(deliveries).release(WEBHOOK.deliveryId(), "owner");
    }

    private void ownedIssue(String... bodies) throws IOException {
        when(deliveries.acquire(WEBHOOK.deliveryId())).thenReturn("owner");
        when(clients.getInstallationClient(WEBHOOK.installationId())).thenReturn(github);
        when(github.getRepository(WEBHOOK.repository())).thenReturn(repository);
        when(repository.getIssue(WEBHOOK.issueNumber())).thenReturn(issue);
        when(issue.listComments()).thenReturn(comments);
        when(comments.iterator()).thenReturn(iterator);
        var entries = new ArrayList<GHIssueComment>();
        for (String body : bodies) {
            var comment = mock(GHIssueComment.class);
            when(comment.getBody()).thenReturn(body);
            entries.add(comment);
        }
        var entriesIterator = entries.iterator();
        when(iterator.hasNext()).thenAnswer(invocation -> entriesIterator.hasNext());
        if (!entries.isEmpty()) {
            when(iterator.next()).thenAnswer(invocation -> entriesIterator.next());
        }
    }
}
