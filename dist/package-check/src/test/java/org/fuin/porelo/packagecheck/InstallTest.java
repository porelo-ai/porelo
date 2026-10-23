package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstallTest {

    private static final String VERSION = "0.1.0~snapshot.177";

    private static final String EVERYTHING =
            """
            STEP: selecting noninteractive apt
            STEP: configuring apt timeouts
            STEP: apt-get update
            STEP: installing porelo
            STEP: installing the agent
            AGENT-OK
            STEP: porelo --version
            PORELO-OK
            STEP: reporting the installed version
            REPORTED:porelo 0.1.0~snapshot.177
            STEP: porelo setup
            SETUP-OK
            STEP: checking the packaged hook descriptors
            HOOKS-PACKAGED
            STEP: porelo agents
            DISCOVERY-OK
            STEP: porelo agents --verbose
            DESCRIBED
            STEP: checking the installed agent filenames
            NO-PORELO-NAME
            STEP: running the packaged setup script
            SETUP-SCRIPT-RUNS
            """;

    private final Reports out = new Reports();

    private static final String AZURE = "http://azure.archive.ubuntu.com/ubuntu/";
    private static final String ARCHIVE = "http://archive.ubuntu.com/ubuntu/";

    @Test
    void aTimedOutAzureInstallCanPassOnTheArchiveMirror() {
        final var attempts = new java.util.ArrayList<List<String>>();
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            if (command.get(1).equals("rm")) {
                                return new Commands.Result(0, "");
                            }
                            attempts.add(command);
                            return attempts.size() == 1
                                    ? new Commands.Result(
                                            Commands.TIMED_OUT,
                                            "STEP: installing porelo\nslow archive\n")
                                    : new Commands.Result(0, EVERYTHING);
                        });

        install.checkUbuntu("Ubuntu 26.04", VERSION, out.report);

        assertThat(out.report.failures())
                .as("a passing second mirror recovers the install")
                .isZero();
        assertThat(attempts).as("the retry runs a fresh podman container").hasSize(2);
        assertThat(attempts.get(0)).as("Azure is tried first").startsWith("podman", "run", "--rm");
        assertThat(attempts.get(0).getLast())
                .as("the first container uses Azure")
                .contains("URIs: " + AZURE);
        assertThat(attempts.get(1))
                .as("the retry starts a new container")
                .startsWith("podman", "run", "--rm");
        assertThat(attempts.get(1).getLast())
                .as("the new container uses the archive mirror")
                .contains("URIs: " + ARCHIVE)
                .doesNotContain(AZURE);
        assertThat(out.text())
                .as("recovery retains the first mirror and its stalled step")
                .contains(
                        "Ubuntu 26.04 ended at its limit on azure.archive.ubuntu.com during installing porelo; tried again on archive.ubuntu.com");
    }

    @Test
    void twoTimedOutMirrorsFailAndNameBothSteps() {
        final var commands = new java.util.ArrayList<List<String>>();
        final var attempts = new java.util.ArrayList<List<String>>();
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            commands.add(command);
                            if (command.get(1).equals("rm")) {
                                return new Commands.Result(0, "");
                            }
                            attempts.add(command);
                            return new Commands.Result(
                                    Commands.TIMED_OUT,
                                    attempts.size() == 1
                                            ? "STEP: apt-get update\nAzure stalled\n"
                                            : "STEP: installing porelo\narchive stalled\n");
                        });

        install.checkUbuntu("Ubuntu 26.04", VERSION, out.report);

        assertThat(out.text())
                .as("both exhausted mirrors retain their own step")
                .contains("on azure.archive.ubuntu.com during apt-get update")
                .contains("on archive.ubuntu.com during installing porelo");
        assertThat(attempts).as("there are exactly two bounded attempts").hasSize(2);
        assertThat(commands).as("both exhausted containers are removed").hasSize(4);
        for (final int attempt : List.of(0, 2)) {
            final List<String> command = commands.get(attempt);
            assertThat(command).as("each exhausted install has a name").contains("--name");
            assertThat(commands.get(attempt + 1))
                    .as("each exhausted install is followed by its own cleanup")
                    .containsExactly(
                            "podman", "rm", "-f", command.get(command.indexOf("--name") + 1));
        }
        assertThat(out.report.failures()).as("neither mirror completed the install").isPositive();
    }

    @Test
    void aFailureOtherThanATimeoutIsNotRetried() {
        final var attempts = new java.util.ArrayList<List<String>>();
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            if (command.get(1).equals("rm")) {
                                return new Commands.Result(0, "");
                            }
                            attempts.add(command);
                            return new Commands.Result(
                                    1, "FAILED: installing porelo\nFAILED:   broken dependency\n");
                        });

        install.checkUbuntu("Ubuntu 26.04", VERSION, out.report);

        assertThat(attempts).as("a package failure must not be hidden by a retry").hasSize(1);
        assertThat(out.report.failures()).as("a package failure stays red").isPositive();
        assertThat(out.text())
                .as("the original failure remains visible")
                .contains("broken dependency")
                .doesNotContain("tried again");
    }

    @Test
    void aSuccessfulFirstAttemptRunsExactlyOneContainer() {
        final var commands = new java.util.ArrayList<List<String>>();
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            commands.add(command);
                            return new Commands.Result(0, EVERYTHING);
                        });

        install.checkUbuntu("Ubuntu", VERSION, out.report);

        assertThat(commands).as("a healthy first mirror runs exactly one container").hasSize(1);
        assertThat(commands.getFirst())
                .as("the one invocation installs the packages")
                .startsWith("podman", "run");
        assertThat(out.report.failures()).as(out.text()).isZero();
    }

    @Test
    void firstAttemptMarkersCannotPassTheSecondAttempt() {
        final var commands = new java.util.ArrayList<List<String>>();
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            if (command.get(1).equals("rm")) {
                                return new Commands.Result(0, "");
                            }
                            commands.add(command);
                            return commands.size() == 1
                                    ? new Commands.Result(Commands.TIMED_OUT, EVERYTHING)
                                    : new Commands.Result(0, "second attempt has no markers\n");
                        });

        install.checkUbuntu("Ubuntu", VERSION, out.report);

        assertThat(commands).as("the timed-out mirror is retried exactly once").hasSize(2);
        assertThat(out.report.failures())
                .as("only the second attempt supplies verdicts")
                .isEqualTo(Install.MARKERS.size() + 1);
        for (final String marker : Install.MARKERS.keySet()) {
            assertThat(out.text())
                    .as("the first attempt cannot supply %s", marker)
                    .contains("FAIL  " + marker + " on Ubuntu");
        }
        assertThat(out.text())
                .as("the first attempt cannot supply the reported version")
                .contains("but the binary says 'nothing'");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, Commands.TIMED_OUT, -1})
    void aTimedOutContainerIsRemovedBeforeRetryingEvenIfRemovalFails(int cleanupExit) {
        final var commands = new java.util.ArrayList<List<String>>();
        final var limits = new java.util.ArrayList<Duration>();
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            commands.add(command);
                            limits.add(limit);
                            if (command.get(1).equals("rm")) {
                                if (cleanupExit == -1) {
                                    throw new java.io.UncheckedIOException(
                                            new java.io.IOException("cleanup diagnostic"));
                                }
                                return new Commands.Result(cleanupExit, "cleanup diagnostic\n");
                            }
                            return commands.size() == 1
                                    ? new Commands.Result(Commands.TIMED_OUT, "stalled\n")
                                    : new Commands.Result(0, EVERYTHING);
                        });

        install.checkUbuntu("Ubuntu", VERSION, out.report);

        assertThat(commands).as("removal runs between the two install attempts").hasSize(3);
        final List<String> first = commands.getFirst();
        assertThat(first)
                .as("the timed-out container can be addressed after its client dies")
                .contains("--name");
        final String firstName = first.get(first.indexOf("--name") + 1);
        assertThat(commands.get(1))
                .as("cleanup targets the first container before the next run")
                .containsExactly("podman", "rm", "-f", firstName);
        assertThat(limits.get(1))
                .as("cleanup has a short, positive limit")
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofSeconds(30));
        final List<String> second = commands.getLast();
        assertThat(second)
                .as("the retry starts a named container")
                .startsWith("podman", "run")
                .contains("--name");
        assertThat(second.get(second.indexOf("--name") + 1))
                .as("each container gets a unique name")
                .isNotEqualTo(firstName);
        assertThat(out.report.failures())
                .as("cleanup failure does not fail a healthy fallback")
                .isZero();
        if (cleanupExit != 0) {
            assertThat(out.text())
                    .as("failed cleanup identifies the container and retains diagnostics")
                    .contains("could not remove " + firstName, "cleanup diagnostic");
        }
    }

    @Test
    void ubuntuWritesTheSelectedMirror() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.ubuntu(ARCHIVE))
                .as("the retry must actually change apt's source")
                .contains("URIs: " + ARCHIVE)
                .doesNotContain(AZURE);
    }

    @Test
    void aTimeoutReportsTheLastSixLinesTheStepWrote() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) ->
                                new Commands.Result(
                                        Commands.TIMED_OUT,
                                        "STEP: installing porelo\nfirst retained line\nsecond\nthird\nfourth\nfifth\nlast retained line\n"));

        install.checkUbuntu("Ubuntu 26.04", VERSION, out.report);

        assertThat(out.text())
                .as("a timeout preserves the step's output without a FAILED marker")
                .contains("first retained line", "last retained line");
    }

    @Test
    void eachUbuntuMirrorHasThreeMinutesForTheWholeContainer() {
        final var limits = new java.util.ArrayList<Duration>();
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            if (command.get(1).equals("rm")) {
                                return new Commands.Result(0, "");
                            }
                            limits.add(limit);
                            return new Commands.Result(
                                    Commands.TIMED_OUT, "STEP: apt-get update\n");
                        });

        install.checkUbuntu("Ubuntu 26.04", VERSION, out.report);

        assertThat(limits)
                .as("update and install share a three minute budget per mirror")
                .containsExactly(Duration.ofMinutes(3), Duration.ofMinutes(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 37})
    void aptOutputIsTimestampedWithoutHidingItsExitStatus(int exit) throws java.io.IOException {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));
        final String script = install.ubuntu(ARCHIVE);
        final Path logs =
                java.nio.file.Files.createTempDirectory(Path.of("target"), "apt-output-")
                        .toAbsolutePath();
        final String steps =
                script.substring(0, script.indexOf("echo \"STEP: selecting noninteractive apt\""))
                        + "apt_get() { printf 'archive output\\n'; printf 'archive error\\n' >&2; return "
                        + exit
                        + "; }\n"
                        + script.substring(
                                script.indexOf("echo \"STEP: apt-get update\""),
                                script.indexOf("echo \"STEP: porelo --version\""));

        final Commands.Result result =
                Commands.runMerged(
                        List.of(
                                "sh",
                                "-c",
                                steps.replace("/tmp/step.", logs.resolve("step.").toString())
                                        .replace("\napt-get ", "\napt_get ")
                                        .replace("\nlogged apt-get ", "\nlogged apt_get ")));

        assertThat(result.output())
                .as("apt stdout reaches merged output with a per-line timestamp")
                .containsPattern("(?m)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z archive output$");
        assertThat(result.output())
                .as("apt stderr is timestamped too")
                .containsPattern("(?m)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z archive error$");
        if (exit == 0) {
            assertThat(result.output())
                    .as("a successful apt command still emits the passing marker")
                    .contains("AGENT-OK")
                    .doesNotContain("FAILED:");
        } else {
            assertThat(result.output())
                    .as("a successful logging pipeline cannot mask apt's failure")
                    .contains(
                            "FAILED: apt-get update",
                            "FAILED: installing porelo",
                            "FAILED: installing the agent")
                    .doesNotContain("AGENT-OK");
        }
    }

    private String loggingWrapper(Path logs) {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));
        final String script = install.ubuntu(ARCHIVE);
        return script.substring(0, script.indexOf("echo \"STEP: selecting noninteractive apt\""))
                .replace("/tmp/step.", logs.resolve("step.").toString());
    }

    @Test
    void aptLoggingStreamsWhileAptIsBlocked() throws Exception {
        final Path logs =
                java.nio.file.Files.createTempDirectory(Path.of("target"), "apt-live-")
                        .toAbsolutePath();
        final Process process =
                new ProcessBuilder(
                                "sh",
                                "-c",
                                loggingWrapper(logs)
                                        + "apt_get() { printf 'archive still downloading\\n'; read -r release; }\nlogged apt_get\n")
                        .redirectErrorStream(true)
                        .start();
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                final var output = process.inputReader()) {
            final var line = executor.submit(output::readLine);
            try {
                assertThat(line.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .as("apt output reaches the reader before apt is released")
                        .matches(
                                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z archive still downloading");
                assertThat(process.isAlive()).as("apt is still waiting for its release").isTrue();
            } finally {
                process.getOutputStream()
                        .write("release\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                process.getOutputStream().close();
                assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the wrapper ends after apt is released")
                        .isTrue();
            }
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Test
    void aptLoggingReturnsTheExactExitCode() throws java.io.IOException {
        final Path logs =
                java.nio.file.Files.createTempDirectory(Path.of("target"), "apt-exit-")
                        .toAbsolutePath();

        final Commands.Result result =
                Commands.runMerged(
                        List.of(
                                "sh",
                                "-c",
                                loggingWrapper(logs)
                                        + "apt_get() { printf 'archive failed\\n'; return 37; }\nlogged apt_get\n"));

        assertThat(result.exit()).as("the wrapper returns apt's exact status").isEqualTo(37);
    }

    @Test
    void aTimeoutCapturesTheWrappersStreamedOutput() throws java.io.IOException {
        final Path logs =
                java.nio.file.Files.createTempDirectory(Path.of("target"), "apt-timeout-")
                        .toAbsolutePath();

        final Commands.Result result =
                Commands.runMerged(
                        List.of(
                                "sh",
                                "-c",
                                loggingWrapper(logs)
                                        + "apt_get() { printf 'archive waiting\\n'; sleep 2; }\nlogged apt_get\n"),
                        Duration.ofSeconds(1));

        assertThat(result.exit())
                .as("the wrapper was ended while apt was waiting")
                .isEqualTo(Commands.TIMED_OUT);
        assertThat(result.output())
                .as("apt diagnostics survive the wrapper's timeout")
                .containsPattern(
                        "(?m)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z archive waiting$");
    }

    @Test
    void anInstallEndedAtItsLimitNamesTheLastStepItReached() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) ->
                                new Commands.Result(
                                        Commands.TIMED_OUT,
                                        "STEP: preparing apt\nSTEP: apt-get update\nwaiting for archive\n"));

        install.check(
                "Ubuntu",
                Main.UBUNTU,
                install.ubuntu("http://azure.archive.ubuntu.com/ubuntu/"),
                VERSION,
                out.report);

        assertThat(out.text())
                .as("a timed out install identifies the last step, even without a FAILED line")
                .contains("FAIL  Ubuntu ended at its limit during apt-get update");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ubuntu", "debian", "fedora", "fedora-oldest"})
    void eachDistributionGetsItsOwnInstallLimit(String distribution) {
        final boolean apt = distribution.equals("ubuntu") || distribution.equals("debian");
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            assertThat(limit)
                                    .as("%s install passes its own limit to Commands", distribution)
                                    .isEqualTo(
                                            distribution.equals("ubuntu")
                                                    ? Duration.ofMinutes(3)
                                                    : apt
                                                            ? Duration.ofMinutes(10)
                                                            : Commands.LIMIT);
                            return new Commands.Result(0, EVERYTHING);
                        });
        final String image =
                switch (distribution) {
                    case "ubuntu" -> Main.UBUNTU;
                    case "debian" -> Main.DEBIAN;
                    case "fedora" -> Main.FEDORA;
                    default -> Main.FEDORA_OLDEST;
                };
        final String script =
                switch (distribution) {
                    case "ubuntu" -> install.ubuntu("http://azure.archive.ubuntu.com/ubuntu/");
                    case "debian" -> install.debian();
                    default -> install.fedora();
                };

        install.check(distribution, image, script, VERSION, out.report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ubuntu", "debian"})
    void aptScriptsAnnounceEachStepBeforeRunningIt(String distribution) {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));
        final String script =
                distribution.equals("ubuntu")
                        ? install.ubuntu("http://azure.archive.ubuntu.com/ubuntu/")
                        : install.debian();

        for (final String step :
                List.of(
                        "export DEBIAN_FRONTEND",
                        "printf '%s",
                        "logged apt-get update",
                        "logged apt-get install -y -qq /deb/",
                        "logged apt-get install -y -qq /agent/",
                        "porelo --version >/dev/null",
                        "echo \"REPORTED:",
                        "porelo setup",
                        "grep -qho",
                        "porelo agents 2>",
                        "porelo agents --verbose",
                        "ls /usr/libexec/",
                        "/usr/share/porelo/setup/porelo-setup.sh")) {
            assertThat(script)
                    .as(
                            "%s announces the step before %s on unredirected output",
                            distribution, step)
                    .containsPattern(
                            "echo \"STEP: [^\"\\n]+\"\\n" + java.util.regex.Pattern.quote(step));
        }
        if (distribution.equals("ubuntu")) {
            assertThat(script)
                    .as("Ubuntu announces the mirror change before running sed")
                    .containsPattern("echo \"STEP: [^\"\\n]+\"\\nsed -i");
        }
    }

    @Test
    void passesAnInstallThatDidEveryStep() {
        Install.verdicts("Debian", EVERYTHING, VERSION, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text())
                .as("the passing report identifies the packaged build")
                .contains("the binary names the build it came from (0.1.0~snapshot.177)");
    }

    @Test
    void anAgentInstallErrorRemainsVisibleAfterTheCommonProgressLines() {
        // With porelo unavailable, the common checks print progress without success markers.
        final String output =
                EVERYTHING
                        .replace("AGENT-OK\n", "Depends: porelo but it is not installable\n")
                        .replace("REPORTED:porelo " + VERSION, "REPORTED:")
                        .lines()
                        .filter(
                                line ->
                                        !Install.MARKERS.containsKey(line)
                                                || line.equals("NO-PORELO-NAME"))
                        .collect(java.util.stream.Collectors.joining("\n"));

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text())
                .as("later progress lines must not hide the error from the failed agent install")
                .contains("FAIL  AGENT-OK on Debian")
                .contains("Depends: porelo but it is not installable")
                .doesNotContain("STEP:");
    }

    @Test
    void saysWhichStepFailedAndWhyRatherThanOnlyThatNothingWasReported() {
        final String output =
                "FAILED: apt-get update\nFAILED:   E: Failed to fetch http://azure.archive.ubuntu.com/ubuntu/"
                        + "dists/noble/InRelease  Could not connect\nREPORTED:\n";

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text())
                .as("the cause accompanies the failed step")
                .contains("FAILED: apt-get update")
                .contains("Could not connect");
    }

    @Test
    void everyInstallStepThatFailsSaysSo() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian())
                .as("apt failures retain the command output")
                .contains("|| failed \"apt-get update\"")
                .contains("|| failed \"installing porelo\"")
                .doesNotContain(">/dev/null 2>&1\n");
        assertThat(install.fedora())
                .as("dnf failures retain the command output")
                .contains("|| failed \"installing porelo\"");
    }

    @Test
    void failsABinaryThatDoesNotNameTheBuildItCameFrom() {
        // Every build once answered 0.1.0-SNAPSHOT while its package carried a build number.
        Install.verdicts(
                "Fedora",
                EVERYTHING.replace("porelo 0.1.0~snapshot.177", "porelo 0.1.0-SNAPSHOT"),
                VERSION,
                out.report);

        assertThat(out.text())
                .as("a mismatched binary fails the package check")
                .contains(
                        "FAIL  the package is 0.1.0~snapshot.177 but the binary says 'porelo 0.1.0-SNAPSHOT'");
    }

    @Test
    void failsABinaryThatSaysNothing() {
        Install.verdicts("Fedora", "", VERSION, out.report);

        assertThat(out.text())
                .as("an absent version is diagnosed")
                .contains("but the binary says 'nothing'");
        assertThat(out.report.failures())
                .as("every missing marker and version fails")
                .isEqualTo(Install.MARKERS.size() + 1);
    }

    @Test
    void installsTheAgentAfterPoreloOnBothSides() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian())
                .as("the agent dependency resolves only after porelo installs")
                .containsSubsequence(
                        "/deb/porelo_*.deb", "/agent/porelo-agent-stub_*.deb", "porelo setup");
        assertThat(install.fedora())
                .as("the agent dependency resolves only after porelo installs")
                .containsSubsequence(
                        "/rpm/porelo-0*.rpm", "/agent/porelo-agent-stub-*.rpm", "porelo setup");
    }

    @Test
    void installsDebianFromItsOwnMirrorsAndUbuntuFromTheSelectedOne() {
        // Ubuntu's mirror in Debian's sources would serve it another distribution's release.
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));
        assertThat(install.ubuntu(AZURE))
                .as("Ubuntu uses the requested mirror")
                .contains("azure.archive.ubuntu.com");
        assertThat(install.debian())
                .as("Debian retains its own distribution sources")
                .doesNotContain("ubuntu.com")
                .contains("apt-get update");
    }

    @Test
    void findsTheAgentByItsDescriptionAndByNoFileOfPorelosName() {
        // A vendor's package names its program freely and registers it with one file under
        // agents.d.
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(Install.MARKERS)
                .as("agent discovery and filename isolation remain required")
                .containsKeys("DESCRIBED", "NO-PORELO-NAME");
        for (final String script :
                java.util.List.of(
                        install.debian(),
                        install.ubuntu("http://azure.archive.ubuntu.com/ubuntu/"),
                        install.fedora())) {
            assertThat(script)
                    .as("all distributions check agent discovery and filename isolation")
                    .contains("--verbose")
                    .contains("/usr/share/porelo/agents.d/")
                    .contains("echo DESCRIBED")
                    .contains("/usr/libexec/porelo/agents/porelo-agent-")
                    .contains("echo NO-PORELO-NAME");
        }
    }

    @Test
    void runsThePackagedSetupScriptAndAsksForTheInstalledVersion() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        for (final String script :
                java.util.List.of(
                        install.debian(),
                        install.ubuntu("http://azure.archive.ubuntu.com/ubuntu/"),
                        install.fedora())) {
            assertThat(script)
                    .as("the setup script must identify the installed channel")
                    .contains("/usr/share/porelo/setup/porelo-setup.sh --show")
                    .contains("as the installed porelo $(porelo --version")
                    .contains("echo SETUP-SCRIPT-RUNS");
        }
    }

    @Test
    void anInstallWhosePackagedSetupScriptDidNotRunFails() {
        Install.verdicts(
                "Ubuntu", EVERYTHING.replace("SETUP-SCRIPT-RUNS\n", ""), VERSION, out.report);

        assertThat(out.text())
                .as("the setup script must have run")
                .contains("FAIL  SETUP-SCRIPT-RUNS on Ubuntu");
    }

    @Test
    void anInstallThatFoundTheAgentByItsOldNameFails() {
        Install.verdicts("Fedora", EVERYTHING.replace("NO-PORELO-NAME\n", ""), VERSION, out.report);

        assertThat(out.text())
                .as("a stray agent binary fails the package check")
                .contains("FAIL  NO-PORELO-NAME on Fedora");
    }
}
