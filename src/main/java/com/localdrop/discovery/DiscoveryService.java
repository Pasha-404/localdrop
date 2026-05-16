package com.localdrop.discovery;

import com.localdrop.diagnostics.DiagnosticDirection;
import com.localdrop.diagnostics.DiagnosticEventType;
import com.localdrop.diagnostics.DiagnosticsService;
import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.ProtocolJson;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.protocol.discovery.DiscoveryMessage;
import com.localdrop.util.LogService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class DiscoveryService {
    public record SendTargetResolution(DeviceInfo device, String errorCode, String message) {
        public boolean canSend() {
            return device != null && errorCode == null;
        }
    }

    private final Logger logger = LogService.getLogger(DiscoveryService.class);
    private final Map<String, DeviceInfo> devices = new ConcurrentHashMap<>();
    private final Map<String, Long> unicastResponses = new ConcurrentHashMap<>();
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "localdrop-discovery-listener"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> new Thread(r, "localdrop-discovery-scheduler"));
    private final Consumer<List<DeviceInfo>> devicesChangedCallback;
    private final Supplier<String> localStatusSupplier;
    private final DiagnosticsService diagnosticsService;
    private final String deviceId;
    private final String deviceName;
    private final String deviceType;
    private final int tcpPort;

    private volatile boolean running;
    private DatagramSocket socket;

    public DiscoveryService(
        String deviceId,
        String deviceName,
        String deviceType,
        int tcpPort,
        Supplier<String> localStatusSupplier,
        Consumer<List<DeviceInfo>> devicesChangedCallback,
        DiagnosticsService diagnosticsService
    ) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.tcpPort = tcpPort;
        this.localStatusSupplier = localStatusSupplier;
        this.devicesChangedCallback = devicesChangedCallback;
        this.diagnosticsService = diagnosticsService;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }

        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(ProtocolConstants.DISCOVERY_PORT));
        socket.setBroadcast(true);
        socket.setSoTimeout(1000);

        running = true;
        diagnosticsService.refreshNetworkSnapshot();
        diagnosticsService.setDiscoveryStatus("RUNNING", null);
        diagnosticsService.recordTransferEvent(
            DiagnosticEventType.DISCOVERY_STARTED,
            DiagnosticDirection.INTERNAL,
            null,
            deviceId,
            deviceName,
            deviceType,
            resolveLocalStatus(),
            null,
            "UDP discovery started."
        );

        listenerExecutor.submit(this::listenLoop);
        scheduler.scheduleAtFixedRate(this::safeBroadcast, 0, ProtocolConstants.DISCOVERY_BROADCAST_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::pruneExpiredDevices, 1, 1, TimeUnit.SECONDS);

        logger.info("UDP discovery v2-open started on port " + ProtocolConstants.DISCOVERY_PORT);
    }

    public void refreshNow() {
        scheduler.execute(this::pruneExpiredDevices);
        for (int index = 0; index < ProtocolConstants.MANUAL_REFRESH_BURST_COUNT; index++) {
            scheduler.schedule(this::safeBroadcast, index * ProtocolConstants.MANUAL_REFRESH_BURST_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        listenerExecutor.shutdownNow();
        scheduler.shutdownNow();
        devices.clear();
        emitDevices();
        diagnosticsService.setDiscoveryStatus("STOPPED", null);
        diagnosticsService.recordTransferEvent(
            DiagnosticEventType.DISCOVERY_STOPPED,
            DiagnosticDirection.INTERNAL,
            null,
            deviceId,
            deviceName,
            deviceType,
            null,
            null,
            "UDP discovery stopped."
        );
        logger.info("UDP discovery stopped");
    }

    public SendTargetResolution resolveSendTarget(String remoteDeviceId) {
        if (!running) {
            return new SendTargetResolution(
                null,
                ProtocolConstants.DIAGNOSTIC_TRANSFER_CLIENT_NOT_STARTED,
                "Device discovery is not running."
            );
        }

        DeviceInfo device = devices.get(remoteDeviceId);
        if (device == null) {
            return new SendTargetResolution(
                null,
                ProtocolConstants.DIAGNOSTIC_DEVICE_NOT_FOUND,
                "Device is not currently discovered on the local network."
            );
        }

        long age = System.currentTimeMillis() - device.getLastSeenAt();
        if (age > ProtocolConstants.DISCOVERY_DEVICE_TIMEOUT_MILLIS) {
            return new SendTargetResolution(
                null,
                ProtocolConstants.DIAGNOSTIC_STALE_DEVICE_ADDRESS,
                "The selected device is no longer live in discovery."
            );
        }

        if (!isReceiveReady(device)) {
            return new SendTargetResolution(
                null,
                ProtocolConstants.DIAGNOSTIC_DEVICE_NOT_READY,
                "The selected device is visible but not ready to receive files."
            );
        }

        return new SendTargetResolution(device, null, null);
    }

    private void listenLoop() {
        byte[] buffer = new byte[ProtocolConstants.DISCOVERY_PACKET_MAX_BYTES];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            } catch (SocketTimeoutException ignored) {
                // Polling timeout is expected.
            } catch (SocketException exception) {
                if (running) {
                    diagnosticsService.setDiscoveryStatus("ERROR", ProtocolConstants.DIAGNOSTIC_DISCOVERY_SOCKET_ERROR);
                    diagnosticsService.recordDiscoveryError(
                        ProtocolConstants.DIAGNOSTIC_DISCOVERY_SOCKET_ERROR,
                        exception.getMessage()
                    );
                    logger.warning("Discovery socket error: " + exception.getMessage());
                }
            } catch (IOException exception) {
                if (running) {
                    diagnosticsService.recordDiscoveryError(
                        ProtocolConstants.DIAGNOSTIC_DISCOVERY_PACKET_MALFORMED,
                        exception.getMessage()
                    );
                    logger.warning("Failed to read discovery packet: " + exception.getMessage());
                }
            } catch (RuntimeException exception) {
                if (running) {
                    diagnosticsService.recordDiscoveryError(
                        ProtocolConstants.DIAGNOSTIC_DISCOVERY_PACKET_MALFORMED,
                        exception.getMessage()
                    );
                    logger.warning("Ignored malformed discovery packet: " + exception.getMessage());
                }
            }
        }
        diagnosticsService.recordTransferEvent(
            DiagnosticEventType.DISCOVERY_STOPPED,
            DiagnosticDirection.INTERNAL,
            null,
            deviceId,
            deviceName,
            deviceType,
            null,
            ProtocolConstants.DIAGNOSTIC_DISCOVERY_LISTENER_STOPPED,
            "Discovery listener stopped."
        );
    }

    private void handlePacket(DatagramPacket packet) throws IOException {
        if (packet.getLength() <= 0 || packet.getLength() > ProtocolConstants.DISCOVERY_PACKET_MAX_BYTES) {
            diagnosticsService.recordDiscoveryRejected(
                packet.getAddress().getHostAddress(),
                ProtocolConstants.DIAGNOSTIC_DISCOVERY_PACKET_REJECTED,
                "Rejected discovery packet with invalid size: " + packet.getLength()
            );
            return;
        }

        String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        DiscoveryMessage message;
        try {
            message = ProtocolJson.fromJson(json, DiscoveryMessage.class);
        } catch (RuntimeException exception) {
            diagnosticsService.recordDiscoveryRejected(
                packet.getAddress().getHostAddress(),
                ProtocolConstants.DIAGNOSTIC_DISCOVERY_PACKET_MALFORMED,
                "Malformed discovery payload."
            );
            return;
        }

        String validationError = validateDiscovery(message);
        if (validationError != null) {
            diagnosticsService.recordDiscoveryRejected(packet.getAddress().getHostAddress(), validationError, "Discovery packet rejected.");
            return;
        }
        if (deviceId.equals(message.getDeviceId())) {
            return;
        }

        long now = System.currentTimeMillis();
        DeviceInfo device = new DeviceInfo(
            message.getDeviceId(),
            message.getDeviceName(),
            message.getDeviceType(),
            message.getStatus(),
            packet.getAddress().getHostAddress(),
            message.getTcpPort(),
            message.getCapabilities(),
            now
        );

        DeviceInfo previous = devices.put(device.getDeviceId(), device);
        boolean added = previous == null;
        boolean changed = added || hasPresentationChanged(previous, device);
        diagnosticsService.recordDiscoveryReceived(device, packet.getAddress().getHostAddress());
        diagnosticsService.upsertLiveDevice(device, added, changed);
        if (changed) {
            if (added) {
                logger.info("Discovered device " + device.getDeviceName()
                    + " at " + device.getHostAddress() + ":" + device.getTcpPort());
            } else {
                logger.info("Updated device " + device.getDeviceName()
                    + " at " + device.getHostAddress() + ":" + device.getTcpPort()
                    + " status=" + effectiveStatus(device));
            }
            emitDevices();
        }

        sendThrottledUnicastResponse(packet.getAddress());
    }

    private String validateDiscovery(DiscoveryMessage message) {
        if (message == null || !ProtocolConstants.TYPE_DISCOVERY.equals(message.getType())) {
            return ProtocolConstants.ERROR_MALFORMED_MESSAGE;
        }
        if (!Integer.valueOf(ProtocolConstants.PROTOCOL_VERSION).equals(message.getProtocolVersion())) {
            return ProtocolConstants.ERROR_PROTOCOL_VERSION_MISMATCH;
        }
        if (isBlank(message.getMessageId())
            || isBlank(message.getDeviceId())
            || isBlank(message.getDeviceName())
            || isBlank(message.getDeviceType())) {
            return ProtocolConstants.ERROR_MALFORMED_MESSAGE;
        }
        if (message.getDeviceId().length() > ProtocolConstants.MAX_DEVICE_ID_LENGTH
            || message.getDeviceName().length() > ProtocolConstants.MAX_DEVICE_NAME_LENGTH
            || message.getDeviceType().length() > ProtocolConstants.MAX_DEVICE_TYPE_LENGTH) {
            return ProtocolConstants.ERROR_MALFORMED_MESSAGE;
        }
        if (message.getStatus() != null && message.getStatus().length() > ProtocolConstants.MAX_STATUS_LENGTH) {
            return ProtocolConstants.ERROR_MALFORMED_MESSAGE;
        }
        if (!ProtocolConstants.DEVICE_TYPE_WINDOWS.equals(message.getDeviceType())
            && !ProtocolConstants.DEVICE_TYPE_ANDROID.equals(message.getDeviceType())) {
            return ProtocolConstants.ERROR_MALFORMED_MESSAGE;
        }
        Integer port = message.getTcpPort();
        if (port == null || port < 1 || port > 65_535) {
            return ProtocolConstants.ERROR_TRANSFER_PORT_UNAVAILABLE;
        }
        return null;
    }

    private void sendThrottledUnicastResponse(InetAddress address) {
        String key = address.getHostAddress();
        long now = System.currentTimeMillis();
        Long lastSent = unicastResponses.get(key);
        if (lastSent != null && now - lastSent < ProtocolConstants.DISCOVERY_RESPONSE_THROTTLE_MILLIS) {
            return;
        }
        unicastResponses.put(key, now);

        scheduler.execute(() -> {
            try {
                sendDiscoveryTo(address, true);
            } catch (IOException exception) {
                diagnosticsService.recordDiscoveryError(
                    ProtocolConstants.DIAGNOSTIC_DISCOVERY_SOCKET_ERROR,
                    "Unable to send unicast discovery response: " + exception.getMessage()
                );
                logger.fine("Unable to send discovery unicast response to " + key + ": " + exception.getMessage());
            }
        });
    }

    private void safeBroadcast() {
        try {
            broadcastOnce();
        } catch (IOException exception) {
            if (running) {
                diagnosticsService.setDiscoveryStatus("ERROR", ProtocolConstants.DIAGNOSTIC_DISCOVERY_SOCKET_ERROR);
                diagnosticsService.recordDiscoveryError(
                    ProtocolConstants.DIAGNOSTIC_DISCOVERY_SOCKET_ERROR,
                    exception.getMessage()
                );
                logger.warning("Failed to broadcast discovery: " + exception.getMessage());
            }
        }
    }

    private synchronized void broadcastOnce() throws IOException {
        if (!running || socket == null || socket.isClosed()) {
            return;
        }

        sendDiscoveryTo(InetAddress.getByName("255.255.255.255"), false);
        for (InetAddress broadcastAddress : resolveBroadcastAddresses()) {
            sendDiscoveryTo(broadcastAddress, false);
        }
        diagnosticsService.setDiscoveryStatus("RUNNING", null);
    }

    private void sendDiscoveryTo(InetAddress address, boolean unicast) throws IOException {
        DiscoveryMessage message = DiscoveryMessage.create(
            deviceId,
            deviceName,
            deviceType,
            resolveLocalStatus(),
            tcpPort,
            ProtocolConstants.CAPABILITIES
        );
        byte[] payload = ProtocolJson.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (payload.length > ProtocolConstants.DISCOVERY_PACKET_MAX_BYTES) {
            throw new IOException("Discovery packet is too large: " + payload.length);
        }
        sendPacket(payload, address);
        diagnosticsService.recordDiscoverySent(address.getHostAddress(), unicast);
    }

    private void sendPacket(byte[] payload, InetAddress address) throws IOException {
        DatagramPacket packet = new DatagramPacket(payload, payload.length, address, ProtocolConstants.DISCOVERY_PORT);
        socket.send(packet);
    }

    private List<InetAddress> resolveBroadcastAddresses() throws SocketException {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                continue;
            }
            networkInterface.getInterfaceAddresses().stream()
                .map(address -> address.getBroadcast())
                .filter(Objects::nonNull)
                .forEach(addresses::add);
        }
        return new ArrayList<>(addresses);
    }

    private void pruneExpiredDevices() {
        long cutoff = System.currentTimeMillis() - ProtocolConstants.DISCOVERY_DEVICE_TIMEOUT_MILLIS - ProtocolConstants.MAIN_LIST_EXPIRE_GRACE_MS;
        List<DeviceInfo> removedDevices = devices.values().stream()
            .filter(device -> device.getLastSeenAt() < cutoff)
            .toList();
        boolean removed = devices.entrySet().removeIf(entry -> entry.getValue().getLastSeenAt() < cutoff);
        if (removed) {
            for (DeviceInfo device : removedDevices) {
                diagnosticsService.markDeviceExpired(device);
                logger.info("Device disappeared " + device.getDeviceName() + " at " + device.getHostAddress() + ":" + device.getTcpPort());
            }
            emitDevices();
        }
    }

    private void emitDevices() {
        List<DeviceInfo> snapshot = devices.values().stream()
            .sorted(Comparator.comparing(DeviceInfo::getDeviceName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        devicesChangedCallback.accept(snapshot);
    }

    private String resolveLocalStatus() {
        try {
            String status = localStatusSupplier == null ? null : localStatusSupplier.get();
            return status == null || status.isBlank() ? ProtocolConstants.STATUS_READY : status;
        } catch (RuntimeException exception) {
            diagnosticsService.recordDiscoveryError(ProtocolConstants.ERROR_UNKNOWN, exception.getMessage());
            return ProtocolConstants.STATUS_UNKNOWN;
        }
    }

    private boolean isReceiveReady(DeviceInfo device) {
        long age = System.currentTimeMillis() - device.getLastSeenAt();
        return age <= ProtocolConstants.DISCOVERY_DEVICE_TIMEOUT_MILLIS
            && isReceiveReadyStatus(device.getStatus());
    }

    private boolean isReceiveReadyStatus(String status) {
        return status == null
            || status.isBlank()
            || ProtocolConstants.STATUS_READY.equalsIgnoreCase(status)
            || ProtocolConstants.STATUS_READY_COMPAT.equalsIgnoreCase(status);
    }

    private String effectiveStatus(DeviceInfo device) {
        return device.getStatus() == null || device.getStatus().isBlank()
            ? ProtocolConstants.STATUS_READY_COMPAT
            : device.getStatus();
    }

    private boolean hasPresentationChanged(DeviceInfo previous, DeviceInfo current) {
        return !Objects.equals(previous.getDeviceName(), current.getDeviceName())
            || !Objects.equals(previous.getDeviceType(), current.getDeviceType())
            || !Objects.equals(previous.getStatus(), current.getStatus())
            || !Objects.equals(previous.getHostAddress(), current.getHostAddress())
            || previous.getTcpPort() != current.getTcpPort();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
