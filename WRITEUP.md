# Write-up: BookMySeat

## 1. The atomic decision

**Mechanism: one conditional `UPDATE` per seat, and the row count is the verdict.**
`SeatRepository.tryGrab`:

```sql
UPDATE seats
   SET status = 'confirmed', user_id = ?, reservation_id = ?, updated_at = now()
 WHERE show_id = ? AND label = ? AND status = 'available'
```

If it updates 1 row, this request owns the seat. If it updates 0 rows, the seat was already taken.
There is no "is it free?" read before the write, so there is no gap to race through.

**Why it is race-free.** PostgreSQL's default `READ COMMITTED` isolation locks the row on `UPDATE`. When 500 transactions
target seat A12, one gets the row lock and the other 499 *wait*. When the winner commits, each waiter re-evaluates its
`WHERE` clause against the newly committed row, sees `status = 'confirmed'`, matches nothing, and updates 0 rows.
Exactly one winner, 499 clean declines, no error.

**The database also refuses impossible states.** `seats` has primary key `(show_id, label)`, and a `CHECK` constraint
(`seat_owner_consistent`) says a seat is either `available` with no owner, or owned with *both* a user and a reservation.
A bug that tried to write a half-state would be rejected by the database itself.

**Multi-seat requests: all-or-nothing, in one transaction.** `ReservationService.reserve` grabs each requested seat
with the statement above. The first seat that fails throws `ApiException`, which rolls the transaction back and undoes
every seat already grabbed in that request. A request for `["A12","A13"]` where A13 is taken returns `409` and A12 stays free.
This also holds under concurrency, because the only way to keep a seat is to commit the whole transaction.

**Avoiding deadlock: one fixed global lock order.** Requests lock in this order, always:
1. the idempotency key row,
2. the user's counter row (`user_show_holdings`),
3. the seats, **sorted by label**.

Without the sorted order, two requests for `{A1,A2}` and `{A2,A1}` can each hold one seat and wait for the other.
I checked this on a real PostgreSQL 16 with a small simulation (1,500 concurrent two-seat attempts over 4 seats, with a few
milliseconds of latency between statements): **unsorted order produced 9 deadlocks, sorted order produced 0**, and in both
runs no seat was double-sold. In the service these would have been 500 errors.

**Per-user limit** uses the same idea: `HoldingRepository.tryAdd` is a single upsert guarded by the limit,

```sql
INSERT INTO user_show_holdings (show_id, user_id, seat_count) VALUES (?, ?, ?)
ON CONFLICT (show_id, user_id) DO UPDATE
   SET seat_count = user_show_holdings.seat_count + EXCLUDED.seat_count
 WHERE user_show_holdings.seat_count + EXCLUDED.seat_count <= ?
```

The row lock serializes one user's parallel requests and each re-checks the latest committed count. A user firing 10 parallel
reserves at limit 4 ends with exactly 4 (verified by the burst script, phase C). The increment is inside the booking
transaction, so a booking that fails later gives the quota back automatically.

## 2. Idempotency

* **Where the key is stored:** table `idempotency_keys`, primary key `(user_id, idem_key)`, with `request_hash` and `reservation_id`.
  Keys are scoped per user, so two users can never collide.
* **How exactly-once is enforced:** the first statement of the booking transaction is
  `INSERT ... ON CONFLICT (user_id, idem_key) DO NOTHING`. Only one transaction can ever insert a given key.
  If another request with the same key is still in flight, PostgreSQL makes the second one **wait** until the first
  finishes: if it committed, the insert does nothing and we return the original reservation (a replay); if it rolled back
  (the booking was declined), the insert succeeds and this request does the booking.
* **Same key, different body:** the request is fingerprinted as `SHA-256(show_id + sorted seat list)`. If the stored hash differs
  from the new request's hash the answer is `409 idempotency_key_reuse`. Sorting means `["A1","A2"]` and `["A2","A1"]` are the same request.
* **Declines leave no key.** The key insert is in the same transaction as the booking, so a declined booking rolls it back.
  A later retry with that key is processed fresh. This is a deliberate choice, and it means a decline is not "remembered".
* **Replay response:** the original reservation, `201`, with the header `Idempotent-Replayed: true`. It shows the reservation's *current*
  status, so replaying a key after a cancel returns `status: cancelled`.
* **Tested:** 20 parallel requests with one key create exactly one reservation (phase D). On a real PostgreSQL, 60 parallel
  identical requests produced 1 reservation, 59 replays, and the user's counter was incremented once.

## 3. Holds and expiry

I chose the **explicit-cancel model**, with no auto-expiring holds. A successful reserve is immediately `confirmed`. The
`held` status exists in the schema and in the invariant (`available + held + confirmed == total_seats`, with `held` always
0 today), so adding expiry is an extension, not a rewrite.

**Cancel** (`POST /reservations/{id}/cancel`), all in one transaction:
1. claim the cancel with `UPDATE reservations SET status='cancelled' ... WHERE id = ? AND user_id = <caller> AND status = 'confirmed'`.
   Only the owner can match, and only once; concurrent double-cancels produce one release (30 parallel cancels: 1 released).
2. give the seats back to the user's quota,
3. release each seat with `... WHERE reservation_id = <this one> AND status = 'confirmed'`.
   That guard means a release can **never** touch a seat that now belongs to someone else.

If I added time-boxed holds, I would use `held_until` plus *lazy expiry inside the grab statement*
(`WHERE status = 'available' OR (status = 'held' AND held_until < now())`), so an expired hold is simply re-grabbable with no background
job to fail, and a confirm step guarded by `status = 'held' AND reservation_id = ? AND held_until >= now()`.

## 4. Consistency versus availability under a partition

This is a **consistency-first (CP)** design. There is a single writer: one PostgreSQL primary decides every seat. The API
instance holds no seat state. Tokens are stateless JWTs, so any instance can serve any request.

* **If the API cannot reach the database:** no request can be decided, so nothing is guessed. Seat requests get `429 server_busy`
  (pool wait timed out while the database looks healthy) or `503 database_unavailable` (the health probe says it is down).
  `/ready` flips to `503` within about 2 seconds, so a load balancer stops sending traffic. `/health` stays `200` so the platform
  does not restart a process that only lost its dependency.
* **No split-brain double-sell is possible**, because there is no second copy of the truth to disagree with.
* **Clients can retry safely**, because of idempotency keys. A request that timed out on the wire can be re-sent with the same key.
* **What I give up:** availability during a database outage. The answer to "serve possibly-stale sells to stay up" is no;
  selling a seat twice is the one failure this system must not have.
* **Not built:** read replicas or a hot standby. A real deployment would add managed failover for the primary, accepting a short
  unavailable window at failover. Neon's free tier also scales the database to zero when idle, which adds a cold-connection delay.

## 5. Observability: what would page me at 2am

Everything below is exposed today (see the README):

| Signal | Source | Page when |
|---|---|---|
| Any 5xx | `http_server_requests_seconds_count{status=~"5.."}` | rate above 0 for 2 minutes. Domain outcomes are 4xx, so a 5xx is a real fault. |
| Load being shed | `bookmyseat_requests_shed_total{reason}` | `overload` climbing means the pool or CPU is too small; `database_down` means an outage |
| Not ready | `GET /ready` returns 503 | immediately |
| Pool starvation | `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds` | pending above 0 sustained, or acquire time near the 20 s timeout |
| Latency | `http_server_requests_seconds` p99 on `/shows/{id}/reserve` | p99 above target for 5 minutes |
| Business anomaly | confirmed vs. declined counters | a sudden collapse in confirmed with a spike in `seat_taken` or `per_user_limit` |

**Honest gaps:** there is no automated invariant alert. The check `available + held + confirmed == total_seats` runs in the burst
script and in `GET /shows/{id}` by construction, but I would add a periodic reconcile job exporting a mismatch gauge, and
alert on any non-zero value. Metrics are per-instance counters, which is fine for one instance, and would need aggregation with several.
Logs are JSON with a `request_id` on every line, so one request can be followed end to end.

## 6. AI usage: directed versus decided

**Tool:** an AI chat assistant, used throughout the project (I can name the exact product on request).

**Summary: the assistant generated most of the source code. I set the requirements and the technical direction, ran and checked every
step myself, deployed it, and studied the result so I can explain and change it.**

| Area | Who did what |
|---|---|
| Stack: Java 21, Gradle, Spring Boot | I chose it (my day-job stack); the assistant proposed JDBC (`JdbcTemplate`) over JPA so the SQL stays visible |
| Database and hosting: PostgreSQL, Neon, Render | the assistant recommended and justified them (atomic `UPDATE`, `ON CONFLICT`, row locks, a free database that does not expire); I approved and set them up |
| Package layout (controller / service / repository / model / dto) | **my call.** The first version used package-by-feature and I asked for the layered layout I work in |
| Workflow: app name, public repo, one commit per step, push after each | I set these |
| The design of the atomic seat grab, the sorted lock order, the schema, idempotency and cancel | proposed by me, enhanced by assistant and written by the assistant; I reviewed each step |
| Java and SQL source code | proposed by me, enhanced and generated by the assistant |
| Building, running, debugging, deploying | me: I built and ran every step in Docker, exercised every endpoint in Bruno, ran the burst, created the Neon and Render accounts|
| Scope decisions (e.g. stopping at correctness and leaving further performance work for later) | mine |

**What I verified instead of just accepting**
* Every step was run on my machine and checked against the expected responses before I moved on.
* The assistant could not compile the Spring code in its own environment, so the first real compile of every step happened on my machine.
  What it *could* do was test the SQL patterns against a real PostgreSQL with concurrent simulations, and test the burst tool against a mock API,
  including a deliberately broken double-selling mock, which the tool correctly failed on 8 checks.
* The burst tool exposed an environment problem (transport errors through Docker Desktop's Windows `localhost` forwarding). We diagnosed it by
  running the same test container-to-container, which was clean.

