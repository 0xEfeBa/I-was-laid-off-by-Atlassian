package dev.triedge.broker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.triedge.broker.service.ControlPlaneClient;
import dev.triedge.broker.service.ReservationJanitor;
import dev.triedge.broker.service.TenantReservations;
import dev.triedge.broker.service.TenantWorker;
import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.common.testing.TestEtcd;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class BrokerApiIT {

    private static final String INSTANCES = "/v2/service_instances/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantWorker worker;

    @Autowired
    private ReservationJanitor janitor;

    @Autowired
    private EtcdClient etcd;

    @MockitoBean
    private ControlPlaneClient controlPlane;

    private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());
    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final String suffix = UUID.randomUUID().toString().substring(0, 6);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ClusterConnection connection = TestDatabase.connection(5);
        registry.add("triedge.region", () -> "eu");
        registry.add("triedge.clusters.eu.url", connection::url);
        registry.add("triedge.clusters.eu.username", connection::username);
        registry.add("triedge.clusters.eu.password", connection::password);
        registry.add("triedge.broker.etcd-url", TestEtcd::url);
        registry.add("triedge.broker.control-plane-url", () -> "http://control-plane.invalid");
        registry.add("triedge.broker.max-tenants-per-user", () -> "2");
        registry.add("triedge.broker.worker-interval", () -> "PT1H");
        registry.add("triedge.broker.janitor-interval", () -> "PT1H");
        registry.add("server.port", () -> "0");
    }

    @BeforeEach
    void acceptEverything() {
        when(controlPlane.validate(any())).thenReturn(new ControlPlaneClient.Outcome(true, List.of()));
    }

    @Test
    void catalogNeedsASupportedApiVersion() throws Exception {
        mvc.perform(get("/v2/catalog").header(OsbController.API_VERSION_HEADER, "2.11"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.description").value(Matchers.containsString("2.12")));
        mvc.perform(get("/v2/catalog").header(OsbController.API_VERSION_HEADER, "2.17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].id").value("tri-edge-tenant"))
                .andExpect(jsonPath("$.services[0].plans[0].id").value("standard"));
    }

    @Test
    void fullTenantLifecycle() throws Exception {
        String name = "life-" + suffix;
        String first = name + ".test";
        String second = "www." + name + ".test";

        mvc.perform(as(put(INSTANCES + name), owner).content(provision(name, first)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("AsyncRequired"));
        mvc.perform(async(put(INSTANCES + name), owner).content(provision(name, first)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("provision"));
        lastOperation(name, owner).andExpect(jsonPath("$.state").value("in progress"));
        mvc.perform(async(put(INSTANCES + name), owner).content(provision(name, first)))
                .andExpect(status().isAccepted());

        assertThat(worker.drain()).isEqualTo(1);

        lastOperation(name, owner)
                .andExpect(jsonPath("$.state").value("succeeded"))
                .andExpect(jsonPath("$.description").value("Version 1 is active"));
        mvc.perform(async(put(INSTANCES + name), owner).content(provision(name, first)))
                .andExpect(status().isOk());
        mvc.perform(async(put(INSTANCES + name), owner).content(provision(name, second)))
                .andExpect(status().isConflict());
        mvc.perform(as(get(INSTANCES + name), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameters.hosts[0]").value(first));
        assertThat(events(name)).containsExactly("TENANT_VERSION_ACTIVATED");

        mvc.perform(async(patch(INSTANCES + name), owner).content(update(name, second)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("update"));
        mvc.perform(async(patch(INSTANCES + name), owner).content(update(name, first)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("ConcurrencyError"));
        worker.drain();
        mvc.perform(async(patch(INSTANCES + name), owner).content(update(name, second)))
                .andExpect(status().isOk());
        assertThat(etcd.get(TenantReservations.HOSTS + first)).isEmpty();
        assertThat(etcd.get(TenantReservations.HOSTS + second)).isPresent();

        etcd.put("/triedge/tenants/" + name + "/applied/eu", "2");
        mvc.perform(as(get("/api/tenants"), owner))
                .andExpect(jsonPath("$[0].name").value(name))
                .andExpect(jsonPath("$[0].activeVersion").value(2));
        mvc.perform(as(get("/api/tenants/" + name), owner))
                .andExpect(jsonPath("$.applied.eu").value(2))
                .andExpect(jsonPath("$.versions[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.versions[1].state").value("SUPERSEDED"));
        mvc.perform(as(get("/api/tenants/" + name + "/diff?from=1&to=2"), owner))
                .andExpect(jsonPath("$.changes[0].path").value("/hosts/0"))
                .andExpect(jsonPath("$.changes[0].after").value(second));

        mvc.perform(as(post("/api/tenants/" + name + "/rollback"), owner).content("{\"version\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/tenants/" + name + "/rollback"), owner).content("{\"version\":1}"))
                .andExpect(status().isAccepted());
        worker.drain();
        mvc.perform(as(get("/api/tenants/" + name + "/versions/3"), owner))
                .andExpect(jsonPath("$.reason").value("ROLLBACK"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.spec.hosts[0]").value(first));

        mvc.perform(as(get("/api/tenants/" + name), stranger)).andExpect(status().isNotFound());
        mvc.perform(async(delete(INSTANCES + name + "?service_id=tri-edge-tenant&plan_id=standard"), stranger))
                .andExpect(status().isNotFound());
        mvc.perform(async(delete(INSTANCES + name), owner)).andExpect(status().isBadRequest());
        mvc.perform(async(delete(INSTANCES + name + "?service_id=tri-edge-tenant&plan_id=standard"), owner))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("deprovision"));
        worker.drain();

        lastOperation(name, owner).andExpect(status().isGone());
        mvc.perform(async(delete(INSTANCES + name + "?service_id=tri-edge-tenant&plan_id=standard"), owner))
                .andExpect(status().isGone());
        assertThat(events(name)).containsExactly("TENANT_VERSION_ACTIVATED", "TENANT_VERSION_ACTIVATED",
                "TENANT_VERSION_ACTIVATED", "TENANT_DELETED");
        assertThat(etcd.get(TenantReservations.NAMES + name)).isEmpty();
        assertThat(etcd.get(TenantReservations.HOSTS + first)).isEmpty();
        mvc.perform(async(put(INSTANCES + name), stranger).content(provision(name, first)))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectedAndUnvalidatedVersionsFail() throws Exception {
        String name = "rej-" + suffix;
        when(controlPlane.validate(any())).thenReturn(new ControlPlaneClient.Outcome(false, List.of("/hosts: bad")));

        mvc.perform(async(put(INSTANCES + name), owner).content(provision(name, name + ".test")))
                .andExpect(status().isAccepted());
        worker.drain();

        lastOperation(name, owner)
                .andExpect(jsonPath("$.state").value("failed"))
                .andExpect(jsonPath("$.description").value(Matchers.containsString("/hosts: bad")));
        mvc.perform(as(get("/api/tenants/" + name), owner))
                .andExpect(jsonPath("$.versions[0].state").value("REJECTED"));
        assertThat(etcd.get(TenantReservations.HOSTS + name + ".test")).isEmpty();

        when(controlPlane.validate(any())).thenThrow(new IllegalStateException("control plane down"));
        mvc.perform(async(patch(INSTANCES + name), owner).content(update(name, "api." + name + ".test")))
                .andExpect(status().isAccepted());
        worker.drain();
        lastOperation(name, owner).andExpect(jsonPath("$.state").value("in progress"));

        jdbc.sql("""
                UPDATE platform.job_queue SET attempts = max_attempts - 1, run_after = now()
                WHERE queue = 'tenant-validate' AND status = 'PENDING' AND payload ->> 'version' = '2'
                  AND payload ->> 'tenantId' = (SELECT id::text FROM platform.tenants WHERE name = :name AND deleted_at IS NULL)
                """).param("name", name).update();
        worker.drain();

        lastOperation(name, owner)
                .andExpect(jsonPath("$.state").value("failed"))
                .andExpect(jsonPath("$.description").value(Matchers.containsString("could not validate")));
    }

    @Test
    void badRequestsAreRefused() throws Exception {
        String name = "bad-" + suffix;

        mvc.perform(put(INSTANCES + name + "?accepts_incomplete=true").contentType(MediaType.APPLICATION_JSON)
                        .header(OsbController.API_VERSION_HEADER, "2.17").content(provision(name, name + ".test")))
                .andExpect(status().isUnauthorized());
        mvc.perform(async(put(INSTANCES + "other-" + suffix), owner).content(provision(name, name + ".test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.description").value(Matchers.containsString("instance id")));
        mvc.perform(async(put(INSTANCES + name), owner).content("""
                {"service_id":"tri-edge-tenant","plan_id":"standard","parameters":{"name":"%s"}}
                """.formatted(name)))
                .andExpect(status().isBadRequest());
        mvc.perform(async(put(INSTANCES + name), owner).content("{\"service_id\":\"tri-edge-tenant\",\"plan_id\":\"standard\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(async(put(INSTANCES + name), owner)
                        .content(provision(name, name + ".test").replace("standard", "premium")))
                .andExpect(status().isBadRequest());
        mvc.perform(async(put(INSTANCES + "platform"), owner).content(provision("platform", name + ".test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.description").value(Matchers.containsString("reserved")));
        mvc.perform(async(patch(INSTANCES + name), owner).content(update(name, name + ".test")))
                .andExpect(status().isNotFound());
        mvc.perform(async(patch(INSTANCES + name), owner)
                        .content(update(name, name + ".test").replace("tri-edge-tenant", "other")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/tenants/" + name + "/versions/1"), owner)).andExpect(status().isNotFound());
    }

    @Test
    void hostsAndTenantCountsAreLimited() throws Exception {
        UUID busy = UUID.randomUUID();
        mvc.perform(async(put(INSTANCES + "one-" + suffix), busy).content(provision("one-" + suffix, "one-" + suffix + ".test")))
                .andExpect(status().isAccepted());
        mvc.perform(async(put(INSTANCES + "two-" + suffix), busy).content(provision("two-" + suffix, "two-" + suffix + ".test")))
                .andExpect(status().isAccepted());

        mvc.perform(async(put(INSTANCES + "three-" + suffix), busy)
                        .content(provision("three-" + suffix, "three-" + suffix + ".test")))
                .andExpect(status().isForbidden());
        mvc.perform(async(put(INSTANCES + "four-" + suffix), owner)
                        .content(provision("four-" + suffix, "one-" + suffix + ".test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.description").value(Matchers.containsString("already used")));
        mvc.perform(async(put(INSTANCES + "one-" + suffix), owner)
                        .content(provision("one-" + suffix, "one-" + suffix + ".test")))
                .andExpect(status().isConflict());
        mvc.perform(async(put(INSTANCES + "one-" + suffix), UUID.randomUUID(), "ADMIN")
                        .content(provision("one-" + suffix, "one-" + suffix + ".test")))
                .andExpect(status().isAccepted());
        assertThat(etcd.get(TenantReservations.NAMES + "four-" + suffix)).isEmpty();
    }

    @Test
    void janitorReleasesReservationsOfMissingTenants() {
        String abandoned = UUID.randomUUID() + "|eu|0";
        String foreign = UUID.randomUUID() + "|us|0";
        etcd.put(TenantReservations.NAMES + "gone-" + suffix, abandoned);
        etcd.put(TenantReservations.HOSTS + "gone-" + suffix + ".test", abandoned);
        etcd.put(TenantReservations.NAMES + "far-" + suffix, foreign);

        assertThat(janitor.releaseAbandoned()).isGreaterThanOrEqualTo(2);

        assertThat(etcd.get(TenantReservations.NAMES + "gone-" + suffix)).isEmpty();
        assertThat(etcd.get(TenantReservations.HOSTS + "gone-" + suffix + ".test")).isEmpty();
        assertThat(etcd.get(TenantReservations.NAMES + "far-" + suffix)).contains(foreign);
    }

    private ResultActions lastOperation(String name, UUID caller) throws Exception {
        return mvc.perform(as(get(INSTANCES + name + "/last_operation"), caller));
    }

    private List<String> events(String name) {
        return jdbc.sql("""
                SELECT event_type FROM platform.outbox
                WHERE payload ->> 'name' = :name ORDER BY id
                """).param("name", name).query(String.class).list();
    }

    private static MockHttpServletRequestBuilder async(MockHttpServletRequestBuilder request, UUID caller) {
        return async(request, caller, "USER");
    }

    private static MockHttpServletRequestBuilder async(MockHttpServletRequestBuilder request, UUID caller, String role) {
        return as(request, caller, role).queryParam("accepts_incomplete", "true");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID caller) {
        return as(request, caller, "USER");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID caller, String role) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header(OsbController.API_VERSION_HEADER, "2.17")
                .header(Caller.USER_ID_HEADER, caller.toString())
                .header(Caller.USER_ROLE_HEADER, role);
    }

    private static String provision(String name, String host) {
        return """
                {"service_id":"tri-edge-tenant","plan_id":"standard","parameters":%s}
                """.formatted(spec(name, host));
    }

    private static String update(String name, String host) {
        return """
                {"service_id":"tri-edge-tenant","parameters":%s}
                """.formatted(spec(name, host));
    }

    private static String spec(String name, String host) {
        return """
                {"apiVersion":"tri-edge/v1","name":"%s","hosts":["%s"],
                 "backends":[{"name":"main","address":"main.%s.svc:80"}],"routes":[{"path":"/","backend":"main"}]}
                """.formatted(name, host, name);
    }
}
