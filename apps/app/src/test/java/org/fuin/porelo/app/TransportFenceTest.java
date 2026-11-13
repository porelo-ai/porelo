package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** A transport's mistake must not inherit the account's network access. */
class TransportFenceTest {

    @TempDir Path dir;

    @Test
    void aVerbCannotStartBeforeItsNamespaceRulesAreLoaded() {
        // Harm: a transport reaches another host before its fence exists.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final Command verb = Command.of("/transport", "members", "--project", "p");

        fence(runner, List.of("192.0.2.10:443", "[2001:db8::10]:8448"), false).run(verb);

        final Command started = runner.invocations().getFirst();
        assertThat(started.arguments())
                .as("pasta creates the namespace and configures its network before the wrapper")
                .startsWith(
                        "/usr/bin/pasta",
                        "--config-net",
                        "-t",
                        "none",
                        "-u",
                        "none",
                        "-T",
                        "none",
                        "-U",
                        "none",
                        "--map-host-loopback",
                        "none",
                        "--map-guest-addr",
                        "none",
                        "--",
                        "/usr/bin/sh",
                        "-c")
                .endsWith(
                        "porelo-transport-fence",
                        "/usr/bin/nft",
                        "/transport",
                        "members",
                        "--project",
                        "p");
        assertThat(scriptOf(started))
                .as("nft must succeed before exec, and consumes its own input")
                .contains("\"$1\" -f /dev/stdin <<'PORELO_TRANSPORT_RULES'", "&&", "exec \"$@\"")
                .contains(
                        "policy drop;",
                        "oifname \"lo\" accept",
                        "ip daddr 192.0.2.10 accept",
                        "ip6 daddr 2001:db8:0:0:0:0:0:10 accept")
                .doesNotContain("ct state", "dport 53", "policy accept");
    }

    @Test
    void aDeclaredGatewayCannotBeTranslatedIntoTheHostsLoopback() {
        // Harm: allowing a gateway literal accidentally grants the account's local services.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("192.0.2.1"), false).run(Command.of("/transport", "members"));

        assertThat(runner.invocations().getFirst().arguments())
                .as("a declared address must keep its destination on the host side of pasta")
                .containsSubsequence("--map-host-loopback", "none", "--map-guest-addr", "none");
    }

    @Test
    void anOfflineProjectCannotOpenAnExternalReachEvenWhenTheTransportNamesIt() {
        // Harm: a stale or mistaken reach opens an offline project's network.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("192.0.2.10", "2001:db8::10", "server.example.org"), true)
                .run(Command.of("/transport", "members"));

        assertThat(scriptOf(runner.invocations().getFirst()))
                .as("offline rules have one accept, for namespace loopback alone")
                .contains("policy drop;", "oifname \"lo\" accept")
                .doesNotContain(
                        "ip daddr",
                        "ip6 daddr",
                        "icmpv6",
                        "192.0.2.10",
                        "2001:db8",
                        "server.example.org");
    }

    @Test
    void neighborDiscoveryCannotOpenOtherIcmpv6TypesOrGlobalTraffic() {
        // Harm: an IPv6 discovery exception opens arbitrary control traffic beyond declared
        // reaches.
        final FakeCommandRunner runner = new FakeCommandRunner();

        fence(runner, List.of("2001:db8::10"), false).run(Command.of("/transport", "members"));

        final String script = scriptOf(runner.invocations().getFirst());
        assertThat(script.lines().map(String::strip).filter(line -> line.endsWith(" accept")))
                .as("only loopback, the declared reach and bounded discovery traffic pass")
                .containsExactly(
                        "oifname \"lo\" accept",
                        "ip6 hoplimit 255 ip6 saddr fe80::/10 icmpv6 type { 133, 134, 135, 136 } accept",
                        "ip6 hoplimit 255 ip6 daddr fe80::/10 icmpv6 type { 133, 134, 135, 136 } accept",
                        "ip6 daddr 2001:db8:0:0:0:0:0:10 accept");
        assertThat(script)
                .as("other IPv6 traffic beyond the declared reach falls through to the drop policy")
                .contains("type filter hook output priority filter; policy drop;")
                .doesNotContain("policy accept", "ct state");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "server.example.org",
                "192.0.2.0/24",
                "192.0.2.1; accept",
                "192.0.2.1\naccept",
                "fe80::1%lo",
                "[::1].example.org",
                "192.0.2.10:0",
                "192.0.2.10:65536"
            })
    void anUnsupportedReachCannotInjectRulesOrSilentlyRunUnfenced(final String reach) {
        // Harm: interpreting a reach as nft syntax or resolving it widens the allow-list.
        final FakeCommandRunner runner = new FakeCommandRunner();

        assertThatThrownBy(
                        () ->
                                fence(runner, List.of(reach), false)
                                        .run(Command.of("/transport", "members")))
                .as("unsupported reaches refuse before any process starts")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("literal");
        assertThat(runner.invocations()).as("no fallback starts the verb").isEmpty();
    }

    @Test
    void missingPastaRefusesWithoutStartingTheVerb() {
        // Harm: a machine lacking the namespace helper runs a transport on the host network.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final TransportFence fenced =
                new TransportFence(
                        runner,
                        List.of(),
                        false,
                        name -> {
                            if ("pasta".equals(name))
                                throw new UncheckedIOException(new IOException("pasta is missing"));
                            return Path.of("/usr/bin", name);
                        });

        assertThatThrownBy(() -> fenced.run(Command.of("/transport", "members")))
                .as("the refusal names the missing dependency")
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("pasta");
        assertThat(runner.invocations()).as("no unfenced fallback").isEmpty();
    }

    @Test
    void failingPastaCannotFallBackToTheHostNetwork() {
        // Harm: a namespace creation failure is mistaken for permission to run without one.
        final FakeCommandRunner runner =
                new FakeCommandRunner().failing("/usr/bin/pasta", 1, "namespace refused");

        final CommandResult result =
                fence(runner, List.of(), false).run(Command.of("/transport", "members"));

        assertThat(result.exitCode()).as("the namespace failure is returned").isEqualTo(1);
        assertThat(runner.invocations()).as("only the fenced launch was attempted").hasSize(1);
    }

    @Test
    void wrappingCannotLoseCredentialsInputLimitsOrLiveDiagnostics() {
        // Harm: the wrapper leaks credentials into arguments or drops a timeout or stderr stream.
        final List<String> lines = new ArrayList<>();
        final List<Command> seen = new ArrayList<>();
        final CommandRunner runner =
                new CommandRunner() {
                    @Override
                    public CommandResult run(final Command command) {
                        throw new AssertionError("the streaming overload must be retained");
                    }

                    @Override
                    public CommandResult run(
                            final Command command,
                            final java.util.function.Consumer<String> output) {
                        seen.add(command);
                        output.accept("arriving now");
                        return new CommandResult(command, 75, "answer", "arriving now");
                    }
                };
        final byte[] input = "fixture input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final Command verb =
                Command.of("/transport", "members", "a 'quoted' $(argument)")
                        .withChosenEnvironment(Map.of("LANG", "C.UTF-8"))
                        .withEnvironment(Map.of("TRANSPORT_CREDENTIAL", "fixture value"))
                        .withBinaryInput(input)
                        .in(dir)
                        .withTimeout(Duration.ofSeconds(7));

        final CommandResult result = fence(runner, List.of(), false).run(verb, lines::add);

        final Command wrapped = seen.getFirst();
        assertThat(wrapped.binaryInput()).as("stdin remains the verb's, not nft's").isSameAs(input);
        assertThat(wrapped.environment())
                .as("the chosen environment is retained")
                .isEqualTo(verb.environment());
        assertThat(wrapped.inheritsEnvironment()).as("caller environment stays excluded").isFalse();
        assertThat(wrapped.workingDirectory())
                .as("relative paths retain their meaning")
                .isEqualTo(dir);
        assertThat(wrapped.timeout())
                .as("the verb cannot outlive its limit")
                .isEqualTo(verb.timeout());
        assertThat(wrapped.arguments())
                .as("arguments remain data")
                .endsWith("/transport", "members", "a 'quoted' $(argument)");
        assertThat(wrapped.describe())
                .as("credentials never become arguments")
                .doesNotContain("fixture value", "fixture input");
        assertThat(lines)
                .as("diagnostics arrive through the streaming overload")
                .containsExactly("arriving now");
        assertThat(result.exitCode()).as("temporary failures retain their meaning").isEqualTo(75);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "fixture text")
    void absentBinaryInputCannotReplaceTextOrBecomeAnEmptyByteInput(final @Nullable String input) {
        // Harm: wrapping loses stdin credentials or turns absent input into a different input mode.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final Command verb = Command.of("/transport", "members");
        final Command given = input == null ? verb : verb.withInput(input);

        fence(runner, List.of(), false).run(given);

        final Command wrapped = runner.invocations().getFirst();
        assertThat(wrapped.input())
                .as("text input, including empty or absent text, is preserved")
                .isEqualTo(input);
        assertThat(wrapped.binaryInput()).as("null remains no binary input").isNull();
        assertThat(wrapped.timeout()).as("an absent per-command limit stays absent").isNull();
    }

    @Test
    void aFailedRulesetLoadCannotExecuteTheTransport() throws IOException {
        // Harm: a missing or broken nft leaves the namespace unrestricted when the verb starts.
        final Path nft = script("nft", "exit 42\n");
        final Path ran = dir.resolve("verb-ran");
        final Command wrapped =
                new TransportFence(
                                new FakeCommandRunner(),
                                List.of(),
                                false,
                                name -> "nft".equals(name) ? nft : Path.of("/bin/sh"))
                        .command(
                                Command.of(
                                        "/bin/sh", "-c", "touch \"$1\"", "verb", ran.toString()));

        final CommandResult result = new ProcessCommandRunner().run(withoutPasta(wrapped));

        assertThat(result.exitCode()).as("nft's failure stops the wrapper").isEqualTo(42);
        assertThat(ran).as("the verb never ran").doesNotExist();
    }

    @Test
    void theRulesetCannotConsumeTheVerbsInputOrInterpretItsArguments() throws IOException {
        // Harm: stdin credentials are lost or an argument is executed by the shell wrapper.
        final Path rules = dir.resolve("loaded-rules");
        final Path nft = script("nft", "cat > '" + rules + "'\n");
        final String argument = "$(touch " + dir.resolve("injected") + ") ' ; false";
        final Command wrapped =
                new TransportFence(
                                new FakeCommandRunner(),
                                List.of("192.0.2.10"),
                                false,
                                name -> "nft".equals(name) ? nft : Path.of("/bin/sh"))
                        .command(
                                Command.of(
                                                "/bin/sh",
                                                "-c",
                                                "cat; printf '%s' \"$1\"; exit 75",
                                                "verb",
                                                argument)
                                        .withInput("fixture stdin\n"));

        final CommandResult result = new ProcessCommandRunner().run(withoutPasta(wrapped));

        assertThat(result.exitCode()).as("the verb's status survives exec").isEqualTo(75);
        assertThat(result.standardOutput())
                .as("nft consumes only its rules, not the verb's stdin")
                .isEqualTo("fixture stdin\n" + argument);
        assertThat(Files.readString(rules))
                .as("nft received the actual ruleset")
                .contains("ip daddr 192.0.2.10 accept")
                .doesNotContain("fixture stdin");
        assertThat(dir.resolve("injected")).as("arguments cannot become shell code").doesNotExist();
    }

    private static TransportFence fence(
            final CommandRunner runner, final List<String> reaches, final boolean offline) {
        return new TransportFence(runner, reaches, offline, name -> Path.of("/usr/bin", name));
    }

    private static String scriptOf(final Command command) {
        return command.arguments().get(command.arguments().indexOf("-c") + 1);
    }

    private static Command withoutPasta(final Command command) {
        final Command unwrapped =
                new Command(
                        command.arguments()
                                .subList(
                                        command.arguments().indexOf("--") + 1,
                                        command.arguments().size()),
                        command.workingDirectory(),
                        command.environment(),
                        command.input(),
                        command.inheritsEnvironment());
        final byte @Nullable [] binaryInput = command.binaryInput();
        final Command withInput =
                binaryInput == null ? unwrapped : unwrapped.withBinaryInput(binaryInput);
        final @Nullable Duration timeout = command.timeout();
        return timeout == null ? withInput : withInput.withTimeout(timeout);
    }

    private Path script(final String name, final String body) throws IOException {
        final Path file = Files.writeString(dir.resolve(name), "#!/bin/sh\n" + body);
        assertThat(file.toFile().setExecutable(true))
                .as("the fixture executable is usable")
                .isTrue();
        return file;
    }
}
