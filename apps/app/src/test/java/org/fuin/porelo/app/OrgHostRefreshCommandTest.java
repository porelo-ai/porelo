package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Guards the central token's only exit: stdin of SSH pinned to the accepted host card. */
class OrgHostRefreshCommandTest {
    private static final String TOKEN = "invented-central-token-9f2a";
    private static final String STATUS =
            "{\"host\":\"build\",\"organization\":\"following\",\"refused\":null,"
                    + "\"commit\":null,\"pinned\":null,\"persons\":[],\"projects\":[]}";
    @TempDir Path dir;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final AtomicReference<byte[]> sent = new AtomicReference<>();
    private final AtomicReference<String> known = new AtomicReference<>();
    private final List<String> errorDuringRun = new ArrayList<>();
    private String key;
    private Organization.Card card;
    private List<OrganizationQuestions.Host> hosts;
    private CommandLine cli;
    private PoreloContext context;
    private boolean open = true;
    private boolean explode;
    private final byte[] share = new byte[32];
    private final FakeCommandRunner runner =
            new FakeCommandRunner() {
                /** Hands stderr over before returning so delayed progress cannot pass. */
                @Override
                public CommandResult run(Command command, Consumer<String> lines) {
                    final CommandResult result = run(command);
                    result.standardError()
                            .lines()
                            .forEach(
                                    line -> {
                                        lines.accept(line);
                                        errorDuringRun.add(err.toString());
                                    });
                    return result;
                }

                @Override
                public CommandResult run(Command command) {
                    if (command.program().equals("ssh")) {
                        try {
                            final String pin =
                                    command.arguments().stream()
                                            .filter(a -> a.startsWith("UserKnownHostsFile="))
                                            .findFirst()
                                            .orElseThrow()
                                            .substring("UserKnownHostsFile=".length())
                                            .replace("\"", "");
                            known.set(Files.readString(Path.of(pin)));
                        } catch (java.io.IOException ex) {
                            throw new AssertionError(ex);
                        }
                        // A fake SSH handshake refuses before reading stdin when the peer's key
                        // differs.
                        if (!known.get().equals("[host.example.org]:2222 " + key + "\n")) {
                            return new CommandResult(
                                    command, 255, "", "Host key verification failed.");
                        }
                        sent.set(command.binaryInput().clone());
                        if (explode) throw new IllegalStateException(TOKEN);
                    }
                    return super.run(command);
                }
            };

    @BeforeEach
    void machine() throws Exception {
        key = publicKey(SigningKey.generate("invented host"));
        card = new Organization.Card("host.example.org", 2222, key, 0, 0, 0, "signed");
        hosts = List.of(row(card));
        context =
                new PoreloContext(
                                runner,
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                args -> 0,
                                name -> "FORGE_TOKEN".equals(name) ? TOKEN : null)
                        .withHostVault(
                                new HostVaultAccess() {
                                    public void keep(byte[] value) {
                                        open = true;
                                    }

                                    public boolean isOpen() {
                                        return open;
                                    }

                                    public byte[] share() {
                                        return share.clone();
                                    }

                                    public boolean forget() {
                                        open = false;
                                        return true;
                                    }
                                });
        context.vault().write(Map.of("central", new VaultEntry(TOKEN, "token")), share);
        final Path config = context.paths().xdg().config();
        Files.createDirectories(config);
        Files.writeString(
                config.resolve("organization.yml"),
                "adapter: git\nsettings:\n  repository: https://forge.example.org/acme/organization.git\n");
        Files.writeString(
                context.paths().vault().credentialRegistry(),
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: vault\n    vault: central\n");
        cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final CommandLine refresh =
                cli.getSubcommands()
                        .get("org")
                        .getSubcommands()
                        .get("host")
                        .getSubcommands()
                        .get("refresh");
        assertThat(refresh).as("org host registers refresh beside carry and uncarry").isNotNull();
        ((OrgHostRefreshCommand) refresh.getCommand()).reader = machine -> hosts;
        runner.answering("porelo-host org refresh", STATUS);
    }

    private static String publicKey(SigningKey key) {
        return "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(key.keyBlob());
    }

    private OrganizationQuestions.Host row(Organization.Card value) {
        return new OrganizationQuestions.Host("build", value, List.of(), List.of(), Map.of());
    }

    private int run(String... options) {
        final var args = new java.util.ArrayList<>(List.of("org", "host", "refresh", "build"));
        args.addAll(List.of(options));
        return cli.execute(args.toArray(String[]::new));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void showsProgressBeforeReturningWithoutExposingTheCredential(boolean json, boolean cleanedEcho)
            throws Exception {
        final String token = cleanedEcho ? TOKEN + "?tail" : TOKEN;
        if (cleanedEcho) {
            Files.delete(context.vault().path());
            context.vault().write(Map.of("central", new VaultEntry(token, "token")), share);
        }
        final String echo = cleanedEcho ? TOKEN + "\u001btail" : token;
        runner.answering(
                "porelo-host org refresh", STATUS, "following\u001brepository alpha\n" + echo);

        final int exit = run(json ? new String[] {"--json"} : new String[0]);

        assertThat(errorDuringRun)
                .as(
                        "both host lines must reach stderr before SSH returns, with the credential withheld")
                .hasSize(2);
        assertThat(errorDuringRun.getFirst())
                .as("the request and cleaned progress are visible while the host is still running")
                .hasLineCount(2)
                .endsWith("build: following?repository alpha" + System.lineSeparator());
        assertThat(errorDuringRun.getLast())
                .as("the credential echo adds one replacement line before SSH returns")
                .startsWith(errorDuringRun.getFirst())
                .hasLineCount(3);
        assertThat(errorDuringRun.getLast().lines().toList().getLast())
                .as("a raw or cleaned credential echo is replaced while the host is still running")
                .startsWith("build: ")
                .contains("withheld");
        assertThat(err.toString())
                .as("the completed run must not print streamed diagnostics again")
                .isEqualTo(errorDuringRun.getLast());
        assertThat(out + err.toString())
                .as("neither output stream may disclose any of the credential")
                .doesNotContain(TOKEN, token, echo);
        assertThat(exit).as("progress does not change a successful handover").isZero();
        if (json) {
            assertThat((Map<?, ?>) Json.parse(out.toString()))
                    .as("stdout remains the single JSON handover answer")
                    .isEqualTo(Map.of("handedOver", true, "answer", Json.parse(STATUS)));
        }
    }

    @Test
    void sendsTheTokenOnlyToPinnedSshStandardInput() throws Exception {
        assertThat(run("--json")).as(err.toString()).isZero();
        assertThat(sent.get())
                .as("exact token bytes on stdin")
                .isEqualTo(TOKEN.getBytes(StandardCharsets.US_ASCII));
        final Command ssh = runner.only("porelo-host");
        assertThat(ssh.arguments())
                .as("isolated SSH, the card's address and port, direct root")
                .contains(
                        "-F",
                        "/dev/null",
                        "-T",
                        "ForwardAgent=no",
                        "ClearAllForwardings=yes",
                        "StrictHostKeyChecking=yes",
                        "GlobalKnownHostsFile=/dev/null",
                        "ControlPath=none",
                        "KnownHostsCommand=none",
                        "VerifyHostKeyDNS=no",
                        "-p",
                        "2222")
                .endsWith(
                        "root@host.example.org",
                        // A root SSH session carries pam_systemd's and sshd's names, which
                        // porelo-host refuses: it starts with none but its own path and locale.
                        "env",
                        "-i",
                        "PATH=/usr/sbin:/usr/bin:/sbin:/bin",
                        "LANG=C.UTF-8",
                        "/usr/sbin/porelo-host",
                        "org",
                        "refresh");
        assertThat(known.get())
                .as("only the card's key is trusted")
                .isEqualTo("[host.example.org]:2222 " + key + "\n");
        assertThat(ssh.inheritsEnvironment())
                .as("no inherited token or ssh configuration variables")
                .isFalse();
        assertThat(ssh.environment()).as("no value in the environment").doesNotContainValue(TOKEN);
        assertThat(ssh.toString()).as("no value in command diagnostics").doesNotContain(TOKEN);
        assertThat(ssh.binaryInput()).as("borrowed bytes cleared").containsOnly((byte) 0);
        assertThat(out + err.toString()).as("no value in either answer").doesNotContain(TOKEN);
        assertThat((Map<?, ?>) Json.parse(out.toString()))
                .as("only handover and host answer")
                .isEqualTo(Map.of("handedOver", true, "answer", Json.parse(STATUS)));
        final String pin =
                ssh.arguments().stream()
                        .filter(a -> a.startsWith("UserKnownHostsFile="))
                        .findFirst()
                        .orElseThrow()
                        .substring("UserKnownHostsFile=".length())
                        .replace("\"", "");
        assertThat(Files.exists(Path.of(pin))).as("public temporary pin removed").isFalse();
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
                        .as("no plaintext token written to %s", file)
                        .doesNotContain(TOKEN);
            }
        }
    }

    @Test
    void anotherHostKeyRefusesBeforeTheTokenIsRead() {
        // The peer only accepts its own key; the card's different pin cannot authenticate it.
        hosts =
                List.of(
                        row(
                                new Organization.Card(
                                        "host.example.org",
                                        2222,
                                        publicKey(SigningKey.generate("another invented host")),
                                        0,
                                        0,
                                        0,
                                        "signed")));
        assertThat(run("--json")).as("SSH pin mismatch").isEqualTo(69);
        assertThat(sent.get()).as("no remote stdin was consumed").isNull();
        assertThat(out + err.toString()).as("no value on refusal").doesNotContain(TOKEN);
    }

    @Test
    void missingTokenNamesTheDeclarationBeforeConnecting() throws Exception {
        Files.writeString(
                context.paths().vault().credentialRegistry(),
                "credentials:\n  - match: https://forge.example.org/acme/\n    kind: token\n    source: vault\n    vault: absent\n");
        assertThat(run("--json")).as("no credential to hand over").isEqualTo(69);
        assertThat(err.toString())
                .as("an actionable declaration")
                .contains(
                        "porelo credentials declare https://forge.example.org/acme/",
                        "--kind token",
                        "--vault",
                        "porelo vault put");
        assertThat(runner.invocations()).as("no connection").isEmpty();
    }

    @Test
    void shutVaultRefusesBeforeReadingTheOrganizationOrConnecting() {
        open = false;
        ((OrgHostRefreshCommand)
                                cli.getSubcommands()
                                        .get("org")
                                        .getSubcommands()
                                        .get("host")
                                        .getSubcommands()
                                        .get("refresh")
                                        .getCommand())
                        .reader =
                machine -> {
                    throw new AssertionError(
                            "a shut vault must refuse before reading the organization");
                };
        assertThat(run("--json")).as("shut vault").isEqualTo(77);
        assertThat(err.toString()).as("how to open it").contains("porelo vault unlock");
        assertThat(runner.invocations()).as("no connection").isEmpty();
    }

    @Test
    void missingCardRefusesBeforeConnecting() {
        hosts = List.of(row(null));
        assertThat(run("--json")).as("no card").isEqualTo(65);
        assertThat(err.toString())
                .as("how to file it")
                .contains("porelo org host change", "--host-key");
        assertThat(runner.invocations()).as("no connection").isEmpty();
    }

    @Test
    void missingHostRefusesBeforeConnecting() {
        hosts = List.of();
        assertThat(run("--json")).as("no host").isEqualTo(66);
        assertThat(err.toString())
                .as("how to name it")
                .contains("porelo org --json", "porelo org host add");
        assertThat(runner.invocations()).as("no connection").isEmpty();
    }

    @Test
    void missingCardKeyRefusesBeforeConnecting() {
        hosts =
                List.of(
                        row(
                                new Organization.Card(
                                        "host.example.org", 2222, "", 0, 0, 0, "signed")));
        assertThat(run("--json")).as("no key").isEqualTo(69);
        assertThat(err.toString())
                .as("how to file the key")
                .contains("porelo org host change", "--host-key");
        assertThat(runner.invocations()).as("no connection").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"64,64", "65,65", "69,69", "73,73", "255,69", "23,70"})
    void mapsTheRemoteExitWithoutClaimingHandover(int remote, int expected) {
        runner.failing("porelo-host org refresh", remote, "porelo-host: setting is missing");
        assertThat(run("--json")).as("remote exit %s", remote).isEqualTo(expected);
        assertThat(((Map<?, ?>) Json.parse(out.toString())).get("handedOver"))
                .as("no success claim")
                .isEqualTo(false);
        assertThat(err.toString())
                .as("a failed refresh reports the safe host diagnostic only once")
                .containsOnlyOnce("setting is missing");
    }

    @ParameterizedTest
    @ValueSource(ints = {127, 126})
    void aHostWithoutPoreloHostIsNamedAsSuch(int remote) {
        // env's lines alone, and 70, read as porelo failing, not as a host without its package.
        runner.failing(
                "porelo-host org refresh",
                remote,
                "env: '/usr/sbin/porelo-host': No such file or directory");

        assertThat(run("--json")).as("remote exit %s", remote).isEqualTo(69);
        assertThat(err.toString())
                .as("what is missing, where, and what to do")
                .contains(
                        "/usr/sbin/porelo-host",
                        "on build",
                        "porelo-host collection 0.5.13 or later");
    }

    @Test
    void neitherHostOutputNorExceptionsCanEchoTheToken() {
        runner.answering(
                "porelo-host org refresh",
                STATUS.replace(
                        "\"refused\":null",
                        "\"refused\":\"" + "\\u0069" + TOKEN.substring(1) + "\""),
                TOKEN);
        assertThat(run("--json")).as("an invalid host answer").isEqualTo(70);
        assertThat(out + err.toString()).as("remote output withheld").doesNotContain(TOKEN);
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        explode = true;
        assertThat(run("--json")).as("exception contained").isEqualTo(70);
        assertThat(out + err.toString()).as("exception message withheld").doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"123456789", "true", "false"})
    void scalarTokenEchoesAreWithheld(String exposed) throws Exception {
        Files.delete(context.vault().path());
        context.vault().write(Map.of("central", new VaultEntry(exposed, "token")), share);
        runner.answering(
                "porelo-host org refresh",
                STATUS.replace("\"commit\":null", "\"commit\":" + exposed));
        assertThat(run("--json")).as("a token echoed as a JSON scalar is unsafe").isEqualTo(70);
        assertThat(((Map<?, ?>) Json.parse(out.toString())).get("answer"))
                .as("the host's echo never reaches the answer")
                .isNull();
    }

    @Test
    void aHostDiagnosticCannotEchoTheToken() {
        runner.answering("porelo-host org refresh", STATUS, TOKEN);
        assertThat(run("--json")).as("a safe status still answers").isZero();
        assertThat(out + err.toString()).as("stderr echo is withheld too").doesNotContain(TOKEN);
    }

    @Test
    void malformedSuccessNeverClaimsHandover() {
        runner.answering("porelo-host org refresh", "not JSON");
        assertThat(run("--json")).as("invalid protocol answer").isEqualTo(70);
        assertThat(((Map<?, ?>) Json.parse(out.toString())).get("handedOver"))
                .as("no false success")
                .isEqualTo(false);
    }
}
