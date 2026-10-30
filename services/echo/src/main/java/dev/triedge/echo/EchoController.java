package dev.triedge.echo;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EchoController {

    static final int MAX_BODY_BYTES = 4096;

    private final String instance;

    public EchoController(@Value("${triedge.echo.instance}") String instance) {
        this.instance = instance;
    }

    @GetMapping("/fail")
    public ResponseEntity<Map<String, Object>> fail(@RequestParam(defaultValue = "500") int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved == null || !resolved.isError()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status must be a 4xx or 5xx code"));
        }
        return ResponseEntity.status(resolved).body(Map.of("status", status, "instance", instance));
    }

    @RequestMapping("/**")
    public Map<String, Object> echo(HttpServletRequest request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instance", instance);
        body.put("method", request.getMethod());
        body.put("path", request.getRequestURI());
        body.put("query", request.getQueryString());
        body.put("headers", headers(request));
        body.put("body", body(request));
        return body;
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", Collections.list(request.getHeaders(name))));
        }
        return headers;
    }

    private static @Nullable String body(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES);
        return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
