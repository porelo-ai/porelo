package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PoreloHostTest {
    @TempDir java.nio.file.Path directory;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(Map<String, String> environment, PoreloHost.Uid uid, String... args) {
        return PoreloHost.run(
                args, environment, uid, new PrintWriter(out, true), new PrintWriter(err, true));
    }

    @Test
    void versionAnswersTheFilteredResourceAsAnyUser() throws Exception {
        final Properties properties = new Properties();
        try (var in = getClass().getResourceAsStream("/porelo-version.properties")) {
            properties.load(in);
        }
        assertThat(run(Map.of(), () -> 1000, "--version"))
                .as("version does not require root or a sudo person")
                .isZero();
        assertThat(out.toString())
                .as("the version comes from this build's filtered resource")
                .isEqualTo(
                        "porelo-host " + properties.getProperty("version") + System.lineSeparator())
                .doesNotContain("${", "unknown");
        assertThat(err.toString()).as("a version request succeeds quietly").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", "--version", "-V"})
    void anInformationalOptionNeedsNoUidLookup(String option) {
        assertThat(
                        run(
                                Map.of(),
                                () -> {
                                    throw new AssertionError("uid must not be needed");
                                },
                                option))
                .as("informational requests work for every uid")
                .isZero();
    }

    @Test
    void theEnvironmentIsRefusedBeforeUidLookupOrArgumentParsing() {
        // Even help must not conceal a dangerously widened sudo environment.
        assertThat(
                        run(
                                Map.of("JAVA_TOOL_OPTIONS", "private-marker"),
                                () -> {
                                    throw new AssertionError("environment must be checked first");
                                },
                                "--help"))
                .as("an unsafe environment is EX_CONFIG before the parser runs")
                .isEqualTo(78);
        assertThat(err.toString())
                .as("the diagnostic names only the variable and the next action")
                .contains("JAVA_TOOL_OPTIONS", "env_keep")
                .doesNotContain("private-marker", "\u001b")
                .hasLineCount(1);
        assertThat(out.toString()).as("help never runs after refusal").isEmpty();
    }

    @Test
    void anOrdinaryUserIsRefusedBeforeTheParser() {
        // A person's shell must not enter the privileged command tree.
        assertThat(run(Map.of(), () -> 1000, "unknown"))
                .as("a non-root operation is EX_NOPERM")
                .isEqualTo(77);
        assertThat(err.toString())
                .as("the refusal tells the caller how it must be invoked")
                .contains("sudo rule")
                .doesNotContain("Unknown")
                .hasLineCount(1);
    }

    @Test
    void aFailedUidLookupIsRefusedWithoutLeakingTheFailure() {
        // Failure to prove root must not grant access.
        assertThat(
                        run(
                                Map.of(),
                                () -> {
                                    throw new IOException("private-marker");
                                }))
                .as("uid lookup failure is EX_NOPERM")
                .isEqualTo(77);
        assertThat(err.toString())
                .as("the failure stays one safe diagnostic")
                .contains("procfs")
                .doesNotContain("private-marker")
                .hasLineCount(1);
    }

    @Test
    void helpWithExtraArgumentsDoesNotBypassTheRootCheck() {
        // An informational flag mixed with an operation cannot authorize that operation.
        assertThat(run(Map.of(), () -> 1000, "--help", "unknown"))
                .as("only a standalone informational request bypasses the uid check")
                .isEqualTo(77);
    }

    @Test
    void argumentFilesAreNeverExpanded() throws Exception {
        // A parser must not open caller-chosen files with root's privileges.
        final var file =
                java.nio.file.Files.writeString(directory.resolve("arguments"), "private-marker");
        assertThat(run(Map.of(), () -> 0, "@" + file))
                .as("argument-file syntax is an ordinary invalid argument")
                .isEqualTo(64);
        assertThat(out.toString() + err)
                .as("the parser never discloses argument-file contents")
                .doesNotContain("private-marker");
        assertThat(PoreloHost.commandLine().isExpandAtFiles())
                .as("future command parsing inherits disabled expansion")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-Dx=y", "-XX:+PrintGC", "--unknown"})
    void runtimeLookingOptionsAreOrdinaryInvalidArguments(String argument) {
        // Runtime-looking options must not acquire meaning inside the command parser.
        assertThat(run(Map.of(), () -> 0, argument))
                .as("unrecognized application arguments are EX_USAGE")
                .isEqualTo(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-Dporelo.probe=1", "-XX:+PrintGC", "--unknown"})
    void aRuntimeLookingOptionBeforeVersionIsStillRefused(String argument) {
        // The package check sends exactly this: a version request must not excuse the option.
        assertThat(run(Map.of(), () -> 0, argument, "--version"))
                .as("an unrecognized argument is EX_USAGE even beside --version")
                .isEqualTo(64);
    }

    @Test
    void anInvocationWithoutAVerbPrintsUsageAndFails() {
        assertThat(run(Map.of(), () -> 0))
                .as("the frame does no work without a verb")
                .isEqualTo(64);
        assertThat(err.toString())
                .as("the caller is shown the available next action")
                .contains("--help");
    }

    @Test
    void projectRefusesNonrootWith69BeforeParsing() {
        assertThat(run(Map.of(), () -> 1000, "project", "project-copy")).isEqualTo(69);
        assertThat(err.toString()).isEqualTo("porelo-host: runs only as root\n");
    }
}
