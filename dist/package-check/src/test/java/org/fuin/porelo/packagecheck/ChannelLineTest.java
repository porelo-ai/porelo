package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ChannelLine}. Harm guarded: a channel the line cannot install from, or that
 * offers a test's package, passing; a failure before the line is asked reading as a pass.
 */
class ChannelLineTest {

    @TempDir Path dir;

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    private final Report report =
            new Report(new PrintStream(printed, true, StandardCharsets.UTF_8));

    private String said() {
        return printed.toString(StandardCharsets.UTF_8);
    }

    private static Commands.Result asked(final int exit, final String... lines) {
        return new Commands.Result(
                exit, "noise\n" + ChannelLine.ASKED + "\n" + String.join("\n", lines) + "\n");
    }

    @Test
    void aChannelThatInstallsPasses() {
        ChannelLine.verdict(
                "releases",
                asked(0, "Inst porelo (1 x)", "Conf porelo (1 x)", "Inst keepassxc (2 x)"),
                report);

        assertThat(report.failures()).as("what it said: %s", said()).isZero();
    }

    @Test
    void aConflictFailsAndSaysWhatAptSaid() {
        ChannelLine.verdict(
                "snapshots",
                asked(100, "porelo-build-github : Conflicts: porelo-adapter-github"),
                report);

        assertThat(report.failures()).as("two packages cannot install as one").isEqualTo(1);
        assertThat(said()).as("what apt said").contains("Conflicts: porelo-adapter-github");
    }

    @Test
    void theStubOfferedFailsAndIsNamed() {
        ChannelLine.verdict(
                "snapshots", asked(0, "Inst porelo (1 x)", "Inst porelo-agent-stub (1 x)"), report);

        assertThat(report.failures()).as("a test's agent, as if it were a real one").isEqualTo(1);
        assertThat(said()).as("which").contains("porelo-agent-stub");
    }

    @Test
    void aFailureBeforeTheLineIsAskedFailsAndSaysWhatItGot() {
        ChannelLine.verdict(
                "releases", new Commands.Result(100, "E: the index could not be read\n"), report);

        assertThat(report.failures()).as("nothing was judged, so nothing passed").isEqualTo(1);
        assertThat(said()).as("what it got").contains("the index could not be read");
    }

    @Test
    void anAnswerWithoutPoreloFails() {
        ChannelLine.verdict("releases", asked(0, "Inst keepassxc (2 x)"), report);

        assertThat(report.failures()).as("a glob that matched nothing of ours").isEqualTo(1);
    }

    @Test
    void aNameTheRepositoryNoLongerBuildsFails() {
        ChannelLine.neverOfferedIsBuiltHere(dir, report);

        assertThat(report.failures()).as("a list that guards nothing").isEqualTo(1);
    }

    @Test
    void aNameTheRepositoryBuildsPasses() throws Exception {
        Files.createDirectories(dir.resolve("agents/stub"));
        Files.writeString(dir.resolve("agents/stub/pom.xml"), "<name>porelo-agent-stub</name>");

        ChannelLine.neverOfferedIsBuiltHere(dir, report);

        assertThat(report.failures()).as("what it said: %s", said()).isZero();
    }

    @Test
    void anUpdateCanonicalCannotAnswerIsTriedOnceOnTheTemporaryMirror() {
        final String script = ChannelLine.script("snapshots");
        assertThat(script)
                .as(
                        "a failed fetch fails the first update, which then goes to the temporary mirror")
                .contains("apt-get update -qq --error-on=any ||")
                .contains("URIs: http://mirrors.edge.kernel.org/ubuntu/")
                .doesNotContain("trusted=yes")
                .doesNotContain("allow-unauthenticated");
        assertThat(script.indexOf("mirrors.edge.kernel.org"))
                .as("before the page's own steps go on")
                .isLessThan(script.indexOf("apt-get install -y -qq ca-certificates"));
    }

    @Test
    void theScriptTypesTheLineAndChecksTheKeyAsThePageDoes() {
        assertThat(ChannelLine.script("snapshots"))
                .as("the line itself, simulated, and the fingerprint before the key is used")
                .contains("apt-get -s install 'porelo*' keepassxc")
                .contains("BF27A2FA637F505CAFBE431768D19D175D904415")
                .contains("snapshots main")
                .doesNotContain("apt-get install -y -qq porelo");
    }

    @Test
    void everyChannelIsAskedOnEveryDistribution() throws Exception {
        Files.createDirectories(dir.resolve("agents/stub"));
        Files.writeString(dir.resolve("agents/stub/pom.xml"), "porelo-agent-stub");
        final List<String> ran = new ArrayList<>();

        final boolean passed =
                new ChannelLine(
                                (command, limit) -> {
                                    ran.add(command.getLast());
                                    return asked(0, "Inst porelo (1 x)");
                                })
                        .check(dir, new PrintStream(printed, true, StandardCharsets.UTF_8));

        assertThat(passed).as("what it said: %s", said()).isTrue();
        assertThat(ran).as("two channels on two distributions").hasSize(4);
        assertThat(ran)
                .as("one of them, releases")
                .filteredOn(script -> script.contains(" releases main"))
                .hasSize(2);
    }
}
