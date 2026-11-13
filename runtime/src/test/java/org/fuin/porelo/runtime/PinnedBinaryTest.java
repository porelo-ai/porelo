package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/** The program a task's image pins at build, and the check each start runs before it. */
class PinnedBinaryTest {

    private static final Project PROJECT =
            new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null);

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final Podman podman = new Podman(runner);

    @Test
    void anImageRecordsWhereItsAgentsBinaryResolvesAfterEveryLayerAsRoot() {

        // Looked up by name at each start, the binary was whatever the agent last left on PATH.
        final ImageLayers layers =
                new ImageLayers(List.of("RUN echo root-tooling"), List.of("RUN echo user-tooling"))
                        .pinning("preview");

        final List<String> lines = Containerfile.render(PROJECT, layers).lines().toList();

        assertThat(lines)
                .as("resolved as root once the agent's own layers have run, then back to the agent")
                .containsSubsequence(
                        "RUN echo user-tooling",
                        "USER root",
                        "RUN set -eu; \\",
                        "    found=$(command -v 'preview') \\",
                        "    printf '%s\\n' \"$found\" \"$target\" \"$interp\" \"$itarget\" \"$iarg\" > "
                                + PinnedBinary.RECORDS
                                + "/preview; \\",
                        "USER agent",
                        "LABEL org.fuin.porelo.recipe=\""
                                + Containerfile.fingerprint(PROJECT)
                                + "\"");
        assertThat(lines.stream().filter(line -> line.startsWith("USER ")).toList().getLast())
                .as("the task still runs as the agent")
                .isEqualTo("USER agent");
    }

    @Test
    void aNameAShellWouldReadIsNeverPinned() {

        // The name is spelled into a RUN line and a file name; a quote or a slash would be code.
        final ImageLayers layers = ImageLayers.none().pinning("x'; rm -rf /; '");

        assertThatThrownBy(() -> Containerfile.render(PROJECT, layers))
                .as("refused before anything is built")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no bare program name");
    }

    @Test
    void theCheckRunsAsRootAndAnswersTheRecordedPath() {

        // Asked as the agent, the check would be one the agent's own files could answer.
        runner.answering(PinnedBinary.CHECKED_BY, "run\t/usr/local/bin/preview\n");

        final PinnedBinary.Answer answer = podman.pinnedBinary("box", "preview");

        assertThat(answer)
                .as("the recorded path, as the check found it")
                .isEqualTo(new PinnedBinary.Pinned("preview", List.of("/usr/local/bin/preview")));
        assertThat(runner.only(PinnedBinary.CHECKED_BY).describe())
                .as(
                        "run as the container's root, the name an argument and never part of the script")
                .startsWith("podman exec --user root box /bin/sh -c ")
                .endsWith(PinnedBinary.CHECKED_BY + " preview");
        assertThat(((PinnedBinary.Pinned) answer).running(List.of("preview", "-p", "go on")))
                .as("the start runs the absolute path, never the bare name")
                .containsExactly("/usr/local/bin/preview", "-p", "go on");
    }

    @Test
    void aBinaryTheAgentsUserCouldWriteIsRefusedNamingThePath() {

        // An agent that wrote over its own binary picked what Porelo started as it next time.
        runner.failing(PinnedBinary.CHECKED_BY, 3, "changeable\t/home/agent/.local/bin\n", "");

        assertThat(podman.pinnedBinary("box", "preview"))
                .as("refused, with the path the agent's user can write")
                .isEqualTo(
                        new PinnedBinary.Unpinned(
                                PinnedBinary.Reason.CHANGEABLE, "/home/agent/.local/bin"));
    }

    @Test
    void aCheckThatAnsweredNothingIsNoYes() {

        // A check that did not run must not read as a binary that passed it.
        assertThat(podman.pinnedBinary("box", "preview"))
                .as("an empty answer refuses")
                .isEqualTo(new PinnedBinary.Unpinned(PinnedBinary.Reason.UNCHECKED, ""));
    }

    @Test
    void aCommandThatStartsWithAnotherProgramIsNotRunByThePinnedPath() {

        // Only the first word was checked; a command that starts with another runs it unchecked.
        final PinnedBinary.Pinned pinned =
                new PinnedBinary.Pinned("preview", List.of("/usr/local/bin/preview"));

        assertThatThrownBy(() -> pinned.running(List.of("sh", "-c", "preview")))
                .as("refused rather than run unchecked")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aScriptRunsThroughTheInterpreterTheCheckNamesNeverByItsShebang() {

        // Left to the kernel, '#!/usr/bin/env node' looked node up on the start's PATH, whose
        // last folder the agent writes.
        runner.answering(
                PinnedBinary.CHECKED_BY,
                "run\t/usr/bin/node\nrun\t--no-warnings\nrun\t/usr/local/bin/preview\n");

        final PinnedBinary.Answer answer = podman.pinnedBinary("box", "preview");

        assertThat(((PinnedBinary.Pinned) answer).running(List.of("preview", "-p", "go on")))
                .as("the recorded interpreter, its argument, the script, then the agent's words")
                .containsExactly(
                        "/usr/bin/node", "--no-warnings", "/usr/local/bin/preview", "-p", "go on");
    }

    @Test
    void anAnswerWithALineThatIsNoWordToRunIsNoYes() {

        // Whatever else the check printed could be anything; only its run lines are words.
        runner.answering(PinnedBinary.CHECKED_BY, "run\t/usr/bin/node\nnoise\n");

        assertThat(podman.pinnedBinary("box", "preview"))
                .as("refused rather than run in part")
                .isEqualTo(new PinnedBinary.Unpinned(PinnedBinary.Reason.UNCHECKED, ""));
    }

    @Test
    void theCheckLooksAtEveryDirectoryAResolutionOfThePathPassesThrough() {

        // Walked by name only, /opt/dir/bin/x passed while /opt/dir led into the agent's home.
        assertThat(PinnedBinary.CHECK)
                .as("every prefix resolved, and each walked up to the root")
                .contains("r=$(readlink -f -- \"$p\") || refuse unchecked \"$p\"")
                .contains("        above \"$r\"")
                .contains("above \"$hop\"; through \"$hop\"")
                .contains("above \"$2\"; through \"$2\"");
    }
}
