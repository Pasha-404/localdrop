package com.localdrop.diagnostics;

import com.localdrop.i18n.I18n;
import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.util.LogService;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public class DiagnosticsService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final Logger logger = LogService.getLogger(DiagnosticsService.class);
    private final Object lock = new Object();
    private final String localDeviceId;
    private final String localDeviceName;
    private final String localDeviceType;
    private final Map<String, DiagnosticDeviceEntry> liveDevices = new LinkedHashMap<>();
    private final Map<String, DiagnosticDeviceEntry> recentExpiredDevices = new LinkedHashMap<>();
    private final Deque<DiagnosticEvent> eventBuffer = new ArrayDeque<>(ProtocolConstants.DIAGNOSTIC_EVENT_BUFFER_SIZE);

    private List<String> localIpAddresses = List.of();
    private List<String> activeNetworkInterfaces = List.of();
    private int discoveryPort = ProtocolConstants.DISCOVERY_PORT;
    private int transferPort = ProtocolConstants.DEFAULT_TRANSFER_PORT;
    private String discoveryStatus = "STOPPED";
    private String transferServerStatus = "STOPPED";
    private long lastDiscoverySentAt;
    private long lastDiscoveryReceivedAt;
    private String lastDiscoveryReceivedFrom = "";
    private String lastDiscoveryErrorCode = ProtocolConstants.ERROR_NONE;
    private String lastTransferErrorCode = ProtocolConstants.ERROR_NONE;
    private int mainListDevicesCount;

    public DiagnosticsService(String localDeviceId, String localDeviceName, String localDeviceType) {
        this.localDeviceId = localDeviceId;
        this.localDeviceName = localDeviceName;
        this.localDeviceType = localDeviceType;
        refreshNetworkSnapshot();
    }

    public void refreshNetworkSnapshot() {
        List<String> addresses = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces != null && networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                interfaces.add(networkInterface.getDisplayName());
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (address != null) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException exception) {
            logger.fine("Unable to enumerate local interfaces: " + exception.getMessage());
        }

        synchronized (lock) {
            localIpAddresses = List.copyOf(addresses);
            activeNetworkInterfaces = List.copyOf(interfaces);
        }
    }

    public void setTransferPort(int transferPort) {
        synchronized (lock) {
            this.transferPort = transferPort;
        }
    }

    public void setDiscoveryStatus(String status, String errorCode) {
        synchronized (lock) {
            discoveryStatus = status;
            if (errorCode != null && !errorCode.isBlank()) {
                lastDiscoveryErrorCode = errorCode;
            }
        }
    }

    public void setTransferServerStatus(String status, String errorCode) {
        synchronized (lock) {
            transferServerStatus = status;
            if (errorCode != null && !errorCode.isBlank()) {
                lastTransferErrorCode = errorCode;
            }
        }
    }

    public void setMainListDevicesCount(int mainListDevicesCount) {
        synchronized (lock) {
            this.mainListDevicesCount = mainListDevicesCount;
        }
    }

    public void recordDiscoverySent(String remoteAddress, boolean unicast) {
        synchronized (lock) {
            lastDiscoverySentAt = System.currentTimeMillis();
            addEvent(new DiagnosticEvent(
                lastDiscoverySentAt,
                unicast ? DiagnosticEventType.DISCOVERY_SEND_UNICAST : DiagnosticEventType.DISCOVERY_SEND_BROADCAST,
                DiagnosticDirection.OUT,
                remoteAddress,
                null,
                null,
                null,
                null,
                null,
                unicast ? "Unicast discovery response sent." : "Broadcast discovery packet sent."
            ));
        }
    }

    public void recordDiscoveryReceived(DeviceInfo device, String remoteAddress) {
        synchronized (lock) {
            lastDiscoveryReceivedAt = System.currentTimeMillis();
            lastDiscoveryReceivedFrom = remoteAddress;
            addEvent(new DiagnosticEvent(
                lastDiscoveryReceivedAt,
                DiagnosticEventType.DISCOVERY_RECEIVED,
                DiagnosticDirection.IN,
                remoteAddress,
                device.getDeviceId(),
                device.getDeviceName(),
                device.getDeviceType(),
                effectiveStatus(device),
                null,
                "Discovery packet accepted."
            ));
        }
    }

    public void recordDiscoveryRejected(String remoteAddress, String errorCode, String message) {
        synchronized (lock) {
            lastDiscoveryReceivedAt = System.currentTimeMillis();
            lastDiscoveryReceivedFrom = remoteAddress == null ? "" : remoteAddress;
            if (errorCode != null && !errorCode.isBlank()) {
                lastDiscoveryErrorCode = errorCode;
            }
            addEvent(new DiagnosticEvent(
                System.currentTimeMillis(),
                DiagnosticEventType.DISCOVERY_REJECTED,
                DiagnosticDirection.IN,
                remoteAddress,
                null,
                null,
                null,
                null,
                errorCode,
                message
            ));
        }
    }

    public void recordDiscoveryError(String errorCode, String message) {
        synchronized (lock) {
            lastDiscoveryErrorCode = errorCode == null || errorCode.isBlank() ? ProtocolConstants.ERROR_UNKNOWN : errorCode;
            addEvent(new DiagnosticEvent(
                System.currentTimeMillis(),
                DiagnosticEventType.DISCOVERY_ERROR,
                DiagnosticDirection.INTERNAL,
                null,
                null,
                null,
                null,
                null,
                lastDiscoveryErrorCode,
                message
            ));
        }
    }

    public void recordTransferError(String errorCode, String message, String remoteAddress, String deviceId, String deviceName) {
        synchronized (lock) {
            lastTransferErrorCode = errorCode == null || errorCode.isBlank() ? ProtocolConstants.ERROR_UNKNOWN : errorCode;
            addEvent(new DiagnosticEvent(
                System.currentTimeMillis(),
                DiagnosticEventType.TRANSFER_CLIENT_ERROR,
                DiagnosticDirection.INTERNAL,
                remoteAddress,
                deviceId,
                deviceName,
                null,
                null,
                lastTransferErrorCode,
                message
            ));
        }
    }

    public void recordTransferEvent(
        DiagnosticEventType eventType,
        DiagnosticDirection direction,
        String remoteAddress,
        String deviceId,
        String deviceName,
        String deviceType,
        String status,
        String errorCode,
        String message
    ) {
        synchronized (lock) {
            if (errorCode != null && !errorCode.isBlank()) {
                lastTransferErrorCode = errorCode;
            }
            addEvent(new DiagnosticEvent(
                System.currentTimeMillis(),
                eventType,
                direction,
                remoteAddress,
                deviceId,
                deviceName,
                deviceType,
                status,
                errorCode,
                message
            ));
        }
    }

    public void upsertLiveDevice(DeviceInfo device, boolean added, boolean emitEvent) {
        synchronized (lock) {
            liveDevices.put(device.getDeviceId(), toDiagnosticEntry(device, null));
            if (emitEvent) {
                addEvent(new DiagnosticEvent(
                    System.currentTimeMillis(),
                    added ? DiagnosticEventType.DEVICE_ADDED : DiagnosticEventType.DEVICE_UPDATED,
                    DiagnosticDirection.INTERNAL,
                    device.getHostAddress(),
                    device.getDeviceId(),
                    device.getDeviceName(),
                    device.getDeviceType(),
                    effectiveStatus(device),
                    null,
                    added ? "Device added to discovery cache." : "Device updated in discovery cache."
                ));
            }
            if (emitEvent && !isReceiveReady(device)) {
                addEvent(new DiagnosticEvent(
                    System.currentTimeMillis(),
                    DiagnosticEventType.DEVICE_NOT_READY,
                    DiagnosticDirection.INTERNAL,
                    device.getHostAddress(),
                    device.getDeviceId(),
                    device.getDeviceName(),
                    device.getDeviceType(),
                    effectiveStatus(device),
                    null,
                    "Device is visible but not ready to receive."
                ));
            }
        }
    }

    public void markDeviceExpired(DeviceInfo device) {
        synchronized (lock) {
            liveDevices.remove(device.getDeviceId());
            recentExpiredDevices.put(device.getDeviceId(), toDiagnosticEntry(device, "RECENTLY_EXPIRED"));
            addEvent(new DiagnosticEvent(
                System.currentTimeMillis(),
                DiagnosticEventType.DEVICE_EXPIRED,
                DiagnosticDirection.INTERNAL,
                device.getHostAddress(),
                device.getDeviceId(),
                device.getDeviceName(),
                device.getDeviceType(),
                effectiveStatus(device),
                null,
                "Device expired from live discovery cache."
            ));
        }
    }

    public DiagnosticSnapshot snapshot() {
        synchronized (lock) {
            pruneExpiredRecentDevicesLocked();

            List<DiagnosticDeviceEntry> readyDevices = liveDevices.values().stream()
                .filter(DiagnosticsService::isReceiveReadyEntry)
                .sorted(Comparator.comparing(DiagnosticDeviceEntry::deviceName, String.CASE_INSENSITIVE_ORDER))
                .toList();
            List<DiagnosticDeviceEntry> unavailableDevices = liveDevices.values().stream()
                .filter(entry -> !isReceiveReadyEntry(entry))
                .sorted(Comparator.comparing(DiagnosticDeviceEntry::deviceName, String.CASE_INSENSITIVE_ORDER))
                .toList();
            List<DiagnosticDeviceEntry> expiredDevices = recentExpiredDevices.values().stream()
                .sorted(Comparator.comparingLong(DiagnosticDeviceEntry::lastSeenAt).reversed())
                .toList();
            List<DiagnosticEvent> events = eventBuffer.stream()
                .sorted(Comparator.comparingLong(DiagnosticEvent::timestamp).reversed())
                .toList();

            return new DiagnosticSnapshot(
                ProtocolConstants.CONTRACT_REVISION,
                localDeviceId,
                localDeviceName,
                localDeviceType,
                List.copyOf(localIpAddresses),
                List.copyOf(activeNetworkInterfaces),
                discoveryPort,
                transferPort,
                discoveryStatus,
                transferServerStatus,
                lastDiscoverySentAt,
                lastDiscoveryReceivedAt,
                lastDiscoveryReceivedFrom,
                lastDiscoveryErrorCode,
                lastTransferErrorCode,
                liveDevices.size(),
                mainListDevicesCount,
                liveDevices.size() + recentExpiredDevices.size(),
                readyDevices,
                unavailableDevices,
                expiredDevices,
                events
            );
        }
    }

    public String formatSnapshot(I18n i18n) {
        DiagnosticSnapshot snapshot = snapshot();
        StringBuilder builder = new StringBuilder(4096);
        builder.append("LocalDrop Network Diagnostics").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Contract revision: ").append(snapshot.contractRevision()).append(System.lineSeparator());
        builder.append("Local device: ")
            .append(snapshot.localDeviceName())
            .append(" [")
            .append(snapshot.localDeviceType())
            .append("] ")
            .append(snapshot.localDeviceId())
            .append(System.lineSeparator());
        builder.append("Local IP addresses: ").append(formatList(snapshot.localIpAddresses())).append(System.lineSeparator());
        builder.append("Active interfaces: ").append(formatList(snapshot.activeNetworkInterfaces())).append(System.lineSeparator());
        builder.append("UDP discovery: ").append(snapshot.discoveryStatus()).append(System.lineSeparator());
        builder.append("TCP receive: ").append(snapshot.transferServerStatus()).append(System.lineSeparator());
        builder.append("Discovery port: ").append(snapshot.discoveryPort()).append(System.lineSeparator());
        builder.append("Transfer port: ").append(snapshot.transferPort()).append(System.lineSeparator());
        builder.append("Last discovery sent: ").append(formatTimestamp(snapshot.lastDiscoverySentAt())).append(System.lineSeparator());
        builder.append("Last discovery received: ").append(formatTimestamp(snapshot.lastDiscoveryReceivedAt())).append(System.lineSeparator());
        builder.append("Last discovery sender: ").append(blankToDash(snapshot.lastDiscoveryReceivedFrom())).append(System.lineSeparator());
        builder.append("Last discovery error: ").append(blankToDash(snapshot.lastDiscoveryErrorCode())).append(System.lineSeparator());
        builder.append("Last transfer error: ").append(blankToDash(snapshot.lastTransferErrorCode())).append(System.lineSeparator());
        builder.append("Live devices: ").append(snapshot.liveDevicesCount()).append(System.lineSeparator());
        builder.append("Main list devices: ").append(snapshot.mainListDevicesCount()).append(System.lineSeparator());
        builder.append("Diagnostics device entries: ").append(snapshot.diagnosticDevicesCount()).append(System.lineSeparator());
        builder.append(System.lineSeparator());

        appendDeviceSection(builder, "Ready devices", snapshot.readyDevices());
        appendDeviceSection(builder, "Unavailable / not ready devices", snapshot.unavailableDevices());
        appendDeviceSection(builder, "Recently expired devices", snapshot.recentExpiredDevices());

        builder.append("Recent events").append(System.lineSeparator());
        if (snapshot.recentEvents().isEmpty()) {
            builder.append("- none").append(System.lineSeparator());
        } else {
            for (DiagnosticEvent event : snapshot.recentEvents()) {
                builder.append("- ")
                    .append(formatTimestamp(event.timestamp()))
                    .append(" | ")
                    .append(event.eventType())
                    .append(" | ")
                    .append(event.direction());
                if (event.deviceName() != null && !event.deviceName().isBlank()) {
                    builder.append(" | ").append(event.deviceName());
                }
                if (event.remoteAddress() != null && !event.remoteAddress().isBlank()) {
                    builder.append(" | ").append(event.remoteAddress());
                }
                if (event.status() != null && !event.status().isBlank()) {
                    builder.append(" | status=").append(event.status());
                }
                if (event.errorCode() != null && !event.errorCode().isBlank()) {
                    builder.append(" | error=").append(event.errorCode());
                }
                if (event.message() != null && !event.message().isBlank()) {
                    builder.append(" | ").append(event.message());
                }
                builder.append(System.lineSeparator());
            }
        }
        builder.append(System.lineSeparator());
        builder.append(i18n == null
            ? "If another device is not visible, verify Private network / Windows Firewall / same LAN / VPN disabled / no guest Wi-Fi."
            : i18n.text("diagnostics.helpText"));
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    private void pruneExpiredRecentDevicesLocked() {
        long cutoff = System.currentTimeMillis() - ProtocolConstants.DIAGNOSTIC_RECENT_DEVICE_TTL_MS;
        recentExpiredDevices.entrySet().removeIf(entry -> entry.getValue().lastSeenAt() < cutoff);
    }

    private void appendDeviceSection(StringBuilder builder, String title, List<DiagnosticDeviceEntry> devices) {
        builder.append(title).append(System.lineSeparator());
        if (devices.isEmpty()) {
            builder.append("- none").append(System.lineSeparator()).append(System.lineSeparator());
            return;
        }
        for (DiagnosticDeviceEntry device : devices) {
            builder.append("- ")
                .append(blankToDash(device.deviceName()))
                .append(" [")
                .append(blankToDash(device.deviceType()))
                .append("] ")
                .append(blankToDash(device.hostAddress()))
                .append(":")
                .append(device.tcpPort())
                .append(" | status=")
                .append(blankToDash(device.status()))
                .append(" | lastSeen=")
                .append(formatAge(device.lastSeenAt()));
            if (device.note() != null && !device.note().isBlank()) {
                builder.append(" | note=").append(device.note());
            }
            builder.append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
    }

    private void addEvent(DiagnosticEvent event) {
        while (eventBuffer.size() >= ProtocolConstants.DIAGNOSTIC_EVENT_BUFFER_SIZE) {
            eventBuffer.removeFirst();
        }
        eventBuffer.addLast(event);
    }

    private static DiagnosticDeviceEntry toDiagnosticEntry(DeviceInfo device, String note) {
        return new DiagnosticDeviceEntry(
            device.getDeviceId(),
            device.getDeviceName(),
            device.getDeviceType(),
            effectiveStatus(device),
            device.getHostAddress(),
            device.getTcpPort(),
            device.getLastSeenAt(),
            note
        );
    }

    private static boolean isReceiveReady(DeviceInfo device) {
        return isReceiveReadyStatus(effectiveStatus(device));
    }

    private static boolean isReceiveReadyEntry(DiagnosticDeviceEntry device) {
        return isReceiveReadyStatus(device.status());
    }

    private static boolean isReceiveReadyStatus(String status) {
        return ProtocolConstants.STATUS_READY.equalsIgnoreCase(status)
            || ProtocolConstants.STATUS_READY_COMPAT.equalsIgnoreCase(status);
    }

    private static String effectiveStatus(DeviceInfo device) {
        if (device.getStatus() == null || device.getStatus().isBlank()) {
            return ProtocolConstants.STATUS_READY_COMPAT;
        }
        return device.getStatus();
    }

    private static String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return String.join(", ", values);
    }

    private static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatAge(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        Duration age = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now());
        long seconds = Math.max(0, age.toSeconds());
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        return hours + "h ago";
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
