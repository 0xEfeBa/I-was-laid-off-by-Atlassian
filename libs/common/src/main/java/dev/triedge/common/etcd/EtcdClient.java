package dev.triedge.common.etcd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

public class EtcdClient {

    static final int MAX_TXN_OPERATIONS = 100;

    private final RestClient rest;
    private final @Nullable EtcdCredentials credentials;
    private volatile @Nullable String token;

    public EtcdClient(RestClient rest) {
        this(rest, null);
    }

    public EtcdClient(RestClient rest, @Nullable EtcdCredentials credentials) {
        this.rest = rest;
        this.credentials = credentials;
    }

    public Optional<String> get(String key) {
        RangeResponse response = call("/v3/kv/range", Map.of("key", encode(key)), RangeResponse.class);
        if (response == null || response.kvs() == null || response.kvs().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(decode(response.kvs().getFirst().value()));
    }

    public Map<String, String> getPrefix(String prefix) {
        RangeResponse response = call("/v3/kv/range",
                Map.of("key", encode(prefix), "range_end", prefixEnd(prefix)), RangeResponse.class);
        Map<String, String> result = new LinkedHashMap<>();
        if (response != null && response.kvs() != null) {
            for (KeyValue kv : response.kvs()) {
                result.put(decode(kv.key()), decode(kv.value()));
            }
        }
        return result;
    }

    public void put(String key, String value) {
        call("/v3/kv/put", Map.of("key", encode(key), "value", encode(value)), Map.class);
    }

    public void delete(String key) {
        call("/v3/kv/deleterange", Map.of("key", encode(key)), Map.class);
    }

    public void apply(Map<String, String> puts, Collection<String> deletes) {
        List<Map<String, Object>> operations = new ArrayList<>();
        puts.forEach((key, value) -> operations.add(
                Map.of("request_put", Map.of("key", encode(key), "value", encode(value)))));
        deletes.forEach(key -> operations.add(Map.of("request_delete_range", Map.of("key", encode(key)))));
        for (int start = 0; start < operations.size(); start += MAX_TXN_OPERATIONS) {
            List<Map<String, Object>> chunk = operations.subList(start,
                    Math.min(start + MAX_TXN_OPERATIONS, operations.size()));
            call("/v3/kv/txn", Map.of("success", chunk), TxnResponse.class);
        }
    }

    public boolean putIfAbsent(String key, String value) {
        Map<String, Object> request = Map.of(
                "compare", List.of(Map.of(
                        "key", encode(key),
                        "result", "EQUAL",
                        "target", "CREATE",
                        "create_revision", "0")),
                "success", List.of(Map.of(
                        "request_put", Map.of("key", encode(key), "value", encode(value)))));
        TxnResponse response = call("/v3/kv/txn", request, TxnResponse.class);
        return response != null && Boolean.TRUE.equals(response.succeeded());
    }

    public boolean putAllIfUnchanged(Map<String, String> create, Map<String, String> unchanged) {
        List<Map<String, Object>> compare = new ArrayList<>();
        List<Map<String, Object>> success = new ArrayList<>();
        create.forEach((key, value) -> {
            compare.add(Map.of("key", encode(key), "result", "EQUAL", "target", "CREATE", "create_revision", "0"));
            success.add(Map.of("request_put", Map.of("key", encode(key), "value", encode(value))));
        });
        unchanged.forEach((key, value) -> compare.add(
                Map.of("key", encode(key), "result", "EQUAL", "target", "VALUE", "value", encode(value))));
        if (compare.isEmpty()) {
            return true;
        }
        TxnResponse response = call("/v3/kv/txn", Map.of("compare", compare, "success", success), TxnResponse.class);
        return response != null && Boolean.TRUE.equals(response.succeeded());
    }

    public boolean deleteIfValue(String key, String expected) {
        Map<String, Object> request = Map.of(
                "compare", List.of(Map.of(
                        "key", encode(key),
                        "result", "EQUAL",
                        "target", "VALUE",
                        "value", encode(expected))),
                "success", List.of(Map.of(
                        "request_delete_range", Map.of("key", encode(key)))));
        TxnResponse response = call("/v3/kv/txn", request, TxnResponse.class);
        return response != null && Boolean.TRUE.equals(response.succeeded());
    }

    private <T> @Nullable T call(String path, Object body, Class<T> responseType) {
        if (credentials == null) {
            return send(path, body, responseType, null);
        }
        String current = token;
        try {
            return send(path, body, responseType, current != null ? current : authenticate(null));
        } catch (HttpClientErrorException.Unauthorized e) {
            return send(path, body, responseType, authenticate(current));
        }
    }

    private <T> @Nullable T send(String path, Object body, Class<T> responseType, @Nullable String authToken) {
        RestClient.RequestBodySpec request = rest.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (authToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, authToken);
        }
        return request.body(body).retrieve().body(responseType);
    }

    private synchronized String authenticate(@Nullable String rejected) {
        String current = token;
        if (current != null && !current.equals(rejected)) {
            return current;
        }
        EtcdCredentials login = Objects.requireNonNull(credentials);
        AuthResponse response = send("/v3/auth/authenticate",
                Map.of("name", login.username(), "password", login.password()), AuthResponse.class, null);
        if (response == null || response.token() == null) {
            throw new IllegalStateException("etcd did not return a token for " + login.username());
        }
        token = response.token();
        return response.token();
    }

    private static String prefixEnd(String prefix) {
        byte[] end = prefix.getBytes(StandardCharsets.UTF_8);
        end[end.length - 1]++;
        return Base64.getEncoder().encodeToString(end);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RangeResponse(@Nullable List<KeyValue> kvs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KeyValue(String key, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TxnResponse(@Nullable Boolean succeeded) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthResponse(@Nullable String token) {
    }
}
