package dev.triedge.controlplane.xds;

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AckTracker implements DiscoveryServerCallbacks {

    public record Nack(String nodeId, String typeUrl, long version, String message) {
    }

    private record Sent(String typeUrl, long version) {
    }

    private static final class Stream {
        private volatile @Nullable String nodeId;
        private final Map<String, Sent> sent = new ConcurrentHashMap<>();
        private final Map<String, Long> acked = new ConcurrentHashMap<>();
    }

    private static final Logger log = LoggerFactory.getLogger(AckTracker.class);
    private static final int MAX_NACKS = 50;

    private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
    private final List<Nack> nacks = new CopyOnWriteArrayList<>();
    private final LongSupplier currentVersion;

    public AckTracker(LongSupplier currentVersion) {
        this.currentVersion = currentVersion;
    }

    @Override
    public void onStreamOpen(long streamId, String typeUrl) {
        streams.putIfAbsent(streamId, new Stream());
    }

    @Override
    public void onStreamClose(long streamId, String typeUrl) {
        streams.remove(streamId);
    }

    @Override
    public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
        streams.remove(streamId);
    }

    @Override
    public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
        Stream stream = stream(streamId);
        if (request.hasNode()) {
            stream.nodeId = request.getNode().getId();
        }
        received(stream, request.getResponseNonce(), request.hasErrorDetail() ? request.getErrorDetail().getMessage() : null);
    }

    @Override
    public void onV3StreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
        stream(streamId).sent.put(response.getNonce(), new Sent(response.getTypeUrl(), parse(response.getVersionInfo())));
    }

    @Override
    public void onV3StreamDeltaRequest(long streamId, DeltaDiscoveryRequest request) {
        Stream stream = stream(streamId);
        if (request.hasNode()) {
            stream.nodeId = request.getNode().getId();
        }
        received(stream, request.getResponseNonce(), request.hasErrorDetail() ? request.getErrorDetail().getMessage() : null);
    }

    @Override
    public void onV3StreamDeltaResponse(long streamId, DeltaDiscoveryRequest request, DeltaDiscoveryResponse response) {
        stream(streamId).sent.put(response.getNonce(), new Sent(response.getTypeUrl(), parse(response.getSystemVersionInfo())));
    }

    public int connectedNodes() {
        return (int) streams.values().stream().filter(s -> s.nodeId != null).count();
    }

    public boolean allSynced(Map<String, Long> required) {
        return streams.values().stream()
                .filter(s -> s.nodeId != null)
                .allMatch(s -> required.entrySet().stream()
                        .allMatch(r -> s.acked.getOrDefault(r.getKey(), -1L) >= r.getValue()));
    }

    public List<Nack> recentNacks() {
        return List.copyOf(nacks);
    }

    private void received(Stream stream, String nonce, @Nullable String error) {
        if (nonce.isEmpty()) {
            return;
        }
        Sent sent = stream.sent.remove(nonce);
        if (sent == null) {
            return;
        }
        if (error == null) {
            stream.acked.merge(sent.typeUrl(), sent.version(), Math::max);
            return;
        }
        Nack nack = new Nack(String.valueOf(stream.nodeId), sent.typeUrl(), sent.version(), error);
        log.warn("Envoy {} rejected {} version {}: {}", nack.nodeId(), nack.typeUrl(), nack.version(), error);
        nacks.add(nack);
        while (nacks.size() > MAX_NACKS) {
            nacks.removeFirst();
        }
    }

    private Stream stream(long streamId) {
        return streams.computeIfAbsent(streamId, id -> new Stream());
    }

    private long parse(String version) {
        if (version.startsWith("v")) {
            try {
                return Long.parseLong(version.substring(1));
            } catch (NumberFormatException e) {
                return currentVersion.getAsLong();
            }
        }
        return currentVersion.getAsLong();
    }
}
