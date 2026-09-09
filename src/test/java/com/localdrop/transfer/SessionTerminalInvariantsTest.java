package com.localdrop.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.localdrop.protocol.ProtocolJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionTerminalInvariantsTest {
    @Test
    void matchesSharedTerminalInvariantVectors() throws IOException {
        try (var stream = SessionTerminalInvariantsTest.class.getResourceAsStream(
            "/protocol-vectors/session-terminal-invariants-v2.json"
        )) {
            JsonNode vector = ProtocolJson.mapper().readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("2026-09-09-r3", vector.path("revision").asText());

            for (JsonNode testCase : vector.path("cases")) {
                Set<String> fileIds = new HashSet<>();
                for (JsonNode fileId : testCase.path("fileIds")) {
                    fileIds.add(fileId.asText());
                }
                String expected = testCase.path("expectedErrorCode").isNull()
                    ? null
                    : testCase.path("expectedErrorCode").asText();

                assertEquals(
                    expected,
                    SessionTerminalInvariants.terminalSessionError(
                        testCase.path("completedFiles").asInt(),
                        fileIds,
                        testCase.path("receivedBytes").asLong(),
                        testCase.path("declaredFiles").asInt(),
                        testCase.path("declaredTotalSize").asLong()
                    ),
                    testCase.path("name").asText()
                );
            }
        }
    }
}
