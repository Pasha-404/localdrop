package com.localdrop.transfer;

import java.io.IOException;

public class TransferException extends IOException {
    private final String errorCode;

    public TransferException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
