# Nada connections API

A small in-memory HTTP API that stores people and their mutual connections, and finds the
shortest chain of introductions (up to three hops) between two people.

Plain Java 17: the JDK's built-in `HttpServer`, Jackson for JSON and JUnit 5 for tests. There
is no framework and no database; data lives in memory and is lost on restart.

## Requirements

- JDK 17 or newer. Nothing else: the Maven wrapper (`./mvnw`) downloads Maven itself.
- Internet access on the first build, to download Maven and the dependencies.

## Run the server

```bash
./mvnw package
java -jar target/nada-connections.jar
```

The server listens on **port 8080**. Set the `PORT` environment variable to use another port:

```bash
PORT=9090 java -jar target/nada-connections.jar
```

On Windows, use `mvnw.cmd package` instead of `./mvnw package`.

## Run the tests

```bash
./mvnw test
```

- `PathFinderTest` covers the search on its own, including both sides of the three-hop limit
  (A-B-C-D is connected in 3 hops, A-B-C-D-E is not connected).
- `ApiTest` starts the real server on a free port and checks every endpoint over HTTP:
  status codes, error bodies, Hindi names byte-for-byte, and the same chain scenarios.

## API

All responses are JSON (`Content-Type: application/json; charset=utf-8`), except the empty
`204`.

### `POST /people` - create a person

```bash
curl -i -X POST localhost:8080/people -d '{"name": "Asha"}'
# 201 {"id":1,"name":"Asha"}

curl -X POST localhost:8080/people -d '{"name": "आशा"}'
# 201 {"id":2,"name":"आशा"}
```

Names in any script are stored and returned exactly as sent. They are not trimmed.

### `POST /knows` - connect two people (mutual)

```bash
curl -i -X POST localhost:8080/knows -d '{"a": 1, "b": 2}'
# 204, no body. Sending the same connection again is also a 204.
```

### `GET /people/{id}/contacts` - direct contacts, sorted by id

```bash
curl localhost:8080/people/1/contacts
# 200 [{"id":2,"name":"आशा"}]
```

A person with no contacts gets `[]`.

### `GET /path?from=1&to=5` - shortest path of three hops or fewer

```bash
# Build a small network: Asha(1) - Meera(3) - Kabir(4)
curl -X POST localhost:8080/people -d '{"name": "Meera"}'   # id 3
curl -X POST localhost:8080/people -d '{"name": "Kabir"}'   # id 4
curl -X POST localhost:8080/knows -d '{"a": 1, "b": 3}'
curl -X POST localhost:8080/knows -d '{"a": 3, "b": 4}'

curl 'localhost:8080/path?from=1&to=4'
# 200 {"connected":true,"hops":2,"path":[{"id":1,"name":"Asha"},{"id":3,"name":"Meera"},{"id":4,"name":"Kabir"}]}

curl 'localhost:8080/path?from=1&to=1'
# 200 {"connected":true,"hops":0,"path":[{"id":1,"name":"Asha"}]}
```

If there is no path within three hops, the answer is `200 {"connected":false}`.

### Errors

Every error has a JSON body like `{"error": "no person 9"}`.

| Status | When |
|---|---|
| 400 | Invalid JSON; body is not a JSON object; a required field is missing; `name` is not a string or is empty/blank; `a` or `b` is not an integer; `a` equals `b`; `from`, `to` or the contacts `id` is missing or not an integer |
| 404 | Unknown person (`{"error":"no person 9"}`) or unknown route |
| 405 | Known route, wrong method (the `Allow` header names the right one) |
| 500 | Anything unexpected (`{"error":"internal error"}`); the details are logged on the server |

```bash
curl -i -X POST localhost:8080/knows -d '{"a": 1, "b": 99}'
# 404 {"error":"no person 99"}

curl -i 'localhost:8080/path?from=1&to=abc'
# 400 {"error":"to must be an integer"}
```

## Code layout

All code is in the `com.nada` package.

| Class | Role |
|---|---|
| `Main` | Reads `PORT` (default 8080) and starts the app |
| `App` | `start(port)` wires the store, path finder and handler into an `HttpServer`; tests call `start(0)` |
| `ApiHandler` | One handler for everything: routes by method and path, turns exceptions into JSON errors |
| `Http` | JSON/HTTP helpers: `send`, `readObject`, `requireLong`, `parseId`, query parsing |
| `PersonStore` | People and connections in memory |
| `PathFinder` | Depth-limited breadth-first search |
| `Person`, `ApiException` | A person record, and an exception that carries an HTTP status |

## Design notes

**Why breadth-first search gives the shortest path.** Every connection counts as one hop, so
the graph is unweighted. BFS visits everyone 1 hop away, then everyone 2 hops away, and so on.
The first time it reaches the target, any shorter route would already have been found on an
earlier level, so the path it found is a shortest one. When several paths are equally short,
the API returns one of them, as the spec allows.

**Depth-limited, one level at a time.** `PathFinder` expands one level (hop) per loop
iteration and stops as soon as the target is reached or after `maxHops` levels. `maxHops` is a
constructor argument (3 by default), not a constant inside the algorithm. A `parent` map
records who each person was first reached from. It doubles as the visited set, and following
it back from the target rebuilds the path. `from == to` needs no special case: the target is
already "reached" before the first level, which gives 0 hops.

**Cost.** BFS is O(V + E), but here V and E are only the people and connections within three
hops of `from`, not the whole network. Each person is visited at most once and each of their
connections is scanned once. Stopping early also means a query never pays for distant parts of
the graph.

**In memory and thread safe.** People live in a `ConcurrentHashMap`, and each person's contacts
are a concurrent set inside a second `ConcurrentHashMap` (an adjacency list). IDs come from an
`AtomicLong`, so two requests creating people at the same time never get the same ID. The server
runs requests on a thread pool, so this matters. Sets also make `POST /knows` idempotent for
free. One trade-off: a connection is written as two separate set updates (a to b, then b to a),
so a read that runs at exactly the same moment could briefly see only one direction. That is
fine for this API, and a lock around the two writes would fix it if needed.

**400 before 404.** Each endpoint first parses and validates the whole request and only then
looks people up. So `{"a": 9, "b": 9}` is a 400 (a equals b) even though person 9 does not
exist, and `/path?from=9&to=abc` is a 400, not a 404.

**Names.** Names are stored exactly as received (no trimming), and responses are written as
UTF-8 with `Content-Length` set to the byte length. Hindi or any other script comes back
byte-for-byte.

**Known limitation.** A URL with a broken percent-escape (for example `?from=%zz`) is rejected
by the JDK's `HttpServer` itself, before our handler runs. The status is still 400, but the
body is the server's own HTML, not JSON.

## What I'd do next

- **Persistence.** Keep people and connections in a database (Postgres with an edges table is
  enough at this size) so data survives restarts and can be shared across instances.
- **Configurable max hops.** Accept an optional `maxHops` query parameter on `/path`, with an
  upper bound. `PathFinder` already takes the limit in its constructor.
- **All shortest paths.** Return every equally short path, not just one, by recording all
  parents on the previous level instead of only the first.
- **Bidirectional BFS for scale.** Search from both ends and meet in the middle. Each side only
  goes about half as deep, which is much cheaper when people have many contacts.
- **Pagination** for contacts once people can have thousands of them.
- **Authentication and authorization**, since this is family and marriage data.
- **Request logging and metrics** (method, path, status, latency) for operating it in
  production.
- **A Dockerfile**, so it runs the same everywhere without installing a JDK.

## AI tools used

- **Claude (claude.ai)**: to break the spec into a phased plan and list edge cases (UTF-8 byte
  length, 400-before-404 ordering, duplicate edges).
- **Claude Code**: to write the code one phase at a time, with one commit per phase. It ran the
  tests and curl checks as it built. I reviewed each phase's diff before moving on to the next,
  and asked it to explain any design choice I wasn't sure about.
- Before submitting, I re-ran the full test suite and every README curl example myself against
  a fresh build.
- I chose the stack and approach (JDK HttpServer instead of a framework, level-by-level BFS,
  in-memory store) and gave them to Claude Code as fixed constraints.
