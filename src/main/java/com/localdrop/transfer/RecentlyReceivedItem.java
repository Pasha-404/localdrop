package com.localdrop.transfer;

import java.time.LocalTime;

public record RecentlyReceivedItem(String name, long size, LocalTime receivedAt) {
}
