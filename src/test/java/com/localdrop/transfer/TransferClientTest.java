package com.localdrop.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.transfer.ProtocolMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferClientTest {
    @Test
    void rejectsContradictoryFileAckEvenWhenAnotherFieldLooksSuccessful() {
        ProtocolMessage ack = ProtocolMessage.fileAck(
            "session-1",
            "file-1",
            "receiver-1",
            "Receiver",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            true,
            "OK",
            null
        );
        ack.setSuccess(false);
        ack.setErrorCode(ProtocolConstants.ERROR_FILE_WRITE_ERROR);

        assertEquals(
            ProtocolConstants.ERROR_FILE_WRITE_ERROR,
            TransferClient.validateFileAck(ack, "session-1", "file-1", "receiver-1")
        );
    }

    @Test
    void rejectsAckWithExplicitChecksumFailure() {
        ProtocolMessage ack = ProtocolMessage.fileAck(
            "session-1",
            "file-1",
            "receiver-1",
            "Receiver",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            true,
            "OK",
            null
        );
        ack.setChecksumOk(false);
        ack.setErrorCode(ProtocolConstants.ERROR_FILE_WRITE_ERROR);

        assertEquals(
            ProtocolConstants.ERROR_FILE_WRITE_ERROR,
            TransferClient.validateFileAck(ack, "session-1", "file-1", "receiver-1")
        );
    }
}
