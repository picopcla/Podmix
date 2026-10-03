package com.podmix.next.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public class OfflineDownloadWorkerTest {
    @Test
    public void retriesNetworkFailuresAndTemporaryHttpStatuses() {
        assertTrue(OfflineDownloadWorker.isTransient(new IOException("connection reset")));
        assertTrue(OfflineDownloadWorker.isTransient(new OfflineDownloadWorker.HttpStatusException(408)));
        assertTrue(OfflineDownloadWorker.isTransient(new OfflineDownloadWorker.HttpStatusException(429)));
        assertTrue(OfflineDownloadWorker.isTransient(new OfflineDownloadWorker.HttpStatusException(503)));
    }

    @Test
    public void doesNotRetryPermanentHttpFailures() {
        assertFalse(OfflineDownloadWorker.isTransient(new OfflineDownloadWorker.HttpStatusException(401)));
        assertFalse(OfflineDownloadWorker.isTransient(new OfflineDownloadWorker.HttpStatusException(404)));
        assertFalse(OfflineDownloadWorker.isTransient(new IllegalArgumentException("bad url")));
    }
}
