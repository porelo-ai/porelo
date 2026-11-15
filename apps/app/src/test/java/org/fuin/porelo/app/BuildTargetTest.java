package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Holds the watch and the manual query to the same vault entry and target. */
class BuildTargetTest {
    private static final String UPSTREAM = "https://forge.example/acme/code.git";
    private static final String API = "https://forge.example/api";
    private PoreloContext context;
    private final String token = java.util.UUID.randomUUID().toString();

    @BeforeEach
    void prepare() throws Exception {
        final Path directory = Files.createTempDirectory(Path.of("target"), "bt-").toAbsolutePath();
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> directory.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> directory.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> directory.resolve("run").toString();
                                    default -> null;
                                },
                        directory);
        context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg, directory.resolve("bin")),
                                args -> 0)
                        .asking(prompt -> "test passphrase".toCharArray());
        context.vault()
                .write(
                        Map.of(
                                "selected",
                                VaultEntry.of(token),
                                "other",
                                VaultEntry.of("wrong-entry")),
                        "test passphrase".toCharArray());
    }

    @AfterEach
    void forgetPassphrase() {
        PoreloContext.forgetAsked();
    }

    /** The task's container, whose own forge token a host made at its start. */
    private static final String CONTAINER = "porelo-p_task";

    private Target watchTarget(String... more) throws Throwable {
        return asked("target", more);
    }

    private Target pullRequestTarget(String... more) throws Throwable {
        return asked("pullRequestTarget", more);
    }

    private Target asked(String which, String... more) throws Throwable {
        final TaskWatchBuildsCommand watch = new TaskWatchBuildsCommand();
        watch.setContext(context);
        final java.util.List<String> arguments =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                CONTAINER,
                                "--branch=main",
                                "--upstream=" + UPSTREAM,
                                "--api=" + API,
                                "--forge=test-forge",
                                "--credential=selected"));
        arguments.addAll(java.util.List.of(more));
        new CommandLine(watch).parseArgs(arguments.toArray(String[]::new));
        final var method = TaskWatchBuildsCommand.class.getDeclaredMethod(which);
        method.setAccessible(true);
        try {
            return (Target) method.invoke(watch);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    @Test
    void theWatchAndTheQueryReadExactlyTheNamedCredentialForEachQuestion() throws Throwable {
        // Sharing target construction must not change the repository, API or credential a watch
        // uses.
        try {
            context.openIfShut(true, true, new PrintWriter(new StringWriter()));
            final Target watched = watchTarget();
            assertThat(watched)
                    .as("watch and query ask the same target")
                    .isEqualTo(BuildTarget.of(context, UPSTREAM, API, "selected"));
            assertThat(watched.upstream()).as("the upstream is preserved").isEqualTo(UPSTREAM);
            assertThat(watched.api()).as("the configured API is preserved").isEqualTo(API);
            assertThat(watched.token().equals(token))
                    .as("only the named credential was read")
                    .isTrue();
            context.vault()
                    .write(
                            Map.of("selected", VaultEntry.of("replacement-entry")),
                            "test passphrase".toCharArray());
            assertThat(watchTarget().token().equals("replacement-entry"))
                    .as("a later question reads the replacement")
                    .isTrue();
        } finally {
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).forget();
        }
    }

    @Test
    void aShutVaultKeepsTheWatchWaitingForAnUnlock() {
        // Extracting the target must not turn an unavailable vault into an empty forge credential.
        context = context.asking(VaultPrompt.NOBODY);
        assertThatThrownBy(this::watchTarget)
                .as("the watch retries later")
                .isInstanceOf(BuildWatch.NotNow.class)
                .hasMessageContaining("vault is shut");
    }

    @Test
    void aMissingEntryKeepsTheWatchWaitingForTheNamedEntry() {
        // A watch must not fall back to another credential when its configured entry disappears.
        try {
            context.openIfShut(true, true, new PrintWriter(new StringWriter()));
            context.vault()
                    .write(
                            Map.of("other", VaultEntry.of("wrong-entry")),
                            "test passphrase".toCharArray());
            assertThatThrownBy(this::watchTarget)
                    .as("the watch names the entry to store")
                    .isInstanceOf(BuildWatch.NotNow.class)
                    .hasMessageContaining("porelo vault put selected");
        } finally {
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).forget();
        }
    }

    @Test
    void aMissingEntryTheHostsFileNamesIsStoredWithTheHostsCommand() {
        // A start with the vault shut could not see the entry was missing, so the helper started;
        // once the vault opened without it, it said to store it with 'porelo vault put', which
        // stores no token the host's forge gives.
        try {
            context.openIfShut(true, true, new PrintWriter(new StringWriter()));
            context.vault()
                    .write(
                            Map.of("other", VaultEntry.of("wrong-entry")),
                            "test passphrase".toCharArray());
            assertThatThrownBy(() -> watchTarget("--from-host"))
                    .as("the watch names the host's command")
                    .isInstanceOf(BuildWatch.NotNow.class)
                    .hasMessageContaining("'selected'")
                    .hasMessageContaining("porelo host forge-token")
                    .hasMessageNotContaining("porelo vault put");
        } finally {
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).forget();
        }
    }

    @Test
    void aHostsPullRequestIsOpenedWithTheTasksOwnTokenAndItsBuildsReadWithTheNamedOne()
            throws Throwable {
        // On a host the builds credential is the person's own token: a pull request opened with it
        // names the person as its author, who then cannot approve it.
        TaskIdentityFixture.record(context.paths(), CONTAINER, "p", "task", "a".repeat(64));
        try {
            context.openIfShut(true, true, new PrintWriter(new StringWriter()));
            context.vault()
                    .write(
                            Map.of(
                                    "selected",
                                    VaultEntry.of(token),
                                    TaskSecrets.PREFIX + CONTAINER + "/forge-token",
                                    VaultEntry.of("task-made")),
                            "test passphrase".toCharArray());
            assertThat(pullRequestTarget("--from-host").token().equals("task-made"))
                    .as("the task's own token opens its pull request")
                    .isTrue();
            assertThat(watchTarget("--from-host").token().equals(token))
                    .as("the builds are still read with the named entry")
                    .isTrue();
        } finally {
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).forget();
        }
    }

    @Test
    void aHostsPullRequestWithoutTheTasksTokenIsNotOpenedWithThePersons() throws Throwable {
        // A task token not kept - a vault shut at the start, an uncertified start the host gave
        // none - must never fall back to the person's token.
        TaskIdentityFixture.record(context.paths(), CONTAINER, "p", "task", "a".repeat(64));
        try {
            context.openIfShut(true, true, new PrintWriter(new StringWriter()));
            assertThatThrownBy(() -> pullRequestTarget("--from-host"))
                    .as("no pull request is opened as the person")
                    .isInstanceOf(BuildWatch.NotNow.class)
                    .hasMessageContaining("task's own forge token")
                    .hasMessageContaining("never with the person's");
        } finally {
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).forget();
        }
    }

    @Test
    void offAHostThePullRequestIsOpenedWithTheNamedEntryAsBefore() throws Throwable {
        try {
            context.openIfShut(true, true, new PrintWriter(new StringWriter()));
            assertThat(pullRequestTarget().token().equals(token))
                    .as("the person's own forge and entry, as today")
                    .isTrue();
        } finally {
            new KernelKeyring(context.paths().vault().vaultKeyringKey()).forget();
        }
    }

    @Test
    void aHostsPullRequestWaitsForAShutVaultRatherThanOpeningAsThePerson() {
        context = context.asking(VaultPrompt.NOBODY);
        assertThatThrownBy(() -> pullRequestTarget("--from-host"))
                .as("asked again once the vault is unlocked")
                .isInstanceOf(BuildWatch.NotNow.class)
                .hasMessageContaining("vault is shut")
                .hasMessageContaining("task's own forge token");
    }
}
