package dev.triedge.common.etcd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

class EtcdAuthIT {

    private static final String PASSWORDS = "ETCD_ROOT_PASSWORD=root-pw ETCD_PATRONI_PASSWORD=patroni-pw "
            + "ETCD_IDENTITY_PASSWORD=identity-pw ETCD_BROKER_PASSWORD=broker-pw "
            + "ETCD_CONTROL_PLANE_PASSWORD=control-plane-pw ETCD_EDGE_SIDECAR_PASSWORD=edge-sidecar-pw";

    private static final GenericContainer<?> etcd = new GenericContainer<>(new ImageFromDockerfile("tri-edge-etcd-shell", false)
            .withFileFromString("Dockerfile", """
                    FROM alpine:3.22
                    COPY --from=quay.io/coreos/etcd:v3.6.15 /usr/local/bin/etcd /usr/local/bin/etcdctl /usr/local/bin/
                    """))
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(System.getProperty("triedge.etcd-auth")), 0755),
                    "/usr/local/bin/etcd-auth")
            .withCommand("etcd", "--listen-client-urls=http://0.0.0.0:2379", "--advertise-client-urls=http://localhost:2379",
                    "--auth-token-ttl=1")
            .withExposedPorts(2379)
            .waitingFor(Wait.forHttp("/health").forPort(2379));

    @BeforeAll
    static void start() throws Exception {
        etcd.start();
        for (int i = 0; i < 2; i++) {
            ExecResult setup = etcd.execInContainer("sh", "-c", PASSWORDS + " etcd-auth");
            assertThat(setup.getExitCode()).as(setup.getStderr()).isZero();
        }
    }

    @AfterAll
    static void stop() {
        etcd.stop();
    }

    @Test
    void requestsWithoutCredentialsAreRefused() {
        assertThatThrownBy(() -> client(null).get("/triedge/db/eu/leader"))
                .isInstanceOf(HttpClientErrorException.class);
        assertThatThrownBy(() -> client(new EtcdCredentials("broker", "wrong")).get("/triedge/db/eu/leader"))
                .isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void everyServiceOnlyReachesItsOwnKeys() {
        EtcdClient patroni = client(new EtcdCredentials("patroni", "patroni-pw"));
        EtcdClient broker = client(new EtcdCredentials("broker", "broker-pw"));
        EtcdClient sidecar = client(new EtcdCredentials("edge-sidecar", "edge-sidecar-pw"));

        patroni.put("/triedge/db/eu/leader", "eu-1");
        assertThat(broker.putIfAbsent("/triedge/tenant-hosts/shop.test", "owner")).isTrue();
        assertThat(sidecar.get("/triedge/db/eu/leader")).contains("eu-1");
        assertThat(broker.get("/triedge/db/eu/leader")).contains("eu-1");

        assertThatThrownBy(() -> broker.put("/triedge/db/eu/leader", "broker"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> sidecar.put("/triedge/db/eu/leader", "sidecar"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> client(new EtcdCredentials("identity", "identity-pw")).get("/triedge/tenant-hosts/shop.test"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThat(patroni.get("/triedge/db/eu/leader")).contains("eu-1");
    }

    @Test
    void anExpiredTokenIsRenewed() throws Exception {
        EtcdClient controlPlane = client(new EtcdCredentials("control-plane", "control-plane-pw"));
        controlPlane.put("/triedge/tenants/shop/applied/eu", "1");

        Thread.sleep(2500);

        controlPlane.put("/triedge/tenants/shop/applied/eu", "2");
        assertThat(controlPlane.get("/triedge/tenants/shop/applied/eu")).contains("2");
    }

    private static EtcdClient client(EtcdCredentials credentials) {
        return new EtcdClient(RestClient.create("http://" + etcd.getHost() + ":" + etcd.getMappedPort(2379)), credentials);
    }
}
