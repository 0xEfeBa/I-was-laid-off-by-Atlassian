package dev.triedge.common.region;

import dev.triedge.common.etcd.EtcdClient;
import java.util.Optional;
import java.util.regex.Pattern;

public class LeaderResolver {

    private static final Pattern CLUSTER_NAME = Pattern.compile("[a-z0-9-]+");

    private final EtcdClient etcd;
    private final String namespace;

    public LeaderResolver(EtcdClient etcd, String namespace) {
        this.etcd = etcd;
        this.namespace = namespace;
    }

    public Optional<String> leaderRegion(String cluster) {
        if (!CLUSTER_NAME.matcher(cluster).matches()) {
            throw new IllegalArgumentException("Invalid cluster name: " + cluster);
        }
        return etcd.get(namespace + "/" + cluster + "/leader").map(LeaderResolver::regionOf);
    }

    public boolean leads(String cluster, String localRegion) {
        return leaderRegion(cluster).map(localRegion::equals).orElse(cluster.equals(localRegion));
    }

    static String regionOf(String member) {
        int dash = member.indexOf('-');
        return dash < 0 ? member : member.substring(0, dash);
    }
}
