package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.PrintStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.ecosystem.api.InstalledEcosystem.Answer;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledEcosystemTest {

    private static final Path HOME = Path.of("/people/one");

    @TempDir Path directory;

    @Test
    void asksAPluginOverItsSocketAndHearsEveryAnswerAsItWasGiven() throws IOException {
        try (InstalledEcosystem plugin = new InstalledEcosystem(fake(), run())) {

            assertThat(plugin.ecosystem()).isEqualTo("fake");

            final Detection detection = plugin.detect(Path.of("/work/project"), HOME);
            assertThat(detection.findings())
                    .as("every field crosses the socket, the directory and home it was given too")
                    .containsExactly(
                            new Finding(
                                    Finding.Part.TOOLS,
                                    "java.version",
                                    "25",
                                    "pom.xml",
                                    14,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    "/work/project"),
                            new Finding(
                                    Finding.Part.RULES,
                                    "credential",
                                    "repository/corp",
                                    ".m2/settings.xml",
                                    3,
                                    Finding.Origin.YOURS,
                                    false,
                                    HOME.toString()));
            assertThat(detection.unplaced())
                    .containsExactly(
                            new Unplaced("pom.xml", 40, "a profile activated by a property"));

            assertThat(
                            plugin.configure(
                                    List.of(
                                            new Repository("corp", "http://127.0.0.1:1/corp/"),
                                            new Repository("private", "http://127.0.0.1:1/p/"))))
                    .containsExactly(
                            new File(".fake/corp", "http://127.0.0.1:1/corp/", false),
                            new File(".fake/private", "http://127.0.0.1:1/p/", true));
        }
    }

    @Test
    void hearsARefusalAsTheRefusalItIs() throws IOException {
        try (InstalledEcosystem plugin = new InstalledEcosystem(fake(), run())) {

            assertThatThrownBy(() -> plugin.detect(Path.of("/work/other"), HOME))
                    .isInstanceOfSatisfying(
                            EcosystemRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .isEqualTo(EcosystemRefused.Reason.NOT_RECOGNIZED));
            assertThatThrownBy(() -> plugin.detect(Path.of("/work/locked"), HOME))
                    .isInstanceOfSatisfying(
                            EcosystemRefused.class,
                            refused -> {
                                assertThat(refused.reason())
                                        .isEqualTo(EcosystemRefused.Reason.UNREADABLE);
                                assertThat(refused.detail()).isEqualTo("pom.xml: permission");
                            });
        }
    }

    @Test
    void startsAPluginAgainWhoseProcessEndedSoTheNextQuestionIsAnswered() throws IOException {
        try (InstalledEcosystem plugin = new InstalledEcosystem(fake(), run())) {
            assertThatThrownBy(() -> plugin.detect(Path.of("/work/gone"), HOME))
                    .isInstanceOf(EcosystemException.class);

            assertThat(plugin.detect(Path.of("/work/project"), HOME).findings()).hasSize(2);
        }
    }

    @Test
    void refusesAPluginBelowTheLowestVersionNamingBoth() throws IOException {
        final Path executable = named("fake", Version.class, "0");
        assertThatThrownBy(() -> new InstalledEcosystem(executable, run()))
                .isInstanceOf(EcosystemException.class)
                .hasMessageContaining(executable.toString())
                .hasMessageContaining("version 0")
                .hasMessageContaining("at least " + EcosystemProtocol.LOWEST)
                .hasMessageContaining("Upgrade the ecosystem adapter");
        assertThat(run()).as("a refused plugin leaves nothing behind").isEmptyDirectory();
    }

    @Test
    void measuresAVersionAgainstTheLowestAndNotTheCurrentOne() {
        final Path plugin = Path.of("/opt/fake");
        final int above = EcosystemProtocol.VERSION + 1;
        final int below = EcosystemProtocol.VERSION - 1;

        assertThat(InstalledEcosystem.refusal(plugin, EcosystemProtocol.VERSION, above))
                .as("the current version is refused where the lowest is above it")
                .contains("version " + EcosystemProtocol.VERSION)
                .contains("at least " + above);
        assertThat(InstalledEcosystem.refusal(plugin, below, below))
                .as("a version below the current one is accepted at the lowest")
                .isNull();
    }

    @Test
    void refusesAFractionalVersionAndAcceptsOneBeyondAnInt() throws IOException {
        assertThatThrownBy(
                        () -> {
                            try (InstalledEcosystem ignored =
                                    new InstalledEcosystem(
                                            named("fake", Version.class, "1.5"), run())) {}
                        })
                .as("1.5 is no version, and is not read as 1")
                .isInstanceOf(EcosystemException.class)
                .hasMessageContaining("Upgrade the ecosystem adapter");
        try (InstalledEcosystem plugin =
                new InstalledEcosystem(named("fake", Version.class, "4294967297"), run())) {
            assertThat(plugin.ecosystem()).as("newer, and not wrapped around").isEqualTo("fake");
        }
    }

    @Test
    void acceptsAPluginOfANewerVersionAndIgnoresTheFieldsItAdded() throws IOException {
        try (InstalledEcosystem plugin =
                new InstalledEcosystem(
                        named("fake", Version.class, String.valueOf(Integer.MAX_VALUE)), run())) {

            assertThat(plugin.detect(Path.of("/work/project"), HOME).findings())
                    .extracting(Finding::key)
                    .as("a field this Porelo does not know is left aside")
                    .containsExactly("java.version");
        }
    }

    @Test
    void refusesAPluginThatNamesAnotherEcosystemThanItsFile() throws IOException {
        final Path executable = named("gradle-like", FakeEcosystem.class);
        assertThatThrownBy(() -> new InstalledEcosystem(executable, run()))
                .isInstanceOf(EcosystemException.class)
                .hasMessageContaining(executable.toString())
                .hasMessageContaining("'fake'")
                .hasMessageContaining("'gradle-like'");
    }

    @Test
    void keepsAMissingDescribeAsAStartupFailureNamingTheError() throws IOException {
        final Path executable = named("fake", NoDescribe.class);
        assertThatThrownBy(() -> new InstalledEcosystem(executable, run()))
                .isInstanceOf(EcosystemException.class)
                .hasMessageContaining(executable.toString())
                .hasMessageContaining("org.varlink.service.MethodNotFound");
    }

    @Test
    void saysWhatAPluginSaidThatEndedWithoutBecomingReady() throws IOException {
        final Path executable = executable(directory.resolve("fake"), "echo 'no idea'; exit 3");
        assertThatThrownBy(() -> new InstalledEcosystem(executable, run()))
                .isInstanceOf(EcosystemException.class)
                .hasMessageContaining("exited without becoming ready")
                .hasMessageContaining("no idea");
    }

    @Test
    void aMethodThePluginLacksIsCannotSayAndAnyOtherErrorStaysAnError() throws IOException {
        try (InstalledEcosystem plugin =
                new InstalledEcosystem(named("fake", DescribeOnly.class), run())) {

            assertThat(plugin.optional(EcosystemProtocol.DETECT, Map.of()))
                    .as("this plugin cannot say, never nothing found")
                    .isEqualTo(new Answer.CannotSay(EcosystemProtocol.DETECT));
            assertThatThrownBy(() -> plugin.optional(EcosystemProtocol.CONFIGURE, Map.of()))
                    .as("a real failure is not read as an old plugin")
                    .isInstanceOf(EcosystemException.class)
                    .hasMessageContaining("InvalidParameter");
        }
        try (InstalledEcosystem plugin = new InstalledEcosystem(fake(), run())) {
            assertThat(
                            plugin.optional(
                                    EcosystemProtocol.CONFIGURE, Map.of("repositories", List.of())))
                    .isEqualTo(new Answer.Answered(Map.of("files", List.of())));
        }
    }

    @Test
    void aPluginLackingARequiredMethodFailsAndIsNeverReadAsNothingFound() throws IOException {
        try (InstalledEcosystem plugin =
                new InstalledEcosystem(named("fake", DescribeOnly.class), run())) {

            assertThatThrownBy(() -> plugin.detect(Path.of("/work/project"), HOME))
                    .isInstanceOf(EcosystemException.class)
                    .hasMessageContaining("org.varlink.service.MethodNotFound");
        }
    }

    @Test
    void refusesAnAnswerThatIsNotAFindingOrAFileInsideTheHome() throws IOException {
        try (InstalledEcosystem plugin =
                new InstalledEcosystem(named("fake", BadAnswers.class), run())) {

            assertThatThrownBy(() -> plugin.detect(Path.of("/work/project"), HOME))
                    .isInstanceOf(EcosystemException.class)
                    .hasMessageContaining("not a detection");
            assertThatThrownBy(() -> plugin.configure(List.of()))
                    .isInstanceOf(EcosystemException.class)
                    .hasMessageContaining("../outside");
        }
    }

    @Test
    void theCommandItStartsIsThePluginServingItsSocket() {
        assertThat(InstalledEcosystem.command(Path.of("/opt/fake"), Path.of("/run/p.sock")))
                .containsExactly("/opt/fake", "serve", "/run/p.sock");
    }

    @Test
    void aPluginInheritsNothingOfThePoreloThatStartsIt() throws Exception {
        final Path plugin = withoutAShell("fake", ReportsEnvironment.class);

        // Names only: a red run must not print the environment of whoever ran it.
        assertThat(asCaller("64m", plugin).stream().map(line -> line.split("=", 2)[0]))
                .as("the names of the variables the plugin got")
                .isEmpty();
    }

    /**
     * Runs a Porelo of its own, whose environment holds a made-up secret.
     *
     * @return The lines it printed.
     */
    private List<String> asCaller(final String heap, final Path plugin) throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-Xmx" + heap,
                                "-cp",
                                System.getProperty("java.class.path"),
                                Caller.class.getName(),
                                plugin.toString(),
                                run().toString())
                        .redirectErrorStream(true);
        builder.environment().put("MADE_UP_SECRET", "not-a-real-one");
        final Process process = builder.start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output.lines().toList();
    }

    /** What stands for Porelo in {@link #asCaller}: starts a plugin and prints what it hears. */
    public static final class Caller {

        /**
         * Asks.
         *
         * @param arguments The plugin and the caller's directory.
         */
        public static void main(final String[] arguments) {
            try (InstalledEcosystem plugin =
                    new InstalledEcosystem(Path.of(arguments[0]), Path.of(arguments[1]))) {
                plugin.detect(Path.of("/work"), HOME)
                        .unplaced()
                        .forEach(each -> System.out.println(each.note()));
            } catch (EcosystemException ex) {
                System.out.println("refused " + ex.getMessage());
            }
        }
    }

    /** A plugin that answers its own environment, one {@code NAME=value} to a note. */
    public static final class ReportsEnvironment implements Ecosystem {

        /**
         * Runs it.
         *
         * @param arguments The script it was started through, then as every plugin takes them.
         */
        public static void main(final String[] arguments) {
            EcosystemMain.run(
                    new ReportsEnvironment(), Arrays.copyOfRange(arguments, 1, arguments.length));
        }

        @Override
        public String ecosystem() {
            return "fake";
        }

        @Override
        public Detection detect(final Path directory, final Path home) {
            return new Detection(
                    List.of(),
                    System.getenv().entrySet().stream()
                            .map(
                                    each ->
                                            new Unplaced(
                                                    "environment",
                                                    0,
                                                    each.getKey() + "=" + each.getValue()))
                            .toList());
        }

        @Override
        public List<File> configure(final List<Repository> repositories) {
            return List.of();
        }
    }

    @Test
    void startupOutputWithoutEndIsBoundedAndNamedAsAFailureToStart() throws Exception {
        final List<String> said = asCaller("32m", named("fake", Spews.class, "before"));

        assertThat(said).as("%d lines", said.size()).hasSize(1);
        assertThat(said.get(0))
                .startsWith("refused ")
                // Which limit comes first depends on how fast the machine reads: both refuse it.
                .containsAnyOf("exited without becoming ready", "did not answer within")
                .doesNotContain("OutOfMemoryError");
        assertThat(said.get(0).length()).isLessThanOrEqualTo(InstalledEcosystem.MESSAGE + 16);
    }

    @Test
    void outputWithoutEndAfterReadyMeetsNoFullPipeAndNoFullMemory() throws Exception {
        final Path plugin = named("fake", Spews.class, "after");

        final List<String> said =
                assertTimeoutPreemptively(Duration.ofSeconds(60), () -> asCaller("32m", plugin));

        assertThat(said).noneMatch(line -> line.contains("OutOfMemoryError"));
        assertThat(said).contains("all of it was read");
    }

    @Test
    void aPluginThatSaysReadyAndNeverAcceptsIsRefusedWithinTheLimit() throws IOException {
        final Path plugin = named("fake", NeverAccepts.class);

        assertTimeoutPreemptively(
                Duration.ofSeconds(InstalledEcosystem.READY_SECONDS + 20),
                () ->
                        assertThatThrownBy(() -> new InstalledEcosystem(plugin, run()))
                                .isInstanceOf(EcosystemException.class)
                                .hasMessageContaining("did not answer within"));
    }

    @Test
    void aPluginsWordsReachADiagnosticBoundedAndWithoutControlCharacters() throws IOException {
        assertPlain(
                catchThrowable(
                        () ->
                                new InstalledEcosystem(
                                        executable(
                                                directory.resolve("fake"),
                                                "printf 'said \\033[31mred\\007\\n'; exit 3"),
                                        run())),
                "said");
        assertPlain(
                catchThrowable(
                        () -> new InstalledEcosystem(named("fake", Hostile.class, "name"), run())),
                "calls itself");

        try (InstalledEcosystem plugin =
                new InstalledEcosystem(named("fake", Hostile.class, "answers"), run())) {
            assertPlain(
                    catchThrowable(() -> plugin.detect(Path.of("/w/error"), HOME)), "org.example");
            assertPlain(catchThrowable(() -> plugin.detect(Path.of("/w/unreadable"), HOME)), "bad");
            assertPlain(
                    catchThrowable(() -> plugin.detect(Path.of("/w/finding"), HOME)), "detection");
            assertPlain(catchThrowable(() -> plugin.configure(List.of())), "placed");
            assertPlain(
                    catchThrowable(
                            () ->
                                    plugin.optional(
                                            EcosystemProtocol.DETECT,
                                            Map.of("directory", "/w/error"))),
                    "org.example");
        }
    }

    @Test
    void anAnswerLargerThanAMessageMayBeIsAFailureNotAnAnswer() throws IOException {
        try (InstalledEcosystem plugin =
                new InstalledEcosystem(named("fake", Hostile.class, "answers"), run())) {
            assertThatThrownBy(() -> plugin.detect(Path.of("/w/big"), HOME))
                    .isInstanceOf(EcosystemException.class);
        }
    }

    @Test
    void keepsItsSocketInADirectoryOfItsOwnAndLeavesEverythingElseAlone() throws IOException {
        final Path caller = run();
        final Path foreign = Files.writeString(caller.resolve("p.sock"), "not the client's");
        final Path plugin = fake();

        final Path first;
        try (InstalledEcosystem one = new InstalledEcosystem(plugin, caller)) {
            first = one.socket();
            assertThat(foreign).as("what was there is still there").hasContent("not the client's");
            assertThat(first.getParent()).isNotEqualTo(caller);
            assertThat(Files.getPosixFilePermissions(first.getParent()))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));

            try (InstalledEcosystem two = new InstalledEcosystem(plugin, caller)) {
                assertThat(two.socket()).isNotEqualTo(first);
                assertThat(first).as("a second client takes nothing of the first").exists();
                assertThat(two.detect(Path.of("/work/project"), HOME).findings()).hasSize(2);
                assertThat(one.detect(Path.of("/work/project"), HOME).findings()).hasSize(2);
            }
            assertThat(first).as("nor when it goes").exists();
        }
        assertThat(first.getParent()).as("its own directory goes with it").doesNotExist();
        assertThat(foreign).hasContent("not the client's");
    }

    private static void assertPlain(final Throwable thrown, final String said) {
        assertThat(thrown).isNotNull();
        for (Throwable each = thrown; each != null; each = each.getCause()) {
            final String message = String.valueOf(each.getMessage());
            assertThat(message.chars().filter(Character::isISOControl).count())
                    .as("control characters in a %s", each.getClass().getSimpleName())
                    .isZero();
            assertThat(message.length())
                    .as("the length of a %s's message", each.getClass().getSimpleName())
                    .isLessThanOrEqualTo(InstalledEcosystem.MESSAGE);
        }
        assertThat(thrown.getMessage()).contains(said);
    }

    /** A plugin that writes without end and without a line's end, before or after it is ready. */
    public static final class Spews {

        private static final long OUTPUT = 256L * 1024 * 1024;

        /**
         * Serves.
         *
         * @param arguments {@code serve <socket>}, then {@code before} or {@code after}.
         * @throws Exception When it cannot write.
         */
        public static void main(final String[] arguments) throws Exception {
            final PrintStream out = System.out;
            if ("before".equals(arguments[2])) {
                spew(out);
                out.flush();
                System.exit(3);
            }
            try (VarlinkServer server =
                    new VarlinkServer(Path.of(arguments[1]), EcosystemProtocol.INTERFACE)) {
                final Thread spewing = Thread.ofPlatform().daemon().unstarted(() -> spew(out));
                server.method(
                        "Describe",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "protocolVersion",
                                                EcosystemProtocol.VERSION,
                                                "ecosystem",
                                                "fake")));
                server.method(
                        "Detect",
                        (parameters, replies) -> {
                            try {
                                spewing.join();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            replies.last(
                                    new Detection(
                                                    List.of(),
                                                    List.of(
                                                            new Unplaced(
                                                                    "f", 0, "all of it was read")))
                                            .asMap());
                        });
                out.println("ready " + arguments[1]);
                out.flush();
                spewing.start();
                server.run();
            }
        }

        private static void spew(final PrintStream out) {
            final byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'x');
            for (long written = 0; written < OUTPUT; written += chunk.length) {
                out.write(chunk, 0, chunk.length);
            }
        }
    }

    /** A plugin that fills its socket's backlog, says it is ready, and accepts nobody. */
    public static final class NeverAccepts {

        /**
         * Waits.
         *
         * @param arguments {@code serve <socket>}.
         * @throws Exception When it cannot bind.
         */
        public static void main(final String[] arguments) throws Exception {
            final UnixDomainSocketAddress address = UnixDomainSocketAddress.of(arguments[1]);
            try (ServerSocketChannel listening =
                    ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
                listening.bind(address, 1);
                final List<SocketChannel> held = new ArrayList<>();
                try {
                    while (held.size() < 64) {
                        final SocketChannel each =
                                SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
                        each.configureBlocking(false);
                        held.add(each);
                        each.connect(address);
                    }
                } catch (IOException full) {
                    System.out.println("ready " + arguments[1]);
                    System.out.flush();
                }
                // A red run must not leave it behind for long.
                Thread.sleep(60_000);
            }
        }
    }

    /** A plugin whose every word holds an escape sequence, a bell and a megabyte of more. */
    public static final class Hostile {

        private static final String WORDS =
                "bad \u001b[31m\u0007\u009b\u202e" + "x".repeat(1024 * 1024);

        /**
         * Serves.
         *
         * @param arguments {@code serve <socket>}, then {@code name} or {@code answers}.
         */
        public static void main(final String[] arguments) {
            final boolean name = "name".equals(arguments[2]);
            try (VarlinkServer server =
                    new VarlinkServer(Path.of(arguments[1]), EcosystemProtocol.INTERFACE)) {
                server.method(
                        "Describe",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "protocolVersion",
                                                EcosystemProtocol.VERSION,
                                                "ecosystem",
                                                name ? WORDS : "fake")));
                server.method(
                        "Detect",
                        (parameters, replies) -> {
                            final String asked = String.valueOf(parameters.get("directory"));
                            if (asked.endsWith("error")) {
                                throw new VarlinkException(
                                        "org.example.Bad\u001b", Map.of("detail", WORDS));
                            }
                            if (asked.endsWith("unreadable")) {
                                throw new VarlinkException(
                                        EcosystemProtocol.INTERFACE + ".Unreadable",
                                        Map.of("detail", WORDS));
                            }
                            replies.last(
                                    Map.of(
                                            "findings",
                                            List.of(
                                                    Map.of(
                                                            "part",
                                                            asked.endsWith("big")
                                                                    ? "x".repeat(5 * 1024 * 1024)
                                                                    : WORDS)),
                                            "unplaced",
                                            List.of()));
                        });
                server.method(
                        "Configure",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "files",
                                                List.of(
                                                        Map.of(
                                                                "path",
                                                                WORDS,
                                                                "content",
                                                                "",
                                                                "ownerOnly",
                                                                false)))));
                System.out.println("ready " + arguments[1]);
                System.out.flush();
                server.run();
            }
        }
    }

    /** A plugin of the version its second argument names, answering one finding with extras. */
    public static final class Version {

        /**
         * Serves.
         *
         * @param arguments {@code serve <socket>}, then the version from the script.
         */
        public static void main(final String[] arguments) {
            try (VarlinkServer server =
                    new VarlinkServer(Path.of(arguments[1]), EcosystemProtocol.INTERFACE)) {
                final Number version =
                        arguments[2].contains(".")
                                ? Double.valueOf(arguments[2])
                                : Long.valueOf(arguments[2]);
                server.method(
                        "Describe",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "protocolVersion",
                                                version,
                                                "ecosystem",
                                                "fake",
                                                "since",
                                                "a field added later")));
                server.method(
                        "Detect",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "findings",
                                                List.of(
                                                        Map.of(
                                                                "part", "TOOLS",
                                                                "key", "java.version",
                                                                "value", "25",
                                                                "file", "pom.xml",
                                                                "line", 14,
                                                                "origin", "CHECKOUT",
                                                                "unknown", false,
                                                                "note", "",
                                                                "since", "a field added later")),
                                                "unplaced",
                                                List.of())));
                System.out.println("ready " + arguments[1]);
                System.out.flush();
                server.run();
            }
        }
    }

    /** A plugin that serves no Describe at all. */
    public static final class NoDescribe {

        /**
         * Serves.
         *
         * @param arguments {@code serve <socket>}.
         */
        public static void main(final String[] arguments) {
            try (VarlinkServer server =
                    new VarlinkServer(Path.of(arguments[1]), EcosystemProtocol.INTERFACE)) {
                System.out.println("ready " + arguments[1]);
                System.out.flush();
                server.run();
            }
        }
    }

    /** A plugin of an earlier contract: Describe, and a Configure that fails. */
    public static final class DescribeOnly {

        /**
         * Serves.
         *
         * @param arguments {@code serve <socket>}.
         */
        public static void main(final String[] arguments) {
            try (VarlinkServer server =
                    new VarlinkServer(Path.of(arguments[1]), EcosystemProtocol.INTERFACE)) {
                server.method(
                        "Describe",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "protocolVersion",
                                                EcosystemProtocol.LOWEST,
                                                "ecosystem",
                                                "fake")));
                server.method(
                        "Configure",
                        (parameters, replies) -> {
                            throw new VarlinkException(
                                    "org.varlink.service.InvalidParameter",
                                    Map.of("parameter", "repositories"));
                        });
                System.out.println("ready " + arguments[1]);
                System.out.flush();
                server.run();
            }
        }
    }

    /** A plugin that answers things that are neither findings nor files inside the home. */
    public static final class BadAnswers {

        /**
         * Serves.
         *
         * @param arguments {@code serve <socket>}.
         */
        public static void main(final String[] arguments) {
            try (VarlinkServer server =
                    new VarlinkServer(Path.of(arguments[1]), EcosystemProtocol.INTERFACE)) {
                server.method(
                        "Describe",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "protocolVersion",
                                                EcosystemProtocol.VERSION,
                                                "ecosystem",
                                                "fake")));
                server.method(
                        "Detect",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "findings",
                                                List.of(
                                                        Map.of(
                                                                "part", "NEITHER",
                                                                "key", "k",
                                                                "file", "f",
                                                                "origin", "CHECKOUT")),
                                                "unplaced",
                                                List.of())));
                server.method(
                        "Configure",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "files",
                                                List.of(
                                                        Map.of(
                                                                "path",
                                                                "../outside",
                                                                "content",
                                                                "x",
                                                                "ownerOnly",
                                                                false)))));
                System.out.println("ready " + arguments[1]);
                System.out.flush();
                server.run();
            }
        }
    }

    private Path run() throws IOException {
        final Path caller = directory.resolve("run");
        return Files.isDirectory(caller)
                ? caller
                : Files.createDirectory(
                        caller,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
    }

    /**
     * A plugin whose start involves no shell, which would add variables of its own: {@code env -S}
     * reads the line, and the arguments are in a file since the line's length is limited.
     */
    private Path withoutAShell(final String name, final Class<?> main) throws IOException {
        final Path bin = Files.createDirectories(directory.resolve("bin-" + System.nanoTime()));
        final Path arguments =
                Files.writeString(
                        bin.resolve("arguments"),
                        "-cp\n\""
                                + System.getProperty("java.class.path")
                                + "\"\n"
                                + main.getName()
                                + "\n");
        final Path file = bin.resolve(name);
        Files.writeString(
                file,
                "#!/usr/bin/env -S "
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + " @"
                        + arguments
                        + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private Path fake() throws IOException {
        return named("fake", FakeEcosystem.class);
    }

    /** The plugin's file name is what it must call itself, as it is once installed. */
    private Path named(final String name, final Class<?> main, final String... more)
            throws IOException {
        return executable(
                Files.createDirectories(directory.resolve("bin-" + System.nanoTime()))
                        .resolve(name),
                "exec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + main.getName()
                        + "' \"$@\" "
                        + String.join(" ", more));
    }

    private static Path executable(final Path file, final String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
