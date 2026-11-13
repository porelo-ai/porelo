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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a transport borrows from the person's vault for one verb. Harm guarded: a person's token
 * that reaches a verb it was not lent for, lands with the transport's own secrets, or shows on a
 * command line, in a log or in a file; a shut vault read as "she holds no token", so a transport
 * that would have been lent one registers nothing and says the wrong thing; and a transport that
 * borrows the person's other credentials merely by naming them.
 */
class TransportLentTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private static final String NAME = "PORELO_MATRIX_REGISTRATION_TOKEN";

    private static final String PLAIN = "matrix-registration-token";

    /** The entry named for the server {@link #SETTINGS} names, the only one lent. */
    private static final String ENTRY = PLAIN + ".chat.example";

    /**
     * Long enough to be withheld from what a transport says, and recognizable wherever it leaks.
     */
    private static final String TOKEN = "lent-token-RECOGNIZABLE-1234";

    private static final Map<String, Object> SETTINGS =
            Map.of("homeserver", "https://chat.example:8448/base");

    /** A homeserver on a socket, as a tunnel to it is bound on a host. */
    private static final String SOCKET = "unix:/run/porelo/room.sock";

    /** A person's own homeserver on this machine's loopback, over plain http. */
    private static final String LOOPBACK = "http://127.0.0.1:8008";

    /** The entry named for {@link #LOOPBACK}. */
    private static final String LOOPBACK_ENTRY = PLAIN + ".http:127.0.0.1:8008";

    @TempDir Path dir;

    private final FakeCommandRunner fake = new FakeCommandRunner();

    @AfterEach
    void forgetTheAskedPassphrase() {
        PoreloContext.forgetAsked();
    }

    private PoreloContext context(final CommandRunner runner) {
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A vault that holds these entries, opened by a passphrase asked for. */
    private PoreloContext open(final CommandRunner runner, final Map<String, String> entries)
            throws IOException {
        final PoreloContext context = context(runner).asking(prompt -> PASSPHRASE.clone());
        write(context, entries);
        assertThat(context.openerAsking(false, new PrintWriter(new StringWriter())))
                .as("the vault opens for the test")
                .isPresent();
        return context;
    }

    /** A vault that exists, holds these entries, and nothing here can open. */
    private PoreloContext shut(final Map<String, String> entries) throws IOException {
        final PoreloContext context = context(fake);
        write(context, entries);
        assertThat(context.opener()).as("the vault is shut for the test").isEmpty();
        return context;
    }

    private static void write(final PoreloContext context, final Map<String, String> entries)
            throws IOException {
        Files.createDirectories(context.vault().path().getParent());
        final Map<String, VaultEntry> held = new LinkedHashMap<>();
        entries.forEach((name, value) -> held.put(name, VaultEntry.of(value)));
        context.vault().write(held, PASSPHRASE);
    }

    private void adapter(final PoreloContext context) throws IOException {
        final Path adapters =
                Files.createDirectories(
                        context.paths().messaging().transportDirectory().locations().getFirst());
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "matrix");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
    }

    /** The transport as it says of itself, with this {@code lent}, on its input. */
    private static String described(final String lent) {
        return "{\"scheme\":\"matrix\",\"secrets\":\"input\",\"lifecycle\":"
                + "[\"setup\",\"enroll\",\"retire\",\"part\",\"admit\",\"dismiss\",\"members\"],"
                + "\"credentials\":[{\"name\":\""
                + NAME
                + "\",\"as\":\"env\""
                + lent
                + "}]}";
    }

    private void transport(final PoreloContext context, final String describe) throws IOException {
        adapter(context);
        fake.answering(" describe", describe);
        fake.answering(" setup ", "{\"conversation\":\"!room\",\"reaches\":[\"chat.example\"]}");
        fake.answering(" enroll ", "{\"secrets\":{\"TASK\":\"t-1\"}}");
        fake.answering(" retire ", "{}");
        fake.answering(" part ", "{}");
        fake.answering(" admit ", "{}");
        fake.answering(" dismiss ", "{}");
        fake.answering(" members ", "{}");
    }

    private static TransportLifecycle lifecycle(final PoreloContext context) {
        return new TransportLifecycle(context, context.paths().messaging().transportDirectory());
    }

    /** What a verb read as its secrets on its standard input; empty when it read none. */
    private Map<String, String> secretsOf(final String verb) {
        final Map<String, String> found = new LinkedHashMap<>();
        final String input = fake.only(verb).input();
        if (input != null
                && Json.parse(input) instanceof Map<?, ?> read
                && read.get("secrets") instanceof Map<?, ?> secrets) {
            secrets.forEach(
                    (name, value) -> found.put(String.valueOf(name), String.valueOf(value)));
        }
        return found;
    }

    private List<String> verbsRun() {
        return fake.lines().stream().filter(line -> !line.contains(" describe")).toList();
    }

    @Test
    void aVerbItListsReadsTheValueOnItsInputUnderTheNameItGave() throws Exception {
        final PoreloContext context = open(fake, Map.of(ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"setup\",\"enroll\"]"));

        lifecycle(context).setup("matrix", "p", SETTINGS);
        lifecycle(context).enroll("matrix", "p", "porelo-p-t", SETTINGS);

        assertThat(secretsOf(" setup "))
                .as("setup reads it under the name it gave")
                .containsEntry(NAME, TOKEN);
        assertThat(secretsOf(" enroll "))
                .as("enroll, the next verb it lists, is lent it again")
                .containsEntry(NAME, TOKEN);
    }

    @Test
    void anEntryPutWithTheCommandUnderTheServersNameIsLent() throws Exception {

        // The name has dots in it: refused by the command, a person could not lend a token at all.
        final PoreloContext context = open(fake, Map.of());
        transport(context, described(",\"lent\":[\"setup\"]"));
        final Path value = dir.resolve("value");
        Files.writeString(value, TOKEN, StandardCharsets.UTF_8);
        final picocli.CommandLine put =
                new picocli.CommandLine(VaultPutCommand.class, new PoreloFactory(context));
        final StringWriter printed = new StringWriter();
        put.setOut(new PrintWriter(printed));
        put.setErr(new PrintWriter(printed));

        final int code = put.execute(ENTRY, "--from-file", value.toString());
        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(code).as("porelo vault put %s: %s", ENTRY, printed).isZero();
        assertThat(printed.toString()).as("printed").doesNotContain(TOKEN);
        assertThat(secretsOf(" setup ")).as("what setup read").containsEntry(NAME, TOKEN);
    }

    @Test
    void aVerbItDoesNotListIsNotGivenItAndDoesNotAskTheVault() throws Exception {
        final PoreloContext opened = open(fake, Map.of(ENTRY, TOKEN));
        transport(opened, described(",\"lent\":[\"setup\"]"));
        lifecycle(opened).part("matrix", "p", "alice", SETTINGS, false);
        assertThat(secretsOf(" part "))
                .as("a verb not listed is lent nothing, the vault being open")
                .doesNotContainKey(NAME);
        PoreloContext.forgetAsked();

        final PoreloContext shut = context(fake);
        assertThat(shut.opener()).as("the vault is shut for the second part").isEmpty();
        lifecycle(shut).part("matrix", "p", "bob", SETTINGS, false);
        assertThat(fake.lines())
                .as("a shut vault is nothing to a verb not listed: it was never asked")
                .anyMatch(line -> line.contains(" part ") && line.contains("bob"));
    }

    @Test
    void theEntryNamedForTheServerIsLent() throws Exception {
        final PoreloContext context =
                open(fake, Map.of(ENTRY, TOKEN, PLAIN, "plain-never-12345678"));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(secretsOf(" setup ")).containsEntry(NAME, TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"matrix-registration-token", "matrix-registration-token.other.example"})
    void aPlainEntryOrAnotherServersGoesToNoServerAProjectsFileNames(final String entry)
            throws Exception {
        // A project's file names the homeserver: a plain entry would go to any server its admin
        // chose, and that server would hold the person's registration token.
        final PoreloContext context = open(fake, Map.of(entry, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(secretsOf(" setup ")).as("%s stays in the vault", entry).doesNotContainKey(NAME);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://evil.example?@chat.example/",
                "https://evil.example#@chat.example/",
                "https://evil.example\\@chat.example/",
                "https://someone@chat.example/",
                "http://chat.example/",
            })
    void aHomeserverAClientCouldReadAsAnotherHostLendsNothing(final String homeserver)
            throws Exception {
        // Read as chat.example here and as evil.example by the client that connects, it handed
        // that server the token the person holds for chat.example.
        final PoreloContext context = open(fake, Map.of(ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", homeserver));

        assertThat(secretsOf(" setup ")).as(homeserver).doesNotContainKey(NAME);
    }

    @Test
    void aSettingWithNoHostLendsNothing() throws Exception {
        final PoreloContext context = open(fake, Map.of(PLAIN, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", "unix:///run/hs.sock"));

        assertThat(secretsOf(" setup "))
                .as("no host, and no socket either")
                .doesNotContainKey(NAME);
    }

    @Test
    void aTokenStoredForOneSocketReachesThatSocket() throws Exception {
        final PoreloContext context = open(fake, Map.of(PLAIN + "." + SOCKET, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", SOCKET));

        assertThat(secretsOf(" setup "))
                .as("the socket it was stored for")
                .containsEntry(NAME, TOKEN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unix:/run/porelo/other.sock",
                "unix:/run/porelo/room.sock.other",
                "unix:/run/porelo/ROOM.sock",
                "unix:/run/porelo/./room.sock",
                "unix:/run/porelo/../porelo/room.sock",
                "unix:/run/porelo//room.sock",
                "unix:///run/porelo/room.sock",
                "unix:run/porelo/room.sock",
            })
    void aTokenForOneSocketDoesNotReachAnotherOrThatSocketSpelledAnotherWay(final String homeserver)
            throws Exception {
        // A project's file names the socket: whoever listens on another path would hold the
        // person's registration token.
        final PoreloContext context = open(fake, Map.of(PLAIN + "." + SOCKET, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", homeserver));

        assertThat(secretsOf(" setup ")).as(homeserver).doesNotContainKey(NAME);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unix:/run/porelo/./room.sock",
                "unix:/run/porelo/../porelo/room.sock",
                "unix:/run/porelo//room.sock",
                "unix:///run/porelo/room.sock",
                "unix:run/porelo/room.sock",
                "unix:/run/porelo/room.sock/",
                "unix:/run/porelo/room%2Esock",
            })
    void anAddressNotPlainlyOneFileLendsNothingEvenUnderItsOwnName(final String homeserver)
            throws Exception {
        // A person told such a name could store it, and a client could read the address as a
        // different file than the one it names.
        final PoreloContext context = open(fake, Map.of(PLAIN + "." + homeserver, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", homeserver));

        assertThat(secretsOf(" setup ")).as(homeserver).doesNotContainKey(NAME);
    }

    @Test
    void aServersTokenDoesNotReachASocketAndASocketsDoesNotReachAServer() throws Exception {
        // The room's name says nothing of where a socket leads, so a server's entry stays with
        // the server, whatever the file claims.
        final PoreloContext serversOnly = open(fake, Map.of(ENTRY, TOKEN, PLAIN, TOKEN));
        transport(serversOnly, described(",\"lent\":[\"setup\"]"));
        lifecycle(serversOnly)
                .setup(
                        "matrix",
                        "p",
                        Map.of("homeserver", SOCKET, "room", "#project:chat.example"));
        assertThat(secretsOf(" setup "))
                .as("a server's token, asked by a socket")
                .doesNotContainKey(NAME);
        PoreloContext.forgetAsked();

        final FakeCommandRunner second = new FakeCommandRunner();
        final PoreloContext socketsOnly = open(second, Map.of(PLAIN + "." + SOCKET, TOKEN));
        adapter(socketsOnly);
        second.answering(" describe", described(",\"lent\":[\"setup\"]"));
        second.answering(" setup ", "{\"conversation\":\"!room\",\"reaches\":[\"chat.example\"]}");
        lifecycle(socketsOnly).setup("matrix", "p", SETTINGS);
        assertThat(second.only(" setup ").input())
                .as("a socket's token, asked by a server")
                .doesNotContain(TOKEN);
    }

    @Test
    void anEntryPutWithTheCommandUnderTheSocketsNameIsLent() throws Exception {
        // The name holds a colon and slashes: refused or hidden by the vault, a person could not
        // lend a token over a socket at all.
        final PoreloContext context = open(fake, Map.of());
        transport(context, described(",\"lent\":[\"setup\"]"));
        final Path value = dir.resolve("value");
        Files.writeString(value, TOKEN, StandardCharsets.UTF_8);
        final picocli.CommandLine put =
                new picocli.CommandLine(VaultPutCommand.class, new PoreloFactory(context));
        final StringWriter printed = new StringWriter();
        put.setOut(new PrintWriter(printed));
        put.setErr(new PrintWriter(printed));

        final int code = put.execute(PLAIN + "." + SOCKET, "--from-file", value.toString());
        lifecycle(context).setup("matrix", "p", Map.of("homeserver", SOCKET));

        assertThat(code).as("porelo vault put: %s", printed).isZero();
        assertThat(printed.toString()).as("printed").doesNotContain(TOKEN);
        assertThat(secretsOf(" setup ")).as("what setup read").containsEntry(NAME, TOKEN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:8008",
                "http://127.0.0.1:8008/",
                "http://127.0.0.1:8008/base",
                "HTTP://127.0.0.1:8008"
            })
    void aTokenStoredForALoopbackHomeserverReachesIt(final String homeserver) throws Exception {
        // A person's own homeserver on this machine: refused, it could not be named for a project.
        final PoreloContext context = open(fake, Map.of(LOOPBACK_ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", homeserver));

        assertThat(secretsOf(" setup ")).as(homeserver).containsEntry(NAME, TOKEN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:8009",
                "http://127.0.0.2:8008",
                "http://localhost:8008",
                "https://127.0.0.1:8008",
                "http://chat.example:8008",
                "http://user@127.0.0.1:8008",
                "http://127.0.0.1:8008@chat.example",
            })
    void aLoopbackHomeserversTokenReachesNoOtherServerOrPort(final String homeserver)
            throws Exception {
        // Another port of loopback is another listener, and plain http elsewhere is read on the
        // way: either would hold the token the person stored for their own homeserver.
        final PoreloContext context = open(fake, Map.of(LOOPBACK_ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", homeserver));

        assertThat(secretsOf(" setup ")).as(homeserver).doesNotContainKey(NAME);
    }

    @Test
    void anHttpsServersTokenDoesNotReachLoopbackHttpOfTheSameHost() throws Exception {
        // Stored for https on 127.0.0.1, which proves the server; plain http proves nothing.
        final PoreloContext context = open(fake, Map.of(PLAIN + ".127.0.0.1", TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", LOOPBACK));

        assertThat(secretsOf(" setup ")).doesNotContainKey(NAME);
    }

    @Test
    void anEntryPutWithTheCommandUnderTheLoopbackNameIsLent() throws Exception {
        // The name holds colons: refused or hidden by the vault, a person could not lend a token to
        // their own homeserver at all.
        final PoreloContext context = open(fake, Map.of());
        transport(context, described(",\"lent\":[\"setup\"]"));
        final Path value = dir.resolve("value");
        Files.writeString(value, TOKEN, StandardCharsets.UTF_8);
        final picocli.CommandLine put =
                new picocli.CommandLine(VaultPutCommand.class, new PoreloFactory(context));
        final StringWriter printed = new StringWriter();
        put.setOut(new PrintWriter(printed));
        put.setErr(new PrintWriter(printed));

        final int code = put.execute(LOOPBACK_ENTRY, "--from-file", value.toString());
        lifecycle(context).setup("matrix", "p", Map.of("homeserver", LOOPBACK));

        assertThat(code).as("porelo vault put: %s", printed).isZero();
        assertThat(printed.toString()).as("printed").doesNotContain(TOKEN);
        assertThat(secretsOf(" setup ")).as("what setup read").containsEntry(NAME, TOKEN);
    }

    /** Every way a shut vault reaches a verb: past what each one checks first. */
    private static void verb(final TransportLifecycle lifecycle, final String verb)
            throws TransportLifecycle.Refused {
        switch (verb) {
            case "setup" -> lifecycle.setup("matrix", "p", SETTINGS);
            case "enroll" -> lifecycle.enroll("matrix", "p", "porelo-p-t", SETTINGS);
            case "retire" -> lifecycle.retire("matrix", "p", "porelo-p-t", SETTINGS);
            case "part" -> lifecycle.part("matrix", "p", "alice", SETTINGS, false);
            case "admit" -> lifecycle.admit("matrix", "p", "m", SETTINGS);
            case "dismiss" -> lifecycle.dismiss("matrix", "p", "m", SETTINGS);
            default -> throw new IllegalArgumentException(verb);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"setup", "enroll", "retire", "part", "admit", "dismiss"})
    void aShutVaultAnswers77AndTheTransportIsNotRun(final String name) throws Exception {
        final PoreloContext context = shut(Map.of(ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"" + name + "\"]"));

        assertThatThrownBy(() -> verb(lifecycle(context), name))
                .as("a shut vault is not an empty one")
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        refused -> assertThat(refused.code()).isEqualTo(77));
        assertThat(verbsRun()).as("%s was never run", name).isEmpty();
    }

    @Test
    void aVaultThatCannotBeReadAnswers77RatherThanLendingNothing() throws Exception {
        // Absence and failure differ: read as absence, the transport would be told to do without.
        final PoreloContext context = open(fake, Map.of(ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));
        final byte[] sealed = Files.readAllBytes(context.vault().path());
        sealed[sealed.length - 1] ^= 1;
        Files.write(context.vault().path(), sealed);

        assertThatThrownBy(() -> lifecycle(context).setup("matrix", "p", SETTINGS))
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        refused -> assertThat(refused.code()).isEqualTo(77));
        assertThat(verbsRun()).as("setup was never run").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                ",\"lent\":\"setup\"",
                ",\"lent\":{\"setup\":true}",
                ",\"lent\":[1,2]",
                ",\"lent\":[\"setup\",1]",
                ",\"lent\":[]"
            })
    void aTransportThatListsNoLentVerbRunsExactlyAsToday(final String lent) throws Exception {
        final PoreloContext context = open(fake, Map.of(ENTRY, TOKEN));
        transport(context, described(lent));

        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(Json.parse(fake.only(" setup ").input()))
                .as("its input holds its settings, and no secrets")
                .isEqualTo(Map.of("settings", SETTINGS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"lent\":\"setup\"", ",\"lent\":[\"setup\",1]"})
    void aTransportThatListsNoLentVerbIsRefusedAShutVaultAsToday(final String lent)
            throws Exception {
        final PoreloContext context = shut(Map.of(ENTRY, TOKEN));
        transport(context, described(lent));

        assertThatThrownBy(() -> lifecycle(context).setup("matrix", "p", SETTINGS))
                .as("what it hands back has nowhere to go: the same refusal as before lending")
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        refused -> assertThat(refused.code()).isZero());
    }

    @Test
    void aTransportTakingItsSecretsInItsEnvironmentIsLentNothing() throws Exception {
        // There another process of the account reads them, in /proc/<pid>/environ.
        final PoreloContext context = open(fake, Map.of(ENTRY, TOKEN));
        transport(
                context, described(",\"lent\":[\"setup\"]").replace("\"secrets\":\"input\",", ""));

        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(fake.only(" setup ").environment())
                .as("the person's token is not put where another process can read it")
                .doesNotContainKey(NAME);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ANTHROPIC",
                "PORELO_OPENAI",
                "GIT_TOKEN",
                "git.token",
                "PORELO_",
                PLAIN + ".other.example"
            })
    void aTransportBorrowsOnlyWhatIsNamedForItsOwnScheme(final String name) throws Exception {
        // 'porelo vault put anthropic' and 'git.token' are the person's other credentials: naming
        // one in describe must not be enough to be handed it.
        final PoreloContext context =
                open(
                        fake,
                        Map.of(
                                "anthropic.chat.example",
                                TOKEN,
                                "openai.chat.example",
                                TOKEN,
                                "git-token.chat.example",
                                TOKEN,
                                "git.token.chat.example",
                                TOKEN,
                                PLAIN + ".other.example",
                                TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]").replace(NAME, name));

        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(secretsOf(" setup ")).as("%s lends nothing", name).isEmpty();
    }

    @Test
    void aTransportCannotBorrowWhatThisMachineKeepsForItByNamingIt() throws Exception {
        final PoreloContext context =
                open(fake, Map.of(VaultNames.TRANSPORT_PREFIX + "matrix/account", "{\"A\":\"b\"}"));
        transport(
                context,
                described(",\"lent\":[\"setup\"]")
                        .replace(NAME, VaultNames.TRANSPORT_PREFIX + "matrix/account"));

        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(secretsOf(" setup "))
                .as("only the transport's own account secrets, handed as they always were")
                .containsOnlyKeys("A");
    }

    @Test
    void whatATransportPrintsBackOfItIsNotKeptWithItsOwnSecrets() throws Exception {
        final PoreloContext context = open(fake, Map.of(ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));
        fake.answering(
                " setup ",
                "{\"conversation\":\"!room\",\"reaches\":[],"
                        + "\"secrets\":{\""
                        + NAME
                        + "\":\""
                        + TOKEN
                        + "\",\"RENAMED\":\""
                        + TOKEN
                        + "\",\"NESTED\":{\"t\":\""
                        + TOKEN
                        + "\"},\"HEADER\":\"Bearer "
                        + TOKEN
                        + "\",\"POLLER\":\"p-1\"},"
                        + "\"account\":{\""
                        + NAME
                        + "\":\""
                        + TOKEN
                        + "\",\"ADMIN\":\"a-1\"}}");

        lifecycle(context).setup("matrix", "p", SETTINGS);

        final Map<String, VaultEntry> held = context.vault().read(PASSPHRASE);
        assertThat(held.get(ENTRY).value()).as("it stays where the person put it").isEqualTo(TOKEN);
        assertThat(held.entrySet())
                .as("and nowhere else in the vault")
                .filteredOn(entry -> !ENTRY.equals(entry.getKey()))
                .allSatisfy(entry -> assertThat(entry.getValue().value()).doesNotContain(TOKEN));
        assertThat(held.get(VaultNames.TRANSPORT_PREFIX + "matrix/project/p").value())
                .as("what the transport made of its own is kept as ever")
                .contains("p-1");
        assertThat(held.get(VaultNames.TRANSPORT_PREFIX + "matrix/account").value())
                .contains("a-1");
    }

    /**
     * A transport that is really started: writes what the account's other processes could read of
     * it where the test finds it, keeps what it read, and repeats its input on its error when asked
     * to part.
     */
    private Path standIn(final PoreloContext context) throws IOException {
        final Path adapters =
                Files.createDirectories(
                        context.paths().messaging().transportDirectory().locations().getFirst());
        final Path script = adapters.resolve(TransportDirectory.PREFIX + "matrix");
        final Path seen = Files.createDirectories(dir.resolve("seen"));
        Files.writeString(
                script,
                """
                #!/bin/sh
                if [ "$1" = describe ]; then
                  echo '%s'
                  exit 0
                fi
                tr '\\0' ' ' < /proc/$$/cmdline >> "%s/arguments"
                tr '\\0' '\\n' < /proc/$$/environ >> "%s/environment"
                cat > "%s/input"
                if [ "$1" = part ]; then
                  cat "%s/input" >&2
                  exit 78
                fi
                echo '{"conversation":"!room","reaches":["chat.example"]}'
                """
                        .formatted(
                                described(",\"lent\":[\"setup\",\"part\"]"),
                                seen,
                                seen,
                                seen,
                                seen),
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return seen;
    }

    private static boolean holds(final Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                    .contains(TOKEN);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @Test
    void theValueReachesTheVerbsInputAndNoArgumentEnvironmentOutputOrFile() throws Exception {
        final List<Command> ran = new java.util.ArrayList<>();
        final ProcessCommandRunner real = new ProcessCommandRunner(Duration.ofSeconds(20));
        final PoreloContext context =
                open(
                        command -> {
                            ran.add(command);
                            return real.run(command);
                        },
                        Map.of(ENTRY, TOKEN));
        final Path seen = standIn(context);

        lifecycle(context).setup("matrix", "p", SETTINGS);

        assertThat(Files.readString(seen.resolve("input")))
                .as("the check below looks for a value that did travel")
                .contains(TOKEN);
        assertThatThrownBy(() -> lifecycle(context).part("matrix", "p", "alice", SETTINGS, false))
                .as("what a refusing transport says of it is what a person and a log are shown")
                .isInstanceOfSatisfying(
                        TransportLifecycle.Refused.class,
                        refused -> {
                            assertThat(refused.getMessage())
                                    .contains("[withheld]")
                                    .doesNotContain(TOKEN);
                            assertThat(refused.cause()).doesNotContain(TOKEN);
                        });
        assertThat(Files.readString(seen.resolve("arguments")))
                .as("the verb's real command line")
                .contains(" setup ")
                .doesNotContain(TOKEN);
        assertThat(Files.readString(seen.resolve("environment")))
                .as("what another process of the account reads")
                .isNotEmpty()
                .doesNotContain(TOKEN);
        assertThat(ran)
                .as("the recorded command lines")
                .isNotEmpty()
                .allSatisfy(command -> assertThat(command.describe()).doesNotContain(TOKEN));
        try (Stream<Path> files = Files.walk(context.paths().xdg().state())) {
            final List<Path> state = files.filter(Files::isRegularFile).toList();
            assertThat(state)
                    .as("the walk reads what setup remembered, not an empty directory")
                    .isNotEmpty()
                    .noneMatch(TransportLentTest::holds);
        }
        try (Stream<Path> files = Files.walk(dir.resolve("data"))) {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .as("nor anything else Porelo wrote; the vault holds it encrypted")
                    .noneMatch(TransportLentTest::holds);
        }
    }

    private int command(final PoreloContext context, final String... arguments) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final picocli.CommandLine cli =
                new picocli.CommandLine(new TalkCommand(), new PoreloFactory(context));
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final int code = cli.execute(arguments);
        assertThat(out + err.toString()).as("printed").doesNotContain(TOKEN);
        return code;
    }

    private void registered(final PoreloContext context) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: p\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n"
                        + "mail:\n  transports:\n    matrix:\n"
                        + "      homeserver: https://chat.example\n"
                        + "  peers:\n    people: {address: 'matrix:', trust: external}\n",
                StandardCharsets.UTF_8);
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("p", file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"join", "admit"})
    void aShutVaultExits77OnTheCommandLine(final String talk) throws Exception {
        final PoreloContext context = shut(Map.of(ENTRY, TOKEN));
        registered(context);
        transport(context, described(",\"lent\":[\"setup\",\"admit\"]"));
        final String[] arguments =
                switch (talk) {
                    case "join" -> new String[] {"join", "p", "alice"};
                    default ->
                            new String[] {
                                "admit",
                                "p",
                                "other-machine",
                                "--into",
                                dir.resolve("admission").toString()
                            };
                };

        assertThat(command(context, arguments))
                .as("porelo talk %s, with the vault shut", talk)
                .isEqualTo(77);
        assertThat(verbsRun()).as("the transport was not run").isEmpty();
    }

    @Test
    void theEntryAProjectShowsIsTheEntryAServerOfMixedCaseIsLent() throws Exception {
        // Shown one way and looked for another, a person holds the token under the name they were
        // told, and the transport goes without it.
        final Map<String, Object> settings = Map.of("homeserver", "https://Chat.EXAMPLE:8448/base");
        final org.fuin.porelo.core.project.Project read =
                org.fuin.porelo.core.project.ProjectReader.read(
                        new java.io.StringReader(
                                """
                        project:
                          name: "p"
                          security_class: "guarded"
                        image:
                          from: "ubuntu:24.04"
                        mail:
                          transports:
                            matrix:
                              homeserver: "https://Chat.EXAMPLE:8448/base"
                        """),
                        "project.yml");
        assertThat(ProjectShowCommand.registrationEntry(read)).isEqualTo(ENTRY);
        final PoreloContext context = open(fake, Map.of(ENTRY, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", settings);

        assertThat(secretsOf(" setup ")).containsEntry(NAME, TOKEN);
    }

    @Test
    void theEntryAProjectShowsForASocketIsTheEntryItIsLent() throws Exception {
        // Shown one way and looked for another, a person holds the token under the name they were
        // told, and the transport goes without it.
        final org.fuin.porelo.core.project.Project read =
                org.fuin.porelo.core.project.ProjectReader.read(
                        new java.io.StringReader(
                                """
                        project:
                          name: "p"
                          security_class: "guarded"
                        image:
                          from: "ubuntu:24.04"
                        mail:
                          transports:
                            matrix:
                              homeserver: "%s"
                        """
                                        .formatted(SOCKET)),
                        "project.yml");
        final String shown = ProjectShowCommand.registrationEntry(read);
        assertThat(shown).as("what the project shows").isEqualTo(PLAIN + "." + SOCKET);
        final PoreloContext context = open(fake, Map.of(shown, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", SOCKET));

        assertThat(secretsOf(" setup ")).containsEntry(NAME, TOKEN);
    }

    @Test
    void theEntryAProjectShowsForALoopbackHomeserverIsTheEntryItIsLent() throws Exception {
        // Shown one way and looked for another, a person holds the token under the name they were
        // told, and the transport goes without it.
        final org.fuin.porelo.core.project.Project read =
                org.fuin.porelo.core.project.ProjectReader.read(
                        new java.io.StringReader(
                                """
                        project:
                          name: "p"
                          security_class: "guarded"
                        image:
                          from: "ubuntu:24.04"
                        mail:
                          transports:
                            matrix:
                              homeserver: "http://LocalHost:8008/"
                        """),
                        "project.yml");
        final String shown = ProjectShowCommand.registrationEntry(read);
        assertThat(shown).as("what the project shows").isEqualTo(PLAIN + ".http:localhost:8008");
        final PoreloContext context = open(fake, Map.of(shown, TOKEN));
        transport(context, described(",\"lent\":[\"setup\"]"));

        lifecycle(context).setup("matrix", "p", Map.of("homeserver", "http://LocalHost:8008/"));

        assertThat(secretsOf(" setup ")).containsEntry(NAME, TOKEN);
    }
}
