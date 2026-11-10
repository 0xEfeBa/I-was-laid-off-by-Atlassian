package dev.triedge.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Capability;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

class TenantEgressIT {

    private static final String TENANT = "10.99.0.1";
    private static final String SERVER = "203.0.113.5";
    private static final String OUTSIDE = "198.51.100.7";

    private static final GenericContainer<?> host = new GenericContainer<>("python:3.13-alpine")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN))
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of(System.getProperty("triedge.tenant-egress")), 0755),
                    "/usr/local/sbin/triedge-tenant-egress")
            .withCommand("sh", "-c", """
                    apk add --no-cache iptables iproute2 curl >/dev/null \
                    && ip addr add %s/32 dev lo && ip addr add %s/32 dev lo \
                    && (python3 -m http.server 8180 >/dev/null 2>&1 &) \
                    && (python3 -m http.server 9000 >/dev/null 2>&1 &) \
                    && echo ready && sleep infinity
                    """.formatted(SERVER, OUTSIDE))
            .waitingFor(Wait.forLogMessage("ready\\n", 1));

    private static String ownAddress;

    @BeforeAll
    static void start() throws Exception {
        host.start();
        ownAddress = run("hostname -i").getStdout().trim();
        for (int i = 0; i < 2; i++) {
            ExecResult setup = run("TENANT_SOURCE=%s TENANT_ALLOW=127.0.0.1:8180 SERVER_IPS=%s triedge-tenant-egress"
                    .formatted(TENANT, SERVER));
            assertThat(setup.getExitCode()).as(setup.getStderr()).isZero();
        }
    }

    @AfterAll
    static void stop() {
        host.stop();
    }

    @Test
    void tenantTrafficCannotReachInternalAddresses() throws Exception {
        assertThat(fromTenant("127.0.0.1:9000")).isFalse();
        assertThat(fromTenant(ownAddress + ":9000")).isFalse();
        assertThat(fromTenant(SERVER + ":9000")).isFalse();
        assertThat(fromTenant("169.254.169.254:80")).isFalse();
    }

    @Test
    void tenantTrafficReachesTheOutsideAndTheSampleBackend() throws Exception {
        assertThat(fromTenant(OUTSIDE + ":9000")).isTrue();
        assertThat(fromTenant("127.0.0.1:8180")).isTrue();
    }

    @Test
    void platformTrafficIsNotAffected() throws Exception {
        assertThat(run("curl -fsS -o /dev/null --max-time 3 http://127.0.0.1:9000/").getExitCode()).isZero();
        assertThat(run("curl -fsS -o /dev/null --max-time 3 http://" + SERVER + ":9000/").getExitCode()).isZero();
    }

    @Test
    void runningTheSetupAgainAddsNothing() throws Exception {
        assertThat(run("iptables -S OUTPUT | grep -c TRIEDGE-TENANT").getStdout().trim()).isEqualTo("1");
        assertThat(run("iptables -t nat -S POSTROUTING | grep -c MASQUERADE").getStdout().trim()).isEqualTo("1");
        assertThat(run("iptables -S TRIEDGE-TENANT | grep -c REJECT").getStdout().trim()).isEqualTo("10");
    }

    private static boolean fromTenant(String address) throws Exception {
        return run("curl -fsS -o /dev/null --max-time 3 --interface " + TENANT + " http://" + address + "/")
                .getExitCode() == 0;
    }

    private static ExecResult run(String command) throws Exception {
        return host.execInContainer("sh", "-c", command);
    }
}
