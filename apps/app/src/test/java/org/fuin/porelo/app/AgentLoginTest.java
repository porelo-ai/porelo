package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.LoginFlow;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for logging in with an agent.
 *
 * <p>What can be asserted without a real agent is the shape of the refusals and the shape of the
 * image this builds - and the second matters more than it looks, because a login container that
 * came out looking like a task container would be given a task's ruleset and a task's broker, and
 * an agent cannot log in through a broker that swaps in the credential it is trying to obtain.
 */
class AgentLoginTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    @Test
    void sweepsALoginContainerAnEarlierRunLeftBehind(@TempDir Path dir) {

        // Reported from a machine carrying one thirteen hours old, outliving an interrupted login.
        // A login container is never reused - the name carries the millisecond it was made - so one
        // still there is litter, and a teardown cannot cover a kill.
        runner.answering("ps", "porelo-login-1788886971400\nporelo-uc-shell-1\n");

        // Swept by a login that passed its refusals, before anything is built.
        AgentLogin.login(
                context(dir),
                definition(dir, "paste-code"),
                false,
                false,
                false,
                new PrintWriter(out, true),
                directory -> false,
                AgentLoginTest::anOpener,
                (command, directory, opener) ->
                        new AgentLogin.Result(
                                AgentLogin.Outcome.NOTHING_TO_COLLECT, "", "", 0, ""));

        assertThat(runner.lines())
                .anyMatch(
                        line ->
                                line.startsWith("podman rm")
                                        && line.contains("porelo-login-1788886971400"));
        // And nothing else: a task is not litter, whatever state it is in.
        assertThat(runner.lines()).noneMatch(line -> line.contains("porelo-uc-shell-1"));
    }

    /** Holds an agent's sign-in lock on a thread of its own until released, as a running login. */
    private static final class Held implements AutoCloseable {

        private final java.util.concurrent.CountDownLatch taken =
                new java.util.concurrent.CountDownLatch(1);

        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

        private final Thread thread;

        Held(final Path lock) throws InterruptedException {
            thread =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            FileLocks.holding(
                                                    lock,
                                                    () -> {
                                                        taken.countDown();
                                                        try {
                                                            release.await();
                                                        } catch (InterruptedException ex) {
                                                            Thread.currentThread().interrupt();
                                                        }
                                                        return "";
                                                    });
                                        } catch (java.io.IOException ex) {
                                            throw new IllegalStateException(ex);
                                        }
                                    });
            assertThat(taken.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the other sign-in holds its lock")
                    .isTrue();
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            thread.join();
        }
    }

    @Test
    void aLoginOfAnotherAgentSparesTheContainerOfASignInThatRuns(@TempDir Path dir)
            throws Exception {

        // Every login container used to be swept, so a second person's sign-in removed the
        // first one's live container half way through their login.
        final PoreloContext context = context(dir);
        runner.answering(
                "ps",
                "porelo-login-1000000000001\tworker\n"
                        + "porelo-login-1000000000002\t\n"
                        + "porelo-login-1000000000003\texample\n"
                        + "porelo-login-1000000000004\tgone\n");

        try (Held worker = new Held(AgentLogin.lockFile(context, "worker"))) {
            AgentLogin.login(
                    context,
                    definition(dir, "paste-code"),
                    false,
                    false,
                    false,
                    new PrintWriter(out, true),
                    directory -> false,
                    AgentLoginTest::anOpener,
                    (command, directory, opener) ->
                            new AgentLogin.Result(
                                    AgentLogin.Outcome.NOTHING_TO_COLLECT, "", "", 0, ""));
        }

        assertThat(runner.lines())
                .as("the container of a sign-in whose lock is held is that sign-in's, live")
                .noneMatch(
                        line ->
                                line.startsWith("podman rm")
                                        && line.contains("porelo-login-1000000000001"));
        for (final String litter :
                List.of(
                        "porelo-login-1000000000002",
                        "porelo-login-1000000000003",
                        "porelo-login-1000000000004")) {
            assertThat(runner.lines())
                    .as(
                            "one with no agent, of this login's own agent, or whose sign-in ended"
                                    + " is litter: "
                                    + litter)
                    .anyMatch(line -> line.startsWith("podman rm") && line.contains(litter));
        }
    }

    @Test
    void aSecondLoginOfAnAgentWhoseSignInRunsSaysSoAndRunsNothing(@TempDir Path dir)
            throws Exception {

        // Queued or run beside the first, a second login is a second authorization of the same
        // person's account, and its sweep removed the first one's container.
        final PoreloContext context = context(dir);
        runner.answering("ps", "porelo-login-1000000000001\texample\n");

        final AgentLogin.Result result;
        try (Held first = new Held(AgentLogin.lockFile(context, "example"))) {
            result =
                    AgentLogin.login(
                            context,
                            definition(dir, "paste-code"),
                            false,
                            false,
                            false,
                            new PrintWriter(out, true),
                            directory -> false,
                            AgentLoginTest::anOpener,
                            (command, directory, opener) -> {
                                throw new AssertionError("a second sign-in must not launch");
                            });
            assertThat(AgentLogin.running(context, "example"))
                    .as("the running sign-in is found by its lock")
                    .isPresent();
        }

        assertThat(result.outcome())
                .as("it says that one runs, without waiting for it")
                .isEqualTo(AgentLogin.Outcome.RUNNING);
        assertThat(runner.lines())
                .as("nothing of the running sign-in is removed")
                .noneMatch(line -> line.startsWith("podman rm"));
        assertThat(AgentLogin.running(context, "example"))
                .as("once its lock is free, no sign-in runs")
                .isEmpty();
    }

    private static AgentLogin.Signing signing(
            final AgentDefinition definition, final org.fuin.porelo.agent.api.Credential made) {
        return new AgentLogin.Signing(
                definition.name(), definition, directory -> java.util.Optional.of(made));
    }

    private AgentLogin.Result signedIn(
            final PoreloContext context, final Path dir, final AgentLogin.Signing signing) {
        return AgentLogin.run(
                context,
                signing,
                List.of("example-cli", "auth", "login"),
                dir.toString(),
                org.fuin.porelo.vault.VaultFile.Opener.passphrase(PASSPHRASE.clone()),
                null,
                new PrintWriter(out, true));
    }

    @Test
    void aSignInIsStoredWithTheOpenerTakenBeforeItThoughTheVaultWasLockedMeanwhile(
            @TempDir Path dir) {

        // The vault was asked again once the login ended: a 'vault lock' while the person signed
        // in dropped the credential they had just made. Nothing here opens the vault on its own,
        // as after a lock.
        final PoreloContext context = context(dir);
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);
        assertThat(context.opener()).as("the vault is locked").isEmpty();

        final AgentLogin.Result result =
                signedIn(
                        context,
                        dir,
                        signing(
                                definition(dir, "paste-code"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "at-made-here")));

        assertThat(result.outcome())
                .as("stored: " + result.detail())
                .isEqualTo(AgentLogin.Outcome.STORED);
        assertThat(context.vault().read(PASSPHRASE).get("example").value())
                .as("what the login made is in the vault")
                .isEqualTo("at-made-here");
    }

    @Test
    void aSignInWhoseVaultWasLockedMeanwhileSaysSoPlainly(@TempDir Path dir) {

        // A host vault opened by a kept share asks for the share again at the store; locked
        // meanwhile, it no longer opens, and the sign-in ended in a general failure that read like
        // a broken login. Here the opener taken before stops opening, as such a share does.
        final PoreloContext context = context(dir);
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);

        final AgentLogin.Result result =
                AgentLogin.run(
                        context,
                        signing(
                                definition(dir, "paste-code"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "at-made-here")),
                        List.of("example-cli", "auth", "login"),
                        dir.toString(),
                        org.fuin.porelo.vault.VaultFile.Opener.passphrase(
                                "no longer opens".toCharArray()),
                        null,
                        new PrintWriter(out, true));

        assertThat(result.outcome())
                .as("said as the vault locked: " + result.detail())
                .isEqualTo(AgentLogin.Outcome.VAULT_LOCKED);
        assertThat(result.detail())
                .as("what to do, plainly")
                .contains("the vault was locked during the sign-in")
                .contains("sign in again");
    }

    @Test
    void aVaultThatBrokeDuringTheSignInIsNotSaidAsLocked(@TempDir Path dir) throws Exception {

        // A store that fails for another reason - the file gone, porelod not answering - is no
        // lock: told "unlock and sign in again", the person would unlock a vault that was open.
        final PoreloContext context = context(dir);
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);
        java.nio.file.Files.delete(context.paths().vault().vaultFile());

        final AgentLogin.Result result =
                signedIn(
                        context,
                        dir,
                        signing(
                                definition(dir, "paste-code"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "at-made-here")));

        assertThat(result.outcome())
                .as("a failure, said as what it is: " + result.detail())
                .isEqualTo(AgentLogin.Outcome.FAILED);
    }

    @Test
    void aSignInTheWatchStoredIsStoredThoughTheVaultIsLockedBeforeTheLoginEnds(@TempDir Path dir) {

        // The watch stores the moment the login writes its credential; the path after the run
        // stored it once more and, with a host vault locked in between, said "not stored" about a
        // credential that was in the vault. Here the device's way in is revoked while the login
        // still runs, after the watch stored: what opened the vault before no longer does, as
        // after a lock.
        final byte[] share = new byte[32];
        java.util.Arrays.fill(share, (byte) 7);
        final java.util.concurrent.atomic.AtomicReference<String> slot =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<PoreloContext> made =
                new java.util.concurrent.atomic.AtomicReference<>();
        final PoreloContext base = context(dir);
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        base.paths(),
                        command -> {
                            if (!command.contains("run")) {
                                // The login image's build, before the login runs.
                                return 0;
                            }
                            try {
                                // Longer than one look of the watch.
                                Thread.sleep(java.time.Duration.ofMillis(4500));
                            } catch (final InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            final String id = slot.getAndSet(null);
                            if (id != null) {
                                made.get()
                                        .vault()
                                        .revoke(
                                                org.fuin.porelo.vault.VaultFile.Opener.passphrase(
                                                        PASSPHRASE.clone()),
                                                id);
                            }
                            return 0;
                        },
                        base.environment(),
                        base.recognizesTasks(),
                        base.prompt(),
                        base.input(),
                        base.machineName(),
                        base.hostVault());
        made.set(context);
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);
        slot.set(
                context.vault()
                        .enroll(
                                org.fuin.porelo.vault.VaultFile.Opener.passphrase(
                                        PASSPHRASE.clone()),
                                share.clone(),
                                "device",
                                "USER_SCOPED")
                        .id());

        final AgentLogin.Result result =
                AgentLogin.run(
                        context,
                        signing(
                                definition(dir, "paste-code"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "at-made-here")),
                        List.of("example-cli", "auth", "login"),
                        dir.toString(),
                        org.fuin.porelo.vault.VaultFile.Opener.share(share.clone()),
                        null,
                        new PrintWriter(out, true));

        assertThat(result.outcome())
                .as("stored once is stored: " + result.detail())
                .isEqualTo(AgentLogin.Outcome.STORED);
        assertThat(context.vault().read(PASSPHRASE).get("example").value())
                .isEqualTo("at-made-here");
    }

    @Test
    void aSignInIsStoredUnderTheProviderItsExtractorNames(@TempDir Path dir) throws Exception {

        // Stored under the agent's default, a sign-in to another provider replaced that
        // provider's credential and left the one it was for without any.
        final PoreloContext context = context(dir);
        final Path providers =
                java.nio.file.Files.createDirectories(
                        context.paths().xdg().data().resolve("providers"));
        for (final String name : List.of("alpha", "beta")) {
            java.nio.file.Files.writeString(
                    providers.resolve(name + ".yaml"),
                    """
                    name: %s
                    upstream: https://%s.example.com
                    dialects: { preview: "" }
                    auth_header: { _default: Authorization }
                    auth_prefix: { _default: "Bearer " }
                    """
                            .formatted(name, name));
        }
        final java.util.Map<String, org.fuin.porelo.vault.VaultEntry> before =
                new java.util.LinkedHashMap<>();
        before.put("alpha", new org.fuin.porelo.vault.VaultEntry("alphas-own", "api-key"));
        context.vault().write(before, PASSPHRASE);
        final AgentDefinition brokered =
                AgentDefinitionReader.read(
                        new java.io.StringReader(
                                """
                                name: example
                                binary: example-cli
                                git_identity: { name: Example, email: noreply@example.com }
                                headless: { prompt_flag: "-p" }
                                config_dir: %s
                                login:
                                  arguments: [auth, login]
                                provider:
                                  dialect: preview
                                  default: alpha
                                  endpoint: url
                                  base_url_env: EXAMPLE_BASE_URL
                                """
                                        .formatted(dir)),
                        "test.yaml");

        final AgentLogin.Result result =
                signedIn(
                        context,
                        dir,
                        signing(
                                brokered,
                                org.fuin.porelo.agent.api.Credential.of("oauth", "at-for-beta")
                                        .forProvider("beta")));

        assertThat(result.name())
                .as("kept under the provider it is for: " + result.detail())
                .isEqualTo("beta");
        assertThat(context.vault().read(PASSPHRASE))
                .as("the sign-in under its provider, the default's credential untouched")
                .hasEntrySatisfying(
                        "beta", entry -> assertThat(entry.value()).isEqualTo("at-for-beta"))
                .hasEntrySatisfying(
                        "alpha", entry -> assertThat(entry.value()).isEqualTo("alphas-own"));
    }

    @Test
    void aSignInClosesTheOpenQuestionOfAuthorizingItsCredential(@TempDir Path dir) {

        // Left open, every interface kept asking the person to authorize what they had just
        // signed in to.
        final PoreloContext context = context(dir);
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);
        AuthorizationsNeeded.raise(context, "example", "", "", AuthorizationsNeeded.NEVER);

        signedIn(
                context,
                dir,
                signing(
                        definition(dir, "paste-code"),
                        org.fuin.porelo.agent.api.Credential.of("oauth", "at-made-here")));

        assertThat(AuthorizationsNeeded.open(context))
                .as("the question for the credential just stored is closed")
                .noneMatch(question -> "example".equals(question.get("credential")));
    }

    @Test
    void aDetachedSignInNobodyFinishesIsStoppedAtItsLimit(@TempDir Path dir) throws Exception {

        // Its login waits on a terminal nobody attached to; waited on without end, it held the
        // agent's lock and a container on the host's network for the daemon's life.
        final java.util.concurrent.CountDownLatch never =
                new java.util.concurrent.CountDownLatch(1);
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> {
                            if (command.contains("wait")) {
                                try {
                                    never.await();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return 0;
                        });
        final java.util.concurrent.atomic.AtomicReference<String> started =
                new java.util.concurrent.atomic.AtomicReference<>("");
        try {
            final AgentLogin.Result result =
                    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                            java.time.Duration.ofSeconds(60),
                            () ->
                                    AgentLogin.run(
                                            context,
                                            new AgentLogin.Signing(
                                                    "example",
                                                    definition(dir, "paste-code"),
                                                    directory -> java.util.Optional.empty()),
                                            List.of("example-cli", "auth", "login"),
                                            dir.toString(),
                                            org.fuin.porelo.vault.VaultFile.Opener.passphrase(
                                                    PASSPHRASE.clone()),
                                            new AgentLogin.Detached(
                                                    started::set, java.time.Duration.ofMillis(300)),
                                            new PrintWriter(out, true)));

            assertThat(result.outcome())
                    .as("it ends, storing nothing")
                    .isEqualTo(AgentLogin.Outcome.NOTHING_TO_COLLECT);
            assertThat(runner.lines())
                    .as("its container is stopped and removed")
                    .anyMatch(line -> line.startsWith("podman rm") && line.contains(started.get()));
        } finally {
            never.countDown();
        }
    }

    @Test
    void aLoginDetachedFromItsTerminalStoresNothingAndRemovesItsContainer(@TempDir Path dir) {

        // Measured on the test machine: an agent's device-code page ignored Ctrl-C, and nothing
        // in the terminal ended the login. Porelo's own detach key ends 'podman run' with 0 while
        // the container still runs - the credential the agent wrote is not kept, since the person
        // walked away from the login.
        runner.answering("{{.State.Pid}}", "4242\n");
        final java.util.concurrent.atomic.AtomicReference<List<String>> ran =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> {
                            if (command.contains("run")) {
                                ran.set(command);
                            }
                            return 0;
                        });
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);

        final AgentLogin.Result result =
                signedIn(
                        context,
                        dir,
                        signing(
                                definition(dir, "paste-code"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "at-made-here")));

        assertThat(result.outcome().name())
                .as("ended from its terminal: " + result.detail())
                .isEqualTo("CANCELLED");
        assertThat(context.vault().read(PASSPHRASE))
                .as("nothing is stored")
                .doesNotContainKey("example");
        final String container = ran.get().get(ran.get().indexOf("--name") + 1);
        assertThat(runner.lines())
                .as("its container is stopped and removed")
                .anyMatch(line -> line.startsWith("podman stop") && line.contains(container))
                .anyMatch(line -> line.startsWith("podman rm") && line.contains(container))
                .as("nothing is copied out of it")
                .noneMatch(line -> line.startsWith("podman cp"));
        assertThat(String.join(" ", ran.get()))
                .as("Porelo names its own way out, which no containers.conf changes")
                .contains("--detach-keys ctrl-],ctrl-]");
    }

    @Test
    void theTeardownDeletesTheLoginsCopiesAsWellAsItsContainer(@TempDir Path dir) throws Exception {

        // Run alone, as a SIGHUP or SIGTERM runs it: the finally that deleted the watch's copies,
        // the agent's credential among them, never runs then.
        final Path copies = java.nio.file.Files.createDirectories(dir.resolve("porelo-login-1"));
        java.nio.file.Files.createDirectories(copies.resolve("watch"));
        java.nio.file.Files.writeString(copies.resolve("watch/.credentials.json"), "{}");

        AgentLogin.teardown(
                        new org.fuin.porelo.runtime.Podman(runner),
                        "porelo-login-1000000000007",
                        copies)
                .run();

        assertThat(runner.lines())
                .anyMatch(
                        line ->
                                line.startsWith("podman rm")
                                        && line.contains("porelo-login-1000000000007"));
        assertThat(copies).as("the copies go with the container").doesNotExist();
    }

    @Test
    void aSweepDeletesTheCopiesAKilledLoginLeftAndSparesOnesAHeldLoginUses(@TempDir Path dir)
            throws Exception {

        // A SIGKILL runs no teardown at all; the next login sweeps what it left. One whose login
        // still runs - another agent's, holding the copies' own lock - is kept.
        final java.nio.file.attribute.FileTime old =
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minus(java.time.Duration.ofHours(2)));
        final Path left = java.nio.file.Files.createDirectories(dir.resolve("porelo-login-11"));
        java.nio.file.Files.writeString(left.resolve("watch.json"), "{}");
        java.nio.file.Files.setLastModifiedTime(left, old);
        final Path unrelated = java.nio.file.Files.createDirectories(dir.resolve("unrelated-1"));
        java.nio.file.Files.setLastModifiedTime(unrelated, old);
        final Path live = java.nio.file.Files.createDirectories(dir.resolve("porelo-login-22"));
        try (Held held = new Held(live.resolve(AgentLogin.HELD))) {
            java.nio.file.Files.setLastModifiedTime(live, old);

            AgentLogin.sweepLeftoverCopies(dir, new PrintWriter(out, true));

            assertThat(left).as("what a killed login left").doesNotExist();
            assertThat(live).as("what a running login uses").exists();
            assertThat(unrelated).as("what is not a login's").exists();
        }
    }

    @Test
    void aSignInDoesNotNameTheContainerOfOneThatWasKilled(@TempDir Path dir) throws Exception {

        // A killed sign-in leaves its container's name; read beside the next one's lock, a window
        // was sent to attach to a container that is gone.
        final PoreloContext context = context(dir);
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);
        java.nio.file.Files.createDirectories(
                AgentLogin.containerFile(context, "example").getParent());
        java.nio.file.Files.writeString(
                AgentLogin.containerFile(context, "example"), "porelo-login-1000000000005");
        final java.util.concurrent.atomic.AtomicReference<java.util.Optional<String>> seen =
                new java.util.concurrent.atomic.AtomicReference<>();

        AgentLogin.login(
                context,
                definition(dir, "paste-code"),
                false,
                false,
                false,
                new PrintWriter(out, true),
                directory -> false,
                AgentLoginTest::anOpener,
                (command, directory, opener) -> {
                    seen.set(AgentLogin.running(context, "example"));
                    return new AgentLogin.Result(
                            AgentLogin.Outcome.NOTHING_TO_COLLECT, "", "", 0, "");
                });

        assertThat(seen.get()).as("this sign-in runs, with no container yet").contains("");
    }

    @Test
    void aSignInWithoutAVaultRunsNothingThoughAPassphraseIsKept(@TempDir Path dir) {

        // The person signed in at the browser, and only then was there nowhere to store it.
        final AgentLogin.Result result =
                AgentLogin.login(
                        context(dir),
                        definition(dir, "paste-code"),
                        false,
                        false,
                        false,
                        new PrintWriter(out, true),
                        directory -> false,
                        AgentLoginTest::anOpener,
                        (command, directory, opener) -> {
                            throw new AssertionError("nothing may run without a vault to store in");
                        });

        assertThat(result.outcome())
                .as("refused before")
                .isEqualTo(AgentLogin.Outcome.VAULT_LOCKED);
    }

    @Test
    void aSignInWhoseKeptPassphraseNoLongerOpensTheVaultRunsNothing(@TempDir Path dir) {
        final PoreloContext context = context(dir);
        context.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);

        final AgentLogin.Result result =
                AgentLogin.login(
                        context,
                        definition(dir, "paste-code"),
                        false,
                        false,
                        false,
                        new PrintWriter(out, true),
                        directory -> false,
                        () ->
                                java.util.Optional.of(
                                        org.fuin.porelo.vault.VaultFile.Opener.passphrase(
                                                "a stale one".toCharArray())),
                        (command, directory, opener) -> {
                            throw new AssertionError("nothing may run that could not be stored");
                        });

        assertThat(result.outcome())
                .as("refused before")
                .isEqualTo(AgentLogin.Outcome.VAULT_LOCKED);
    }

    @Test
    void aMachineWithNoAgentSaysSoRatherThanFailing(@TempDir Path dir) {

        final AgentLogin.Result result =
                AgentLogin.login(context(dir), null, false, false, new PrintWriter(out, true));

        assertThat(result.outcome()).isEqualTo(AgentLogin.Outcome.NO_SUCH_AGENT);
        assertThat(result.detail()).isNotBlank();
        assertThat(runner.invocations()).as("nothing is swept for a login refused").isEmpty();
    }

    @Test
    void aLoginSectionWithNoArgumentsIsNeitherOfferedNorRun(@TempDir Path dir) {
        // With a config directory it would have started a container, let the person sign in, and
        // collected nothing, since the sign-in lies where no extractor reads. 'vault import'
        // offered
        // that login through signsInHere as well.
        final AgentDefinition definition =
                AgentDefinitionReader.read(
                        new java.io.StringReader(
                                """
                                name: example
                                binary: example-cli
                                git_identity:
                                  name: Example
                                  email: noreply@example.com
                                headless:
                                  prompt_flag: "-p"
                                config_dir: %s
                                login:
                                  flow: device-code
                                """
                                        .formatted(dir)),
                        "test");

        assertThat(definition.loginArguments()).isNull();
        assertThat(AgentLogin.signsInHere(context(dir), definition)).isFalse();
        assertThat(
                        AgentLogin.login(
                                        context(dir),
                                        definition,
                                        false,
                                        false,
                                        false,
                                        new PrintWriter(new StringWriter(), true),
                                        directory -> false,
                                        AgentLoginTest::anOpener,
                                        (command, directory, opener) -> {
                                            throw new AssertionError("nothing may be run");
                                        })
                                .outcome())
                .isEqualTo(AgentLogin.Outcome.UNSUPPORTED);
    }

    @Test
    void anAgentNotInstalledHasNoLoginToNameAsAWayOut(@TempDir Path dir) {
        assertThat(AgentLogin.signsInHere(context(dir), "absent")).isFalse();
    }

    @Test
    void theHintFollowsTheDeclaredFlow() {
        assertThat(String.join("\n", AgentLogin.hint(null, "box")))
                .as("an undeclared flow keeps the forward it always had")
                .contains("ssh -L <port>:localhost:<port> box");
        assertThat(String.join("\n", AgentLogin.hint(LoginFlow.PASTE_CODE, "box")))
                .contains("paste the returned code")
                .doesNotContain("ssh -L");
        assertThat(String.join("\n", AgentLogin.hint(LoginFlow.DEVICE_CODE, "box")))
                .contains("device code")
                .doesNotContain("ssh -L");
        assertThat(AgentLogin.hint(LoginFlow.LOOPBACK, "box")).isEmpty();
    }

    @Test
    void forceIsWhatDistinguishesADeliberateSecondLogin() {

        // The check itself needs a real agent to exercise - it asks the agent's own extractor
        // whether this machine already holds a credential - so what is pinned here is that the
        // decision has a switch at all, and that it is off by default. A second authorization may
        // invalidate the first, and that is the provider's business rather than something Porelo
        // can promise either way.
        assertThat(AgentLogin.Outcome.valueOf("ALREADY_SIGNED_IN")).isNotNull();
    }

    @Test
    void theLoginImageCarriesSomethingForTheAgentToOpenAUrlWith() {

        // Without it the login stops dead: an agent asked to authenticate tries to open a browser,
        // a container has none and no display, and the attempt neither succeeds nor reports
        // anything - leaving somebody at a prompt that never continues, inside a container they did
        // not know how to leave. That is what happened the first time.
        final String rendered =
                org.fuin.porelo.runtime.Containerfile.render(
                        AgentLogin.loginProject(),
                        org.fuin.porelo.runtime.ImageLayers.none()
                                .and(AgentLogin.browserShim(), java.util.List.of()));

        // All three standard names, because which one an agent reaches for is its own business.
        assertThat(rendered)
                .contains("/usr/local/bin/xdg-open")
                .contains("sensible-browser")
                .contains("www-browser");
        assertThat(rendered)
                .as("setting BROWSER can change the adapter's chosen sign-in flow")
                .doesNotContain("BROWSER");
    }

    private static java.util.Optional<org.fuin.porelo.vault.VaultFile.Opener> anOpener() {
        return java.util.Optional.of(
                org.fuin.porelo.vault.VaultFile.Opener.passphrase(PASSPHRASE.clone()));
    }

    private static AgentDefinition definition(Path directory, String flow) {
        return AgentDefinitionReader.read(
                new java.io.StringReader(
                        """
                        name: example
                        binary: example-cli
                        git_identity:
                          name: Example
                          email: noreply@example.com
                        headless:
                          prompt_flag: "-p"
                        config_dir: %s
                        login:
                          arguments: [auth, login]
                        %s
                        """
                                .formatted(directory, flow.isEmpty() ? "" : "  flow: " + flow)),
                "test.yaml");
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.1 50000 192.0.2.2 22", ""})
    void refusesALoopbackSignInOverSshBeforeAskingTheVaultOrLaunching(
            String ssh, @TempDir Path dir) {
        final PoreloContext base = context(dir);
        final PoreloContext remote =
                new PoreloContext(
                                runner,
                                base.paths(),
                                command -> {
                                    throw new AssertionError("no build or container may start");
                                },
                                name -> "SSH_CONNECTION".equals(name) ? ssh : null)
                        .asking(
                                prompt -> {
                                    throw new AssertionError("the vault must not be asked");
                                });
        remote.vault().write(Map.of(), "test-passphrase".toCharArray());

        final AgentLogin.Result result =
                AgentLogin.login(
                        remote,
                        definition(dir, "loopback"),
                        false,
                        false,
                        false,
                        new PrintWriter(out, true),
                        directory -> {
                            throw new AssertionError("no existing credential may be read");
                        },
                        () -> {
                            throw new AssertionError("the vault must not be asked");
                        },
                        (command, directory, opener) -> {
                            throw new AssertionError("no login may launch");
                        });

        assertThat(result.outcome())
                .as("a loopback redirect cannot reach the SSH account")
                .isEqualTo(AgentLogin.Outcome.LOOPBACK_OVER_SSH);
        assertThat(result.detail()).as("the refusal identifies the agent").contains("example");
        assertThat(runner.invocations()).as("no container operation on refusal").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true", "true, true"})
    void neitherPreviewNorForceBypassesTheSshRefusal(
            boolean dryRun, boolean force, @TempDir Path dir) {
        final PoreloContext remote =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> 0,
                        name -> "SSH_CONNECTION".equals(name) ? "connected" : null);

        final AgentLogin.Result result =
                AgentLogin.login(
                        remote,
                        definition(dir, "loopback"),
                        dryRun,
                        force,
                        false,
                        new PrintWriter(out, true),
                        directory -> false,
                        AgentLoginTest::anOpener,
                        (command, directory, opener) -> {
                            throw new AssertionError("a refused login must not launch");
                        });

        assertThat(result.outcome())
                .as("flags do not make the remote redirect reachable")
                .isEqualTo(AgentLogin.Outcome.LOOPBACK_OVER_SSH);
    }

    @ParameterizedTest
    @CsvSource({
        "loopback, false",
        "paste-code, true",
        "device-code, true",
        "'', true",
        "'', false"
    })
    void launchesLocalLoopbackAndRemoteCodeOrUndeclaredSignIns(
            String flow, boolean ssh, @TempDir Path dir) {
        final PoreloContext allowed =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> 0,
                        name -> ssh && "SSH_CONNECTION".equals(name) ? "connected" : null);
        allowed.vault().write(new java.util.LinkedHashMap<>(), PASSPHRASE);
        final List<List<String>> launched = new ArrayList<>();
        final AgentLogin.Result ran =
                new AgentLogin.Result(AgentLogin.Outcome.NOTHING_TO_COLLECT, "", "", 0, "");

        final AgentLogin.Result result =
                AgentLogin.login(
                        allowed,
                        definition(dir, flow),
                        false,
                        false,
                        false,
                        new PrintWriter(out, true),
                        directory -> false,
                        AgentLoginTest::anOpener,
                        (command, directory, opener) -> {
                            launched.add(List.copyOf(command));
                            assertThat(directory)
                                    .as("the declared directory is collected")
                                    .isEqualTo(dir.toString());
                            return ran;
                        });

        assertThat(launched)
                .as("the permitted flow reaches the launch with the declared command")
                .containsExactly(List.of("example-cli", "auth", "login"));
        assertThat(result).as("the launch's result reaches the caller").isSameAs(ran);
    }

    @ParameterizedTest
    @CsvSource({
        "loopback, true, false",
        "loopback, false, true",
        "paste-code, true, true",
        "device-code, true, true",
        "'', true, true"
    })
    void namesTheLoginAsAWayOutOnlyWhereItCanRun(
            String flow, boolean ssh, boolean offered, @TempDir Path dir) {
        // Every place that tells a person to sign in asks this; a login it would then refuse is
        // no way out.
        final PoreloContext here =
                new PoreloContext(
                        runner,
                        context(dir).paths(),
                        command -> 0,
                        name -> ssh && "SSH_CONNECTION".equals(name) ? "connected" : null);

        assertThat(AgentLogin.signsInHere(here, definition(dir, flow))).isEqualTo(offered);
    }

    @Test
    void theLoginImageIsOfflineAndNamedForWhatItIs() {

        // It is never registered as a project and never runs a task. Offline because none of
        // Porelo's networking applies to it: the container is run without the annotation that makes
        // the hooks fire, so an egress class would describe something that is not there.
        assertThat(AgentLogin.loginProject().securityClass()).isEqualTo(SecurityClass.OFFLINE);
        assertThat(AgentLogin.loginProject().name()).isEqualTo("porelo-login");
    }

    @Test
    void theLoginImageDoesNotBorrowATasksName() {

        // Sharing 'porelo/<project>' would have a login rebuild the image a task uses, and a task
        // rebuild the one a login uses - each quietly undoing the other's layers.
        assertThat(AgentLogin.loginProject().imageName()).doesNotContain("porelo/demo");
        assertThat(AgentLogin.loginProject().imageName()).contains("porelo-login");
    }

    @Test
    void aConfigDirectoryIsResolvedInsideTheContainerAndNotOnTheNode() {

        // Off by one user, and it would have looked like a login that produced nothing: the agent
        // runs as 'agent' in the image, so '~/.claude' is /home/agent/.claude there while the
        // node's expansion gives whoever is running Porelo. Pinned to the container's path, never
        // compared with this machine's: run as 'agent' in a task, the two are the same.
        assertThat(AgentLogin.inContainer("~/.claude"))
                .as("the container's home, whoever runs Porelo")
                .isEqualTo("/home/agent/.claude");
        assertThat(AgentLogin.inContainer("/etc/somewhere"))
                .as("an absolute path as it is")
                .isEqualTo("/etc/somewhere");
    }

    @Test
    void nothingIsBuiltOrRunForAPreviewOnAMachineWithNoAgent(@TempDir Path dir) {

        AgentLogin.login(context(dir), null, true, false, new PrintWriter(out, true));

        assertThat(runner.invocations())
                .noneSatisfy(command -> assertThat(command.arguments()).contains("build"));
    }

    @Test
    void copiesTheConfigDirectorysContentsRatherThanTheDirectory() {

        // Why a login had never stored anything, for any agent whose config directory holds the
        // credential as a file inside it. 'podman cp container:/home/agent/.claude target' copies
        // the DIRECTORY, leaving target/.claude/.credentials.json - and the extractor is handed
        // target and looks for target/.credentials.json, one level above the file. So every login
        // found nothing, said "it may have been cancelled", and was believed, because nobody had
        // run one end to end.
        //
        // Measured against podman rather than read: with the trailing "/." the contents land in the
        // target, without it the directory does.
        assertThat(AgentLogin.contentsOf("~/.claude")).isEqualTo("/home/agent/.claude/.");
        assertThat(AgentLogin.contentsOf("/etc/agent")).isEqualTo("/etc/agent/.");
        // And the plain form is still what names the directory itself, for anything that wants it.
        assertThat(AgentLogin.inContainer("~/.claude")).isEqualTo("/home/agent/.claude");
    }
}
