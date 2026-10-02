import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BookMySeat burst test: reproduces the on-sale stampede against a live URL and proves correctness.
 *
 *   java burst/Burst.java https://your-service.onrender.com
 *
 * Phases:  A hot-seat storm | B on-sale stampede (hot seats, multi-seat, retries) |
 *          C per-user limit | D idempotency | E identity spoofing + cancel
 * Then:    final reconciliation against GET /shows/{id} and against /metrics.
 * Exit code 0 = every check passed, 1 = at least one check failed.
 *
 * Needs only a JDK 21. Tunables (environment variables):
 *   ADMIN_KEY (dev-admin-key)  USERS (3000)  REQUESTS (20000)  SEATS (5000)  HOT (10)
 *   STORM_USERS (500)  CONCURRENCY (1000)  SEED
 */
public class Burst {

    // ------------------------------------------------------------------ configuration
    static String BASE;
    static final String ADMIN_KEY = env("ADMIN_KEY", "dev-admin-key");
    static final int USERS = envInt("USERS", 3000);
    static final int REQUESTS = envInt("REQUESTS", 20000);
    static final int HALL_SEATS = envInt("SEATS", 5000);
    static final int HOT = envInt("HOT", 10);
    static final int STORM_USERS = envInt("STORM_USERS", 500);
    static final int CONCURRENCY = envInt("CONCURRENCY", 1000);
    static final long SEED = envLong("SEED", System.nanoTime());
    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    // ------------------------------------------------------------------ data types
    record Resp(int status, String body, Map<String, String> headers, long ms, String transportError) {
    }

    record User(String id, String token) {
    }

    record Attempt(String phase, String user, List<String> seats, String key, int status, String code,
                   String reservationId, boolean replay, long ms, String transportError) {
    }

    static final class Booking {
        final String id;
        final String user;
        final List<String> seats;
        boolean cancelled;

        Booking(String id, String user, List<String> seats) {
            this.id = id;
            this.user = user;
            this.seats = seats;
        }
    }

    record Sample(String name, Map<String, String> labels, double value) {
    }

    record CheckResult(String name, boolean ok, boolean warning, String detail) {
    }

    static final List<Attempt> ALL = Collections.synchronizedList(new ArrayList<>());
    static final List<CheckResult> CHECKS = new ArrayList<>();

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws Exception {
        BASE = stripSlash(args.length > 0 ? args[0] : env("BASE_URL", "http://localhost:8080"));
        System.out.println("=== BookMySeat burst test ===");
        System.out.printf("target=%s  run=%s  users=%d  requests=%d  hall=%d seats  hot=%d  storm=%d  concurrency=%d%n",
                BASE, RUN, USERS, REQUESTS, HALL_SEATS, HOT, STORM_USERS, CONCURRENCY);

        waitUntilReady();

        // ---- admin token + fresh show
        String adminToken = mintToken("admin-" + RUN, ADMIN_KEY);
        if (adminToken == null) {
            System.out.println("FATAL: could not get an admin token. Check ADMIN_KEY.");
            System.exit(2);
        }
        List<String> seatLabels = new ArrayList<>();
        for (int i = 1; i <= HALL_SEATS; i++) {
            seatLabels.add("A" + i);
        }
        seatLabels.add("VIP1");
        for (int i = 1; i <= 10; i++) {
            seatLabels.add("LIM" + i);
        }
        seatLabels.addAll(List.of("IDEM1", "IDEM2", "ID1"));

        Resp created = call("POST", "/shows", adminToken,
                "{\"name\":\"burst-" + RUN + "\",\"seats\":" + jsonArray(seatLabels) + ",\"price_paise\":25000}", Map.of());
        if (created.status() != 201) {
            System.out.println("FATAL: could not create show: " + created.status() + " " + created.body()
                    + " " + (created.transportError() == null ? "" : created.transportError()));
            System.exit(2);
        }
        Map<String, Object> show = parseMap(created.body());
        String showId = str(show, "id");
        int userLimit = (int) num(show, "per_user_limit");
        int totalSeats = (int) num(show, "total_seats");
        System.out.printf("created show %s with %d seats (per-user limit %d)%n", showId, totalSeats, userLimit);

        List<Sample> metricsBefore = scrape();
        if (metricsBefore == null) {
            System.out.println("note: /metrics not reachable, metric reconciliation will be skipped");
        }

        // ---- users
        System.out.println("minting tokens ...");
        List<User> stampedeUsers = mintUsers("u" + RUN + "-", USERS);
        List<User> stormUsers = mintUsers("s" + RUN + "-", STORM_USERS);
        User limUser = mintUsers("lim" + RUN, 1).get(0);
        User idemUser = mintUsers("idem" + RUN, 1).get(0);
        User userX = mintUsers("x" + RUN, 1).get(0);
        User userY = mintUsers("y" + RUN, 1).get(0);

        // ---- Phase A: hot-seat storm
        System.out.printf("%n--- Phase A: hot-seat storm: %d users, ONE seat (VIP1), all at the same instant%n", STORM_USERS);
        List<Supplier<Attempt>> stormTasks = new ArrayList<>();
        for (User u : stormUsers) {
            stormTasks.add(() -> reserve("A-hot-seat", u, showId, List.of("VIP1"), null, false, null));
        }
        long t0 = System.nanoTime();
        List<Attempt> stormResults = fire(stormTasks, Math.max(CONCURRENCY, STORM_USERS));
        double stormSecs = (System.nanoTime() - t0) / 1e9;
        ALL.addAll(stormResults);
        printTable(stormResults);
        long stormWins = stormResults.stream().filter(a -> a.status() == 201 && !a.replay()).count();
        long stormUnclean = stormResults.stream()
                .filter(a -> a.transportError() == null && a.status() != 201
                        && !(a.status() == 409 && "seat_taken".equals(a.code())))
                .count();
        long stormTransport = stormResults.stream().filter(a -> a.transportError() != null).count();
        check("A: exactly one winner for the hot seat", stormWins == 1, "winners=" + stormWins);
        check("A: every answered loser got a clean 409 seat_taken (never an error)", stormUnclean == 0,
                "unclean answers=" + stormUnclean + ", unanswered (transport)=" + stormTransport);
        System.out.printf("   (%.2fs)%n", stormSecs);

        // ---- Phase B: on-sale stampede
        System.out.printf("%n--- Phase B: on-sale stampede: %d requests, %d%% on %d hot seats, multi-seat, retries with same key%n",
                REQUESTS, 75, HOT);
        Random rnd = new Random(SEED);
        List<Supplier<Attempt>> tasks = new ArrayList<>();
        int keyCounter = 0;
        while (tasks.size() < REQUESTS) {
            User u = stampedeUsers.get(rnd.nextInt(stampedeUsers.size()));
            List<String> seats = pickSeats(rnd);
            String key = "k" + (keyCounter++);
            boolean headerKey = rnd.nextInt(4) == 0;
            tasks.add(() -> reserve("B-stampede", u, showId, seats, key, headerKey, null));
            double p = rnd.nextDouble();
            if (p < 0.12 && tasks.size() < REQUESTS) {
                tasks.add(() -> reserve("B-stampede", u, showId, seats, key, headerKey, null));      // retry
            } else if (p < 0.15 && tasks.size() < REQUESTS) {
                List<String> other = pickSeats(rnd);
                tasks.add(() -> reserve("B-stampede", u, showId, other, key, headerKey, null));      // same key, other body
            }
        }
        Collections.shuffle(tasks, rnd);
        t0 = System.nanoTime();
        List<Attempt> stampede = fire(tasks, CONCURRENCY);
        double secs = (System.nanoTime() - t0) / 1e9;
        ALL.addAll(stampede);
        printTable(stampede);
        printLatency(stampede, secs);

        // ---- Phase C: per-user limit
        System.out.printf("%n--- Phase C: one user fires 10 PARALLEL reserves for 10 different seats (limit %d)%n", userLimit);
        List<Supplier<Attempt>> limTasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String seat = "LIM" + i;
            limTasks.add(() -> reserve("C-limit", limUser, showId, List.of(seat), null, false, null));
        }
        List<Attempt> limResults = fire(limTasks, 10);
        ALL.addAll(limResults);
        printTable(limResults);
        long limWins = limResults.stream().filter(a -> a.status() == 201).count();
        long limDeclined = limResults.stream().filter(a -> a.status() == 409 && "per_user_limit_exceeded".equals(a.code())).count();
        check("C: parallel reserves from one user stop at the limit", limWins == userLimit,
                "confirmed=" + limWins + " limit=" + userLimit);
        check("C: the rest are clean 409 per_user_limit_exceeded", limDeclined == 10 - userLimit, "declined=" + limDeclined);

        // ---- Phase D: idempotency
        System.out.println("\n--- Phase D: idempotency: same key x20 in parallel, then same key with different seats");
        List<Supplier<Attempt>> idemTasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            idemTasks.add(() -> reserve("D-idempotency", idemUser, showId, List.of("IDEM1"), "idem-key-" + RUN, alternateKeyForm(), null));
        }
        List<Attempt> idemResults = fire(idemTasks, 20);
        ALL.addAll(idemResults);
        Attempt differentBody = reserve("D-idempotency", idemUser, showId, List.of("IDEM2"), "idem-key-" + RUN, false, null);
        ALL.add(differentBody);
        List<Attempt> idemAll = new ArrayList<>(idemResults);
        idemAll.add(differentBody);
        printTable(idemAll);
        Set<String> idemIds = idemResults.stream().filter(a -> a.status() == 201).map(Attempt::reservationId).collect(Collectors.toSet());
        long idemNew = idemResults.stream().filter(a -> a.status() == 201 && !a.replay()).count();
        check("D: 20 parallel requests with one key create exactly one reservation",
                idemIds.size() == 1 && idemNew == 1, "distinct reservations=" + idemIds.size() + " new=" + idemNew);
        check("D: same key + different seats is rejected with 409 idempotency_key_reuse",
                differentBody.status() == 409 && "idempotency_key_reuse".equals(differentBody.code()),
                "status=" + differentBody.status() + " code=" + differentBody.code());

        // ---- Phase E: identity + cancel
        System.out.println("\n--- Phase E: identity spoofing, owner-only cancel, re-booking a released seat");
        Attempt spoof = reserve("E-identity", userX, showId, List.of("ID1"), null, false, "\"user_id\":\"admin\"");
        ALL.add(spoof);
        boolean spoofIgnored = spoof.status() == 201 && (spoof.code() == null || !spoof.code().startsWith("WRONG_OWNER"));
        check("E: a spoofed body user_id is ignored, the booking belongs to the token's user",
                spoofIgnored, "status=" + spoof.status() + " code=" + spoof.code());
        String xRes = spoof.reservationId();
        Resp crossCancel = call("POST", "/reservations/" + xRes + "/cancel", userY.token(), null, Map.of());
        check("E: another user cannot cancel it (403)", crossCancel.status() == 403, "status=" + crossCancel.status());
        Resp ownCancel = call("POST", "/reservations/" + xRes + "/cancel", userX.token(), null, Map.of());
        check("E: the owner can cancel (200, cancelled)", ownCancel.status() == 200
                && "cancelled".equals(str(parseMap(ownCancel.body()), "status")), "status=" + ownCancel.status());
        Attempt rebook = reserve("E-identity", userY, showId, List.of("ID1"), null, false, null);
        ALL.add(rebook);
        check("E: a released seat can be booked by someone else (201)", rebook.status() == 201, "status=" + rebook.status());
        Set<String> cancelledIds = new HashSet<>();
        if (ownCancel.status() == 200 && xRes != null) {
            cancelledIds.add(xRes);
        }

        // ---- Final reconciliation
        System.out.println("\n=== OVERALL OUTCOME DISTRIBUTION (all phases) ===");
        List<Attempt> everything;
        synchronized (ALL) {
            everything = new ArrayList<>(ALL);
        }
        printTable(everything);
        reconcile(showId, totalSeats, userLimit, everything, cancelledIds, metricsBefore);

        // ---- verdict
        System.out.println("\n=== RESULT ===");
        int failed = 0;
        int warned = 0;
        for (CheckResult c : CHECKS) {
            String tag = c.ok() ? "PASS" : (c.warning() ? "WARN" : "FAIL");
            System.out.printf("[%s] %s  (%s)%n", tag, c.name(), c.detail());
            if (!c.ok() && !c.warning()) {
                failed++;
            }
            if (!c.ok() && c.warning()) {
                warned++;
            }
        }
        System.out.printf("%n%d checks, %d failed, %d warnings%n", CHECKS.size(), failed, warned);
        System.out.println(failed == 0 ? "ALL CORRECTNESS CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // The 20 parallel idempotency requests alternate between the body-key and header-key forms.
    static int counter = 0;

    static boolean alternateKeyForm() {
        synchronized (Burst.class) {
            return (counter++ % 2) == 0;
        }
    }

    // ------------------------------------------------------------------ reconciliation
    static void reconcile(String showId, int totalSeats, int userLimit, List<Attempt> attempts,
                          Set<String> cancelledIds, List<Sample> metricsBefore) throws Exception {
        System.out.println("\n=== FINAL RECONCILIATION ===");

        // Ledger: every distinct reservation that clients were told about.
        Map<String, Booking> ledger = new LinkedHashMap<>();
        for (Attempt a : attempts) {
            if (a.status() == 201 && a.reservationId() != null) {
                ledger.putIfAbsent(a.reservationId(), new Booking(a.reservationId(), a.user(), a.seats()));
            }
        }
        for (String id : cancelledIds) {
            Booking b = ledger.get(id);
            if (b != null) {
                b.cancelled = true;
            }
        }

        // Replays must point at a known reservation of the same user and same seats.
        int badReplays = 0;
        for (Attempt a : attempts) {
            if (a.status() == 201 && a.replay()) {
                Booking b = ledger.get(a.reservationId());
                if (b == null || !b.user.equals(a.user()) || !new HashSet<>(b.seats).equals(new HashSet<>(a.seats()))) {
                    badReplays++;
                }
            }
        }
        check("every idempotent replay returned the original reservation", badReplays == 0, "bad replays=" + badReplays);

        // One (user, key) must never map to two reservations.
        Map<String, Set<String>> byKey = new HashMap<>();
        for (Attempt a : attempts) {
            if (a.status() == 201 && a.key() != null) {
                byKey.computeIfAbsent(a.user() + "|" + a.key(), k -> new HashSet<>()).add(a.reservationId());
            }
        }
        long keyViolations = byKey.values().stream().filter(s -> s.size() > 1).count();
        check("one idempotency key never produced two reservations", keyViolations == 0, "violations=" + keyViolations);

        // No seat may be confirmed to two live reservations (the double-sell check).
        Map<String, List<String>> owners = new HashMap<>();
        Map<String, Integer> perUser = new HashMap<>();
        for (Booking b : ledger.values()) {
            if (b.cancelled) {
                continue;
            }
            for (String s : b.seats) {
                owners.computeIfAbsent(s, k -> new ArrayList<>()).add(b.id);
            }
            perUser.merge(b.user, b.seats.size(), Integer::sum);
        }
        long doubleSold = owners.values().stream().filter(l -> l.size() > 1).count();
        check("NO SEAT SOLD TWICE (each seat has at most one live reservation)", doubleSold == 0, "double-sold seats=" + doubleSold);

        int maxHeld = perUser.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        check("no user holds more than the per-user limit", maxHeld <= userLimit, "max held=" + maxHeld + " limit=" + userLimit);

        long wrongOwners = attempts.stream().filter(a -> a.code() != null && a.code().startsWith("WRONG_OWNER")).count();
        check("identity: every booking belongs to the token's user, never to a body field", wrongOwners == 0,
                "wrong owners=" + wrongOwners);

        // Zero 5xx, zero transport errors, report 429.
        long serverErrors = attempts.stream().filter(a -> a.status() >= 500).count();
        long transport = attempts.stream().filter(a -> a.transportError() != null).count();
        long shed = attempts.stream().filter(a -> a.status() == 429).count();
        check("ZERO 5xx responses across the whole burst", serverErrors == 0, "5xx=" + serverErrors);
        check("no transport errors (timeouts / resets)", transport == 0, "errors=" + transport);
        checkWarn("no requests shed with 429 (pool large enough for this load)", shed == 0, "429s=" + shed);

        // Server state.
        Resp stateResp = call("GET", "/shows/" + showId, null, null, Map.of());
        Map<String, Object> state = parseMap(stateResp.body());
        long available = num(state, "available");
        long held = num(state, "held");
        long confirmed = num(state, "confirmed");
        long total = num(state, "total_seats");
        System.out.printf("server state: available=%d held=%d confirmed=%d total=%d%n", available, held, confirmed, total);
        check("reconciliation invariant: available + held + confirmed == total_seats",
                available + held + confirmed == total && total == totalSeats,
                available + "+" + held + "+" + confirmed + " vs " + total);

        Set<String> expectedConfirmed = new HashSet<>(owners.keySet());
        Set<String> actualConfirmed = new HashSet<>();
        Object seatsObj = state.get("seats");
        if (seatsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && "confirmed".equals(m.get("status"))) {
                    actualConfirmed.add(String.valueOf(m.get("seat")));
                }
            }
        }
        check("seats confirmed on the server == seats clients were told they won",
                expectedConfirmed.equals(actualConfirmed) && confirmed == expectedConfirmed.size(),
                "server=" + actualConfirmed.size() + " clients=" + expectedConfirmed.size());

        // Metrics reconciliation.
        if (metricsBefore == null) {
            return;
        }
        Thread.sleep(600); // seat gauges are cached for 200 ms
        List<Sample> after = scrape();
        if (after == null) {
            checkWarn("metrics reachable for reconciliation", false, "second scrape failed");
            return;
        }
        long newBookings = attempts.stream().filter(a -> a.status() == 201 && !a.replay()).count();
        long replays = attempts.stream().filter(a -> a.status() == 201 && a.replay()).count();
        long seatTaken = attempts.stream().filter(a -> a.status() == 409 && "seat_taken".equals(a.code())).count();
        long limitHits = attempts.stream().filter(a -> a.status() == 409 && "per_user_limit_exceeded".equals(a.code())).count();
        long keyReuse = attempts.stream().filter(a -> a.status() == 409 && "idempotency_key_reuse".equals(a.code())).count();

        System.out.println("\nmetrics (delta over the test) vs what clients observed:");
        metricCheck("bookmyseat_reservations_confirmed_total", delta(before(metricsBefore), after, "bookmyseat_reservations_confirmed_total", null), newBookings);
        metricCheck("declined{reason=seat_taken}", delta(before(metricsBefore), after, "bookmyseat_reservations_declined_total", "seat_taken"), seatTaken);
        metricCheck("declined{reason=per_user_limit}", delta(before(metricsBefore), after, "bookmyseat_reservations_declined_total", "per_user_limit"), limitHits);
        metricCheck("declined{reason=idempotent_replay}", delta(before(metricsBefore), after, "bookmyseat_reservations_declined_total", "idempotent_replay"), replays);
        metricCheck("declined{reason=idempotency_key_reuse}", delta(before(metricsBefore), after, "bookmyseat_reservations_declined_total", "idempotency_key_reuse"), keyReuse);

        double availBefore = sum(metricsBefore, "bookmyseat_seats_available", null);
        double availAfter = sum(after, "bookmyseat_seats_available", null);
        double gaugeDrop = availBefore - availAfter;
        check("gauge bookmyseat_seats_available fell by exactly the seats sold (" + expectedConfirmed.size() + ")",
                Math.abs(gaugeDrop - expectedConfirmed.size()) < 0.5,
                "before=" + (long) availBefore + " after=" + (long) availAfter + " (only valid if nobody else uses the server meanwhile)");
    }

    static List<Sample> before(List<Sample> s) {
        return s;
    }

    static void metricCheck(String name, double metricDelta, long observed) {
        check("metric " + name + " matches observed outcomes", Math.abs(metricDelta - observed) < 0.5,
                "metric delta=" + (long) metricDelta + " observed=" + observed);
    }

    static double delta(List<Sample> before, List<Sample> after, String name, String reason) {
        return sum(after, name, reason) - sum(before, name, reason);
    }

    static double sum(List<Sample> samples, String name, String reason) {
        double total = 0;
        for (Sample s : samples) {
            if (s.name().equals(name) && (reason == null || reason.equals(s.labels().get("reason")))) {
                total += s.value();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ requests
    static Attempt reserve(String phase, User u, String showId, List<String> seats, String key,
                           boolean keyInHeader, String extraBodyField) {
        StringBuilder body = new StringBuilder("{\"seats\":").append(jsonArray(seats));
        if (key != null && !keyInHeader) {
            body.append(",\"idempotency_key\":\"").append(key).append('"');
        }
        if (extraBodyField != null) {
            body.append(',').append(extraBodyField);
        }
        body.append('}');
        Map<String, String> headers = (key != null && keyInHeader) ? Map.of("Idempotency-Key", key) : Map.of();
        Resp r = call("POST", "/shows/" + showId + "/reserve", u.token(), body.toString(), headers);
        String code = null;
        String reservationId = null;
        if (r.transportError() == null) {
            Map<String, Object> m = parseMap(r.body());
            code = str(m, "error");
            if (r.status() == 201) {
                reservationId = str(m, "reservation_id");
                String owner = str(m, "user_id");
                if (owner != null && !owner.equals(u.id())) {
                    code = "WRONG_OWNER:" + owner; // surfaces as a failed check below
                }
            }
        }
        boolean replay = r.headers().containsKey("idempotent-replayed");
        return new Attempt(phase, u.id(), seats, key, r.status(), code, reservationId, replay, r.ms(), r.transportError());
    }

    static Resp call(String method, String path, String token, String body, Map<String, String> extraHeaders) {
        long t0 = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(120));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                b.header(h.getKey(), h.getValue());
            }
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, BodyPublishers.ofString(body));
            } else {
                b.method(method, BodyPublishers.noBody());
            }
            HttpResponse<String> r = CLIENT.send(b.build(), BodyHandlers.ofString());
            Map<String, String> headers = new HashMap<>();
            r.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            return new Resp(r.statusCode(), r.body(), headers, (System.nanoTime() - t0) / 1_000_000, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(-1, "", Map.of(), (System.nanoTime() - t0) / 1_000_000, "interrupted");
        } catch (Exception e) {
            Throwable cause = e.getCause();
            String text = e.getClass().getSimpleName() + ": " + e.getMessage()
                    + (cause == null ? "" : " <- " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return new Resp(-1, "", Map.of(), (System.nanoTime() - t0) / 1_000_000,
                    text.length() > 160 ? text.substring(0, 160) : text);
        }
    }

    static void waitUntilReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            Resp r = call("GET", "/ready", null, null, Map.of());
            if (r.status() == 200) {
                System.out.println("service is ready" + (attempt > 0 ? " (after waiting for a cold start)" : ""));
                return;
            }
            attempt++;
            System.out.printf("waiting for the service to be ready (status %d %s) ...%n", r.status(),
                    r.transportError() == null ? "" : r.transportError());
            Thread.sleep(3000);
        }
        System.out.println("FATAL: service did not become ready within 3 minutes");
        System.exit(2);
    }

    static String mintToken(String userId, String adminKey) {
        String body = adminKey == null
                ? "{\"user_id\":\"" + userId + "\"}"
                : "{\"user_id\":\"" + userId + "\",\"admin_key\":\"" + adminKey + "\"}";
        Resp r = call("POST", "/auth/token", null, body, Map.of());
        if (r.status() != 200) {
            return null;
        }
        return str(parseMap(r.body()), "access_token");
    }

    static List<User> mintUsers(String prefix, int count) throws Exception {
        List<Supplier<User>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = count == 1 && !prefix.endsWith("-") ? prefix : prefix + i;
            tasks.add(() -> {
                String token = mintToken(id, null);
                if (token == null) {
                    token = mintToken(id, null); // one retry
                }
                return new User(id, token);
            });
        }
        List<User> users = fire(tasks, 200);
        for (User u : users) {
            if (u.token() == null) {
                System.out.println("FATAL: could not mint a token for " + u.id());
                System.exit(2);
            }
        }
        return users;
    }

    static List<Sample> scrape() {
        Resp r = call("GET", "/metrics", null, null, Map.of());
        if (r.status() != 200) {
            return null;
        }
        List<Sample> out = new ArrayList<>();
        Pattern line = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{([^}]*)\\})?\\s+(\\S+)$");
        Pattern label = Pattern.compile("(\\w+)=\"([^\"]*)\"");
        for (String l : r.body().split("\n")) {
            if (l.startsWith("#") || l.isBlank()) {
                continue;
            }
            Matcher m = line.matcher(l.trim());
            if (!m.matches()) {
                continue;
            }
            Map<String, String> labels = new HashMap<>();
            if (m.group(3) != null) {
                Matcher lm = label.matcher(m.group(3));
                while (lm.find()) {
                    labels.put(lm.group(1), lm.group(2));
                }
            }
            try {
                out.add(new Sample(m.group(1), labels, Double.parseDouble(m.group(4))));
            } catch (NumberFormatException ignored) {
                // skip NaN / odd values
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ concurrency helper
    /** Starts every task at once behind a gate (a real stampede), but never more than 'concurrency' in flight. */
    static <T> List<T> fire(List<Supplier<T>> tasks, int concurrency) throws Exception {
        Semaphore permits = new Semaphore(concurrency);
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Supplier<T> task : tasks) {
                futures.add(ex.submit(() -> {
                    gate.await();
                    permits.acquire();
                    try {
                        return task.get();
                    } finally {
                        permits.release();
                    }
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>(tasks.size());
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    static List<String> pickSeats(Random rnd) {
        double p = rnd.nextDouble();
        String hot = "A" + (1 + rnd.nextInt(HOT));
        String any = "A" + (1 + rnd.nextInt(HALL_SEATS));
        if (p < 0.60) {
            return List.of(hot);
        }
        if (p < 0.75) {
            if (hot.equals(any)) {
                return List.of(hot);
            }
            return rnd.nextBoolean() ? List.of(hot, any) : List.of(any, hot); // both orders: exercises lock ordering
        }
        return List.of(any);
    }

    // ------------------------------------------------------------------ output
    static String label(Attempt a) {
        if (a.transportError() != null) {
            return "transport error";
        }
        int s = a.status();
        if (s == 201) {
            if (a.code() != null && a.code().startsWith("WRONG_OWNER")) {
                return "201 WRONG OWNER (identity bug)";
            }
            return a.replay() ? "201 idempotent replay" : "201 confirmed (new booking)";
        }
        if (s >= 500) {
            return s + " SERVER ERROR";
        }
        if (s == 429) {
            return "429 shed (server_busy)";
        }
        return s + " " + (a.code() == null ? "?" : a.code());
    }

    static void printTable(List<Attempt> attempts) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Attempt a : attempts) {
            counts.merge(label(a), 1, Integer::sum);
        }
        System.out.printf("   %-38s %8s %7s%n", "outcome", "count", "share");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            System.out.printf("   %-38s %8d %6.1f%%%n", e.getKey(), e.getValue(), 100.0 * e.getValue() / attempts.size());
        }
        System.out.printf("   %-38s %8d%n", "TOTAL", attempts.size());
        printTransportErrors(attempts);
    }

    static void printTransportErrors(List<Attempt> attempts) {
        Map<String, Integer> kinds = new HashMap<>();
        for (Attempt a : attempts) {
            if (a.transportError() != null) {
                kinds.merge(a.transportError(), 1, Integer::sum);
            }
        }
        if (kinds.isEmpty()) {
            return;
        }
        System.out.println("   transport errors = requests that never got an HTTP answer. What the client saw:");
        kinds.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .limit(6)
                .forEach(e -> System.out.printf("     %6d x %s%n", e.getValue(), e.getKey()));
    }

    static void printLatency(List<Attempt> attempts, double secs) {
        long[] ms = attempts.stream().filter(a -> a.transportError() == null).mapToLong(Attempt::ms).sorted().toArray();
        if (ms.length == 0) {
            return;
        }
        System.out.printf("   %d requests in %.2fs = %.0f req/s | latency p50=%dms p95=%dms p99=%dms max=%dms%n",
                attempts.size(), secs, attempts.size() / secs,
                ms[(int) (ms.length * 0.50)], ms[(int) Math.min(ms.length - 1, ms.length * 0.95)],
                ms[(int) Math.min(ms.length - 1, ms.length * 0.99)], ms[ms.length - 1]);
    }

    static void check(String name, boolean ok, String detail) {
        CHECKS.add(new CheckResult(name, ok, false, detail));
        System.out.printf("   %s %s  (%s)%n", ok ? "[ok]" : "[FAILED]", name, detail);
    }

    static void checkWarn(String name, boolean ok, String detail) {
        CHECKS.add(new CheckResult(name, ok, true, detail));
        System.out.printf("   %s %s  (%s)%n", ok ? "[ok]" : "[warn]", name, detail);
    }

    // ------------------------------------------------------------------ tiny helpers + JSON
    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    static int envInt(String k, int d) {
        return Integer.parseInt(env(k, Integer.toString(d)));
    }

    static long envLong(String k, long d) {
        return Long.parseLong(env(k, Long.toString(d)));
    }

    static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    static String jsonArray(List<String> items) {
        return items.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseMap(String json) {
        try {
            Object o = new Json(json).parse();
            return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : -1;
    }

    /** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            return value();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    i += 4;
                    return Boolean.TRUE;
                case 'f':
                    i += 5;
                    return Boolean.FALSE;
                case 'n':
                    i += 4;
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // ':'
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
            }
        }

        private List<Object> array() {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
            }
        }

        private String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String t = s.substring(start, i);
            if (t.contains(".") || t.contains("e") || t.contains("E")) {
                return Double.parseDouble(t);
            }
            return Long.parseLong(t);
        }
    }
}
