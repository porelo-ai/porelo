package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HostPeopleTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
    }

    private HostReadFixture.Answer run(String... arguments) {
        return run(0, Map.of(), arguments);
    }

    private HostReadFixture.Answer run(
            long uid, Map<String, String> environment, String... arguments) {
        final var out = new StringWriter();
        final var err = new StringWriter();
        final int code =
                PoreloHost.run(
                        arguments,
                        environment,
                        () -> uid,
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        f.host,
                        ignored -> {
                            throw new AssertionError("Root maintenance has no sudo person");
                        },
                        new HostSweep(f.host),
                        f.runtime.until(f.clock, f.now.plusSeconds(300)),
                        InputStream.nullInputStream(),
                        f.clock);
        return new HostReadFixture.Answer(code, out.toString(), err.toString());
    }

    private HostReadFixture.Answer purge() {
        return run("person", "purge", "bob");
    }

    private void record(String login, String members, boolean locked, String day, boolean pending)
            throws Exception {
        f.write(
                "/etc/porelo-host/people/" + login + ".conf",
                members
                        + "locked="
                        + locked
                        + "\nssh="
                        + FollowFixture.key(2)
                        + "\nexpires=\n"
                        + (day == null ? "" : "locked_on=" + day + "\n")
                        + (pending ? "lock=pending\n" : ""));
    }

    private void due() throws Exception {
        record("bob", "", true, "2026-10-28", false);
        f.write(
                FollowFixture.ORG + "applied.json",
                write(
                        fields(
                                "commit",
                                "a".repeat(40),
                                "persons",
                                List.of(),
                                "projects",
                                List.of(),
                                "admins",
                                List.of(),
                                "revoked",
                                List.of())));
        f.write("/etc/ssh/authorized_keys/bob", FollowFixture.key(2) + "\n");
        for (String kind : List.of("subuid", "subgid")) {
            f.write("/etc/" + kind, "other:100000:65536\nbob:200000:65536\nbob:300000:65536\n");
            Files.setAttribute(f.path("/etc/" + kind), "unix:mode", 0600);
        }
    }

    private void refused(HostReadFixture.Answer answer, int code, String words) {
        assertThat(answer.code()).as(answer.err()).isEqualTo(code);
        assertThat(answer.err()).as("the refusal explains the failed boundary").contains(words);
        assertThat(answer.out()).as("a refusal claims no completed act").isEmpty();
        assertThat(f.accounts).as("the target account survives refusal").containsKey("bob");
        assertThat(f.path("/etc/porelo-host/people/bob.conf"))
                .as("retry state survives refusal")
                .exists();
    }

    private void noTeardown() {
        assertThat(f.commands)
                .as("refusal must precede access removal or account execution")
                .allMatch(
                        argv -> Path.of(argv.getFirst()).getFileName().toString().equals("getent"));
    }

    // Harm: an upgrade must restore admitted people's return sockets and managers without admitting
    // anyone else.
    @Test
    void restoreRepairsOnlyAdmittedPeopleAndRestartsTheirManagers() throws Exception {
        record("bob", "member=alpha developer\n", true, null, false);
        record("charlie", "member=alpha developer\n", false, null, true);
        record("dora", "", false, null, false);
        for (String login : List.of("bob", "charlie", "dora")) {
            f.write("/run/porelo-host/people/" + login + "/kept", "the person's existing runtime");
            f.write("/etc/tmpfiles.d/porelo-person-" + login + ".conf", "the existing boot rule");
        }
        final var first = run("person", "restore-runtime");
        assertThat(first.code()).as(first.err()).isZero();
        assertThat(object(parse(first.out())))
                .as("only Alice's runtime is repaired")
                .containsEntry("changed", BigInteger.ONE);
        final Path alice = f.path("/run/porelo-host/people/alice");
        assertThat((int) Files.getAttribute(alice, "unix:mode") & 07777)
                .as("delivery is private")
                .isEqualTo(0700);
        assertThat(Files.readSymbolicLink(alice.resolve("porelod.sock")))
                .as("tunnels reach Alice's own daemon")
                .isEqualTo(
                        Path.of("/run/user/" + f.accounts.get("alice") + "/porelo/porelod.sock"));
        assertThat(f.commands.stream().filter(a -> a.contains("restart")).toList())
                .as("locked, pending and membership-free people have no manager restarted")
                .containsExactly(
                        List.of(
                                "systemctl",
                                "restart",
                                "user@" + f.accounts.get("alice") + ".service"));
        for (String login : List.of("bob", "charlie", "dora")) {
            assertThat(f.read("/run/porelo-host/people/" + login + "/kept"))
                    .as("unadmitted runtimes are not touched")
                    .isEqualTo("the person's existing runtime");
            assertThat(f.read("/etc/tmpfiles.d/porelo-person-" + login + ".conf"))
                    .as("unadmitted boot rules are not touched")
                    .isEqualTo("the existing boot rule");
        }
        assertThat(object(parse(run("person", "restore-runtime").out())))
                .as("file repair is idempotent")
                .containsEntry("changed", BigInteger.ZERO);
    }

    // Harm: root must preserve a person's existing daemon entry and restore a missing one without
    // following it.
    @ParameterizedTest
    @ValueSource(strings = {"missing", "file", "dangling"})
    void restorePreservesExistingPersonEntriesAndRepairsMissingLinks(String entry)
            throws Exception {
        assertThat(run("person", "restore-runtime").code())
                .as("initial runtime restoration")
                .isZero();
        final Path link = f.path("/run/porelo-host/people/alice/porelod.sock");
        Files.delete(link);
        if (entry.equals("file")) Files.writeString(link, "the person's own entry");
        if (entry.equals("dangling")) Files.createSymbolicLink(link, Path.of("nowhere"));
        final var answer = run("person", "restore-runtime");
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(object(parse(answer.out())))
                .as("only a missing link changes files")
                .containsEntry(
                        "changed", entry.equals("missing") ? BigInteger.ONE : BigInteger.ZERO);
        if (entry.equals("file"))
            assertThat(Files.readString(link))
                    .as("root preserves user content")
                    .isEqualTo("the person's own entry");
        if (entry.equals("dangling"))
            assertThat(Files.readSymbolicLink(link))
                    .as("root preserves dangling entries")
                    .isEqualTo(Path.of("nowhere"));
    }

    // Harm: a safe but unusable boot-rule mode must not silently leave a person without runtime
    // restoration.
    @Test
    void restoreRepairsTheBootRuleMode() throws Exception {
        assertThat(run("person", "restore-runtime").code()).as("initial restoration").isZero();
        final Path rule = f.path("/etc/tmpfiles.d/porelo-person-alice.conf");
        Files.setAttribute(rule, "unix:mode", 0600);
        final var answer = run("person", "restore-runtime");
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat((int) Files.getAttribute(rule, "unix:mode") & 07777)
                .as("tmpfiles can read the rule")
                .isEqualTo(0644);
        assertThat(object(parse(answer.out())))
                .as("mode repair counts a changed person")
                .containsEntry("changed", BigInteger.ONE);
    }

    // Harm: root must refuse an unsafe boot rule before it creates a person-owned delivery
    // directory.
    @ParameterizedTest
    @ValueSource(strings = {"link", "directory", "writable"})
    void restoreRefusesUnsafeBootRulesBeforeCreatingRuntime(String damage) throws Exception {
        final Path rule = f.path("/etc/tmpfiles.d/porelo-person-alice.conf");
        f.write("/etc/tmpfiles.d/placeholder", "keep");
        if (damage.equals("link")) Files.createSymbolicLink(rule, f.path("/etc/subuid"));
        if (damage.equals("directory")) Files.createDirectory(rule);
        if (damage.equals("writable")) {
            Files.writeString(rule, "bad");
            Files.setAttribute(rule, "unix:mode", 0666);
        }
        final var answer = run("person", "restore-runtime");
        refused(answer, 73, "root's alone");
        assertThat(f.path("/run/porelo-host/people/alice"))
                .as("unsafe rule refuses before runtime writes")
                .doesNotExist();
        noTeardown();
    }

    // Harm: a missing or reassigned account must never receive a runtime or a manager restart.
    @ParameterizedTest
    @ValueSource(strings = {"missing", "root", "relative-home"})
    void restoreRefusesUnusableAccounts(String damage) {
        if (damage.equals("missing")) f.accounts.remove("alice");
        if (damage.equals("root")) f.accounts.put("alice", 0L);
        if (damage.equals("relative-home"))
            f.override =
                    argv ->
                            Path.of(argv.getFirst()).getFileName().toString().equals("getent")
                                            && argv.getLast().equals("alice")
                                    ? new HostPrograms.Result(
                                            0, "alice:x:1001:1001::relative:/bin/bash\n")
                                    : null;
        refused(run("person", "restore-runtime"), 69, "account");
        noTeardown();
    }

    // Harm: an untrusted delivery directory must never be accepted as the person's private return
    // channel.
    @Test
    void restoreRefusesAPublicDeliveryDirectory() throws Exception {
        assertThat(run("person", "restore-runtime").code()).as("initial restoration").isZero();
        Files.setAttribute(f.path("/run/porelo-host/people/alice"), "unix:mode", 0755);
        f.commands.clear();
        refused(run("person", "restore-runtime"), 73, "private directory");
        noTeardown();
    }

    // Harm: a manager restart failure must not be reported as successful recovery.
    @Test
    void restoreReportsAFailedManagerRestart() {
        f.override =
                argv ->
                        argv.contains("restart")
                                ? new HostPrograms.Result(1, "", "private child output")
                                : null;
        refused(run("person", "restore-runtime"), 69, "systemctl failed");
    }

    /** Exercises runtime creation with the kernel's actual inherited mask. */
    public static final class RuntimeChild {
        private RuntimeChild() {}

        /** Uses only the synthetic host below the supplied test directory. */
        public static void main(String[] arguments) throws Exception {
            final Path root = Path.of(arguments[0]);
            Files.createDirectory(root);
            Files.setAttribute(root, "unix:mode", 0700);
            final var fixture = new FollowFixture(root);
            final Path runtime = fixture.path("/run/porelo-host/people/alice");
            try {
                new HostPeople(fixture.context()).restoreRuntime();
                assertThat((int) Files.getAttribute(runtime, "unix:mode") & 07777)
                        .as("a restrictive caller mask cannot make the person's runtime unusable")
                        .isEqualTo(0700);
            } finally {
                if (Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setAttribute(runtime, "unix:mode", 0700);
                }
            }
        }
    }

    // Harm: a restrictive service umask must not leave an admitted person's return channel
    // unusable.
    @Test
    void restoreCreatesAUsableRuntimeUnderARestrictiveUmask() throws Exception {
        final var arguments =
                List.of(
                        "/bin/sh",
                        "-c",
                        "umask 0777; exec \"$@\" 2>&1",
                        "runtime-mask",
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-XX:-UsePerfData",
                        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        RuntimeChild.class.getName(),
                        directory.resolve("masked").toString());
        final var result =
                new HostPrograms(Duration.ofSeconds(30), 65536, Path::of)
                        .run(arguments, new byte[0], ProjectRuntime.SAFE_ENV);
        assertThat(result.exitCode())
                .as("runtime creation must work under the service's mask: " + result.output())
                .isZero();
    }

    // Harm: maintenance must require the real root uid before parsing or looking at host files.
    @ParameterizedTest
    @ValueSource(strings = {"purge", "restore-runtime"})
    void nonRootMaintenanceRefusesBeforeParsing(String verb) {
        final var answer = run(1000, Map.of(), "person", verb, "--bad-argument");
        refused(answer, 69, "runs only as root");
        assertThat(f.commands).as("non-root reaches no host boundary").isEmpty();
    }

    // Harm: a stale deadline cannot delete anyone who remains listed, including a locked project
    // member.
    @ParameterizedTest
    @ValueSource(strings = {"active", "locked", "admin", "record"})
    void aListedPersonIsNeverPurged(String listing) throws Exception {
        due();
        final Map<String, Object> state = f.applied();
        if (listing.equals("record"))
            record("bob", "member=alpha developer\n", true, "2026-10-28", false);
        else if (listing.equals("admin")) state.put("admins", List.of(fields("login", "bob")));
        else
            state.put(
                    "projects",
                    List.of(
                            fields(
                                    "project",
                                    "alpha",
                                    "members",
                                    List.of(
                                            fields(
                                                    "login",
                                                    "bob",
                                                    "locked",
                                                    listing.equals("locked"))))));
        f.write(FollowFixture.ORG + "applied.json", write(state));
        refused(purge(), 65, "listed");
        noTeardown();
        assertThat(f.path("/etc/ssh/authorized_keys/bob"))
                .as("membership refusal preserves access files")
                .exists();
    }

    // Harm: an active account or an unexpired grace period must never become an administrative
    // deletion shortcut.
    @ParameterizedTest
    @CsvSource({"false,2026-10-28", "true,2026-10-29", "true,2027-01-01", "true,none"})
    void unlockedOrNotYetDuePeopleAreRefused(boolean locked, String day) throws Exception {
        due();
        record("bob", "", locked, day.equals("none") ? null : day, false);
        refused(purge(), 65, locked ? "due" : "locked");
        noTeardown();
    }

    // Harm: missing or malformed organization membership cannot be treated as proof that a person
    // left.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "projects",
                "members",
                "member",
                "admins",
                "admin",
                "refused-project"
            })
    void purgeRequiresACompleteMembershipInventory(String damage) throws Exception {
        due();
        final var state = f.applied();
        switch (damage) {
            case "projects" -> state.remove("projects");
            case "members" -> state.put("projects", List.of(fields("project", "alpha")));
            case "member" ->
                    state.put(
                            "projects",
                            List.of(
                                    fields(
                                            "project",
                                            "alpha",
                                            "members",
                                            List.of(fields("locked", true)))));
            case "admins" -> state.remove("admins");
            case "admin" -> state.put("admins", List.of(fields("key", "unknown")));
            case "refused-project" ->
                    state.put("project_refusals", fields("alpha", "invalid members"));
            default -> {}
        }
        if (damage.equals("missing")) Files.delete(f.path(FollowFixture.ORG + "applied.json"));
        else f.write(FollowFixture.ORG + "applied.json", write(state));
        refused(purge(), 65, "organization");
        noTeardown();
    }

    // Harm: a rejected project must not disappear from the follow's proof that somebody left every
    // project.
    @Test
    void anIncompleteProjectInventoryNeverRunsTheFollowPurge() throws Exception {
        due();
        final var state = f.applied();
        state.put("project_refusals", fields("alpha", "invalid members"));
        f.write(FollowFixture.ORG + "applied.json", write(state));
        assertThatThrownBy(() -> new FollowPeople(f.context()).memberships(object(state)))
                .as("a rejected project is not evidence of membership absence")
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("organization");
        assertThat(f.accounts)
                .as("the follow keeps the account until membership can be judged")
                .containsKey("bob");
    }

    // Harm: root, system, unrecorded and path-like targets must never reach account removal.
    @ParameterizedTest
    @CsvSource({
        "root,65",
        "daemon,65",
        "outside,65",
        "unknown,65",
        "Alice,64",
        "../bob,64",
        "-bob,64",
        "porelo-bob,64"
    })
    void purgeRefusesTargetsOutsideThePeopleContract(String login, int code) throws Exception {
        due();
        f.accounts.put("daemon", 1L);
        f.accounts.put("outside", 1000L);
        final var answer = run("person", "purge", login);
        assertThat(answer.code()).as(answer.err()).isEqualTo(code);
        noTeardown();
    }

    // Harm: a reassigned service account must stay protected by the host's own UID_MIN.
    @Test
    void purgeUsesTheHostsMinimumUid() throws Exception {
        due();
        f.write("/etc/login.defs", "# local policy\nUID_MIN\t\t 2000\n");
        f.accounts.put("bob", 1500L);
        refused(purge(), 65, "system account");
        noTeardown();
    }

    // Harm: missing teardown dependencies must not leave a partly revoked person.
    @ParameterizedTest
    @ValueSource(
            strings = {"gpasswd", "loginctl", "usermod", "systemctl", "userdel", "runuser", "env"})
    void purgePreflightsItsRequiredPrograms(String program) throws Exception {
        due();
        f.missing.add(program);
        refused(purge(), 69, program);
        noTeardown();
        assertThat(f.path("/etc/ssh/authorized_keys/bob"))
                .as("dependency refusal precedes key removal")
                .exists();
    }

    // Harm: malformed ranges or untrusted subordinate-ID files must refuse before revocation or
    // deletion.
    @ParameterizedTest
    @ValueSource(
            strings = {"subuid-malformed", "subgid-malformed", "subuid-writable", "subgid-missing"})
    void purgeValidatesBothSubordinateIdInventoriesBeforeEffects(String damage) throws Exception {
        due();
        final String kind = damage.substring(0, 6);
        if (damage.endsWith("malformed")) f.write("/etc/" + kind, "bob:4294967294:2\n");
        if (damage.endsWith("writable"))
            Files.setAttribute(f.path("/etc/" + kind), "unix:mode", 0666);
        if (damage.endsWith("missing")) Files.delete(f.path("/etc/" + kind));
        refused(purge(), 69, kind);
        noTeardown();
    }

    // Harm: unsafe configuration must refuse before any root account operation.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-conf",
                "writable-conf",
                "linked-conf",
                "linked-person",
                "malformed-person"
            })
    void maintenanceRefusesUntrustedConfiguration(String damage) throws Exception {
        due();
        final Path conf = f.path("/etc/porelo-host/forge.conf");
        if (damage.equals("missing-conf")) Files.delete(conf);
        if (damage.equals("writable-conf")) Files.setAttribute(conf, "unix:mode", 0666);
        if (damage.equals("linked-conf")) {
            Files.delete(conf);
            Files.createSymbolicLink(conf, f.path("/etc/subuid"));
        }
        if (damage.equals("linked-person")) {
            Files.delete(f.path("/etc/porelo-host/people/bob.conf"));
            Files.createSymbolicLink(
                    f.path("/etc/porelo-host/people/bob.conf"), f.path("/etc/subuid"));
        }
        if (damage.equals("malformed-person"))
            f.write("/etc/porelo-host/people/bob.conf", "locked=maybe\n");
        final var answer = purge();
        assertThat(answer.code())
                .as(answer.err())
                .isEqualTo(damage.equals("malformed-person") ? 65 : 69);
        noTeardown();
    }

    // Harm: purge must stop tasks and clear the person's vault before deleting their account and
    // only their ranges.
    @Test
    void duePurgeClearsAsThePersonBeforeAccountAndRangeRemoval() throws Exception {
        due();
        final long bobUid = f.accounts.get("bob");
        final var answer = purge();
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(object(parse(answer.out())))
                .as("purge reports the explicit target")
                .containsEntry("person", "bob")
                .containsEntry("purged", true);
        assertThat(f.accounts)
                .as("only Bob is deleted")
                .containsKeys("alice", "charlie")
                .doesNotContainKey("bob");
        assertThat(f.path("/etc/porelo-host/people/bob.conf"))
                .as("completed retry state is removed")
                .doesNotExist();
        assertThat(f.path("/etc/ssh/authorized_keys/bob"))
                .as("SSH access is removed")
                .doesNotExist();
        final var clear =
                f.commands.stream().filter(a -> a.contains("clear")).findFirst().orElseThrow();
        assertThat(clear)
                .as("vault cleanup runs as Bob from his account's runtime")
                .containsSequence("runuser", "-u", "bob", "--", "env", "-C", "/")
                .contains("HOME=/home/bob", "XDG_RUNTIME_DIR=/run/user/" + bobUid);
        final int clearing = f.commands.indexOf(clear);
        assertThat(f.commands.subList(0, clearing))
                .as("task stop precedes vault clear")
                .anyMatch(a -> a.contains("task") && a.contains("--all"));
        assertThat(f.commands.get(f.commands.size() - 2))
                .as("manager ends synchronously before userdel")
                .contains("systemctl", "stop");
        assertThat(f.commands.getLast())
                .as("userdel follows cleanup")
                .containsExactly("userdel", "--remove", "bob");
        for (String kind : List.of("subuid", "subgid")) {
            assertThat(f.read("/etc/" + kind))
                    .as("unrelated subordinate ranges stay")
                    .isEqualTo("other:100000:65536\n");
            assertThat((int) Files.getAttribute(f.path("/etc/" + kind), "unix:mode") & 07777)
                    .as("range file modes stay")
                    .isEqualTo(0600);
        }
        assertThat(new HostRecords(f.host).purged())
                .as("the login is permanently reserved")
                .containsExactly("bob");
    }

    // Harm: an interrupted deletion must remain finishable without an account or the account-only
    // programs.
    @Test
    void purgeFinishesAnAlreadyAbsentAccount() throws Exception {
        due();
        f.accounts.remove("bob");
        f.missing.addAll(
                List.of("loginctl", "usermod", "systemctl", "userdel", "porelo", "runuser", "env"));
        final var answer = purge();
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(f.path("/etc/porelo-host/people/bob.conf"))
                .as("remaining record is removed")
                .doesNotExist();
        assertThat(new HostRecords(f.host).purged())
                .as("the absent login remains reserved")
                .contains("bob");
        assertThat(f.commands)
                .as("no account-only command is needed")
                .allMatch(
                        a ->
                                Path.of(a.getFirst()).getFileName().toString().equals("getent")
                                        || a.contains("gpasswd"));
    }

    // Harm: an optional missing Porelo must not skip manager stop or OS account deletion.
    @Test
    void purgeWithoutPoreloStillStopsTheManagerBeforeDeleting() throws Exception {
        due();
        f.missing.add("porelo");
        f.override =
                argv -> argv.contains("terminate-user") ? new HostPrograms.Result(1, "") : null;
        final var answer = purge();
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(f.commands)
                .as("missing Porelo runs no person cleanup")
                .noneMatch(a -> a.contains("runuser"));
        assertThat(f.commands.get(f.commands.size() - 2))
                .as("manager stop remains mandatory")
                .contains("systemctl", "stop");
        assertThat(f.commands.getLast())
                .as("logind's already-gone result does not block userdel")
                .containsExactly("userdel", "--remove", "bob");
    }

    // Harm: any failed destructive step must keep a retryable locked person instead of deleting
    // their remaining work.
    @ParameterizedTest
    @ValueSource(strings = {"matrix", "task", "clear", "userdel"})
    void failedPurgeKeepsTheAccountAndRecordAndCanRetry(String step) throws Exception {
        due();
        if (step.equals("matrix")) f.accounts.put("porelo-matrix", f.accounts.get("alice"));
        f.override =
                argv ->
                        (step.equals("matrix")
                                        ? argv.contains("expel")
                                        : step.equals("task")
                                                ? argv.contains("task") && argv.contains("--all")
                                                : argv.contains(step))
                                ? new HostPrograms.Result(1, "", "private failure bytes")
                                : null;
        refused(
                purge(),
                69,
                step.equals("matrix")
                        ? "expel"
                        : step.equals("clear")
                                ? "vault clear"
                                : step.equals("task") ? "runuser" : step);
        assertThat(f.read("/etc/porelo-host/people/bob.conf"))
                .as("retry intent remains durable")
                .contains("lock=pending");
        assertThat(f.commands)
                .as("a failed earlier step cannot reach account deletion")
                .noneMatch(a -> !step.equals("userdel") && a.contains("userdel"));
        if (step.equals("task"))
            assertThat(f.commands)
                    .as("a surviving task prevents vault cleanup")
                    .noneMatch(a -> a.contains("clear"));
        f.override = argv -> null;
        assertThat(purge().code())
                .as("retry completes after the failed dependency recovers")
                .isZero();
    }

    // Harm: a nonzero vault answer may spare only remote transport accounts, never local secrets or
    // refused cleanup.
    @ParameterizedTest
    @ValueSource(strings = {"transport", "vault", "mixed", "refused", "empty", "malformed"})
    void purgeAcceptsOnlyTransportAccountsKeptElsewhere(String kept) throws Exception {
        due();
        final var transport =
                fields(
                        "kind",
                        "conversation",
                        "status",
                        "NOT_REMOVED",
                        "transport",
                        "matrix",
                        "project",
                        "alpha");
        final var vault = fields("kind", "vault", "status", "NOT_REMOVED");
        final var items =
                switch (kept) {
                    case "transport", "refused" -> List.of(transport);
                    case "vault" -> List.of(vault);
                    case "mixed" -> List.of(transport, vault);
                    default -> List.of();
                };
        final String output =
                kept.equals("malformed")
                        ? "not-json"
                        : write(fields("refused", kept.equals("refused"), "items", items));
        f.override = argv -> argv.contains("clear") ? new HostPrograms.Result(1, output) : null;
        final var answer = purge();
        if (kept.equals("transport")) assertThat(answer.code()).as(answer.err()).isZero();
        else {
            refused(answer, 69, "vault clear");
            assertThat(f.commands)
                    .as("kept secrets prevent deletion")
                    .noneMatch(a -> a.contains("userdel"));
        }
    }
}
