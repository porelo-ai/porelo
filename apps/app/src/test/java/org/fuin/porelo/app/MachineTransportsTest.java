package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for the machines a machine admits: only the machine let in is named, an offline project
 * admits nobody, and a secret never lands where a person or a log can read it.
 */
class MachineTransportsTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    /** A secret long enough to be withheld, and recognizable wherever it leaks. */
    private static final String TOKEN = "machine-token-SECRET-1234";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private @Nullable PoreloContext used;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        used = context;
        return context;
    }

    @AfterEach
    void forgetWhatTheKeyringHolds() {
        PoreloContext.forgetAsked();
        if (used != null && KernelKeyring.available()) {
            new KernelKeyring(used.paths().vault().vaultKeyringKey()).forget();
            VaultShare.forget(used.paths());
        }
    }

    /**
     * A vault holding the transport's account, opened by a passphrase asked for rather than the
     * kernel keyring, so these tests run where the keyring is refused too, a task among them.
     */
    private PoreloContext unlockedVault() throws IOException {
        return unlockedVault(Map.of());
    }

    /** A vault as {@link #unlockedVault()}, holding the project's secrets {@code project} too. */
    private PoreloContext unlockedVault(final Map<String, String> project) throws IOException {
        final PoreloContext context = context().asking(prompt -> PASSPHRASE.clone());
        used = context;
        Files.createDirectories(context.vault().path().getParent());
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        entries.put(
                VaultNames.TRANSPORT_PREFIX + "room/account", VaultEntry.of("{\"ADMIN\":\"a-1\"}"));
        if (!project.isEmpty()) {
            entries.put(
                    VaultNames.TRANSPORT_PREFIX + "room/project/p",
                    VaultEntry.of(org.fuin.porelo.wire.Json.write(project)));
        }
        context.vault().write(entries, PASSPHRASE);
        assertThat(context.openerAsking(false, new PrintWriter(new StringWriter())))
                .as("the vault opens for the test")
                .isPresent();
        return context;
    }

    /** A project registered here whose conversation is on the transport {@code room}. */
    private Project project(final PoreloContext context, final String securityClass)
            throws IOException {
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        final Path file = dir.resolve("p-project.yml");
        Files.writeString(
                file,
                "project:\n  name: \"p\"\n  security_class: \""
                        + securityClass
                        + "\"\n"
                        + "image:\n  base_image: \"ubuntu:24.04\"\n"
                        + "mail:\n  transports:\n    room:\n      homeserver: \"https://project.example\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("data/porelo/projects").resolve("p"),
                file + "\n",
                StandardCharsets.UTF_8);
        return GateSupport.byName(context, "p");
    }

    /** The transport {@code room}, which lets other machines in. */
    private void transport() throws IOException {
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering(
                " describe",
                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"settings\",\"admit\",\"dismiss\","
                        + "\"clear\"],\"takes\":[\"machine\"]}");
        runner.answering(" settings", "{\"refused\":[],\"warnings\":[]}");
    }

    private Command only(final String verb) {
        final List<Command> found =
                runner.invocations().stream()
                        .filter(each -> each.arguments().contains(verb))
                        .toList();
        assertThat(found).as("exactly one '%s' was run", verb).hasSize(1);
        return found.getFirst();
    }

    private int execute(final PoreloContext context, final String... arguments) {
        final CommandLine command = PoreloCli.commandLine(context);
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        return command.execute(arguments);
    }

    @Test
    void aMachinesNameThatReadsAsAnOptionIsRefused() throws Exception {
        final PoreloContext context = unlockedVault();
        transport();
        final Project project = project(context, "guarded");

        assertThatThrownBy(() -> MachineTransports.admit(context, project, "--help"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("not a machine's name");
        assertThatThrownBy(() -> MachineTransports.dismiss(context, project, "-x"))
                .isInstanceOf(Conversations.Refused.class);
        assertThat(runner.lines())
                .noneMatch(line -> line.contains(" admit") || line.contains(" dismiss"));
    }

    @Test
    void anOfflineProjectAdmitsNobody() throws Exception {

        // An offline project's messages never leave this machine.
        final PoreloContext context = context();
        transport();
        final Project project = project(context, "offline");

        assertThatThrownBy(() -> MachineTransports.admit(context, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("offline");
        assertThatThrownBy(() -> MachineTransports.dismiss(context, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("offline");
        assertThat(runner.lines()).noneMatch(line -> line.contains(" admit"));
    }

    @Test
    void anOfflineSetupReachingAwayIsRefusedOnEveryPathNotOnlyAtEnroll() throws Exception {

        // Set up through the daemon or a join, it was checked nowhere: an offline project's
        // conversation could reach another server and the room was remembered.
        final PoreloContext context = unlockedVault();
        transport();
        runner.answering(
                " setup",
                "{\"conversation\":\"!room\",\"reaches\":[\"machine.example\"],"
                        + "\"secrets\":{\"POLLER\":\"poll-12345678\"}}");
        final Project project = project(context, "offline");

        assertThatThrownBy(() -> new TaskConversations(context).setUp(project))
                .isInstanceOf(TransportLifecycle.Refused.class)
                .hasMessageContaining("machine.example");
        assertThat(dir.resolve("state/porelo/transport/room/p.json"))
                .as("no room remembered from a refused setup")
                .doesNotExist();
        assertThat(
                        new TransportLifecycle(
                                        context, context.paths().messaging().transportDirectory())
                                .secrets("room", "project/p"))
                .as("no secret kept that would start polling the server elsewhere")
                .isEmpty();
    }

    @Test
    void admitNamesOnlyTheMachineLetInAndWritesItsSecretToANewFileOfTheOwners() throws Exception {

        // This machine's own name appended after it would let this machine in instead; a token
        // printed to the terminal would sit in its scrollback.
        final PoreloContext context = unlockedVault();
        transport();
        runner.answering(
                " admit",
                "{\"admission\":{\"machine\":\"host2\",\"room\":\"!r\"},"
                        + "\"secrets\":{\"registration_token\":\""
                        + TOKEN
                        + "\"}}");
        project(context, "guarded");
        final Path into = dir.resolve("admission.json");

        final int code = execute(context, "talk", "admit", "p", "host2", "--into", into.toString());

        assertThat(code).as(err.toString()).isZero();
        final List<String> arguments = only("admit").arguments();
        assertThat(arguments.subList(arguments.indexOf("admit"), arguments.size()))
                .as("the admitted machine, and no other --machine")
                .containsExactly("admit", "--project", "p", "--machine", "host2");
        assertThat(out.toString() + err).as("no secret on the terminal").doesNotContain(TOKEN);
        assertThat(Files.readString(into)).as("the secret in the file").contains(TOKEN);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(into)))
                .isEqualTo("rw-------");
        assertThat(execute(context, "talk", "admit", "p", "host2", "--into", into.toString()))
                .as("an existing file is never replaced")
                .isEqualTo(73);
        only("admit");
        assertThat(
                        execute(
                                context,
                                "talk",
                                "admit",
                                "p",
                                "host3",
                                "--into",
                                dir.resolve("none/a.json").toString()))
                .as("nowhere to write it")
                .isEqualTo(73);
        assertThat(runner.lines())
                .as("no token is minted that nobody could keep")
                .noneMatch(line -> line.contains("host3"));
        try (var left = Files.list(dir)) {
            assertThat(left.map(each -> each.getFileName().toString()))
                    .as("nothing staged is left behind")
                    .noneMatch(name -> name.startsWith(".admission"));
        }
    }

    @Test
    void aRefusalsMessageWithholdsTheSecretsTheTransportWasGiven() throws Exception {

        // The message is what every caller prints; a transport echoing its environment put the
        // secret on the terminal.
        final PoreloContext context = unlockedVault(Map.of("ROOM_TOKEN", TOKEN));
        transport();
        runner.failing(" setup", 78, "cannot register with token " + TOKEN);
        project(context, "guarded");
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());

        assertThatThrownBy(
                        () ->
                                lifecycle.setup(
                                        "room",
                                        "p",
                                        Map.of("homeserver", "https://project.example"),
                                        false))
                .isInstanceOf(TransportLifecycle.Refused.class)
                .satisfies(
                        ex ->
                                assertThat(ex.getMessage())
                                        .as("the secret withheld from the message")
                                        .doesNotContain(TOKEN)
                                        .contains("[withheld]"));
    }

    @Test
    void aSecretSplitByAnEscapeSequenceOrPrintedBeforeFailingIsWithheldToo() throws Exception {

        // Redacted before it was cleaned, a secret split by an escape sequence was whole again in
        // the cause; a password a failing verb printed was not among the secrets it was given.
        final PoreloContext context = unlockedVault(Map.of("ROOM_TOKEN", TOKEN));
        transport();
        runner.failing(
                " setup",
                78,
                "{\"login\":{\"password\":\"pw-SECRET-98765\"}}",
                "cannot register with machine-token-\u001b[31mSECRET-1234 and pw-SECRET-98765");
        project(context, "guarded");
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());

        assertThatThrownBy(
                        () ->
                                lifecycle.setup(
                                        "room",
                                        "p",
                                        Map.of("homeserver", "https://project.example"),
                                        false))
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        ex -> {
                            assertThat(ex.getMessage() + ex.cause())
                                    .doesNotContain("SECRET")
                                    .doesNotContain("\u001b");
                        });
    }

    @Test
    void aTransportThatCannotRunIsARefusalAndAdmitSaysItNeedsTheVault() throws Exception {
        final PoreloContext shut = context();
        transport();
        final Project project = project(shut, "guarded");

        assertThatThrownBy(() -> MachineTransports.admit(shut, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("needs the secrets it keeps");

        final PoreloContext context = unlockedVault();
        runner.timingOut(" dismiss ");
        assertThatThrownBy(() -> MachineTransports.dismiss(context, project, "host2"))
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("could not dismiss");
    }

    @Test
    void aNameTheFileSystemRefusesIsFoundBeforeATokenIsMinted() throws Exception {
        final PoreloContext context = unlockedVault();
        transport();
        project(context, "guarded");

        assertThat(
                        execute(
                                context,
                                "talk",
                                "admit",
                                "p",
                                "host2",
                                "--into",
                                dir.resolve("x".repeat(300) + ".json").toString()))
                .isEqualTo(73);
        assertThat(runner.lines()).noneMatch(line -> line.contains(" admit"));
        try (var left = Files.list(dir)) {
            assertThat(left.map(each -> each.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".admission"));
        }
    }
}
