package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostGatherPushTest {
    private static final char[] PASSPHRASE = "test-gather-vault".toCharArray();
    private static final String CENTRAL = "central-fixture-token-for-tests";
    private static final String LOCAL = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b";
    @TempDir Path dir;
    private PoreloContext context;
    private boolean refuseStorage;
    private String pushOutput = "";
    private String pushError = "";
    private int pushCode;
    private java.util.function.Consumer<org.fuin.porelo.core.process.Command> afterRun =
            command -> {};
    private final FakeCommandRunner runner =
            new FakeCommandRunner() {
                @Override
                public org.fuin.porelo.core.process.CommandResult run(
                        org.fuin.porelo.core.process.Command command) {
                    final var answer = super.run(command);
                    if (refuseStorage && command.arguments().contains("issue-token")) {
                        try {
                            Files.writeString(context.vault().path(), "not a vault any more");
                        } catch (java.io.IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }
                    afterRun.accept(command);
                    if (command.arguments().contains("main:main"))
                        return new org.fuin.porelo.core.process.CommandResult(
                                command, pushCode, pushOutput, pushError);
                    return answer;
                }
            };
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final AtomicInteger prompts = new AtomicInteger();
    private Path conf;
    private Path home;

    @BeforeEach
    void fixture() throws Exception {
        conf = dir.resolve("forge.conf");
        home = dir.resolve("home");
        Files.writeString(conf, "name=forge.example.org\naccount=porelo-forge\ngit_port=3001\n");
        final var xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        home);
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        args -> 0,
                        name -> null,
                        false,
                        prompt -> {
                            prompts.incrementAndGet();
                            return PASSPHRASE.clone();
                        },
                        new StandardInput(
                                new java.io.InputStream() {
                                    @Override
                                    public int read() {
                                        throw new AssertionError("stdin must stay unread");
                                    }
                                },
                                false));
        final Path push = home.resolve(".config/porelo-host/push");
        Files.createDirectories(push);
        for (Path path :
                java.util.List.of(
                        home, home.resolve(".config"), home.resolve(".config/porelo-host"), push))
            Files.setAttribute(path, "unix:mode", 0700);
        final Path ca = dir.resolve("central.pem");
        Files.writeString(ca, "fixture-ca");
        final Path config = push.resolve("acme.conf");
        Files.writeString(
                config,
                "local http://127.0.0.1:3001\nowner acme\ncentral-ca "
                        + ca
                        + "\nrepository first https://central.example/acme/first.git\n");
        Files.setAttribute(config, "unix:mode", 0600);
        runner.answering("getent passwd", "alice:x:1000:1000::" + home + ":/bin/bash\n");
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", LOCAL + "\n");
        runner.answering("git", "commit\n");
    }

    private void vault(Map<String, VaultEntry> entries, boolean open) {
        context.vault().write(entries, PASSPHRASE.clone());
        if (open)
            context.asking(prompt -> PASSPHRASE.clone())
                    .openIfShut(true, true, new PrintWriter(Writer.nullWriter()));
    }

    @AfterEach
    void forget() {
        PoreloContext.forgetAsked();
        VaultLockCommand.forget(context);
    }

    private HostGatherPush push() {
        final var push = new HostGatherPush(context);
        push.forgeConf = conf;
        push.attributes =
                path ->
                        Map.of(
                                "uid",
                                0,
                                "mode",
                                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                        ? 0040755
                                        : 0100644);
        push.taken = path -> path;
        push.found = program -> Path.of("/usr/bin", program);
        push.terminal = () -> false;
        push.uid = () -> 1000L;
        push.files.openedAttributes =
                path -> {
                    final var attrs =
                            new java.util.HashMap<>(Files.readAttributes(path, "unix:uid,mode"));
                    attrs.put("uid", 1000);
                    return attrs;
                };
        return push;
    }

    private HostGatherRefusal refused(
            HostGatherPush push, java.util.List<java.util.Map<String, Object>> events) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                HostGatherRefusal.class,
                () ->
                        push.push(
                                "acme",
                                java.util.List.of(),
                                (repository, stage) ->
                                        events.add(
                                                Map.of("repository", repository, "stage", stage)),
                                () -> true));
    }

    @Test
    void refusedCentralAuthenticationHasANamedOutcomeWithoutPromptWords() {
        // A window must repair the central credential without presenting Git's prompt or leaking
        // it.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        pushCode = 128;
        pushError =
                "fatal: could not read Username for 'https://central.example': terminal prompts disabled "
                        + CENTRAL
                        + " "
                        + LOCAL
                        + " "
                        + basic(CENTRAL)
                        + " "
                        + basic(LOCAL);
        final var events = new java.util.ArrayList<Map<String, Object>>();
        final var failure = refused(push(), events);
        assertThat(failure.kind()).isEqualTo(HostGatherRefusal.Kind.CENTRAL_TOKEN_REFUSED);
        assertThat(failure.details()).containsEntry("credential", "forge.example.org.central");
        assertThat(failure.details().toString() + events)
                .doesNotContain(CENTRAL, LOCAL, "Username", "terminal prompts");
    }

    @Test
    void shutVaultHasANamedOutcomeAndRunsNothing() {
        // A daemon must never prompt or rotate the host token while the vault is shut.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), false);
        final var failure = refused(push(), new java.util.ArrayList<>());
        assertThat(failure.kind()).isEqualTo(HostGatherRefusal.Kind.VAULT_SHUT);
        assertThat(failure.details())
                .containsEntry("unlockCommand", java.util.List.of("porelo", "vault", "unlock"));
        assertThat(runner.invocations()).isEmpty();
        assertThat(prompts.get()).isZero();
    }

    @Test
    void repliesAndProgressNeverContainEitherTokenOrGitOutput() throws Exception {
        // A hostile Git answer must not carry credentials or arbitrary output into the window.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        runner.answering("git", CENTRAL + " " + LOCAL + " " + basic(CENTRAL) + " " + basic(LOCAL));
        final var events = new java.util.ArrayList<Map<String, Object>>();
        final var result =
                push().push(
                                "acme",
                                java.util.List.of(),
                                (repository, stage) ->
                                        events.add(
                                                Map.of("repository", repository, "stage", stage)),
                                () -> true);
        assertThat(result.pushed()).containsExactly("first");
        assertThat(result.upToDate()).isEmpty();
        assertThat(events)
                .containsExactly(
                        Map.of("repository", "first", "stage", "reading"),
                        Map.of("repository", "first", "stage", "pushing"),
                        Map.of("repository", "first", "stage", "pushed"));
        for (var command : runner.invocations()) {
            assertThat(command.arguments().toString())
                    .doesNotContain(CENTRAL, LOCAL, basic(CENTRAL), basic(LOCAL));
            if (!command.program().endsWith("git"))
                assertThat(command.environment().toString())
                        .doesNotContain(CENTRAL, LOCAL, basic(CENTRAL), basic(LOCAL));
        }
        assertThat(events.toString() + result)
                .doesNotContain(CENTRAL, LOCAL, "commit", basic(CENTRAL), basic(LOCAL));
    }

    @Test
    void nothingToPushHasItsOwnOutcome() {
        // A no-op release must not be reported as a newly published main.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        pushOutput = "=\trefs/heads/main:refs/heads/main\t[up to date]\n";
        final var failure = refused(push(), new java.util.ArrayList<>());
        assertThat(failure.kind()).isEqualTo(HostGatherRefusal.Kind.NOTHING_TO_PUSH);
        assertThat(failure.details()).containsEntry("upToDate", java.util.List.of("first"));
    }

    private static String basic(String token) {
        return java.util.Base64.getEncoder()
                .encodeToString(
                        ("x-access-token:" + token)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void overlapCannotRevokeTheActiveTransfersToken() throws Exception {
        // A second release must not rotate the host token while the first release uses it.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final var push = push();
        final var nested = new java.util.concurrent.atomic.AtomicReference<HostGatherRefusal>();
        push.push(
                "acme",
                java.util.List.of(),
                (repository, stage) -> {
                    if (stage.equals("pushing"))
                        nested.set(refused(push(), new java.util.ArrayList<>()));
                },
                () -> true);
        assertThat(nested.get().kind()).isEqualTo(HostGatherRefusal.Kind.HOST_TOKEN);
        assertThat(nested.get().details()).containsEntry("reason", "busy");
        assertThat(runner.invocations())
                .filteredOn(command -> command.arguments().contains("issue-token"))
                .hasSize(1);
    }

    @Test
    void disconnectAfterConfirmedPushKeepsItsResultAndStartsNoLaterRepository() throws Exception {
        // Disconnect after a remote write must not erase its confirmation or start another write.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final Path configuration = home.resolve(".config/porelo-host/push/acme.conf");
        Files.writeString(
                configuration,
                Files.readString(configuration)
                        + "repository second https://central.example/acme/second.git\n");
        final var connected = new java.util.concurrent.atomic.AtomicBoolean(true);
        afterRun =
                command -> {
                    if (command.arguments().contains("main:main")) connected.set(false);
                };
        final var error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        HostGatherRefusal.class,
                        () ->
                                push().push(
                                                "acme",
                                                java.util.List.of(),
                                                (repository, stage) -> {},
                                                connected::get));
        assertThat(error.kind()).isEqualTo(HostGatherRefusal.Kind.CANCELLED);
        assertThat(error.details()).containsEntry("pushed", java.util.List.of("first"));
        assertThat(runner.invocations())
                .noneMatch(
                        command ->
                                command.arguments()
                                        .contains("https://central.example/acme/second.git"));
        assertThat(runner.invocations())
                .filteredOn(command -> command.arguments().contains("main:main"))
                .hasSize(1);
    }

    @Test
    void failedFinalNotificationRetainsTheConfirmedPush() {
        // A broken progress sink must not turn a confirmed remote write into an unreported write.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final var error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        HostGatherRefusal.class,
                        () ->
                                push().push(
                                                "acme",
                                                java.util.List.of(),
                                                (repository, stage) -> {
                                                    if (stage.equals("pushed"))
                                                        throw new java.io.IOException(
                                                                "secret-fixture");
                                                },
                                                () -> true));
        assertThat(error.details()).containsEntry("pushed", java.util.List.of("first"));
        assertThat(error.getMessage()).doesNotContain("secret-fixture");
    }

    @Test
    void aLostPushAcknowledgementIsNotAConfirmedRefusal() {
        // Retrying a possibly completed release requires the window to distinguish an unknown
        // outcome.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        pushCode = 128;
        pushError = "fatal: the remote end hung up unexpectedly " + CENTRAL;
        final var error = refused(push(), new java.util.ArrayList<>());
        assertThat(error.kind()).isEqualTo(HostGatherRefusal.Kind.CANCELLED);
        assertThat(error.details().toString()).doesNotContain(CENTRAL, "hung up");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleShareNamesAnAvailableUnlockRoute(boolean host) {
        // An unusable opener must not send a person to passphrase unlock when that route is absent.
        final byte[] share = new byte[32];
        if (host) context.vault().write(Map.of(), share);
        else {
            context.vault().write(Map.of(), PASSPHRASE.clone());
            context.vault()
                    .enroll(
                            VaultFile.Opener.passphrase(PASSPHRASE),
                            share,
                            "device",
                            "USER_SCOPED");
            context.vault()
                    .revoke(
                            VaultFile.Opener.share(share),
                            org.fuin.porelo.vault.Keyslot.PASSPHRASE);
        }
        final var push = push();
        final byte[] stale = new byte[32];
        stale[0] = 1;
        push.opener = () -> java.util.Optional.of(VaultFile.Opener.share(stale));
        final var error = refused(push, new java.util.ArrayList<>());
        assertThat(error.kind()).isEqualTo(HostGatherRefusal.Kind.VAULT_SHUT);
        assertThat(error.details())
                .containsEntry(
                        "unlockCommand",
                        host
                                ? java.util.List.of("porelo", "host", "unlock", "<host>")
                                : java.util.List.of());
        assertThat(runner.invocations()).isEmpty();
        assertThat(prompts.get()).isZero();
    }
}
