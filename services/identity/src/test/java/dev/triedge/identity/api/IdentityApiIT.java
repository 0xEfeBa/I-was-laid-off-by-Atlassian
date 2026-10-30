package dev.triedge.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.testing.TestDatabase;
import dev.triedge.common.testing.TestEtcd;
import dev.triedge.identity.service.ReservationJanitor;
import dev.triedge.identity.service.UsernameReservation;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityApiIT {

    private static final String ORIGIN = "http://app.test";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private ReservationJanitor janitor;

    @Autowired
    private UsernameReservation reservations;

    private final JdbcClient jdbc = JdbcClient.create(TestDatabase.dataSource());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ClusterConnection connection = TestDatabase.connection(5);
        registry.add("triedge.region", () -> "eu");
        registry.add("triedge.clusters.eu.url", connection::url);
        registry.add("triedge.clusters.eu.username", connection::username);
        registry.add("triedge.clusters.eu.password", connection::password);
        registry.add("triedge.identity.etcd-url", TestEtcd::url);
        registry.add("triedge.identity.allow-ephemeral-key", () -> "true");
        registry.add("triedge.identity.secure-cookies", () -> "false");
        registry.add("triedge.identity.allowed-origins", () -> ORIGIN);
        registry.add("triedge.identity.argon2-memory-kib", () -> "1024");
        registry.add("triedge.identity.argon2-iterations", () -> "1");
        registry.add("triedge.identity.janitor-interval", () -> "PT1H");
        registry.add("server.port", () -> "0");
    }

    @Test
    void signupLoginAndManageTokens() throws Exception {
        String username = unique();
        JsonNode user = body(mvc.perform(jsonPost("/v1/signup", signup(username)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn());
        UUID id = UUID.fromString(user.get("id").asString());

        mvc.perform(jsonPost("/v1/login", login(username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(120))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("SameSite=Strict")));

        mvc.perform(as(get("/v1/users/me"), id, "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.homeRegion").value("eu"));

        JsonNode created = body(mvc.perform(as(jsonPost("/v1/tokens",
                        "{\"name\":\"laptop\",\"scopes\":[\"git:read\",\"git:write\"],\"expiresInDays\":30}"), id, "USER"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(created.get("value").asString()).startsWith("tep_");
        UUID tokenId = UUID.fromString(created.get("token").get("id").asString());

        mvc.perform(as(get("/v1/tokens"), id, "USER"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("laptop"));
        assertThat(events(id.toString())).contains("USER_CREATED");

        mvc.perform(as(delete("/v1/tokens/" + tokenId), id, "USER")).andExpect(status().isNoContent());
        mvc.perform(as(delete("/v1/tokens/" + tokenId), id, "USER")).andExpect(status().isNotFound());
        mvc.perform(as(get("/v1/tokens"), id, "USER")).andExpect(jsonPath("$.length()").value(0));
        assertThat(events(tokenId.toString())).containsExactly("PAT_CREATED", "PAT_REVOKED");
    }

    @Test
    void rejectsInvalidSignups() throws Exception {
        String username = unique();
        mvc.perform(jsonPost("/v1/signup", signup(username))).andExpect(status().isCreated());

        mvc.perform(jsonPost("/v1/signup", signup(username))).andExpect(status().isConflict());
        mvc.perform(jsonPost("/v1/signup", "{\"username\":\"Bad Name\",\"email\":\"a@b.io\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonPost("/v1/signup", "{\"username\":\"" + unique() + "\",\"email\":\"nope\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonPost("/v1/signup", "{\"username\":\"" + unique() + "\",\"email\":\"a@b.io\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailReleasesTheUsername() throws Exception {
        String first = unique();
        mvc.perform(jsonPost("/v1/signup", signup(first))).andExpect(status().isCreated());
        String second = unique();

        mvc.perform(jsonPost("/v1/signup",
                        "{\"username\":\"" + second + "\",\"email\":\"" + first + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict());

        assertThat(reservations.find(second)).isEmpty();
    }

    @Test
    void wrongPasswordsAreThrottled() throws Exception {
        String username = unique();
        mvc.perform(jsonPost("/v1/signup", signup(username))).andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            mvc.perform(jsonPost("/v1/login", login(username, "not the password"))).andExpect(status().isUnauthorized());
        }
        mvc.perform(jsonPost("/v1/login", login(username, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        mvc.perform(jsonPost("/v1/login", login(unique(), PASSWORD))).andExpect(status().isUnauthorized());
        mvc.perform(jsonPost("/v1/login", "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void refreshRotatesAndDetectsReuse() throws Exception {
        String username = unique();
        mvc.perform(jsonPost("/v1/signup", signup(username))).andExpect(status().isCreated());
        Cookie first = refreshCookie(mvc.perform(jsonPost("/v1/login", login(username, PASSWORD))).andReturn());

        Cookie second = refreshCookie(mvc.perform(post("/v1/token/refresh").cookie(first).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(username))
                .andReturn());

        mvc.perform(post("/v1/token/refresh").cookie(first).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/token/refresh").cookie(second).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/token/refresh").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieEndpointsRequireAnAllowedOrigin() throws Exception {
        mvc.perform(post("/v1/token/refresh")).andExpect(status().isForbidden());
        mvc.perform(post("/v1/token/refresh").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/logout").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String username = unique();
        mvc.perform(jsonPost("/v1/signup", signup(username))).andExpect(status().isCreated());
        Cookie cookie = refreshCookie(mvc.perform(jsonPost("/v1/login", login(username, PASSWORD))).andReturn());

        mvc.perform(post("/v1/logout").cookie(cookie).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isNoContent());
        mvc.perform(post("/v1/logout").header(HttpHeaders.ORIGIN, ORIGIN)).andExpect(status().isNoContent());

        mvc.perform(post("/v1/token/refresh").cookie(cookie).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanBanAndBannedUserLosesAccess() throws Exception {
        String username = unique();
        UUID id = UUID.fromString(body(mvc.perform(jsonPost("/v1/signup", signup(username))).andReturn())
                .get("id").asString());
        mvc.perform(as(jsonPost("/v1/tokens", "{\"name\":\"ci\",\"scopes\":[\"git:read\"]}"), id, "USER"))
                .andExpect(status().isCreated());

        mvc.perform(as(post("/v1/admin/users/" + id + "/ban"), id, "USER")).andExpect(status().isForbidden());
        mvc.perform(as(post("/v1/admin/users/" + id + "/ban"), UUID.randomUUID(), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bannedAt").exists());
        mvc.perform(as(post("/v1/admin/users/" + id + "/ban"), UUID.randomUUID(), "ADMIN"))
                .andExpect(status().isNotFound());

        mvc.perform(jsonPost("/v1/login", login(username, PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(as(get("/v1/tokens"), id, "USER")).andExpect(jsonPath("$.length()").value(0));
        assertThat(events(id.toString())).containsExactly("USER_CREATED", "USER_BANNED");
    }

    @Test
    void requestsWithoutAVerifiedCallerAreRejected() throws Exception {
        mvc.perform(get("/v1/tokens")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/tokens").header(Caller.USER_ID_HEADER, "not-a-uuid").header(Caller.USER_ROLE_HEADER, "USER"))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(get("/v1/users/" + UUID.randomUUID()), UUID.randomUUID(), "USER"))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/v1/users/me"), UUID.randomUUID(), "USER")).andExpect(status().isNotFound());
    }

    @Test
    void publicUserProfileShowsOnlyIdAndName() throws Exception {
        String username = unique();
        String id = body(mvc.perform(jsonPost("/v1/signup", signup(username))).andReturn()).get("id").asString();

        mvc.perform(as(get("/v1/users/" + id), UUID.randomUUID(), "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void publishesSigningKeys() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void janitorReleasesAbandonedReservations() {
        String username = unique();
        reservations.reserve(username,
                new UsernameReservation.Reservation("eu", UUID.randomUUID(), Instant.now().minusSeconds(3600)));

        assertThat(janitor.releaseAbandoned()).isGreaterThanOrEqualTo(1);
        assertThat(reservations.find(username)).isEmpty();
    }

    private static String unique() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String signup(String username) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.com\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static String login(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private static MockHttpServletRequestBuilder jsonPost(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID id, String role) {
        return request.header(Caller.USER_ID_HEADER, id.toString()).header(Caller.USER_ROLE_HEADER, role);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("triedge_refresh");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private List<String> events(String aggregateId) {
        return jdbc.sql("SELECT event_type FROM identity.outbox WHERE aggregate_id = :id ORDER BY txid, id")
                .param("id", aggregateId)
                .query(String.class)
                .list();
    }
}
