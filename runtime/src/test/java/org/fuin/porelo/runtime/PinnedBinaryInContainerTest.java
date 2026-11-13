package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The record {@link PinnedBinary#recording} writes and the check a start runs, in a real rootless
 * container, as root: a root-owned binary is pinned, and one the agent's user owns, reaches through
 * a link or a linked directory, can replace through a group-writable directory or can swap the
 * interpreter of is refused.
 *
 * <p>Opt-in, since a unit test runs without a container runtime: enable with {@code
 * -Dporelo.test.pinned.binary=true}. Needs podman and a local {@code ubuntu:24.04} image, and pulls
 * nothing.
 */
@EnabledIfSystemProperty(named = "porelo.test.pinned.binary", matches = "true")
class PinnedBinaryInContainerTest {

    private static final String IMAGE = "docker.io/library/ubuntu:24.04";

    /** The agent's user, its own bin folder last on PATH, as a task image has them. */
    private static final String SETUP =
            String.join(
                    "\n",
                    "set -e",
                    "useradd -u 1001 -m -s /bin/bash agent",
                    "mkdir -p /usr/local/bin /home/agent/.local/bin",
                    "chown -R agent:agent /home/agent/.local",
                    "export PATH=/usr/local/bin:/usr/bin:/bin:/home/agent/.local/bin",
                    "script() { printf '%s\\n' \"$2\" 'echo ran' > \"$1\"; chmod 0755 \"$1\"; }");

    @BeforeAll
    static void podmanIsThere() {
        assumeTrue(podmanWorks(), "podman with a local " + IMAGE + " is needed");
    }

    @Test
    void aRootOwnedBinaryIsPinnedAndOneTheAgentCouldChangeIsRefused() throws Exception {

        // An agent-owned binary, a root-owned link to one, and a directory its group may write.
        final String output =
                inContainer(
                        List.of(
                                "script /usr/local/bin/demo '#!/bin/sh'",
                                "script /home/agent/.local/bin/demo2 '#!/bin/sh'",
                                "chown agent:agent /home/agent/.local/bin/demo2",
                                "ln -s /home/agent/.local/bin/demo2 /usr/local/bin/demo3"),
                        List.of("demo", "demo2", "demo3"),
                        List.of("demo", "demo2", "demo3", "absent"),
                        List.of(
                                "chgrp agent /usr/local/bin && chmod g+w /usr/local/bin",
                                check("demo")));

        assertThat(output)
                .as("a root-owned script in a root-owned directory runs through its shell")
                .contains("== demo\nrun\t/bin/sh\nrun\t/usr/local/bin/demo\nexit=0")
                .as("one the agent owns is refused")
                .contains("== demo2\nchangeable\t/home/agent/.local/bin/demo2\nexit=3")
                .as("a root-owned link to one the agent owns is refused")
                .contains("== demo3\nchangeable\t/usr/local/bin/demo3\nexit=3")
                .as("a program the image never recorded is refused")
                .contains("== absent\nunrecorded\tabsent\nexit=3")
                .as("a root-owned binary in a directory the agent's group may write is refused")
                .contains("== demo\nchangeable\t/usr/local/bin\nexit=3");
    }

    @Test
    void aBinaryReachedThroughADirectoryTheAgentCanRenameIsRefused() throws Exception {

        // Checked lexically, /opt/dir/bin/demo6 passed: /opt/dir is a root-owned link to v1, which
        // the agent can rename away in its own /home/agent/work and put another in its place.
        final String output =
                inContainer(
                        List.of(
                                "mkdir -p /home/agent/work/v1 /usr/share/realbin",
                                "chown agent:agent /home/agent/work",
                                "script /usr/share/realbin/demo6 '#!/bin/sh'",
                                "ln -s /usr/share/realbin /home/agent/work/v1/bin",
                                "ln -s /home/agent/work/v1 /opt/dir",
                                "export PATH=/opt/dir/bin:$PATH"),
                        List.of("demo6"),
                        List.of("demo6"),
                        List.of());

        assertThat(output)
                .as("the agent's directory a resolution passes through is named")
                .contains("== demo6\nchangeable\t/home/agent/work\nexit=3");
    }

    @Test
    void aScriptRunsThroughTheInterpreterItsImageRecordedAndOneTheAgentCouldSwapIsRefused()
            throws Exception {

        // '#!/usr/bin/env NAME' looks NAME up on the start's PATH, which ends in the agent's own
        // bin folder: the agent planted the interpreter and ran as whatever started next.
        final String output =
                inContainer(
                        List.of(
                                "cp /bin/dash /home/agent/.local/bin/demo-interp",
                                "chown agent:agent /home/agent/.local/bin/demo-interp",
                                "script /usr/local/bin/demo7 '#!/usr/bin/env demo-interp'",
                                "cp /bin/dash /usr/local/bin/root-interp",
                                "script /usr/local/bin/demo8 '#!/usr/bin/env root-interp'",
                                "script /usr/local/bin/demo9 '#!/bin/sh -e'",
                                "script /usr/local/bin/demo10 '#!/usr/bin/env -S dash -e'",
                                "script /usr/local/bin/demo11 '#!/usr/local/bin/demo8'"),
                        List.of("demo7", "demo8", "demo9", "demo10", "demo11"),
                        List.of("demo7", "demo8", "demo9"),
                        List.of(ran("demo8"), ran("demo9")));

        assertThat(output)
                .as("an interpreter the agent's user owns is refused")
                .contains("== demo7\nchangeable\t/home/agent/.local/bin/demo-interp\nexit=3")
                .as("a root-owned one is run by its recorded path, the script after it")
                .contains(
                        "== demo8\nrun\t/usr/local/bin/root-interp\nrun\t/usr/local/bin/demo8\nexit=0")
                .as("an absolute interpreter keeps its one argument")
                .contains("== demo9\nrun\t/bin/sh\nrun\t-e\nrun\t/usr/local/bin/demo9\nexit=0")
                .as("each runs as the check says")
                .contains("ran demo8: ran\n")
                .contains("ran demo9: ran\n")
                .as("an env line that names no one program fails the build")
                .contains("build demo10: 1\n")
                .as("an interpreter that is itself a script fails the build")
                .contains("build demo11: 1\n");
    }

    /**
     * Runs the setup, the records' RUN steps each in its own shell, the checks, and what follows.
     */
    private static String inContainer(
            List<String> setup, List<String> recorded, List<String> checked, List<String> after)
            throws Exception {
        final List<String> script = new ArrayList<>(List.of(SETUP));
        script.addAll(setup);
        script.add("set +e");
        for (final String name : recorded) {
            script.add("( " + recording(name) + "\n); echo \"build " + name + ": $?\"");
        }
        for (final String name : checked) {
            script.add(check(name));
        }
        script.addAll(after);
        return run(
                "podman",
                "run",
                "--rm",
                "--network",
                "none",
                "--user",
                "root",
                IMAGE,
                "sh",
                "-c",
                String.join("\n", script),
                "sh",
                PinnedBinary.CHECK);
    }

    /** The record's RUN step, as a shell runs it. */
    private static String recording(String name) {
        final List<String> run = new ArrayList<>();
        boolean in = false;
        for (final String line : PinnedBinary.recording(name)) {
            if (line.startsWith("RUN ")) {
                in = true;
                run.add(line.substring("RUN ".length()));
            } else if (line.startsWith("USER ")) {
                in = false;
            } else if (in) {
                run.add(line);
            }
        }
        return String.join("\n", run);
    }

    private static String check(String name) {
        return "echo '== "
                + name
                + "'; sh -c \"$1\" "
                + PinnedBinary.CHECKED_BY
                + " "
                + name
                + "; echo \"exit=$?\"";
    }

    /** Runs what the check answers for a name, word by word, as a start does. */
    private static String ran(String name) {
        return "( words=$(sh -c \"$1\" "
                + PinnedBinary.CHECKED_BY
                + " "
                + name
                + " | sed -n 's/^run\\t//p'); IFS='\n'; set -f; set -- $words;"
                + " echo \"ran "
                + name
                + ": $(\"$@\")\" )";
    }

    private static boolean podmanWorks() {
        try {
            run("podman", "image", "exists", IMAGE);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String run(String... command) throws Exception {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(3, TimeUnit.MINUTES)).as("%s ends", List.of(command)).isTrue();
        assertThat(process.exitValue()).as("%s: %s", List.of(command), out).isZero();
        return out;
    }
}
