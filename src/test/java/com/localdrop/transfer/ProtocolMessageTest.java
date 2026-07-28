package com.localdrop.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.transfer.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolMessageTest {
    @Test
    void decodesSharedSessionStartGoldenVector() throws IOException {
        try (var stream = ProtocolMessageTest.class.getResourceAsStream("/protocol-vectors/session-start-v2.json")) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            ProtocolMessage message = com.localdrop.protocol.ProtocolJson.fromJson(json, ProtocolMessage.class);

            assertEquals("SESSION_START", message.getType());
            assertEquals(ProtocolConstants.PROTOCOL_VERSION, message.getProtocolVersion());
            assertEquals("golden-session-001", message.getSessionId());
            assertEquals(4L, message.getTotalSize());
        }
    }

    @Test
    void rejectsHeaderWithoutProtocolVersion() throws IOException {
        byte[] header = "{\"type\":\"SESSION_START\"}".getBytes();
        var bytes = new java.io.ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(header.length);
            output.write(header);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThrows(IOException.class, () -> ProtocolMessage.read(input));
        }
    }
}
