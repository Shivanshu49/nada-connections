package com.nada;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Routes every request by method and path, and turns any failure into a JSON error. */
class ApiHandler implements HttpHandler {
    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());

    private final PersonStore store;

    ApiHandler(PersonStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ApiException e) {
            Http.sendError(exchange, e.status(), e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "unexpected error", e);
            Http.sendError(exchange, 500, "internal error");
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/people")) {
            requireMethod(exchange, "POST");
            createPerson(exchange);
        } else {
            throw new ApiException(404, "not found");
        }
    }

    private void createPerson(HttpExchange exchange) throws IOException {
        JsonNode body = Http.readObject(exchange);
        JsonNode name = Http.requireField(body, "name");
        if (!name.isTextual() || name.asText().isBlank()) {
            throw new ApiException(400, "name must be a non-blank string");
        }
        // Stored exactly as sent (no trimming), so any script round-trips unchanged.
        Http.send(exchange, 201, store.create(name.asText()));
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!exchange.getRequestMethod().equals(method)) {
            exchange.getResponseHeaders().set("Allow", method);
            throw new ApiException(405, "method not allowed");
        }
    }
}
