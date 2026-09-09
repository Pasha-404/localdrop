package com.localdrop.transfer;

import com.localdrop.protocol.ProtocolConstants;

import java.util.Set;

final class SessionTerminalInvariants {
    private SessionTerminalInvariants() {
    }

    static String terminalSessionError(
        int completedFiles,
        Set<String> receivedFileIds,
        long receivedBytes,
        int declaredFiles,
        long declaredTotalSize
    ) {
        if (completedFiles != declaredFiles
            || receivedFileIds.size() != declaredFiles
            || receivedBytes != declaredTotalSize) {
            return ProtocolConstants.ERROR_INVALID_SESSION;
        }
        return null;
    }
}
