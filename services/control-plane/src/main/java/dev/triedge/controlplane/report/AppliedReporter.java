package dev.triedge.controlplane.report;

import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import java.util.HashMap;
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
            published.tenantVersions().forEach((tenant, version) -> {
                if (!version.equals(reported.get(tenant))) {
                    etcd.put(key(tenant, region), Integer.toString(version));
                    reported.put(tenant, version);
                    log.info("Tenant {} version {} is live in {}", tenant, version, region);
                }
            });
            reported.keySet().removeIf(tenant -> {
                if (published.tenantVersions().containsKey(tenant)) {
                    return false;
                }
                etcd.delete(key(tenant, region));
                return true;
            });
        } catch (RuntimeException e) {
            log.warn("Could not report applied versions: {}", e.getMessage());
        }
    }
}
