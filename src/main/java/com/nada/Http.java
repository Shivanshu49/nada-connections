package com.nada;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small helpers for reading JSON requests and writing JSON responses. */
final class Http {
    // Without this, Jackson would silently accept {"name":"Asha"}garbage.
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Http() {
    }

    static void send(HttpExchange exchange, int status, Object body) throws IOException {
        // Jackson writes UTF-8, so the byte count is the correct Content-Length for any script.
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, Map.of("error", message));
    }

    static void sendNoContent(HttpExchange exchange) throws IOException {
        // -1 tells HttpServer there is no body at all.
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    static JsonNode readObject(HttpExchange exchange) throws IOException {
        JsonNode body;
        try (InputStream in = exchange.getRequestBody()) {
            body = JSON.readTree(in);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "invalid JSON");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "body must be a JSON object");
        }
        return body;
    }

    static JsonNode requireField(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null) {
            throw new ApiException(400, "missing field " + field);
        }
        return value;
    }

    static long requireLong(JsonNode body, String field) {
        JsonNode value = requireField(body, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ApiException(400, field + " must be an integer");
        }
        return value.longValue();
    }

    /** Parses an ID from a path segment or query parameter. */
    static long parseId(String raw, String name) {
        if (raw == null || raw.isEmpty()) {
            throw new ApiException(400, "missing " + name);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(400, name + " must be an integer");
        }
    }

    /** Parses a raw (still URL-encoded) query string such as "from=1&to=5". */
    static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "malformed query string");
        }
    }
}
