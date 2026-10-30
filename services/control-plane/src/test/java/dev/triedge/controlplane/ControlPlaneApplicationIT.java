package dev.triedge.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.common.testing.TestEtcd;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class ControlPlaneApplicationIT {

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ClusterConnection connection = TestDatabase.connection(2);
        registry.add("triedge.region", () -> "eu");
        registry.add("triedge.clusters.eu.url", connection::url);
        registry.add("triedge.clusters.eu.username", connection::username);
        registry.add("triedge.clusters.eu.password", connection::password);
        registry.add("triedge.control-plane.xds-port", () -> "0");
        registry.add("triedge.control-plane.etcd-url", TestEtcd::url);
        registry.add("triedge.control-plane.system-tenants-dir", () -> System.getProperty("triedge.system-tenants"));
        registry.add("triedge.control-plane.envoy-validation", () -> "false");
        registry.add("server.port", () -> "0");
    }

    @Test
    void validatesCandidateTenants() throws Exception {
        mvc.perform(validate("""
                {"apiVersion":"tri-edge/v1","name":"shop","hosts":["shop.test"],
                 "backends":[{"name":"api","address":"api.t-shop.svc:80"}],"routes":[{"path":"/","backend":"api"}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mvc.perform(validate("""
                {"apiVersion":"tri-edge/v1","name":"shop","hosts":["localhost"],
                 "backends":[{"name":"api","address":"identity:8130"}],"routes":[{"path":"/","backend":"api"}]}
                """))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors.length()").value(2));

        mvc.perform(validate("{\"name\":\"incomplete\"}"))
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void reportsStatus() throws Exception {
        mvc.perform(get("/internal/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.connectedNodes").value(0));
    }

    private static MockHttpServletRequestBuilder validate(String body) {
        return post("/internal/v1/validate").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
