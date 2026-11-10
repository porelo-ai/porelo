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
import picocli.CommandLine;

class HostGatherCommandTest {
    private static final char[] PASSPHRASE = "test-gather-vault".toCharArray();
    private static final String CENTRAL = "central-fixture-token-for-tests";
    private static final String LOCAL = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b";
    @TempDir Path dir;
    private PoreloContext context;
    private boolean refuseStorage;
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

    private CommandLine cli() {
        final var line =
                PoreloCli.commandLine(context)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err));
        for (String name : java.util.List.of("push", "pull")) {
            final HostGatherCommand command =
                    (HostGatherCommand)
                            line.getSubcommands()
                                    .get("host")
                                    .getSubcommands()
                                    .get(name)
                                    .getCommand();
            command.forgeConf = conf;
            command.attributes =
                    path ->
                            Map.of(
                                    "uid",
                                    0,
                                    "mode",
                                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                            ? 0040755
                                            : 0100644);
            command.taken = path -> path;
            command.found = program -> Path.of("/usr/bin", program);
            command.terminal = () -> false;
            command.uid = () -> 1000L;
            command.files.openedAttributes =
                    path -> {
                        final var attrs =
                                new java.util.HashMap<>(
                                        Files.readAttributes(path, "unix:uid,mode"));
                        attrs.put("uid", 1000);
                        return attrs;
                    };
        }
        return line;
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "pull"})
    void missingCentralEntryRefusesBeforeAnyProgram(String verb) {
        // A missing credential must not rotate the local token or run a partial gathered operation.
        vault(Map.of(), true);
        assertThat(cli().execute("host", verb, "acme")).isEqualTo(69);
        assertThat(err.toString())
                .contains(
                        "forge.example.org.central", "porelo vault put forge.example.org.central");
        assertThat(runner.invocations()).isEmpty();
        assertThat(prompts.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "pull"})
    void shutVaultRefusesBeforeAnyProgramOrStdinRead(String verb) {
        // A shut vault must not consume the supplied stdin token or invoke an issuer.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), false);
        assertThat(cli().execute("host", verb, "acme")).isEqualTo(69);
        assertThat(err.toString()).contains("porelo vault unlock");
        assertThat(runner.invocations()).isEmpty();
        assertThat(prompts.get()).isZero();
    }

    @Test
    void configurationRefusalsCannotRepeatTheVaultToken() throws Exception {
        // Malformed config must not repeat a vault credential in a diagnostic.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final Path config = home.resolve(".config/porelo-host/push/acme.conf");
        Files.writeString(
                config, Files.readString(config).replace("owner acme", "owner " + CENTRAL));
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(65);
        assertThat(err.toString() + out).doesNotContain(CENTRAL);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "root-owner",
                "root-mode",
                "root-link",
                "ca-owner",
                "ca-mode",
                "ca-link",
                "ca-missing"
            })
    void untrustedSettingsOrAuthorityCannotReachTheIssuer(String defect) throws Exception {
        // A changeable settings or CA path must not lend a credential to an impostor forge.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        if (defect.equals("ca-missing")) Files.delete(dir.resolve("central.pem"));
        final var line = cli();
        final var command =
                (HostGatherCommand)
                        line.getSubcommands().get("host").getSubcommands().get("push").getCommand();
        final var trusted = command.attributes;
        command.attributes =
                path -> {
                    if (path.equals(
                            defect.startsWith("root") ? conf : dir.resolve("central.pem"))) {
                        return Map.of(
                                "uid",
                                defect.endsWith("owner") ? 1234 : 0,
                                "mode",
                                defect.endsWith("mode")
                                        ? 0100666
                                        : defect.endsWith("link") ? 0120777 : 0100644);
                    }
                    return trusted.read(path);
                };
        assertThat(line.execute("host", "push", "acme"))
                .isEqualTo(defect.startsWith("root") ? 69 : 66);
        assertThat(runner.lines())
                .noneMatch(value -> value.contains("sudo") || value.contains("git "));
    }

    @Test
    void aFailingIssuerCannotLeakAnEncodedTokenOrReachGit() {
        // An issuer's refusal must not reveal its secret channel through a raw or Basic diagnostic.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final String encoded =
                java.util.Base64.getEncoder()
                        .encodeToString(
                                ("x-access-token:" + LOCAL)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        runner.failing(
                "sudo -n /usr/sbin/porelo-host forge issue-token",
                75,
                LOCAL + "\n",
                "refused Authorization: Basic " + encoded);
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(75);
        assertThat(out.toString() + err).doesNotContain(LOCAL, CENTRAL, encoded);
        assertThat(Files.exists(context.paths().failureLog())).isFalse();
        assertThat(runner.lines()).noneMatch(value -> value.contains("git "));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "alice:x:9999:1000::/home/alice:/bin/bash\n",
                "alice:x:1000:1000::/:/bin/bash\n",
                "alice:x:1000:1000::relative:/bin/bash\n",
                "alice:x:1000:1000::/home/alice:/bin/bash\nbob:x:1001:1001::/home/bob:/bin/bash\n"
            })
    void unprovenAccountCannotSelectAHome(String passwd) {
        // An unproven account must not select another person's private config.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        runner.answering("getent passwd", passwd);
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(69);
        assertThat(runner.lines())
                .noneMatch(value -> value.contains("sudo") || value.contains("git "));
    }

    @Test
    void missingVaultNamesInitialization() {
        // A missing vault must prevent token rotation and stdin credentials.
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(69);
        assertThat(err.toString()).contains("porelo vault init");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void usesTheCentralVaultEntryAndRefreshesThePersonsToken() {
        // Only central Git gets the vault token; local Git gets the fresh person token.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        assertThat(cli().execute("host", "push", "acme")).as(err.toString()).isZero();
        assertThat(
                        context.vault()
                                .read(VaultFile.Opener.passphrase(PASSPHRASE))
                                .get("forge.example.org.person")
                                .value())
                .isEqualTo(LOCAL);
        assertThat(runner.lines())
                .contains("/usr/bin/sudo -n /usr/sbin/porelo-host forge issue-token");
        for (var command : runner.invocations()) {
            assertThat(command.arguments().toString() + command)
                    .doesNotContain(CENTRAL, LOCAL, "stdin-must-not-be-used");
            if (!command.program().endsWith("git")) {
                assertThat(command.environment().toString()).doesNotContain(CENTRAL, LOCAL);
                assertThat(command.input()).isNull();
                assertThat(command.binaryInput()).isNull();
            }
        }
        assertThat(out.toString() + err).doesNotContain(CENTRAL, LOCAL);
        assertThat(prompts.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "INVALID", "-option", "@file"})
    void invalidProjectNeverRunsAProgram(String project) {
        // Unsafe project syntax must not select a config outside the person's push directory.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        assertThat(cli().execute("host", "push", project)).isEqualTo(64);
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void rootCannotRunThePersonsTransfer() {
        // Root must not operate on a guessed person's vault or private repository config.
        final var line = cli();
        ((HostGatherCommand)
                                line.getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("push")
                                        .getCommand())
                        .uid =
                () -> 0;
        assertThat(line.execute("host", "push", "acme")).isEqualTo(77);
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void aFreshLocalTokenStillWorksWhenTheVaultCannotStoreIt() {
        // Failed storage must not discard or expose the freshly rotated local credential.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        refuseStorage = true;
        assertThat(cli().execute("host", "push", "acme")).as(err.toString()).isZero();
        assertThat(runner.lines()).anyMatch(line -> line.contains("clone"));
        assertThat(err.toString())
                .contains("vault took no new forge token", "forge.example.org.person")
                .doesNotContain(LOCAL, CENTRAL);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aShutHostVaultNamesHostUnlockWithoutAPassphrase(boolean atTerminal) {
        // A shut host vault must not prompt for a passphrase or rotate a token.
        context.vault().write(Map.of(), new byte[32]);
        final var line = cli();
        ((HostGatherCommand)
                                line.getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("push")
                                        .getCommand())
                        .terminal =
                () -> atTerminal;
        assertThat(line.execute("host", "push", "acme")).isEqualTo(69);
        assertThat(err.toString())
                .contains("porelo host unlock <host>")
                .doesNotContain("porelo vault unlock");
        assertThat(prompts.get()).isZero();
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void aShutPassphraseVaultOpensAtATerminal() {
        // A command-only unlock must not lend credentials to later commands.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), false);
        final var line = cli();
        ((HostGatherCommand)
                                line.getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("push")
                                        .getCommand())
                        .terminal =
                () -> true;
        assertThat(line.execute("host", "push", "acme")).as(err.toString()).isZero();
        assertThat(prompts.get()).isEqualTo(1);
        assertThat(context.opener()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad\rvalue", "bad\nvalue", "bad\0value"})
    void invalidCentralTokensRefuseBeforeAnyProgram(String token) {
        // Invalid header bytes must not inject credentials or rotate the person's working token.
        vault(Map.of("forge.example.org.central", VaultEntry.of(token)), true);
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(69);
        assertThat(runner.invocations()).isEmpty();
        assertThat(err.toString()).contains("porelo vault put forge.example.org.central");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "name=INVALID",
                "account=",
                "git_port=0",
                "git_port=65536",
                "git_port=abc",
                "account=porelo-forge\naccount=other"
            })
    void invalidRootSettingsCannotChooseCredentials(String change) throws Exception {
        // Untrusted or malformed host identity must not choose a vault key or privileged issuer.
        Files.writeString(conf, Files.readString(conf) + change + "\n");
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(69);
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void unsafeSystemGitRefusesBeforeIssuance() {
        // A replaced git binary must not receive either credential or trigger local token rotation.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final var line = cli();
        ((HostGatherCommand)
                                line.getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("push")
                                        .getCommand())
                        .found =
                name -> {
                    if (name.equals("git"))
                        throw new java.io.UncheckedIOException(new java.io.IOException("unsafe"));
                    return Path.of("/usr/bin", name);
                };
        assertThat(line.execute("host", "push", "acme")).isEqualTo(69);
        assertThat(runner.lines())
                .noneMatch(value -> value.contains("sudo") || value.contains("git "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "parent", "relative"})
    void rootOwnedCaLinksRemainUsable(String kind) throws Exception {
        // Trusted distribution bundle links must not prevent pushing approved work.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final Path ca = dir.resolve("central.pem");
        Files.move(ca, dir.resolve("bundle.pem"));
        if (kind.equals("parent")) {
            Files.createDirectory(dir.resolve("certs"));
            Files.move(dir.resolve("bundle.pem"), dir.resolve("certs/bundle.pem"));
            Files.createSymbolicLink(dir.resolve("cert-link"), Path.of("certs"));
            Files.createSymbolicLink(ca, Path.of("cert-link/bundle.pem"));
        } else if (kind.equals("relative")) {
            Files.createDirectory(dir.resolve("certs"));
            Files.createSymbolicLink(ca, Path.of("certs/../bundle.pem"));
        } else Files.createSymbolicLink(ca, Path.of("bundle.pem"));
        final var line = cli();
        final var command =
                (HostGatherCommand)
                        line.getSubcommands().get("host").getSubcommands().get("push").getCommand();
        final var original = command.attributes;
        command.attributes =
                path ->
                        Files.isSymbolicLink(path)
                                ? Map.of("uid", 0, "mode", 0120777)
                                : original.read(path);
        assertThat(line.execute("host", "push", "acme")).as(err.toString()).isZero();
        assertThat(runner.invocations())
                .filteredOn(c -> c.arguments().contains("main:main"))
                .allSatisfy(
                        c ->
                                assertThat(c.environment().get("GIT_SSL_CAINFO"))
                                        .isEqualTo(
                                                dir.resolve(
                                                                kind.equals("parent")
                                                                        ? "certs/bundle.pem"
                                                                        : "bundle.pem")
                                                        .toString()));
    }

    @Test
    void sudoHasOnlyItsRequiredEnvironmentAndAStableDirectory() {
        // Caller secrets, locale and an inaccessible cwd must not reach a privileged helper.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        assertThat(cli().execute("host", "push", "acme")).isZero();
        final var issued = runner.only("issue-token");
        assertThat(issued.inheritsEnvironment()).isFalse();
        assertThat(issued.environment())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("PATH", "/usr/sbin:/usr/bin", "LC_ALL", "C"));
        assertThat(issued.workingDirectory()).isEqualTo(Path.of("/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../bad", "first,first", "unknown"})
    void invalidSelectionDoesNotPromptOrUnlock(String requested) {
        // A rejected selection must not open a person's shut vault.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), false);
        final var line = cli();
        final var command =
                (HostGatherCommand)
                        line.getSubcommands().get("host").getSubcommands().get("push").getCommand();
        command.terminal = () -> true;
        final var args = new java.util.ArrayList<>(java.util.List.of("host", "push", "acme"));
        args.addAll(java.util.List.of(requested.split(",")));
        assertThat(line.execute(args.toArray(String[]::new))).isEqualTo(64);
        assertThat(prompts.get()).isZero();
        assertThat(runner.lines()).noneMatch(v -> v.contains("sudo") || v.contains("git "));
    }

    @Test
    void invalidConfigDoesNotPrompt() throws Exception {
        // Missing project configuration must not unlock a vault merely to refuse a transfer.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), false);
        Files.delete(home.resolve(".config/porelo-host/push/acme.conf"));
        final var line = cli();
        ((HostGatherCommand)
                                line.getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("push")
                                        .getCommand())
                        .terminal =
                () -> true;
        assertThat(line.execute("host", "push", "acme")).isEqualTo(66);
        assertThat(prompts.get()).isZero();
    }

    @Test
    void aSilentIssuerFailureStillExplainsItsRefusal() {
        // A silent privileged failure must not leave a person guessing whether work was sent.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        runner.failing("sudo -n /usr/sbin/porelo-host forge issue-token", 143, "", "");
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(143);
        assertThat(err.toString()).contains("the person's forge token could not be made");
    }

    @Test
    void aCredentialInConfigIsRefusedBeforeRotation() throws Exception {
        // A misplaced central credential must not revoke a working local token before refusal.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final Path file = home.resolve(".config/porelo-host/push/acme.conf");
        Files.writeString(
                file,
                Files.readString(file)
                        .replace(
                                "central.example/acme/first.git",
                                "central.example/acme/" + CENTRAL + ".git"));
        assertThat(cli().execute("host", "push", "acme")).isEqualTo(65);
        assertThat(runner.lines()).noneMatch(v -> v.contains("sudo"));
    }

    @Test
    void recordFieldNamesAreNotCredentialsInConfig() {
        // Record rendering must not turn a valid vault value into an unrelated refusal.
        vault(Map.of("forge.example.org.central", VaultEntry.of("repositories")), true);
        assertThat(cli().execute("host", "push", "acme")).as(err.toString()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "repository"})
    void existingArgumentFilesCannotSelectWork(String position) throws Exception {
        // An argument file must not select unintended repositories or expose its contents.
        vault(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), true);
        final Path file = dir.resolve("arguments");
        Files.writeString(file, position.equals("project") ? "acme" : "first");
        final String[] args =
                position.equals("project")
                        ? new String[] {"host", "push", "@" + file}
                        : new String[] {"host", "push", "acme", "@" + file};
        assertThat(cli().execute(args)).isEqualTo(64);
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void aDeviceOnlyVaultNamesItsAvailableUnlockRouteOnce() {
        // A passphrase-less vault must not send a person to an unusable passphrase command.
        context.vault().write(Map.of(), PASSPHRASE.clone());
        final byte[] share = new byte[32];
        context.vault()
                .enroll(VaultFile.Opener.passphrase(PASSPHRASE), share, "device", "USER_SCOPED");
        context.vault()
                .revoke(VaultFile.Opener.share(share), org.fuin.porelo.vault.Keyslot.PASSPHRASE);
        final var line = cli();
        ((HostGatherCommand)
                                line.getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("push")
                                        .getCommand())
                        .terminal =
                () -> true;
        assertThat(line.execute("host", "push", "acme")).isEqualTo(69);
        assertThat(err.toString())
                .contains("enrolled device")
                .doesNotContain("porelo vault unlock");
        assertThat(err.toString().lines().count()).isEqualTo(1);
        assertThat(prompts.get()).isZero();
    }

    @Test
    void anInvalidCachedOpenerCanBeReplacedAtATerminal() {
        // A stale opener must not prevent a person from unlocking with the current passphrase.
        final char[] stale = "stale-passphrase".toCharArray();
        context.vault().write(Map.of("forge.example.org.central", VaultEntry.of(CENTRAL)), stale);
        context.asking(prompt -> stale.clone())
                .openIfShut(true, false, new PrintWriter(Writer.nullWriter()));
        context.vault().rekey(VaultFile.Opener.passphrase(stale), PASSPHRASE.clone());
        final var line = cli();
        ((HostGatherCommand)
                                line.getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("push")
                                        .getCommand())
                        .terminal =
                () -> true;
        assertThat(line.execute("host", "push", "acme")).as(err.toString()).isZero();
        assertThat(prompts.get()).isEqualTo(1);
    }

    @Test
    void storingTheCentralCredentialDoesNotClaimItIsUnused() throws Exception {
        // Misleading storage guidance must not prompt replacement of a working forge credential.
        vault(Map.of(), true);
        final Path token = dir.resolve("secret");
        Files.writeString(token, CENTRAL);
        assertThat(
                        cli().execute(
                                        "vault",
                                        "put",
                                        "forge.example.org.central",
                                        "--from-file",
                                        token.toString()))
                .isZero();
        assertThat(err.toString()).doesNotContain("no provider");
    }
}
