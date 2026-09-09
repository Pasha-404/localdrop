package com.localdrop.discovery;

import com.localdrop.protocol.discovery.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointCandidatesTest {
    @Test
    void keepsLatestEndpointFirstWithoutGrowingUnbounded() {
        var first = new DeviceInfo.TransferEndpoint("192.168.1.10", 45455);
        var second = new DeviceInfo.TransferEndpoint("192.168.1.11", 45455);
        var third = new DeviceInfo.TransferEndpoint("192.168.1.12", 45455);
        var fourth = new DeviceInfo.TransferEndpoint("192.168.1.13", 45455);

        assertEquals(
            List.of(fourth, third, second),
            EndpointCandidates.withLatest(List.of(third, second, first), fourth)
        );
    }
}
