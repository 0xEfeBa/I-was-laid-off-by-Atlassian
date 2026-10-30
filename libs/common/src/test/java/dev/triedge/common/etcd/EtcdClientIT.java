package dev.triedge.common.etcd;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.testing.TestEtcd;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EtcdClientIT {

    private final EtcdClient etcd = new EtcdClient(TestEtcd.client());

    @BeforeEach
    void clean() {
        TestEtcd.deletePrefix("/test/");
    }

    @Test
    void putIfAbsentOnlyWinsOnce() {
        assertThat(etcd.putIfAbsent("/test/name", "first")).isTrue();
        assertThat(etcd.putIfAbsent("/test/name", "second")).isFalse();
        assertThat(etcd.get("/test/name")).contains("first");
    }

    @Test
    void deleteIfValueRequiresTheExpectedValue() {
        etcd.put("/test/name", "mine");

        assertThat(etcd.deleteIfValue("/test/name", "theirs")).isFalse();
        assertThat(etcd.get("/test/name")).contains("mine");
        assertThat(etcd.deleteIfValue("/test/name", "mine")).isTrue();
        assertThat(etcd.get("/test/name")).isEmpty();
    }

    @Test
    void putAllIfUnchangedIsAllOrNothing() {
        etcd.put("/test/kept", "mine");
        etcd.put("/test/taken", "theirs");

        assertThat(etcd.putAllIfUnchanged(Map.of("/test/a", "1", "/test/taken", "2"), Map.of())).isFalse();
        assertThat(etcd.putAllIfUnchanged(Map.of("/test/a", "1"), Map.of("/test/kept", "other"))).isFalse();
        assertThat(etcd.get("/test/a")).isEmpty();
        assertThat(etcd.putAllIfUnchanged(Map.of("/test/a", "1", "/test/b", "2"), Map.of("/test/kept", "mine"))).isTrue();
        assertThat(etcd.getPrefix("/test/")).containsKeys("/test/a", "/test/b");
        assertThat(etcd.putAllIfUnchanged(Map.of(), Map.of())).isTrue();
    }

    @Test
    void deleteRemovesAKey() {
        etcd.put("/test/gone", "x");

        etcd.delete("/test/gone");

        assertThat(etcd.get("/test/gone")).isEmpty();
    }

    @Test
    void listsEverythingUnderAPrefix() {
        etcd.put("/test/a", "1");
        etcd.put("/test/b", "2");
        etcd.put("/testing/c", "3");

        assertThat(etcd.getPrefix("/test/")).containsExactly(
                Map.entry("/test/a", "1"),
                Map.entry("/test/b", "2"));
        assertThat(etcd.getPrefix("/nothing/")).isEmpty();
    }
}
