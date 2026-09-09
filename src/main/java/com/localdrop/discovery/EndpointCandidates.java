package com.localdrop.discovery;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;

import java.util.ArrayList;
import java.util.List;

final class EndpointCandidates {
    private EndpointCandidates() {
    }

    static List<DeviceInfo.TransferEndpoint> withLatest(
        List<DeviceInfo.TransferEndpoint> previous,
        DeviceInfo.TransferEndpoint latest
    ) {
        List<DeviceInfo.TransferEndpoint> candidates = new ArrayList<>();
        candidates.add(latest);
        for (DeviceInfo.TransferEndpoint endpoint : previous) {
            if (!latest.equals(endpoint)) {
                candidates.add(endpoint);
            }
            if (candidates.size() == ProtocolConstants.MAX_FRESH_ENDPOINTS_PER_DEVICE) {
                break;
            }
        }
        return List.copyOf(candidates);
    }
}
