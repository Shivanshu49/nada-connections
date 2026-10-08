package com.nada;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * End-to-end tests over real HTTP. One server is shared, so each test creates its own people
 * and uses the IDs it gets back rather than assuming 1, 2, 3.
 */
class ApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final long UNKNOWN_ID = 999_999;

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws Exception {
        server = App.start(0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    // POST /people

    @Test
    void hindiNameRoundTripsByteForByte() throws Exception {
        HttpRequest request = request("POST", "/people", "{\"name\":\"आशा\"}");
        HttpResponse<byte[]> response = CLIENT.send(request, BodyHandlers.ofByteArray());

        assertEquals(201, response.statusCode());
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(response.body().length, response.headers().firstValueAsLong("Content-Length").orElseThrow());
        long id = JSON.readTree(response.body()).get("id").asLong();
        String expected = "{\"id\":" + id + ",\"name\":\"आशा\"}";
        assertEquals(expected, new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void nameIsStoredWithoutTrimming() throws Exception {
        JsonNode body = JSON.readTree(post("/people", "{\"name\":\"  Asha  \"}").body());

        assertEquals("  Asha  ", body.get("name").asText());
    }

    @Test
    void blankNameIs400() throws Exception {
        assertError(400, post("/people", "{\"name\":\"   \"}"));
        assertError(400, post("/people", "{\"name\":\"\"}"));
        assertError(400, post("/people", "{\"name\":42}"));
    }

    @Test
    void invalidJsonIs400() throws Exception {
        assertError(400, post("/people", "{\"name\":"));
        assertError(400, post("/people", "[\"Asha\"]"));
        assertError(400, post("/knows", "not json"));
    }

    @Test
    void missingFieldIs400() throws Exception {
        assertError(400, post("/people", "{}"));
        assertError(400, post("/knows", "{\"a\":1}"));
    }

    // POST /knows

    @Test
    void connectingSomeoneToThemselvesIs400() throws Exception {
        long asha = createPerson("Asha");

        assertError(400, knows(asha, asha));
        // 400 wins over 404 even when the person does not exist.
        assertError(400, knows(UNKNOWN_ID, UNKNOWN_ID));
    }

    @Test
    void nonIntegerIdIs400() throws Exception {
        assertError(400, post("/knows", "{\"a\":1.5,\"b\":2}"));
        assertError(400, post("/knows", "{\"a\":\"1\",\"b\":2}"));
    }

    @Test
    void unknownPersonIs404() throws Exception {
        long asha = createPerson("Asha");

        HttpResponse<String> response = knows(asha, UNKNOWN_ID);

        assertError(404, response);
        assertEquals("no person " + UNKNOWN_ID, JSON.readTree(response.body()).get("error").asText());
        assertError(404, get("/people/" + UNKNOWN_ID + "/contacts"));
    }

    @Test
    void repeatingAConnectionIs204Again() throws Exception {
        long asha = createPerson("Asha");
        long rohan = createPerson("Rohan");

        assertEquals(204, knows(asha, rohan).statusCode());
        assertEquals(204, knows(asha, rohan).statusCode());
        assertEquals(204, knows(rohan, asha).statusCode());
        assertEquals(List.of(rohan), contactIds(asha));
    }

    // GET /people/{id}/contacts

    @Test
    void contactsAreEmptyForNewPerson() throws Exception {
        long loner = createPerson("Loner");

        HttpResponse<String> response = get("/people/" + loner + "/contacts");

        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
    }

    @Test
    void contactsAreMutualAndSortedById() throws Exception {
        long asha = createPerson("Asha");
        long rohan = createPerson("Rohan");
        long meera = createPerson("Meera");
        knows(asha, meera);
        knows(rohan, asha);

        assertEquals(List.of(rohan, meera), contactIds(asha));
        assertEquals(List.of(asha), contactIds(rohan));
    }

    @Test
    void malformedContactsIdIs400() throws Exception {
        assertError(400, get("/people/abc/contacts"));
    }

    // GET /path

    @Test
    void chainOfThreeHopsIsConnected() throws Exception {
        List<Long> chain = chain("A", "B", "C", "D");

        JsonNode body = path(chain.get(0), chain.get(3));

        assertTrue(body.get("connected").asBoolean());
        assertEquals(3, body.get("hops").asInt());
        assertEquals(chain, pathIds(body));
    }

    @Test
    void chainOfFourHopsIsNotConnected() throws Exception {
        List<Long> chain = chain("A", "B", "C", "D", "E");

        JsonNode body = path(chain.get(0), chain.get(4));

        assertEquals(JSON.readTree("{\"connected\":false}"), body);
    }

    @Test
    void pathToSelfIsZeroHops() throws Exception {
        long asha = createPerson("Asha");

        JsonNode body = path(asha, asha);

        assertTrue(body.get("connected").asBoolean());
        assertEquals(0, body.get("hops").asInt());
        assertEquals(List.of(asha), pathIds(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/path", "/path?from=1", "/path?to=1", "/path?from=&to=1",
        "/path?from=abc&to=1", "/path?from=1&to=2.5", "/path?from=" + UNKNOWN_ID + "&to=x"
    })
    void missingOrMalformedPathParamIs400(String uri) throws Exception {
        assertError(400, get(uri));
    }

    @Test
    void unknownPathIdIs404() throws Exception {
        long asha = createPerson("Asha");

        assertError(404, get("/path?from=" + asha + "&to=" + UNKNOWN_ID));
        assertError(404, get("/path?from=" + UNKNOWN_ID + "&to=" + asha));
    }

    // Routing

    @Test
    void unknownRouteIs404() throws Exception {
        assertError(404, get("/nope"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        HttpResponse<String> response = get("/people");

        assertError(405, response);
        assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
        assertError(405, send("DELETE", "/knows", null));
        assertError(405, post("/path?from=1&to=2", "{}"));
    }

    // Helpers

    private static void assertError(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertFalse(JSON.readTree(response.body()).get("error").asText().isEmpty());
    }

    private static long createPerson(String name) throws Exception {
        HttpResponse<String> response = post("/people", "{\"name\":\"" + name + "\"}");
        assertEquals(201, response.statusCode());
        return JSON.readTree(response.body()).get("id").asLong();
    }

    private static HttpResponse<String> knows(long a, long b) throws Exception {
        return post("/knows", "{\"a\":" + a + ",\"b\":" + b + "}");
    }

    /** Creates people with the given names and connects each one to the next. */
    private static List<Long> chain(String... names) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String name : names) {
            ids.add(createPerson(name));
        }
        for (int i = 1; i < ids.size(); i++) {
            assertEquals(204, knows(ids.get(i - 1), ids.get(i)).statusCode());
        }
        return ids;
    }

    private static List<Long> contactIds(long id) throws Exception {
        HttpResponse<String> response = get("/people/" + id + "/contacts");
        assertEquals(200, response.statusCode());
        return ids(JSON.readTree(response.body()));
    }

    private static JsonNode path(long from, long to) throws Exception {
        HttpResponse<String> response = get("/path?from=" + from + "&to=" + to);
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body());
    }

    private static List<Long> pathIds(JsonNode body) {
        return ids(body.get("path"));
    }

    private static List<Long> ids(JsonNode people) {
        List<Long> ids = new ArrayList<>();
        people.forEach(person -> ids.add(person.get("id").asLong()));
        return ids;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null);
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body);
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        return CLIENT.send(request(method, path, body), BodyHandlers.ofString());
    }

    private static HttpRequest request(String method, String path, String body) {
        var publisher = body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body);
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).method(method, publisher).build();
    }
}
