package com.localdrop.transfer;

public enum TransferStatus {
    QUEUED("queue.status.queued", "status-queued"),
    SENDING("queue.status.sending", "status-sending"),
    SENT("queue.status.sent", "status-sent"),
    FAILED("queue.status.failed", "status-failed"),
    WAITING_FOR_RETRY("queue.status.waiting", "status-waiting"),
    DELIVERY_UNKNOWN("queue.status.deliveryUnknown", "status-delivery-unknown");

    private final String translationKey;
    private final String styleClass;

    TransferStatus(String translationKey, String styleClass) {
        this.translationKey = translationKey;
        this.styleClass = styleClass;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public String getStyleClass() {
        return styleClass;
    }
}
