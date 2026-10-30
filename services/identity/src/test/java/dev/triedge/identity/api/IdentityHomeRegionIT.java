package dev.triedge.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.db.HomeClusterFilter;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.common.testing.TestEtcd;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityHomeRegionIT {

    private static final String ORIGIN = "http://app.test";
    private static final String PASSWORD = "correct horse battery staple";
    private static final ClusterConnection EU = TestDatabase.connection(3);
    private static final ClusterConnection US = TestDatabase.connection("identity_us", 3);
    private static final HikariDataSource US_DATA = dataSource(US);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    private final JdbcClient eu = JdbcClient.create(TestDatabase.dataSource());
    private final JdbcClient us = JdbcClient.create(US_DATA);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("triedge.region", () -> "eu");
        registry.add("triedge.clusters.eu.url", EU::url);
        registry.add("triedge.clusters.eu.username", EU::username);
        registry.add("triedge.clusters.eu.password", EU::password);
        registry.add("triedge.clusters.us.url", US::url);
        registry.add("triedge.clusters.us.username", US::username);
        registry.add("triedge.clusters.us.password", US::password);
        registry.add("triedge.identity.etcd-url", TestEtcd::url);
        registry.add("triedge.identity.allow-ephemeral-key", () -> "true");
        registry.add("triedge.identity.secure-cookies", () -> "false");
        registry.add("triedge.identity.allowed-origins", () -> ORIGIN);
        registry.add("triedge.identity.argon2-memory-kib", () -> "1024");
        registry.add("triedge.identity.argon2-iterations", () -> "1");
        registry.add("triedge.identity.janitor-interval", () -> "PT1H");
        registry.add("server.port", () -> "0");
    }

    @AfterAll
    static void close() {
        US_DATA.close();
    }

    @Test
    void usersLiveInTheirHomeClusterAndAreServedFromAnyRegion() throws Exception {
        String username = "home-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = body(mvc.perform(post("/v1/signup").contentType(MediaType.APPLICATION_JSON)
                        .header(HomeClusterFilter.HOME_REGION_HEADER, "us")
                        .content(signup(username)))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asString());
        assertThat(count(us, "identity.users", id)).isEqualTo(1);
        assertThat(count(eu, "identity.users", id)).isZero();

        Cookie refresh = mvc.perform(post("/v1/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("triedge_refresh");
        assertThat(refresh).isNotNull();
        assertThat(refresh.getValue()).startsWith("us.");
        assertThat(count(us, "identity.refresh_tokens", id)).isEqualTo(1);

        mvc.perform(post("/v1/token/refresh").cookie(refresh).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(id.toString()));
        assertThat(count(us, "identity.refresh_tokens", id)).isEqualTo(2);

        mvc.perform(get("/v1/users/me").header(Caller.USER_ID_HEADER, id.toString())
                        .header(Caller.USER_ROLE_HEADER, "USER").header(HomeClusterFilter.HOME_REGION_HEADER, "us"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeRegion").value("us"));
        mvc.perform(get("/v1/users/" + id).header(Caller.USER_ID_HEADER, UUID.randomUUID().toString())
                        .header(Caller.USER_ROLE_HEADER, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));

        mvc.perform(post("/v1/admin/users/" + id + "/ban").header(Caller.USER_ID_HEADER, UUID.randomUUID().toString())
                        .header(Caller.USER_ROLE_HEADER, "ADMIN").header(HomeClusterFilter.HOME_REGION_HEADER, "eu"))
                .andExpect(status().isOk());
        assertThat(us.sql("SELECT banned_at IS NOT NULL FROM identity.users WHERE id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(us.sql("SELECT count(*) FROM identity.outbox WHERE aggregate_id = :id AND event_type = 'USER_BANNED'")
                .param("id", id.toString()).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(post("/v1/admin/users/" + UUID.randomUUID() + "/ban")
                        .header(Caller.USER_ID_HEADER, UUID.randomUUID().toString()).header(Caller.USER_ROLE_HEADER, "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void signupWithoutAHomeHeaderStaysInTheLocalRegion() throws Exception {
        String username = "local-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = body(mvc.perform(post("/v1/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signup(username)))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asString());

        assertThat(count(eu, "identity.users", id)).isEqualTo(1);
        assertThat(count(us, "identity.users", id)).isZero();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String signup(String username) {
        return "{\"username\":\"%s\",\"email\":\"%s@example.com\",\"password\":\"%s\"}".formatted(username, username, PASSWORD);
    }

    private static int count(JdbcClient jdbc, String table, UUID userId) {
        String column = table.equals("identity.users") ? "id" : "user_id";
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id")
                .param("id", userId).query(Integer.class).single();
    }

    private static HikariDataSource dataSource(ClusterConnection connection) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(connection.url());
        dataSource.setUsername(connection.username());
        dataSource.setPassword(connection.password());
        dataSource.setMaximumPoolSize(2);
        return dataSource;
    }
}
