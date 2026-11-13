package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostReadFixture.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.organization.gittree.TestForgeAdmin;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostSweepTest {
    @TempDir Path directory;
    private HostReadFixture fixture;
    private final Machine machine = new Machine();
    private final Map<String, Map<Integer, Object>> tokens = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final Set<String> failed = new HashSet<>();
    private int identifier;
    private int opened;
    private int closed;

    @BeforeEach
    void prepareRootOwnedRecordsAndAnInMemoryForge() throws Exception {
        fixture = new HostReadFixture(directory);
        fixture.put("/etc/porelo-host/forge.conf", "account=forge\n");
        fixture.person("alice", "member=alpha developer\n", "");
        fixture.person("bob", "member=alpha developer\n", "");
        final Path bus = fixture.put("/run/user/900/bus", "");
        fixture.overrides.put(bus, Map.of("mode", 0140600));
    }

    private final class Machine implements ProjectRuntime.Commands {
        final List<List<String>> calls = new ArrayList<>();
        final Set<String> missing = new HashSet<>(Set.of("porelo"));
        String fail = "";
        boolean absentPeople;
        boolean absentForge;
        boolean rootPerson;
        boolean relocated;
        String users =
                "ID Username Email\n1 alice alice@example.org\n2 porelo-alice gate@example.org\n3 bob bob@example.org\n4 porelo-bob gate@example.org\n";
        Runnable inspect = () -> {};
        String generated = "a".repeat(40);

        public boolean available(String name) {
            return !missing.contains(name);
        }

        @Override
        public HostPrograms.Result run(
                List<String> args,
                byte[] input,
                Map<String, String> environment,
                Duration timeout) {
            args = new ArrayList<>(args);
            args.set(0, Path.of(args.getFirst()).getFileName().toString());
            inspect.run();
            calls.add(List.copyOf(args));
            if (args.getFirst().equals("getent")) {
                final String user = args.getLast();
                if (absentPeople && !user.equals("forge") || absentForge && user.equals("forge")) {
                    return new HostPrograms.Result(2, "");
                }
                return new HostPrograms.Result(
                        0,
                        user
                                + ":x:"
                                + (user.equals("forge") ? 900 : rootPerson ? 0 : 1000)
                                + ":1000::/nonexistent:/bin/sh\n");
            }
            if (args.getFirst().equals(fail)) {
                return new HostPrograms.Result(7, "private failure body");
            }
            if (args.contains("generate-access-token")) {
                add("porelo-admin", args.get(args.indexOf("--token-name") + 1));
                return new HostPrograms.Result(0, generated + "\n");
            }
            if (args.contains("podman") && args.getLast().equals("list")) {
                return new HostPrograms.Result(0, users);
            }
            return new HostPrograms.Result(0, "");
        }
    }

    private void add(String user, String name) {
        tokens.computeIfAbsent(user, key -> new LinkedHashMap<>()).put(++identifier, name);
    }

    private static final class ApiStatus extends IOException {}

    private TestForgeAdmin.Response response(String method, String path, byte[] body)
            throws IOException {
        try {
            Object answer =
                    api(
                            method,
                            path.substring("/admin/users/".length()),
                            method.equals("DELETE") ? 204 : 200,
                            body.length == 0
                                    ? null
                                    : Json.parseExact(new String(body, StandardCharsets.UTF_8)));
            return new TestForgeAdmin.Response(
                    method.equals("DELETE") ? 204 : 200,
                    answer == null
                            ? new byte[0]
                            : Json.write(answer).getBytes(StandardCharsets.UTF_8));
        } catch (ApiStatus error) {
            return new TestForgeAdmin.Response(503, new byte[0]);
        }
    }

    private Object api(String method, String route, int expected, Object body) throws IOException {
        requests.add(method + " " + route);
        if (failed.contains(method + " " + route) || failed.contains(route.split("/", 2)[0])) {
            throw new ApiStatus();
        }
        final String user = route.split("/", 2)[0];
        if (method.equals("PATCH")) {
            assertThat(body)
                    .as("revocation disables the exact identity without changing its source")
                    .isEqualTo(Map.of("login_name", user, "source_id", 0L, "prohibit_login", true));
            return Map.of();
        }
        final var named = tokens.computeIfAbsent(user, key -> new LinkedHashMap<>());
        if (method.equals("DELETE")) {
            named.remove(Integer.parseInt(route.substring(route.lastIndexOf('/') + 1)));
            return null;
        }
        final int page = Integer.parseInt(route.substring(route.indexOf("&page=") + 6));
        return named.entrySet().stream()
                .skip((long) (page - 1) * 50)
                .limit(50)
                .map(
                        entry ->
                                Map.of(
                                        "id",
                                        BigInteger.valueOf(entry.getKey()),
                                        "name",
                                        entry.getValue()))
                .toList();
    }

    private void taskRecord(String login, String name, String expiry) throws Exception {
        fixture.put(
                "/var/lib/porelo-host/tasks/" + login + "/" + name + ".json",
                task(login, name, FINGERPRINT).replace("2030-01-02T03:04:05Z", expiry));
    }

    private Answer run(Map<String, String> environment, String... arguments) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final HostFiles files = fixture.files();
        final ProjectRuntime runtime =
                new ProjectRuntime(
                        files,
                        machine,
                        program -> {
                            String name = Path.of(program).getFileName().toString();
                            if (machine.relocated && Path.of(program).isAbsolute()) {
                                throw new java.io.UncheckedIOException(
                                        new org.fuin.porelo.wire.SystemProgram.Missing(
                                                name, List.of()));
                            }
                            if (!machine.available(name)) {
                                throw new java.io.UncheckedIOException(
                                        new org.fuin.porelo.wire.SystemProgram.Missing(
                                                name, List.of()));
                            }
                            return Path.of(name);
                        },
                        (socket, token) -> {
                            assertThat(socket)
                                    .as("the forge account's private socket is used")
                                    .isEqualTo(
                                            fixture.path("/run/user/900/porelo-forge/http.sock"));
                            opened++;
                            return TestForgeAdmin.create(
                                    token,
                                    false,
                                    (method, path, authorization, body) -> {
                                        assertThat(authorization)
                                                .as(
                                                        "only the minted administrator credential is used")
                                                .isEqualTo(
                                                        ("token " + machine.generated)
                                                                .getBytes(
                                                                        StandardCharsets.US_ASCII));
                                        return response(method, path, body);
                                    },
                                    () -> closed++);
                        });
        final HostSweep sweep =
                new HostSweep(
                        files,
                        runtime,
                        Clock.fixed(Instant.parse("2030-01-02T03:04:05Z"), ZoneOffset.UTC));
        final int code =
                PoreloHost.run(
                        arguments,
                        environment,
                        () -> 0,
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        files,
                        env -> {
                            throw new AssertionError("Root's sweep never resolves a sudo person");
                        },
                        sweep);
        return new Answer(code, out.toString(), err.toString());
    }

    private Answer sweep() {
        return run(Map.of(), "task", "sweep");
    }

    @Test
    void aProjectsEndedTaskIsSweptByItsProjectsTokenBesideAnOldLayoutOne() throws Exception {
        fixture.put(
                "/var/lib/porelo-host/tasks/alice/alpha/ended.json",
                task("alice", "ended", FINGERPRINT));
        fixture.put(
                "/var/lib/porelo-host/tasks/alice/beta/ended.json",
                task("alice", "ended", "SHA256:" + "C".repeat(43))
                        .replace("task:alpha:", "task:beta:")
                        .replace("2030-01-02T03:04:05Z", "2030-01-02T03:04:06Z"));
        taskRecord("alice", "old", "2030-01-02T03:04:05Z");
        add("porelo-alice", "porelo-task-alpha_ended");
        add("porelo-alice", "porelo-task-beta_ended");
        add("porelo-alice", "porelo-task-old");
        assertThat(sweep())
                .isEqualTo(
                        new Answer(0, "{\"swept\":[\"alice/alpha/ended\",\"alice/old\"]}\n", ""));
        assertThat(tokens.get("porelo-alice").values())
                .as("beta's live task of that name keeps its token")
                .containsExactly("porelo-task-beta_ended");
    }

    @Test
    void onlyEndedTaskTokensAreDeletedAndAnotherSweepIsEmpty() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        taskRecord("alice", "live", "2030-01-02T03:04:06Z");
        add("porelo-alice", "porelo-task-ended");
        add("porelo-alice", "porelo-task-live");
        add("porelo-alice", "unrelated");
        add("alice", "person-token");
        assertThat(sweep())
                .as("ending at this instant expires the task, without touching other credentials")
                .isEqualTo(new Answer(0, "{\"swept\":[\"alice/ended\"]}\n", ""));
        assertThat(tokens.get("porelo-alice").values())
                .as("live and unrelated gate tokens stay")
                .containsExactly("porelo-task-live", "unrelated");
        assertThat(tokens.get("alice").values())
                .as("the person's own token stays")
                .containsExactly("person-token");
        assertThat(tokens.get("porelo-admin"))
                .as("the temporary administrator token is deleted")
                .isEmpty();
        assertThat(Files.readString(fixture.path("/var/lib/porelo-host/tasks/alice/ended.json")))
                .as("sweeping retains the durable certificate")
                .contains("ended");
        assertThat(closed)
                .as("the first session releases its client")
                .isEqualTo(opened)
                .isEqualTo(1);
        assertThat(sweep())
                .as("a retry reports only deletions made by that call")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}"})
    void aMalformedTokenNameRefusesOnePersonAndStillSweepsTheNext(String json) throws Exception {
        tokens.computeIfAbsent("porelo-alice", key -> new LinkedHashMap<>())
                .put(++identifier, Json.parseExact(json));
        for (String user : List.of("alice", "bob")) {
            taskRecord(user, "ended", "2030-01-02T03:04:05Z");
            add("porelo-" + user, "porelo-task-ended");
        }
        assertThat(sweep())
                .as("bad names are safe refusals, never loop-ending exceptions")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: the sweep deleted bob/ended; it failed for alice: Forgejo answered no valid access-token name\n"));
        assertThat(tokens.get("porelo-bob")).as("the next person's due token goes").isEmpty();
        assertThat(tokens.get("porelo-alice").values())
                .as("no ambiguous-name deletion is attempted")
                .contains("porelo-task-ended");
        assertThat(tokens.get("porelo-admin")).as("cleanup still runs").isEmpty();
        assertThat(closed).as("failed sessions release their client").isEqualTo(opened);
    }

    @Test
    void aMalformedAdministratorTokenNameIsSafelyRefusedAndTheClientIsClosed() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        tokens.computeIfAbsent("porelo-admin", key -> new LinkedHashMap<>())
                .put(++identifier, List.of());
        assertThat(sweep())
                .as("cleanup never guesses about malformed names")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: Forgejo answered no valid access-token name\n"));
        assertThat(closed)
                .as("even refused cleanup releases its client")
                .isEqualTo(opened)
                .isEqualTo(1);
        assertThat(tokens.get("porelo-admin"))
                .as("the refusal cannot claim that the temporary token was removed")
                .hasSize(2);
    }

    @Test
    void fixedPathSearchAlsoAppliesWhenTheForgeCommandIsBuilt() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        add("porelo-alice", "porelo-task-ended");
        machine.relocated = true;
        assertThat(sweep())
                .as("preflight and invocation use the same fixed-path lookup")
                .isEqualTo(new Answer(0, "{\"swept\":[\"alice/ended\"]}\n", ""));
    }

    @Test
    void fixedPathSearchAlsoAppliesToStoppingAPendingPersonsTasks() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        machine.missing.remove("porelo");
        machine.relocated = true;
        assertThat(sweep())
                .as("a relocated runuser cannot abort pending lock retries")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
        assertThat(machine.calls)
                .as("the person's tasks really were stopped")
                .anyMatch(
                        args ->
                                args.containsAll(
                                        List.of(
                                                "runuser", "bob", "porelo", "task", "stop",
                                                "--all")));
    }

    @Test
    void aPendingPersonResolvingToRootCannotTouchRootsManagerOrDelivery() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        final Path delivery = fixture.put("/run/porelo-host/people/bob/token", "keep");
        machine.rootPerson = true;
        assertThat(sweep())
                .as("an NSS alias cannot turn a person's revocation into root's")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: unfinished host revocation: bob: a required account is root or has no usable absolute home\n"));
        assertThat(delivery).as("root's delivery tree survives").exists();
        assertThat(machine.calls)
                .as("no mutation follows the root lookup")
                .allMatch(args -> args.getFirst().equals("getent"));
    }

    @Test
    void onePersonsFailureDoesNotStopAnotherAndTheDiagnosticNamesBoth() throws Exception {
        for (String user : List.of("alice", "bob")) {
            taskRecord(user, "ended", "2030-01-02T03:04:05Z");
            add("porelo-" + user, "porelo-task-ended");
        }
        failed.add("porelo-alice");
        assertThat(sweep())
                .as("the forge failure is aggregated after the other person's deletion")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: the sweep deleted bob/ended; it failed for alice: Forgejo admin API answered HTTP 503 to GET porelo-alice/tokens?limit=50&page=1\n"));
        assertThat(tokens.get("porelo-bob")).as("bob's token has actually gone").isEmpty();
        assertThat(tokens.get("porelo-admin"))
                .as("failure does not retain the administrator token")
                .isEmpty();
    }

    @Test
    void theSweepFinishesPendingRevocationsBeforeExaminingExpiredTasks() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "reason=membership\nlock=pending\n");
        fixture.put("/etc/ssh/authorized_keys/bob", "public fixture\n");
        fixture.put("/etc/tmpfiles.d/porelo-person-bob.conf", "fixture\n");
        fixture.put("/run/porelo-host/people/bob/token", "private fixture");
        fixture.overrides.put(fixture.path("/run/porelo-host/people/bob"), Map.of("uid", 1000));
        add("bob", "personal");
        add("bob", "porelo-host-lend-20261030T110000Z-young");
        add("bob", "porelo-host-lend-20261029T110000Z-old");
        add("porelo-bob", "porelo-task-live");
        assertThat(sweep())
                .as("successful retry clears its debt even with no expired task")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
        assertThat(Files.readString(fixture.path("/etc/porelo-host/people/bob.conf")))
                .as("the canonical key and reason remain while the pending marker is removed")
                .contains("locked=true\n", "ssh=" + KEY, "reason=membership\n")
                .doesNotContain("lock=pending");
        assertThat(fixture.path("/etc/ssh/authorized_keys/bob"))
                .as("SSH authorization is removed")
                .doesNotExist();
        assertThat(fixture.path("/etc/tmpfiles.d/porelo-person-bob.conf"))
                .as("runtime recreation is disabled")
                .doesNotExist();
        assertThat(requests)
                .as("forge revocation disables each login before removing all its tokens")
                .containsSubsequence(
                        "PATCH bob",
                        "GET bob/tokens?limit=50&page=1",
                        "PATCH porelo-bob",
                        "GET porelo-bob/tokens?limit=50&page=1");
        assertThat(machine.calls)
                .as("group, sessions and user manager go before the account is locked and expired")
                .containsSubsequence(
                        List.of("gpasswd", "--delete", "bob", "porelo-people"),
                        List.of("loginctl", "terminate-user", "bob"),
                        List.of("systemctl", "stop", "user@1000.service"),
                        List.of("usermod", "--lock", "--expiredate", "1", "bob"));
        assertThat(fixture.path("/run/porelo-host/people/bob"))
                .as("a revoked person's delivered credentials are removed")
                .doesNotExist();
        assertThat(tokens.get("bob"))
                .as("the person's tokens are revoked by the pending lock")
                .isEmpty();
        assertThat(tokens.get("porelo-bob"))
                .as("a pending lock removes even an unexpired gate token")
                .isEmpty();
    }

    @Test
    void expiredTaskTokensAreSweptDespiteAnUnfinishedLock() throws Exception {
        fixture.put(
                "/etc/porelo-host/people/bob.conf",
                "member=alpha developer\nlocked=true\nssh="
                        + KEY
                        + "\nexpires=\nreason=membership\nlock=pending\n");
        taskRecord("bob", "ended", "2030-01-02T03:04:05Z");
        add("porelo-bob", "porelo-task-ended");
        failed.add("PATCH bob");
        failed.add("PATCH porelo-bob");
        final Answer answer = sweep();
        assertThat(answer.code())
                .as("unfinished service revocation remains a failure")
                .isEqualTo(69);
        assertThat(answer.out()).as("a partial sweep never emits successful JSON").isEmpty();
        assertThat(answer.err())
                .as("the diagnostic reports the expired token deletion and lock debt")
                .contains(
                        "the sweep deleted bob/ended; it failed for bob:", "HTTP 503 to PATCH bob");
        assertThat(tokens.get("porelo-bob"))
                .as("the later expiry sweep still removes the token")
                .isEmpty();
        assertThat(Files.readString(fixture.path("/etc/porelo-host/people/bob.conf")))
                .as("the failed service lock stays pending for retry")
                .contains("locked=true\n", "lock=pending\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "", "root"})
    void everySetSudoUserIsRefusedAndReachesNoProgram(String user) {
        assertThat(run(Map.of("SUDO_USER", user), "task", "sweep"))
                .as("root's unit verb refuses even an empty sudo marker")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host: the sweep is root's alone, from its timer\n"));
        assertThat(machine.calls)
                .as("a refused caller never reaches a program or the forge")
                .isEmpty();
    }

    @Test
    void anUnfinishedLockWithoutDueTasksUsesTheRevocationDiagnostic() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        machine.fail = "gpasswd";
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        assertThat(sweep())
                .as("revocation errors do not become an empty successful sweep")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: unfinished host revocation: bob: gpasswd failed (exit 7)\n"));
    }

    @Test
    void noForgeAndNoDueTasksNeedNoServiceAccountOrPrograms() throws Exception {
        fixture.put("/etc/porelo-host/forge.conf", "account=invalid account\n");
        assertThat(sweep())
                .as("no due task short circuits forge configuration validation")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
        assertThat(machine.calls).as("an empty sweep never consults NSS").isEmpty();
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        assertThat(sweep())
                .as("an explicitly disabled forge has nothing to delete")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
    }

    @Test
    void unknownPeopleAndCompletedLocksAreSkippedButTheirTaskRecordsAreValidated()
            throws Exception {
        taskRecord("unknown", "ended", "2030-01-02T03:04:05Z");
        taskRecord("bob", "ended", "2030-01-02T03:04:05Z");
        final Path person = fixture.path("/etc/porelo-host/people/bob.conf");
        Files.writeString(person, Files.readString(person).replace("locked=false", "locked=true"));
        assertThat(sweep())
                .as("unknown and fully locked people are absent from the due set")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
        fixture.put("/var/lib/porelo-host/tasks/unknown/bad.json", "{}");
        assertThat(sweep().code())
                .as("an ignored person's corrupt task still refuses the census")
                .isEqualTo(65);
    }

    @Test
    void failureAfterOneDeletionLeavesThatDeletionButDoesNotClaimIt() throws Exception {
        taskRecord("alice", "one", "2030-01-02T03:04:05Z");
        taskRecord("alice", "two", "2030-01-02T03:04:05Z");
        add("porelo-alice", "porelo-task-one");
        add("porelo-alice", "porelo-task-two");
        failed.add("DELETE porelo-alice/tokens/2");
        assertThat(sweep())
                .as("a person's tasks count as swept only once all their deletes finish")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: the sweep deleted nothing; it failed for alice: Forgejo admin API answered HTTP 503 to DELETE porelo-alice/tokens/2\n"));
        assertThat(tokens.get("porelo-alice").values())
                .as("the earlier successful deletion is not rolled back")
                .containsExactly("porelo-task-two");
    }

    @Test
    void failureToRemoveTheAdministratorTokenOverridesTheSweepAnswer() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        add("porelo-alice", "porelo-task-ended");
        failed.add("porelo-admin");
        assertThat(sweep())
                .as("admin cleanup must finish before successful JSON or aggregate errors")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: Forgejo admin API answered HTTP 503 to GET porelo-admin/tokens?limit=50&page=1\n"));
        assertThat(tokens.get("porelo-alice"))
                .as("cleanup failure does not restore the task token")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"runuser", "podman", "env"})
    void aMissingForgeProgramRefusesBeforeTokenCreation(String program) throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        machine.missing.add(program);
        assertThat(sweep())
                .as("dependencies are preflighted before minting a credential")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: " + program + " is missing from the fixed PATH\n"));
        assertThat(requests).as("no HTTP call follows a failed preflight").isEmpty();
    }

    @Test
    void anInvalidGeneratedTokenIsRefusedWithoutPrintingItsValue() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        machine.generated = "private-invalid-value";
        assertThat(sweep())
                .as("unusable credential output never reaches an HTTP header or diagnostic")
                .isEqualTo(new Answer(69, "", "porelo-host: Forgejo made no valid access token\n"));
        assertThat(requests)
                .as("an invalid admin token never reaches the private socket")
                .isEmpty();
        assertThat(tokens.get("porelo-admin"))
                .as("without a usable credential, cleanup cannot be authenticated")
                .hasSize(1);
    }

    @Test
    void aMissingUserManagerBusRefusesBeforeTokenCreation() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        Files.delete(fixture.path("/run/user/900/bus"));
        assertThat(sweep())
                .as("rootless podman requires the forge account's bus")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: the user manager of forge has no session bus at /run/user/900/bus\n"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "account=bad account\n",
                "account=forge\naccount=other\n",
                "account=forge\nagents=bad agent\n",
                "account=forge\nagents=legacy\nagents=other\n",
                "account=forge\nagents=legacy,legacy\n"
            })
    void ambiguousOrUnsafeForgeConfigurationIsRefused(String config) throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        fixture.put("/etc/porelo-host/forge.conf", config);
        final String message =
                config.contains("bad account") || config.contains("account=other")
                        ? "forge.conf names no single valid account"
                        : config.contains("legacy,legacy")
                                ? "forge.conf repeats an identity"
                                : "forge.conf names no valid agent list";
        assertThat(sweep())
                .as("configuration text is validated before account switching")
                .isEqualTo(new Answer(69, "", "porelo-host: " + message + "\n"));
    }

    @Test
    void extraSweepArgumentsChangeNothing() {
        final Answer answer = run(Map.of(), "task", "sweep", "now");
        assertThat(answer.code()).as("sweep takes no task, person or time argument").isEqualTo(64);
        assertThat(answer.out()).as("invalid calls produce no result").isEmpty();
        assertThat(machine.calls).as("parsing refuses before the forge is reached").isEmpty();
    }

    @Test
    void theConfigurationAndRecordsAreCheckedBeforeTheSudoRefusal() throws Exception {
        fixture.put("/etc/porelo-host/people/unknown", "bad");
        assertThat(run(SUDO, "task", "sweep"))
                .as("records are read under the lock before SUDO_USER is checked")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host: the people directory holds an unknown record\n"));
        Files.delete(fixture.path("/etc/porelo-host/forge.conf"));
        final Answer answer = run(SUDO, "task", "sweep");
        assertThat(answer.code())
                .as("missing configuration wins over caller refusal")
                .isEqualTo(69);
        assertThat(answer.err())
                .as("the required lock file is never created")
                .endsWith("forge.conf is missing; prepare the host first\n");
    }

    @Test
    void theExclusiveConfigurationLockCoversForgeCalls() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        machine.inspect =
                () -> {
                    try {
                        final Process probe =
                                new ProcessBuilder(
                                                "/usr/bin/flock",
                                                "--shared",
                                                "--nonblock",
                                                fixture.path("/etc/porelo-host/forge.conf")
                                                        .toString(),
                                                "/usr/bin/true")
                                        .start();
                        try {
                            assertThat(probe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .as("the independent reader probe is bounded")
                                    .isTrue();
                            assertThat(probe.exitValue())
                                    .as(
                                            "even readers are excluded throughout NSS and forge commands")
                                    .isEqualTo(1);
                        } finally {
                            probe.destroyForcibly();
                            probe.getInputStream().close();
                            probe.getErrorStream().close();
                            probe.getOutputStream().close();
                        }
                    } catch (Exception ex) {
                        throw new AssertionError("Cannot run the independent flock probe", ex);
                    }
                };
        assertThat(sweep().code())
                .as("the sweep completes under the exclusive Linux flock")
                .isZero();
    }

    @Test
    void anUnavailableServiceAccountIsRefusedBeforeMintingAToken() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        machine.absentForge = true;
        assertThat(sweep())
                .as("a configured but absent forge is not a disabled forge")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: a required service or person account is missing\n"));
        assertThat(requests).as("the forge was never reached").isEmpty();
    }

    @Test
    void anAccountAcceptedByConfigurationStillMustHaveAValidEndpointName() throws Exception {
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        fixture.put("/etc/porelo-host/forge.conf", "account=_forge\n");
        assertThat(sweep())
                .as(
                        "an account name the configuration accepts must still be a valid forge endpoint name")
                .isEqualTo(new Answer(69, "", "porelo-host: forge.conf names no valid account\n"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "private failure text",
                "ID Username Email\nnot-an-id alice a@example.org\n"
            })
    void invalidForgeUserListsLeaveThePersonPending(String users) throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        machine.users = users;
        assertThat(sweep())
                .as("a lock cannot invent or skip identities after an invalid user listing")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: unfinished host revocation: bob: Forgejo answered no valid user list\n"));
        assertThat(Files.readString(fixture.path("/etc/porelo-host/people/bob.conf")))
                .as("the failure is retryable")
                .contains("lock=pending\n");
        assertThat(tokens.get("porelo-admin"))
                .as("the temporary token is still cleaned up")
                .isEmpty();
    }

    @Test
    void aPendingPersonWhoseAccountHasGoneStillFinishesServiceRevocation() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        machine.absentPeople = true;
        assertThat(sweep())
                .as("account removal does not prevent retrying the forge lock")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
        assertThat(machine.calls)
                .as("absent accounts have no login session or user manager to stop")
                .noneMatch(
                        args ->
                                List.of("loginctl", "systemctl", "usermod")
                                        .contains(args.getFirst()));
        assertThat(requests)
                .as("service identities are still locked")
                .contains("PATCH bob", "PATCH porelo-bob");
    }

    @Test
    void pendingLockTaskStopRestoresExpiryAndStopsTheUserManagerEvenOnFailure() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        machine.missing.remove("porelo");
        machine.fail = "runuser";
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        assertThat(sweep())
                .as("an unsuccessful stop is still an unfinished lock")
                .isEqualTo(
                        new Answer(
                                69,
                                "",
                                "porelo-host: unfinished host revocation: bob: runuser failed (exit 7)\n"));
        assertThat(machine.calls)
                .as("temporary PAM expiry relief is always undone")
                .containsSubsequence(
                        List.of("usermod", "--expiredate", "", "bob"),
                        List.of("systemctl", "start", "user@1000.service"),
                        List.of("usermod", "--expiredate", "1", "bob"),
                        List.of("systemctl", "stop", "user@1000.service"));
        assertThat(Files.readString(fixture.path("/etc/porelo-host/people/bob.conf")))
                .as("the local lock stays in effect with retry debt")
                .contains("locked=true\n", "lock=pending\n");
    }

    @Test
    void deliveryCleanupNeverFollowsAPersonsLinks() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        final Path sentinel = fixture.put("/sentinel", "must remain");
        fixture.put("/run/porelo-host/people/bob/nested/entry", "fixture");
        final Path delivery = fixture.path("/run/porelo-host/people/bob");
        fixture.overrides.put(delivery, Map.of("uid", 1000));
        Files.createSymbolicLink(delivery.resolve("link"), sentinel);
        assertThat(sweep())
                .as("a pending lock safely removes the person's runtime tree")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
        assertThat(delivery).as("all runtime entries are gone").doesNotExist();
        assertThat(Files.readString(sentinel))
                .as("a link cannot redirect root's deletion")
                .isEqualTo("must remain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-owner", "symbolic-link"})
    void anUnsafeDeliveryDirectoryIsRefusedAndLeftForRetry(String defect) throws Exception {
        fixture.person("bob", "member=alpha developer\n", "lock=pending\n");
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        final Path sentinel = fixture.put("/sentinel/entry", "must remain");
        final Path delivery = fixture.path("/run/porelo-host/people/bob");
        fixture.put("/run/porelo-host/people/fixture", "fixture");
        if (defect.equals("symbolic-link")) {
            Files.createSymbolicLink(delivery, sentinel.getParent());
        } else {
            fixture.put("/run/porelo-host/people/bob/entry", "fixture");
            fixture.overrides.put(delivery, Map.of("uid", 2000));
        }
        final Answer answer = sweep();
        assertThat(answer.code())
                .as("unsafe runtime cleanup remains unfinished revocation")
                .isEqualTo(69);
        assertThat(answer.err())
                .as("the unsafe directory is refused at both owed removal steps")
                .isEqualTo(
                        "porelo-host: unfinished host revocation: bob: the forge token delivery directory is not a private directory of the person's; the forge token delivery directory is not a private directory of the person's\n");
        assertThat(Files.readString(sentinel))
                .as("an unrelated tree is never removed")
                .isEqualTo("must remain");
        assertThat(Files.readString(fixture.path("/etc/porelo-host/people/bob.conf")))
                .as("failed local revocation must not claim a completed lock")
                .contains("locked=false\n", "lock=pending\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"bad-person", "wrong-task-mode", "unknown-task-owner", "invalid-task-json"})
    void unsafeOrMalformedStoredDataRefusesBeforeMintingAToken(String defect) throws Exception {
        final Path task =
                fixture.put(
                        "/var/lib/porelo-host/tasks/alice/ended.json",
                        task("alice", "ended", FINGERPRINT));
        switch (defect) {
            case "bad-person" -> fixture.put("/etc/porelo-host/people/alice.conf", "bad");
            case "wrong-task-mode" -> fixture.overrides.put(task, Map.of("mode", 0100640));
            case "unknown-task-owner" ->
                    fixture.put("/var/lib/porelo-host/tasks/unknown-entry/entry", "bad");
            default -> fixture.put("/var/lib/porelo-host/tasks/alice/ended.json", "{}");
        }
        final Answer answer = sweep();
        assertThat(answer.code())
                .as("stored data refuses with its own validation status")
                .isEqualTo(defect.equals("wrong-task-mode") ? 73 : 65);
        assertThat(answer.out()).as("refusal never looks like an empty sweep").isEmpty();
        assertThat(machine.calls).as("all records are validated before a forge session").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"crlf", "no-final-newline"})
    void aRetriedLockPublishesCanonicalLinesWithoutLosingRecordFields(String shape)
            throws Exception {
        String record =
                "member=alpha developer\nlocked=false\nssh="
                        + KEY
                        + "\nexpires=20300101000000Z\nreason=membership\nlocked_on=2030-01-01\nlock=pending\n";
        record = shape.equals("crlf") ? record.replace("\n", "\r\n") : record.stripTrailing();
        fixture.put("/etc/porelo-host/people/bob.conf", record);
        fixture.put("/etc/porelo-host/forge.conf", "account=\n");
        assertThat(sweep())
                .as("both line encodings accepted by the reader can complete a pending lock")
                .isEqualTo(new Answer(0, "{\"swept\":[]}\n", ""));
        assertThat(Files.readString(fixture.path("/etc/porelo-host/people/bob.conf")))
                .as("the lock writes LF lines and keeps the key, expiry, reason and lock day")
                .isEqualTo(
                        "member=alpha developer\nlocked=true\nssh="
                                + KEY
                                + "\nexpires=20300101000000Z\nreason=membership\nlocked_on=2030-01-01\n");
    }

    @Test
    void expirySweepingDoesNotRequireAnUnlockedPersonsCurrentMembership() throws Exception {
        fixture.person("alice", "", "");
        taskRecord("alice", "ended", "2030-01-02T03:04:05Z");
        add("porelo-alice", "porelo-task-ended");
        assertThat(sweep())
                .as("sweep uses the lock state, not task show's admission requirement")
                .isEqualTo(new Answer(0, "{\"swept\":[\"alice/ended\"]}\n", ""));
        assertThat(tokens.get("porelo-alice")).as("the expired token is removed").isEmpty();
    }
}
