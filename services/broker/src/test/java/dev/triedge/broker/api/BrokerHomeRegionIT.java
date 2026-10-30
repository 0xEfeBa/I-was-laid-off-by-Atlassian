package dev.triedge.broker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import dev.triedge.broker.service.ControlPlaneClient;
import dev.triedge.broker.service.TenantWorker;
import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.db.HomeClusterFilter;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.common.testing.TestEtcd;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class BrokerHomeRegionIT {

    private static final ClusterConnection EU = TestDatabase.connection(3);
    private static final ClusterConnection US = TestDatabase.connection("broker_us", 3);
    private static final HikariDataSource US_DATA = new HikariDataSource();

    static {
        US_DATA.setJdbcUrl(US.url());
        US_DATA.setUsername(US.username());
        US_DATA.setPassword(US.password());
        US_DATA.setMaximumPoolSize(2);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantWorker worker;

    @MockitoBean
    private ControlPlaneClient controlPlane;

    private final JdbcClient eu = JdbcClient.create(TestDatabase.dataSource());
    private final JdbcClient us = JdbcClient.create(US_DATA);
    private final UUID owner = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("triedge.region", () -> "eu");
        registry.add("triedge.clusters.eu.url", EU::url);
        registry.add("triedge.clusters.eu.username", EU::username);
        registry.add("triedge.clusters.eu.password", EU::password);
        registry.add("triedge.clusters.us.url", US::url);
        registry.add("triedge.clusters.us.username", US::username);
        registry.add("triedge.clusters.us.password", US::password);
        registry.add("triedge.broker.etcd-url", TestEtcd::url);
        registry.add("triedge.broker.control-plane-url", () -> "http://control-plane.invalid");
        registry.add("triedge.broker.leader-namespace", () -> "/test-db");
        registry.add("triedge.broker.worker-interval", () -> "PT1H");
        registry.add("triedge.broker.janitor-interval", () -> "PT1H");
        registry.add("server.port", () -> "0");
    }

    @AfterEach
    void forgetLeaders() {
        TestEtcd.deletePrefix("/test-db/");
    }

    @AfterAll
    static void close() {
        US_DATA.close();
    }

    @Test
    void tenantsLiveInTheOwnersHomeClusterAndAreValidatedWhereItsLeaderIs() throws Exception {
        when(controlPlane.validate(any())).thenReturn(new ControlPlaneClient.Outcome(true, List.of()));
        String name = "home-" + UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(asOwner(put("/v2/service_instances/" + name + "?accepts_incomplete=true"), "us").content("""
                {"service_id":"tri-edge-tenant","plan_id":"standard","parameters":{"apiVersion":"tri-edge/v1",
                 "name":"%s","hosts":["%s.test"],"backends":[{"name":"main","address":"main.svc:80"}],
                 "routes":[{"path":"/","backend":"main"}]}}
                """.formatted(name, name)))
                .andExpect(status().isAccepted());
        assertThat(homeRegion(us, name)).isEqualTo("us");
        assertThat(eu.sql("SELECT count(*) FROM platform.tenants WHERE name = :name")
                .param("name", name).query(Integer.class).single()).isZero();

        TestEtcd.put("/test-db/us/leader", "us-us");
        worker.drain();
        assertThat(state(name)).isEqualTo("in progress");

        TestEtcd.put("/test-db/us/leader", "eu-us");
        worker.drain();
        assertThat(state(name)).isEqualTo("succeeded");
        assertThat(us.sql("SELECT count(*) FROM platform.outbox WHERE payload ->> 'name' = :name")
                .param("name", name).query(Integer.class).single()).isEqualTo(1);

        mvc.perform(asOwner(get("/api/tenants"), "us"))
                .andExpect(jsonPath("$[0].name").value(name))
                .andExpect(jsonPath("$[0].homeRegion").value("us"));
        mvc.perform(asOwner(get("/api/tenants"), "eu"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private String state(String name) {
        return us.sql("SELECT operation_state FROM platform.service_instances WHERE instance_id = :name")
                .param("name", name).query(String.class).single();
    }

    private static String homeRegion(JdbcClient jdbc, String name) {
        return jdbc.sql("SELECT home_region FROM platform.tenants WHERE name = :name")
                .param("name", name).query(String.class).single();
    }

    private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request, String home) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header(OsbController.API_VERSION_HEADER, "2.17")
                .header(Caller.USER_ID_HEADER, owner.toString())
                .header(Caller.USER_ROLE_HEADER, "USER")
                .header(HomeClusterFilter.HOME_REGION_HEADER, home);
    }
}
