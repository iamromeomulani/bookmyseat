# BookMySeat: seat reservation that never sells a seat twice

A small JSON HTTP service that sells assigned seats for an event and stays correct when thousands of buyers
stampede the same show at on-sale time. Built for the Paytm Money "Seat Reservation at Scale" take-home.

| | |
|---|---|
| **Live URL** | https://bookmyseat-uf2r.onrender.com (Render free tier, Neon Postgres, Singapore) |
| **Cold start** | The free instance sleeps after 15 min idle. The first request can take about a minute. `/ready` tells you when it is up. |
| **Stack** | Java 21, Spring Boot 3.4, plain JDBC (`JdbcTemplate`), PostgreSQL, Flyway, Micrometer/Prometheus |
| **Write-up** | [`WRITEUP.md`](WRITEUP.md): the atomic decision, idempotency, consistency, observability, AI usage |

> The admin key for the live service is **not** in this repo. It is in the submission email.

## Run it locally (one command)

```bash
docker compose up --build        # Postgres 16 + the API on http://localhost:8080
curl localhost:8080/ready        # {"status":"UP","database":"UP"}
```

Local defaults: admin key `dev-admin-key`. Production must override `JWT_SECRET` and `ADMIN_API_KEY` (see Configuration).

## Prove it works: the one-command burst

`burst/Burst.java` reproduces the on-sale stampede against any URL, prints the outcome distribution, then
reconciles the result against the server state and against `/metrics`. It needs only a JDK 21.

```bash
./burst.sh https://bookmyseat-uf2r.onrender.com        # macOS / Linux
make burst URL=https://bookmyseat-uf2r.onrender.com    # same thing
burst.cmd https://bookmyseat-uf2r.onrender.com         # Windows
docker compose run --rm burst                          # local, container-to-container (no JDK needed)
```

Against the live service set the admin key first: `ADMIN_KEY=<key> ./burst.sh <url>`
(PowerShell: `$env:ADMIN_KEY="<key>"`).

Defaults: 3,000 users, 20,000 requests, a 5,000-seat hall, 10 hot seats, 500-user hot-seat storm, 1,000 in flight.
Override with the environment variables `USERS REQUESTS SEATS HOT STORM_USERS CONCURRENCY`.

What it checks (26 checks, exit code 0 only if all pass):

| Phase | What happens | Expected |
|---|---|---|
| A | 500 users hit **one** seat at the same instant | exactly 1 x `201`, the rest `409 seat_taken` |
| B | 20,000 requests, about 75% on 10 hot seats, multi-seat requests in both orders, retries with the same key, same key with different seats | zero 5xx, no seat with two owners |
| C | one user fires 10 parallel reserves (limit 4) | exactly 4 succeed |
| D | the same idempotency key 20 x in parallel, then with different seats | 1 reservation, then `409 idempotency_key_reuse` |
| E | spoofed body `user_id`; another user tries to cancel; owner cancels; seat re-booked | token's user always wins; `403`; `200`; `201` |
| Final | `available + held + confirmed == total_seats`; server-confirmed seats equal seats clients were told they won; metric deltas equal observed outcomes | all equal |

### Measured results

| Run | Requests | Time | Throughput | p50 / p99 | 5xx | Double-sold | Checks |
|---|---|---|---|---|---|---|---|
| Local Docker, in-network (16 CPUs) | 20,000 | 5.78 s | 3,458 req/s | 264 ms / 733 ms | 0 | 0 | 26 / 26 |
| Live Render free tier (0.1 CPU), reduced size | 2,000 | 53.6 s | 37 req/s | 2.3 s / 7.9 s | 0 | 0 | 26 / 26 |

Correctness is identical on both. **Capacity is not**: the free instance has 0.1 CPU, so a 20k-request burst against
it queues for minutes. See "Known limits" below.

## API

All bodies are JSON with `snake_case` fields. Money is an integer number of **paise** (floats are rejected).
Errors are `{"error": "<code>", "message": "<text>"}`.

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /auth/token` | none | get a token: `{"user_id":"alice"}`; add `"admin_key"` for an ADMIN token |
| `POST /shows` | admin | create a show: `{"name","seats":["A1",...],"price_paise":25000,"per_user_limit":4?}` |
| `GET /shows/{id}` | none | per-seat status plus `available`, `held`, `confirmed` counts |
| `POST /shows/{id}/reserve` | user | `{"seats":["A12"],"idempotency_key":"..."}` (or an `Idempotency-Key` header) |
| `POST /reservations/{id}/cancel` | owner only | release the reservation's seats |
| `GET /health` | none | liveness (never touches the DB) |
| `GET /ready` | none | readiness: `200` only if the DB answers, else `503` (fails closed) |
| `GET /metrics` | none | Prometheus text (same as `/actuator/prometheus`) |

```bash
BASE=http://localhost:8080
ADMIN=$(curl -s $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"user_id":"admin","admin_key":"dev-admin-key"}' | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
USER1=$(curl -s $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"user_id":"alice"}' | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

SHOW=$(curl -s $BASE/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | sed -E 's/.*"id":"([^"]+)".*/\1/')

curl -s $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $USER1" -H 'Content-Type: application/json' \
  -d '{"seats":["A1"],"idempotency_key":"k-1"}'
# 201 {"reservation_id":"...","show_id":"...","user_id":"alice","seats":["A1"],"amount_paise":25000,"status":"confirmed"}
```

A [Bruno](https://www.usebruno.com/) collection with every request and its expected status is in [`bruno/`](bruno/).

### Behaviour decisions

* **Identity comes only from the token** (`sub`). The reserve body has no user field, and extra fields such as a spoofed `user_id` are ignored.
* **Multi-seat requests are all-or-nothing.** If any seat is unavailable the whole request is declined with `409` and nothing is held.
* **Per-user limit** (default 4 per show, set per show): going over is `409 per_user_limit_exceeded`.
* **Idempotency:** a retry with the same key returns the original reservation (`201`, header `Idempotent-Replayed: true`). The same key with different seats is `409 idempotency_key_reuse`. Keys are scoped per user. A declined request leaves no key behind.
* **Release model: explicit cancel** (no auto-expiring holds). Only the owner can cancel (`403` otherwise, even for admins). Cancelling twice is `200` both times. A released seat is re-bookable.
* **Overload:** if no DB connection frees up in time the answer is `429` with `Retry-After`, never a `500`. If the database is genuinely down the answer is an honest `503`.

| Status | `error` | Meaning |
|---|---|---|
| 201 | | booked (or an idempotent replay) |
| 400 | `validation_failed`, `duplicate_seat`, `invalid_request`, `idempotency_key_mismatch` | bad input |
| 401 / 403 | `unauthorized` / `forbidden`, `not_reservation_owner` | missing token / not allowed |
| 404 | `show_not_found`, `seat_not_found`, `reservation_not_found` | unknown id |
| 409 | `seat_taken`, `per_user_limit_exceeded`, `idempotency_key_reuse` | clean domain declines |
| 429 | `server_busy` | overloaded, retry |
| 503 | `database_unavailable` | DB down |

## Observability

* **Metrics** (`/metrics`): `bookmyseat_reservations_confirmed_total`, `bookmyseat_reservations_declined_total{reason=seat_taken|per_user_limit|idempotent_replay|idempotency_key_reuse|...}`, `bookmyseat_seats_available|held|confirmed` (read from the database on scrape), `bookmyseat_requests_shed_total{reason}`, plus HTTP and Hikari pool metrics. The burst script checks that these reconcile with what clients observed.
* **Logs:** one JSON object per line on stdout. Every line carries `request_id` (taken from `X-Request-Id` or generated, and returned in the response header), and each request logs method, path, status, duration, user and outcome. On Render: dashboard, service, **Logs**. Logs under load: **<LINK TO SCREEN RECORDING, add before submitting>**.
* **Health:** `/health` is liveness. `/ready` is readiness. Both, and the seat gauges, use a **separate 2-connection pool**, so a burst that saturates the main pool cannot make the platform think a healthy service is down.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | JDBC URL, e.g. `jdbc:postgresql://host/db?sslmode=require` |
| `JWT_SECRET` | dev value | token signing key, at least 32 bytes (the app refuses to start otherwise) |
| `ADMIN_API_KEY` | `dev-admin-key` | key that makes `/auth/token` return an ADMIN token |
| `DB_POOL_SIZE` | 10 | main connection pool |
| `DB_CONNECTION_TIMEOUT_MS` | 20000 | how long a request waits for a connection before `429` |
| `TOMCAT_ACCEPT_COUNT`, `TOMCAT_MAX_CONNECTIONS` | 2000, 30000 | sized for a connection stampede |
| `PORT` | 8080 | injected by Render |

## Deploy (Render + Neon)

1. **Neon:** create a project in the same region as Render (Singapore). Use the **direct** connection host (pooling off) and turn the string into `DB_URL`, `DB_USER`, `DB_PASSWORD`. Drop `channel_binding=require`.
2. **Render:** *New, Blueprint*, pick this repo ([`render.yaml`](render.yaml)). Enter `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ADMIN_API_KEY`. `JWT_SECRET` is generated for you.
3. Flyway creates the schema on first start. Health check path is `/health`.

## Project layout

```
src/main/java/com/bookmyseat/
  controller/   HTTP only (Show, Reservation, Cancellation, Auth, Health, Metrics)
  service/      business rules and the @Transactional boundary
  repository/   ALL the SQL: the atomic seat grab, the limit upsert, the idempotency claim, cancel
  model/ dto/   database rows / request and response shapes
  config/       security, request-id filter, monitoring pool, DB health probe
  metrics/      business counters and seat gauges
  exception/    ApiException and the JSON error mapping
src/main/resources/db/migration/V1__init_schema.sql   schema, constraints, indexes
burst/Burst.java                                      the stampede + reconciliation tool
```

## Known limits (honest)

* **Free-tier capacity.** Render's free instance has 0.1 CPU. Correctness holds, but throughput is about 37 req/s, so a 20k-request burst takes minutes. The service scales with CPU (Render *Starter* is 0.5 CPU, *Standard* is 1 CPU). The code also has easy wins left, see `WRITEUP.md`, "What I would do next".
* **Tests:** the proof of correctness is the burst script (26 end-to-end checks). There are no JUnit integration tests yet.
* **The built-in token endpoint** lets anyone mint a token for any `user_id`. It is a stand-in for a real identity provider (swap the JWT decoder for the IdP's JWKS).
* **Idempotency keys never expire**, and Neon's free tier has a 0.5 GB storage cap.
