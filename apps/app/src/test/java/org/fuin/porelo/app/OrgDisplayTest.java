package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeOffline;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Displays are descriptive; every path that can change permissions still checks the source. */
class OrgDisplayTest {
    @TempDir Path dir;
    private RegistryCheckout machine;
    private TestOrganization organization;
    private PoreloContext context;
    private GitTreeStore store;
    private final AtomicInteger fetches = new AtomicInteger();
    private boolean unreachable;
    private String unreachableSays = "fixture unavailable";
    private boolean captureRefresh;
    private boolean failScheduling;
    private int refreshRequests;
    private boolean blockOpening;
    private final java.util.concurrent.CountDownLatch opening =
            new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch resumeOpening =
            new java.util.concurrent.CountDownLatch(1);
    private final Map<String, String> repositories = new java.util.HashMap<>();
    private org.fuin.porelo.core.process.CommandRunner blockedFetch;
    private java.time.Duration refreshLimit;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void organization() throws Exception {
        machine = new RegistryCheckout(dir);
        organization = new TestOrganization(machine);
        repositories.put(TestOrganization.ADDRESS, organization.repo.toString());
        organization.filed("michael");
        organization
                .writes()
                .addProject(organization.change("michael", "michael", "demo"), "demo", "michael");
        final Path card = machine.paths.xdg().config().resolve("identity/card");
        Files.createDirectories(card.getParent());
        Files.writeString(card, organization.card("michael"));
        context =
                new PoreloContext(
                        command -> {
                            if (command.arguments().contains("fetch")) {
                                fetches.incrementAndGet();
                                if (blockedFetch != null) return blockedFetch.run(command);
                                if (unreachable)
                                    return new CommandResult(command, 1, "", unreachableSays);
                            }
                            return machine.runner.run(
                                    new Command(
                                            command.arguments().stream()
                                                    .map(arg -> repositories.getOrDefault(arg, arg))
                                                    .toList(),
                                            command.workingDirectory(),
                                            command.environment(),
                                            command.input(),
                                            command.inheritsEnvironment(),
                                            command.binaryInput()));
                        },
                        machine.paths,
                        args -> 0,
                        machine.environment());
        store =
                GitTreeStore.at(
                        context.runner(),
                        TestOrganization.ADDRESS,
                        "acme",
                        "forge.example.org",
                        FetchAccess.NONE,
                        machine.paths.xdg().state().resolve("organization"));
    }

    @AfterEach
    void close() throws Exception {
        machine.close();
    }

    private OrgAccess.Opened opened(PoreloContext ignored, String address) {
        if (blockOpening) {
            opening.countDown();
            try {
                if (!resumeOpening.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("opening was not released");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
        return new OrgAccess.Opened(store, machine.forge);
    }

    private IdentityOrganization.Opened identity(PoreloContext ignored, String address) {
        return new IdentityOrganization.Opened(
                store, machine.forge, organization.forgeAddress, signing -> organization.writes());
    }

    private int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine line = PoreloCli.commandLine(context);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        wire(line);
        return line.execute(args);
    }

    private void wire(CommandLine line) {
        if (line.getCommand() instanceof OrgReading reading) {
            reading.opener = this::opened;
            if (captureRefresh) reading.refresher = (machine, kept) -> refreshRequests++;
            if (failScheduling)
                reading.refresher =
                        (machine, kept) -> {
                            throw new IllegalStateException("fixture scheduling failed");
                        };
        }
        if (line.getCommand() instanceof OrgRefreshCommand refresh && refreshLimit != null)
            refresh.limit = refreshLimit;
        line.getSubcommands().values().forEach(this::wire);
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    private String keep() throws Exception {
        final String revision = store.read(machine.forge).revision();
        fetches.set(0);
        machine.forge.asked.clear();
        return revision;
    }

    // A slow or unreachable forge must not hold a page, and an old role must never look current.
    @Test
    void anOrganizationDisplayAnswersTheKeptRevisionWithoutFetchingAndSaysItsAge()
            throws Exception {
        final String kept = keep();
        organization.git("commit", "-q", "--allow-empty", "-m", "later");
        assertThat(run("org", "--json")).as("the kept page answers").isZero();
        assertThat(answer().get("revision")).as("the answer precedes the refresh").isEqualTo(kept);
        assertThat(fetches.get()).as("display does no fetch").isZero();
        assertThat(machine.forge.asked).as("display asks no forge").isEmpty();
        assertThat(answer().get("stale")).as("kept roles are descriptive").isEqualTo(true);
        assertThat(answer().get("checked_at"))
                .as("the last successful check is identified")
                .isInstanceOf(String.class);
        assertThat(((Number) answer().get("age_seconds")).longValue())
                .as("age is nonnegative")
                .isGreaterThanOrEqualTo(0);
        assertThat(Instant.parse((String) answer().get("checked_at")))
                .as("check time is valid")
                .isBeforeOrEqualTo(Instant.now());
    }

    // A project's page must not fetch or validate new content using the kept admins' keys.
    @Test
    void aProjectDisplayAnswersKeptMembershipWithoutFetching() throws Exception {
        final String kept = keep();
        assertThat(run("project", "show", "demo", "--json"))
                .as("the kept project answers")
                .isZero();
        assertThat(answer().get("revision"))
                .as("the shown membership is the kept one")
                .isEqualTo(kept);
        assertThat(answer().get("stale")).as("kept membership is identified").isEqualTo(true);
        assertThat(fetches.get()).as("display fetches nothing").isZero();
        assertThat(answer().get("age_seconds"))
                .as("project age crosses the CLI boundary")
                .isInstanceOf(Number.class);
    }

    // A withdrawn role must replace the stale page once an explicit refresh completes.
    @Test
    void aRefreshAnswersTheNewTipAfterTheKeptDisplay() throws Exception {
        final String kept = keep();
        organization
                .writes()
                .removeProject(organization.change("michael", "michael", "remove"), "demo");
        final String tip = organization.git("rev-parse", "HEAD");
        assertThat(run("org", "--json")).as("the first answer does not wait").isZero();
        assertThat(answer().get("revision")).as("the initial page is kept").isEqualTo(kept);
        assertThat(run("org", "--json", "--refresh")).as("a replacement can be requested").isZero();
        assertThat(answer().get("revision"))
                .as("the replacement sees the withdrawal")
                .isEqualTo(tip);
        assertThat(answer().get("stale")).as("the replacement was checked").isEqualTo(false);
        assertThat(answer().get("projects")).as("removed projects disappear").isEqualTo(List.of());
    }

    // Repeated page opens must not enqueue repeated fetches after the same display answer.
    @Test
    void backgroundRefreshesOfTheSameDisplayCoalesce() throws Exception {
        keep();
        final Map<?, ?> record =
                (Map<?, ?>)
                        Json.parse(
                                Files.readString(
                                        machine.paths
                                                .xdg()
                                                .state()
                                                .resolve("organization/trust-record.json")));
        final String after =
                record.get("checked_at") == null ? "" : String.valueOf(record.get("checked_at"));
        assertThat(
                        run(
                                "org",
                                "refresh-view",
                                "--repository",
                                TestOrganization.ADDRESS,
                                "--after",
                                after,
                                "--json"))
                .as("the refresh helper runs")
                .isZero();
        assertThat(fetches.get()).as("the first refresh checks even an unchanged tip").isEqualTo(1);
        assertThat(
                        run(
                                "org",
                                "refresh-view",
                                "--repository",
                                TestOrganization.ADDRESS,
                                "--after",
                                after,
                                "--json"))
                .as("a duplicate refresh completes")
                .isZero();
        assertThat(fetches.get()).as("a duplicate joins the completed generation").isEqualTo(1);
    }

    // A refresh from an old settings file must not replace the new organization's displayed state.
    @Test
    void aRefreshForAnotherOrganizationIsDiscarded() throws Exception {
        keep();
        assertThat(
                        run(
                                "org",
                                "refresh-view",
                                "--repository",
                                "https://forge.example.org/other/organization.git",
                                "--after",
                                "",
                                "--json"))
                .as("an obsolete request is ignored")
                .isZero();
        assertThat(fetches.get()).as("no request is sent for the wrong organization").isZero();
    }

    // A page process must finish while its refresh is still running and holds no output pipe open.
    @Test
    void aDisplayStartsAnIndependentRefreshAfterAnswering() throws Exception {
        keep();
        final Path helper = dir.resolve("refresh-helper");
        final Path started = dir.resolve("refresh-started");
        final Path release = dir.resolve("refresh-release");
        Files.writeString(
                helper,
                """
                #!/bin/sh
                root=$(dirname "$0")
                printf '%s' "$$" > "$root/refresh-started"
                printf 'helper output must not join the display\\n'
                left=100
                while [ ! -f "$root/refresh-release" ] && [ "$left" -gt 0 ]; do
                  sleep 0.05
                  left=$((left - 1))
                done
                """);
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
        final String before = System.getProperty(PoreloBinary.NAMED);
        try {
            System.setProperty(PoreloBinary.NAMED, helper.toString());
            assertThat(run("org", "--json"))
                    .as("the display does not await refresh completion")
                    .isZero();
            assertThat(answer().get("stale"))
                    .as("the one output object is still the kept answer")
                    .isEqualTo(true);
            final long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(started) && System.nanoTime() < until) Thread.sleep(10);
            assertThat(started).as("a separate process was scheduled").exists();
            final ProcessHandle child =
                    ProcessHandle.of(Long.parseLong(Files.readString(started))).orElseThrow();
            assertThat(child.isAlive())
                    .as("the parent answered before its child finished")
                    .isTrue();
            Files.createFile(release);
            child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            if (!Files.exists(release)) Files.createFile(release);
            if (before == null) System.clearProperty(PoreloBinary.NAMED);
            else System.setProperty(PoreloBinary.NAMED, before);
        }
    }

    // A refresh that cannot judge a new card must not make old roles look freshly checked.
    @Test
    void anIncompleteRefreshStaysStaleAndKeepsThePreviousCheckTime() throws Exception {
        keep();
        final Path record = machine.paths.xdg().state().resolve("organization/trust-record.json");
        final Object before = ((Map<?, ?>) Json.parse(Files.readString(record))).get("checked_at");
        final Path waiting = Files.createDirectories(organization.repo.resolve("projects/waiting"));
        Files.writeString(waiting.resolve("card"), "name: waiting\nadmin: alice\n");
        organization.git("add", "projects/waiting/card");
        organization.git(
                "-c",
                "user.signingkey=" + machine.keyFiles.get("michael"),
                "commit",
                "-q",
                "-S",
                "-m",
                "file project");
        organization.filed("alice");
        machine.forge.keys.remove("alice");
        Files.writeString(
                waiting.resolve("members.yml"), "members:\n  - login: alice\n    role: admin\n");
        organization.git("add", "projects/waiting/members.yml");
        organization.git(
                "-c",
                "user.signingkey=" + machine.keyFiles.get("alice"),
                "commit",
                "-q",
                "-S",
                "-m",
                "file members");
        assertThat(run("org", "--refresh", "--json"))
                .as("the partial result can still be displayed")
                .isZero();
        assertThat(answer().get("stale"))
                .as("a partial walk cannot clear the stale marker")
                .isEqualTo(true);
        assertThat(((Map<?, ?>) Json.parse(Files.readString(record))).get("checked_at"))
                .as("an incomplete check does not erase the last successful one")
                .isEqualTo(before);
        final String said =
                "waits at "
                        + organization.git("rev-parse", "HEAD").strip().substring(0, 12)
                        + ": the identity source has not vouched for the signer's card";
        final Map<?, ?> why = (Map<?, ?>) answer().get("stale_why");
        assertThat(why.get("cause")).isEqualTo("incomplete");
        assertThat(why.get("said"))
                .as("the person is told what the read waits for")
                .isEqualTo(said);
        assertThat(String.valueOf(answer().get("warnings"))).contains(said);

        out.getBuffer().setLength(0);
        assertThat(run("org", "--refresh")).isZero();
        assertThat(out.toString()).contains("Stale because: ").contains(said);
    }

    // A cached absence must not permanently hide a newly created project from its member.
    @Test
    void aProjectAbsentFromTheKeptStateStillSchedulesARefresh() throws Exception {
        keep();
        captureRefresh = true;
        organization
                .writes()
                .addProject(organization.change("michael", "michael", "later"), "later", "michael");
        assertThat(run("project", "show", "later", "--json"))
                .as("the cached absence is a refusal")
                .isEqualTo(66);
        assertThat(refreshRequests).as("a refusal must also refresh the display").isEqualTo(1);
        assertThat(answer().get("stale")).as("the absence was only cached").isEqualTo(true);
        assertThat(run("project", "show", "later", "--refresh", "--json"))
                .as("the newly created project becomes visible")
                .isZero();
    }

    // A newly granted member must not remain locked out of the page by a cached refusal.
    @Test
    void aMembershipAbsentFromTheKeptStateStillSchedulesARefresh() throws Exception {
        organization.filed("alice");
        Files.writeString(
                machine.paths.xdg().config().resolve("identity/card"), organization.card("alice"));
        keep();
        captureRefresh = true;
        organization
                .writes()
                .addProjectMember(
                        organization.change("michael", "michael", "member"),
                        "demo",
                        "alice",
                        "developer");
        assertThat(run("project", "show", "demo", "--json"))
                .as("the kept membership is missing")
                .isEqualTo(65);
        assertThat(refreshRequests).as("the refusal still requests fresh membership").isEqualTo(1);
        assertThat(answer().get("stale"))
                .as("the refusal identifies its old state")
                .isEqualTo(true);
        assertThat(run("project", "show", "demo", "--refresh", "--json"))
                .as("the grant becomes visible")
                .isZero();
    }

    // A post-answer refresh failure must not append a second JSON object and break the page.
    @Test
    void aSchedulerFailureDoesNotCorruptTheKeptAnswer() throws Exception {
        keep();
        failScheduling = true;
        assertThat(run("org", "--json")).as("the kept answer already succeeded").isZero();
        assertThat(answer().get("stale")).as("one readable kept answer remains").isEqualTo(true);
    }

    // An obsolete helper must never erase the replacement organization's rollback protection.
    @Test
    void aRefreshSupersededWhileOpeningCannotReplaceTheNewOrganizationsRecord() throws Exception {
        keep();
        blockOpening = true;
        try (var callers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            final var refreshing =
                    callers.submit(
                            () ->
                                    run(
                                            "org",
                                            "refresh-view",
                                            "--repository",
                                            TestOrganization.ADDRESS,
                                            "--after",
                                            "",
                                            "--json"));
            try {
                assertThat(opening.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the old helper passed its settings check")
                        .isTrue();
                final String replacement = "https://forge.example.org/other/organization.git";
                repositories.put(replacement, organization.repo.toString());
                Files.writeString(
                        machine.paths.xdg().config().resolve("organization.yml"),
                        "adapter: git\nsettings:\n  repository: " + replacement + "\n");
                final GitTreeStore newer =
                        GitTreeStore.at(
                                context.runner(),
                                replacement,
                                "acme",
                                "forge.example.org",
                                FetchAccess.NONE,
                                machine.paths.xdg().state().resolve("organization"));
                final String trusted = newer.read(machine.forge).revision();
                resumeOpening.countDown();
                assertThat(refreshing.get(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the obsolete helper completes")
                        .isZero();
                assertThat(newer.last()).as("the new organization's anchor survives").isPresent();
                assertThat(newer.last().orElseThrow().revision())
                        .as("the newer anchor is unchanged")
                        .isEqualTo(trusted);
            } finally {
                resumeOpening.countDown();
            }
        }
    }

    // A first display racing setup must not erase the newly selected organization's anchor.
    @Test
    void aFirstDisplaySupersededWhileOpeningCannotReplaceTheNewOrganizationsRecord()
            throws Exception {
        blockOpening = true;
        try (var callers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            final var showing = callers.submit(() -> run("org", "--json"));
            try {
                assertThat(opening.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the first display opened its original organization")
                        .isTrue();
                final String replacement = "https://forge.example.org/other/organization.git";
                repositories.put(replacement, organization.repo.toString());
                Files.writeString(
                        machine.paths.xdg().config().resolve("organization.yml"),
                        "adapter: git\nsettings:\n  repository: " + replacement + "\n");
                final GitTreeStore newer =
                        GitTreeStore.at(
                                context.runner(),
                                replacement,
                                "acme",
                                "forge.example.org",
                                FetchAccess.NONE,
                                machine.paths.xdg().state().resolve("organization"));
                final String trusted = newer.read(machine.forge).revision();
                resumeOpening.countDown();
                assertThat(showing.get(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the obsolete first read is refused")
                        .isNotZero();
                assertThat(newer.last())
                        .as("the replacement anchor survives a cache miss")
                        .isPresent();
                assertThat(newer.last().orElseThrow().revision())
                        .as("the replacement anchor is unchanged")
                        .isEqualTo(trusted);
            } finally {
                resumeOpening.countDown();
            }
        }
    }

    // A timed-out helper must finish canceling its own subprocesses before the CLI exits.
    @Test
    void aRefreshDeadlineWaitsForChildCleanup() throws Exception {
        keep();
        final String after =
                String.valueOf(
                        ((Map<?, ?>)
                                        Json.parse(
                                                Files.readString(
                                                        machine.paths
                                                                .xdg()
                                                                .state()
                                                                .resolve(
                                                                        "organization/trust-record.json"))))
                                .get("checked_at"));
        refreshLimit = java.time.Duration.ofMillis(250);
        final java.util.concurrent.atomic.AtomicReference<Process> child =
                new java.util.concurrent.atomic.AtomicReference<>();
        final var cleaned = new java.util.concurrent.CountDownLatch(1);
        blockedFetch =
                command -> {
                    try {
                        final Process process =
                                new ProcessBuilder("sh", "-c", "sleep 30 & wait").start();
                        child.set(process);
                        try {
                            process.waitFor();
                        } catch (InterruptedException ex) {
                            // Model a resource owner that needs a short interval to reap its
                            // descendants.
                            final long until =
                                    System.nanoTime()
                                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                                    200);
                            while (System.nanoTime() < until) {
                                java.util.concurrent.locks.LockSupport.parkNanos(
                                        until - System.nanoTime());
                                Thread.interrupted();
                            }
                        } finally {
                            process.descendants().forEach(handle -> handle.destroyForcibly());
                            process.destroyForcibly();
                            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                            cleaned.countDown();
                        }
                        return new CommandResult(command, 1, "", "fixture canceled");
                    } catch (java.io.IOException | InterruptedException ex) {
                        throw new AssertionError(ex);
                    }
                };
        try {
            assertThat(
                            run(
                                    "org",
                                    "refresh-view",
                                    "--repository",
                                    TestOrganization.ADDRESS,
                                    "--after",
                                    after,
                                    "--json"))
                    .as("the deadline refuses the refresh")
                    .isEqualTo(69);
            assertThat(cleaned.getCount())
                    .as("resource cleanup completes before the helper returns")
                    .isZero();
            assertThat(child.get().isAlive()).as("the child has exited").isFalse();
        } finally {
            final Process process = child.get();
            if (process != null) {
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
            }
            cleaned.await(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // Without trusted local knowledge, a display must prove what it answers.
    @Test
    void noKeptStateReadsFresh() throws Exception {
        assertThat(run("org", "--json")).as("a first read answers").isZero();
        assertThat(fetches.get()).as("a first read fetches").isEqualTo(1);
        assertThat(answer().get("stale")).as("the first answer was checked").isEqualTo(false);
    }

    // A person shown only "could not be read" cannot tell a refused key from a forge out of reach.
    @Test
    void aRefreshThatCannotReachSaysWhyInGitsOwnWords() throws Exception {
        keep();
        unreachable = true;
        unreachableSays =
                "remote: token ghx_secret_value is not valid\n"
                        + "git@forge.example.org: Permission denied (publickey).\n"
                        + "fatal: Could not read from remote repository.\n";
        assertThat(run("org", "--json", "--refresh")).as("the last state answers").isZero();
        assertThat(answer().get("stale")).as("it is the last state").isEqualTo(true);
        final Map<?, ?> why = (Map<?, ?>) answer().get("stale_why");
        assertThat(why).as("why it is stale is said").isNotNull();
        assertThat(why.get("cause")).as("the refused key is named").isEqualTo("key-refused");
        assertThat((String) why.get("said"))
                .as("git's own words")
                .contains("Permission denied (publickey)")
                .doesNotContain("ghx_secret_value");
        assertThat(out.toString() + err)
                .as("nothing the forge said is shown")
                .doesNotContain("ghx_secret_value");
    }

    // A page that showed the kept state must say why the check after it failed, not only stale.
    @Test
    void aKeptDisplayAfterAFailedRefreshSaysItsCause() throws Exception {
        keep();
        final Map<?, ?> record =
                (Map<?, ?>)
                        Json.parse(
                                Files.readString(
                                        machine.paths
                                                .xdg()
                                                .state()
                                                .resolve("organization/trust-record.json")));
        final String after =
                record.get("checked_at") == null ? "" : String.valueOf(record.get("checked_at"));
        unreachable = true;
        unreachableSays =
                "ssh: Could not resolve hostname forge.example.org: Temporary failure in name"
                        + " resolution\nfatal: Could not read from remote repository.\n";
        assertThat(
                        run(
                                "org",
                                "refresh-view",
                                "--repository",
                                TestOrganization.ADDRESS,
                                "--after",
                                after,
                                "--json"))
                .as("the refresh fails")
                .isNotZero();
        captureRefresh = true;
        assertThat(run("org", "--json")).as("the kept page answers").isZero();
        final Map<?, ?> why = (Map<?, ?>) answer().get("stale_why");
        assertThat(why).as("the failed refresh is said").isNotNull();
        assertThat(why.get("cause")).as("its cause").isEqualTo("no-network");
        assertThat((String) why.get("said"))
                .as("git's own words")
                .contains("Could not resolve hostname");
        assertThat(why.get("at")).as("when it was tried").isInstanceOf(String.class);
        assertThat(run("org")).as("the text answers").isZero();
        assertThat(out.toString())
                .as("a person reads the cause")
                .contains("Could not resolve hostname");
    }

    // A kept page with no failed check must not read as a failure, nor as checked now.
    @Test
    void aKeptDisplaySaysItIsTheKeptState() throws Exception {
        keep();
        captureRefresh = true;
        assertThat(run("org", "--json")).as("the kept page answers").isZero();
        final Map<?, ?> why = (Map<?, ?>) answer().get("stale_why");
        assertThat(why).as("why it is stale is said").isNotNull();
        assertThat(why.get("cause")).as("only kept").isEqualTo("kept");
        assertThat(why.get("said")).as("no words of git's").isNull();
        assertThat(run("org", "--json", "--refresh")).as("a check answers").isZero();
        assertThat(answer().get("stale_why")).as("a checked state is not stale").isNull();
    }

    // "Its repositories could not be read" with no reason leaves a person nothing to do.
    @Test
    void aStaleProjectSaysWhyItsRepositoriesCannotBeRead() throws Exception {
        final Path demo = Files.createDirectories(organization.repo.resolve("projects/demo"));
        Files.writeString(
                demo.resolve("repository.yml"), "address: git@forge.example.org:acme/demo.git\n");
        organization.git("add", "projects/demo/repository.yml");
        organization.git(
                "-c",
                "user.signingkey=" + machine.keyFiles.get("michael"),
                "commit",
                "-q",
                "-S",
                "-m",
                "open demo");
        keep();
        unreachable = true;
        unreachableSays = "Host key verification failed.\nfatal: Could not read from remote.\n";
        assertThat(run("project", "show", "demo", "--json", "--refresh"))
                .as("the last state answers: %s", err)
                .isZero();
        assertThat(answer().get("opened")).as("the project is opened").isEqualTo(true);
        final Map<?, ?> repositories = (Map<?, ?>) answer().get("repositories");
        assertThat(repositories.get("state")).as("unread").isEqualTo("cannot-say");
        assertThat((String) repositories.get("why"))
                .as("why, and how to read them")
                .contains("host-key")
                .contains("--refresh");
        assertThat(((Map<?, ?>) answer().get("stale_why")).get("cause"))
                .as("the project names the cause too")
                .isEqualTo("host-key");
    }

    // A damaged or publicly writable record must not lend unproven permissions to a page.
    @Test
    void aKeptStateThatFailsItsChecksIsNeverAnswered() throws Exception {
        keep();
        final Path record = machine.paths.xdg().state().resolve("organization/trust-record.json");
        Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("rw-rw-rw-"));
        unreachable = true;
        assertThat(run("org", "--json")).as("unsafe state is refused").isNotZero();
        assertThat(fetches.get()).as("a discarded record needs a fresh check").isEqualTo(1);
        assertThat(answer().get("revision")).as("no revision is vouched for").isNull();
    }

    // A write must see a withdrawal even when a display has a usable kept answer.
    @Test
    void organizationWritingStillFetches() throws Exception {
        keep();
        unreachable = true;
        assertThatThrownBy(() -> OrgWriting.current(context, this::opened))
                .as("no stale write base")
                .isInstanceOf(org.fuin.porelo.organization.OrganizationQuestions.Refused.class);
        assertThat(fetches.get()).as("a write checks now").isEqualTo(1);
    }

    // A cached key must not let an identity change avoid the current organization's checks.
    @Test
    void identityChangesStillFetch() throws Exception {
        keep();
        unreachable = true;
        assertThatThrownBy(() -> IdentityOrganization.required(context, this::identity))
                .as("no stale identity authority")
                .isInstanceOf(IdentityChange.Refused.class);
        assertThat(fetches.get()).as("identity change checks now").isEqualTo(1);
    }

    // A first-card decision must not treat a known organization as an empty one while offline.
    @Test
    void firstCardDecisionsStillFetch() throws Exception {
        keep();
        unreachable = true;
        assertThatThrownBy(() -> IdentityOrganization.requiredUnlessEmpty(context, this::identity))
                .as("no stale first-card decision")
                .isInstanceOf(IdentityChange.Refused.class);
        assertThat(fetches.get()).as("first-card checks now").isEqualTo(1);
    }

    // A start must not run with an admin or membership withdrawn since the last page was read.
    @Test
    void taskStartsStillFetch() throws Exception {
        keep();
        unreachable = true;
        final ProjectTaking taking =
                new ProjectTaking(
                        context,
                        this::identity,
                        FetchAccess.NONE,
                        OrganizationProjects.of(context));
        assertThatThrownBy(() -> taking.taken("demo"))
                .as("no start from kept membership")
                .isInstanceOf(ProjectTaking.Refused.class);
        assertThat(fetches.get()).as("a start checks now").isEqualTo(1);
    }

    // A host must not continue granting access after an organization withdrawal it has not read.
    @Test
    void hostChecksStillFetch() throws Exception {
        keep();
        unreachable = true;
        assertThatThrownBy(() -> new GitTreeOffline(store, machine.forge).check("build", "pin", ""))
                .as("no host grant from kept state")
                .isInstanceOf(GitTreeStore.Unreachable.class);
        assertThat(fetches.get()).as("a host checks now").isEqualTo(1);
    }

    private void attempt(
            Object target, Class<?> owner, String name, Class<?>[] types, Object... arguments)
            throws Exception {
        final var method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            method.invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            if (!(ex.getCause()
                    instanceof org.fuin.porelo.organization.OrganizationQuestions.Refused))
                throw ex;
        }
    }

    // A replacement organization must be checked before setup makes it the machine's authority.
    @Test
    void setupStillFetches() throws Exception {
        keep();
        unreachable = true;
        final OrgSetup setup = new OrgSetup();
        setup.opener = this::opened;
        attempt(
                setup,
                OrgSetup.class,
                "checked",
                new Class<?>[] {PoreloContext.class, String.class},
                context,
                TestOrganization.ADDRESS);
        assertThat(fetches.get()).as("setup checks now").isEqualTo(1);
    }

    // New repositories must not be created with an admin role that was only shown from cache.
    @Test
    void projectRepositoryCreationStillFetches() throws Exception {
        keep();
        unreachable = true;
        final var change = organization.change("michael", "michael", "create");
        final var files =
                new org.fuin.porelo.organization.gittree.ProjectFile(
                        command -> {
                            throw new AssertionError("stale authority reached project creation");
                        },
                        FetchAccess.NONE,
                        org.fuin.porelo.organization.gittree.CommitSigning.agent(""),
                        dir.resolve("project-memory"));
        final var session =
                new OrgWriting.Session(
                        organization.writes(), change.acting(), machine.keys.get("michael"), files);
        final Throwable refused =
                catchThrowable(
                        () ->
                                ProjectCreateRepositoryCommand.create(
                                        context,
                                        this::opened,
                                        session,
                                        change,
                                        "demo",
                                        null,
                                        (machine, origin, name) -> {
                                            throw new AssertionError("no forge creation");
                                        },
                                        java.util.function.UnaryOperator.identity()));
        assertThat(fetches.get()).as("repository creation checks now").isEqualTo(1);
        assertThat(refused)
                .as("no repository from stale authority")
                .isInstanceOf(org.fuin.porelo.organization.OrganizationQuestions.Refused.class);
    }

    // Editing a project file must not use signing keys that its current admins withdrew.
    @Test
    void projectFileChangesStillFetch() throws Exception {
        keep();
        unreachable = true;
        attempt(
                null,
                ProjectRepositoryCommand.class,
                "place",
                new Class<?>[] {
                    PoreloContext.class, OrgAccess.Opener.class, String.class, String.class
                },
                context,
                (OrgAccess.Opener) this::opened,
                "demo",
                "michael");
        assertThat(fetches.get()).as("project file changes check now").isEqualTo(1);
    }

    // Changing an address must check whether it is currently filed before making the change.
    @Test
    void projectAddressChangesStillFetch() throws Exception {
        keep();
        unreachable = true;
        attempt(
                null,
                ProjectAddressCommand.class,
                "filedNow",
                new Class<?>[] {PoreloContext.class, String.class},
                context,
                "demo");
        assertThat(fetches.get()).as("address changes check now").isEqualTo(1);
    }

    // A host credential must never go to a host card read only from the kept state.
    @Test
    void hostCredentialRefreshStillFetches() throws Exception {
        keep();
        unreachable = true;
        attempt(
                null,
                OrgHostRefreshCommand.class,
                "hosts",
                new Class<?>[] {PoreloContext.class},
                context);
        assertThat(fetches.get()).as("host credential refresh checks now").isEqualTo(1);
    }

    // A host change must not assemble its replacement card without first asking the organization.
    @Test
    void hostCardChangesStillFetch() throws Exception {
        keep();
        unreachable = true;
        attempt(
                null,
                OrgHostCommand.class,
                "heldCard",
                new Class<?>[] {PoreloContext.class, String.class},
                context,
                "build");
        assertThat(fetches.get()).as("host card changes check now").isEqualTo(1);
    }

    // A checkout signing decision must walk new history rather than reusing its old signing keys.
    @Test
    void checkoutSigningStillWalksTheCurrentCheckout() throws Exception {
        keep();
        organization.filed("alice");
        assertThat(store.readCheckout(organization.repo, machine.forge).getPersons())
                .as("the checked-out history is judged anew")
                .anyMatch(person -> person.login().equals("alice"));
    }

    // Optional identity checks must not treat an existing organization as merely cached authority.
    @Test
    void optionalIdentityChangesStillFetch() throws Exception {
        keep();
        unreachable = true;
        assertThatThrownBy(() -> IdentityOrganization.requiredIfAny(context, this::identity))
                .as("no stale optional identity authority")
                .isInstanceOf(IdentityChange.Refused.class);
        assertThat(fetches.get()).as("optional identity checks now").isEqualTo(1);
    }

    // Identity inspection also feeds revocation and starts, whose callers must see that it is
    // stale.
    @Test
    void identityInspectionStillFetchesBeforeFallingBack() throws Exception {
        keep();
        unreachable = true;
        assertThat(IdentityOrganization.read(context, this::identity).stale())
                .as("the fallback has no fresh authority")
                .isTrue();
        assertThat(fetches.get()).as("identity inspection checks now").isEqualTo(1);
    }
}
