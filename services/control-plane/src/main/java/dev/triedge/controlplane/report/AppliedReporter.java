package dev.triedge.controlplane.report;

import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class AppliedReporter {

    public static final String PREFIX = "/triedge/tenants/";

    private static final Logger log = LoggerFactory.getLogger(AppliedReporter.class);

    private final SnapshotPublisher publisher;
    private final AckTracker tracker;
    private final EtcdClient etcd;
    private final String region;
    private final Map<String, Integer> reported = new HashMap<>();
    private boolean loaded;

    public AppliedReporter(SnapshotPublisher publisher, AckTracker tracker, EtcdClient etcd, String region) {
        this.publisher = publisher;
        this.tracker = tracker;
        this.etcd = etcd;
        this.region = region;
    }

    public static String key(String tenant, String region) {
        return PREFIX + tenant + "/applied/" + region;
    }

    @Scheduled(fixedDelayString = "${triedge.control-plane.report-interval:PT0.2S}")
    public synchronized void report() {
        SnapshotPublisher.Published published = publisher.latest();
        if (published == null || tracker.connectedNodes() == 0 || !tracker.allSynced(published.requiredAcks())) {
            return;
        }
        try {
            if (!loaded) {
                reported.clear();
                reported.putAll(appliedHere());
                loaded = true;
            }
            Map<String, Integer> live = published.tenantVersions();
            Map<String, String> puts = new HashMap<>();
            live.forEach((tenant, version) -> {
                if (!version.equals(reported.get(tenant))) {
                    puts.put(key(tenant, region), Integer.toString(version));
                }
            });
            List<String> gone = reported.keySet().stream().filter(tenant -> !live.containsKey(tenant)).toList();
            if (puts.isEmpty() && gone.isEmpty()) {
                return;
            }
            etcd.apply(puts, gone.stream().map(tenant -> key(tenant, region)).toList());
            live.forEach((tenant, version) -> {
                if (!version.equals(reported.put(tenant, version))) {
                    log.info("Tenant {} version {} is live in {}", tenant, version, region);
                }
            });
            gone.forEach(reported::remove);
        } catch (RuntimeException e) {
            log.warn("Could not report applied versions: {}", e.getMessage());
        }
    }

    private Map<String, Integer> appliedHere() {
        String suffix = "/applied/" + region;
        Map<String, Integer> found = new HashMap<>();
        etcd.getPrefix(PREFIX).forEach((key, value) -> {
            if (key.endsWith(suffix)) {
                try {
                    found.put(key.substring(PREFIX.length(), key.length() - suffix.length()), Integer.valueOf(value));
                } catch (NumberFormatException e) {
                    found.put(key.substring(PREFIX.length(), key.length() - suffix.length()), -1);
                }
            }
        });
        return found;
    }
}
