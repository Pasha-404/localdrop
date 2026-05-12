package com.localdrop.discovery;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscoveryService {
    private final Logger logger = LogService.getLogger(DiscoveryService.class);
    private final Map<String, DeviceInfo> devices = new ConcurrentHashMap<>();
    private final Map<String, Long> unicastResponses = new ConcurrentHashMap<>();
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "localdrop-discovery-listener"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> new Thread(r, "localdrop-discovery-scheduler"));
    private final Consumer<List<DeviceInfo>> devicesChangedCallback;
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
        Consumer<List<DeviceInfo>> devicesChangedCallback
    ) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.tcpPort = tcpPort;
        this.devicesChangedCallback = devicesChangedCallback;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }

        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(ProtocolConstants.DISCOVERY_PORT));
        socket.setBroadcast(true);
        socket.setSoTimeout(1500);

        running = true;
        listenerExecutor.submit(this::listenLoop);
        scheduler.scheduleAtFixedRate(this::safeBroadcast, 0, ProtocolConstants.DISCOVERY_BROADCAST_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pruneExpiredDevices, 1, 1, TimeUnit.SECONDS);

        logger.info("UDP discovery v2-open started on port " + ProtocolConstants.DISCOVERY_PORT);
    }

    public void refreshNow() {
        scheduler.execute(this::pruneExpiredDevices);
        for (int index = 0; index < 3; index++) {
            scheduler.schedule(this::safeBroadcast, index * 250L, TimeUnit.MILLISECONDS);
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
        logger.info("UDP discovery stopped");
    }

    private void listenLoop() {
        byte[] buffer = new byte[ProtocolConstants.DISCOVERY_PACKET_MAX_BYTES];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            } catch (SocketTimeoutException ignored) {
                // Polling timeout is expected; periodic broadcasts may arrive later.
            } catch (SocketException exception) {
                if (running) {
                    logger.warning("Discovery socket error: " + exception.getMessage());
                }
            } catch (IOException exception) {
                if (running) {
                    logger.warning("Failed to read discovery packet: " + exception.getMessage());
                }
            } catch (RuntimeException exception) {
                if (running) {
                    logger.warning("Ignored malformed discovery packet: " + exception.getMessage());
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) throws IOException {
        if (packet.getLength() <= 0 || packet.getLength() > ProtocolConstants.DISCOVERY_PACKET_MAX_BYTES) {
            logger.fine("Rejected discovery packet with invalid size: " + packet.getLength());
            return;
        }

        String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        DiscoveryMessage message = ProtocolJson.fromJson(json, DiscoveryMessage.class);
        if (!isValidDiscovery(message)) {
            logger.fine("Rejected invalid discovery packet from " + packet.getAddress().getHostAddress());
            return;
        }
        if (deviceId.equals(message.getDeviceId())) {
            return;
        }

        long now = System.currentTimeMillis();
        int remoteTcpPort = message.getTcpPort() == null ? ProtocolConstants.DEFAULT_TRANSFER_PORT : message.getTcpPort();
        DeviceInfo device = new DeviceInfo(
            message.getDeviceId(),
            message.getDeviceName(),
            message.getDeviceType(),
            message.getStatus(),
            packet.getAddress().getHostAddress(),
            remoteTcpPort,
            message.getCapabilities(),
            now
        );

        DeviceInfo previous = devices.put(device.getDeviceId(), device);
        if (previous == null || hasPresentationChanged(previous, device)) {
            logger.info("Discovered device " + device.getDeviceName()
                + " at " + device.getHostAddress() + ":" + device.getTcpPort());
            emitDevices();
        } else if (logger.isLoggable(Level.FINE)) {
            logger.fine("Refreshed discovery heartbeat for " + device.getDeviceName());
        }

        sendThrottledUnicastResponse(packet.getAddress());
    }

    private boolean isValidDiscovery(DiscoveryMessage message) {
        if (message == null || !ProtocolConstants.TYPE_DISCOVERY.equals(message.getType())) {
            return false;
        }
        if (!Integer.valueOf(ProtocolConstants.PROTOCOL_VERSION).equals(message.getProtocolVersion())) {
            return false;
        }
        if (isBlank(message.getMessageId()) || isBlank(message.getDeviceId()) || isBlank(message.getDeviceName())) {
            return false;
        }
        if (message.getDeviceName().length() > ProtocolConstants.MAX_DEVICE_NAME_LENGTH) {
            return false;
        }
        if (!ProtocolConstants.DEVICE_TYPE_WINDOWS.equals(message.getDeviceType())
            && !ProtocolConstants.DEVICE_TYPE_ANDROID.equals(message.getDeviceType())) {
            return false;
        }
        Integer port = message.getTcpPort();
        return port != null && port > 0 && port <= 65_535;
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
                sendDiscoveryTo(address);
            } catch (IOException exception) {
                logger.fine("Unable to send discovery unicast response to " + key + ": " + exception.getMessage());
            }
        });
    }

    private void safeBroadcast() {
        try {
            broadcastOnce();
        } catch (IOException exception) {
            if (running) {
                logger.warning("Failed to broadcast discovery: " + exception.getMessage());
            }
        }
    }

    private synchronized void broadcastOnce() throws IOException {
        if (!running || socket == null || socket.isClosed()) {
            return;
        }

        sendDiscoveryTo(InetAddress.getByName("255.255.255.255"));
        for (InetAddress broadcastAddress : resolveBroadcastAddresses()) {
            sendDiscoveryTo(broadcastAddress);
        }
    }

    private void sendDiscoveryTo(InetAddress address) throws IOException {
        DiscoveryMessage message = DiscoveryMessage.create(deviceId, deviceName, deviceType, tcpPort);
        byte[] payload = ProtocolJson.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (payload.length > ProtocolConstants.DISCOVERY_PACKET_MAX_BYTES) {
            throw new IOException("Discovery packet is too large: " + payload.length);
        }
        sendPacket(payload, address);
        logger.fine("Sent discovery packet to " + address.getHostAddress());
    }

    private void sendPacket(byte[] payload, InetAddress address) throws IOException {
        DatagramPacket packet = new DatagramPacket(payload, payload.length, address, ProtocolConstants.DISCOVERY_PORT);
        socket.send(packet);
    }

    private List<InetAddress> resolveBroadcastAddresses() throws SocketException {
        List<InetAddress> addresses = new ArrayList<>();
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
        return addresses;
    }

    private void pruneExpiredDevices() {
        long cutoff = System.currentTimeMillis() - ProtocolConstants.DISCOVERY_DEVICE_TIMEOUT_MILLIS;
        List<DeviceInfo> removedDevices = devices.values().stream()
            .filter(device -> device.getLastSeenAt() < cutoff)
            .toList();
        boolean removed = devices.entrySet().removeIf(entry -> entry.getValue().getLastSeenAt() < cutoff);
        if (removed) {
            for (DeviceInfo device : removedDevices) {
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
