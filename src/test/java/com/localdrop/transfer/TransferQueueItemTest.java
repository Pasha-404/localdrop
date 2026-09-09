package com.localdrop.transfer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferQueueItemTest {
    @Test
    void progressIsIgnoredAfterATerminalOrRetryState() {
        TransferQueueItem item = new TransferQueueItem(Path.of("source.txt"), "source.txt", 10, 0);

        item.setStatus(TransferStatus.FAILED);
        assertFalse(item.updateProgressIfSending(0.5));
        assertTrue(item.getStatus() == TransferStatus.FAILED);

        item.setStatus(TransferStatus.SENDING);
        assertTrue(item.updateProgressIfSending(0.5));
        assertTrue(item.getProgress() == 0.5);

        item.setStatus(TransferStatus.WAITING_FOR_RETRY);
        assertFalse(item.updateProgressIfSending(1.0));
        assertTrue(item.getStatus() == TransferStatus.WAITING_FOR_RETRY);
    }

    @Test
    void reservationPreventsRemovalUntilTheActiveBatchFinishes() {
        TransferQueueItem item = new TransferQueueItem(Path.of("source.txt"), "source.txt", 10, 0);

        assertTrue(item.canRemove());
        assertTrue(item.reserve("batch-1"));
        assertFalse(item.canRemove());
        assertFalse(item.reserve("batch-2"));

        item.release("batch-1");
        assertTrue(item.canRemove());
    }
}
