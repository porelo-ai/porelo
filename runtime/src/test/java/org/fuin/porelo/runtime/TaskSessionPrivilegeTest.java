package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task's session with real podman: its tmux server runs as the container's root, each pane and
 * every exec of the agent's holds no capability, and the agent reaches neither the server nor what
 * it reads.
 *
 * <p>Builds a task image on Ubuntu, so it needs podman and the network, which a unit test may not
 * assume. It runs only when asked, and skips without podman: {@code ./mvnw -s settings.xml -pl
 * runtime -am test -Dtest=TaskSessionPrivilegeTest -Dsurefire.failIfNoSpecifiedTests=false
 * -Dporelo.test.podman.build=true}. The container and the image are removed afterwards.
 */
class TaskSessionPrivilegeTest {

    private static final String ENABLED = "porelo.test.podman.build";

    private record Ran(int exit, String out) {}

    private static Ran run(List<String> command, int minutes) throws Exception {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(minutes, TimeUnit.MINUTES)).as("%s ends", command).isTrue();
        return new Ran(process.exitValue(), out);
    }

    private static boolean podman() {
        try {
            return run(List.of("podman", "--version"), 1).exit() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static Ran exec(String container, String user, String script) throws Exception {
        return run(List.of("podman", "exec", "--user", user, container, "sh", "-c", script), 2);
    }

    @Test
    void theServerIsRootsAndTheAgentHoldsNoCapabilityAndReachesNeither(@TempDir Path context)
            throws Exception {
        assumeTrue(Boolean.getBoolean(ENABLED), "set -D" + ENABLED + "=true to build with podman");
        assumeTrue(podman(), "no podman");

        final String suffix = String.valueOf(ProcessHandle.current().pid());
        final String tag = "localhost/session-privilege-test-" + suffix;
        final String container = "session-privilege-test-" + suffix;
        final Path file =
                Files.writeString(
                        context.resolve("Containerfile"),
                        Containerfile.render(
                                new Project(
                                        "privilege-test",
                                        "",
                                        SecurityClass.GUARDED,
                                        "docker.io/library/ubuntu:24.04",
                                        null)));
        try {
            final Ran build =
                    run(
                            List.of(
                                    "podman",
                                    "build",
                                    "--file",
                                    file.toString(),
                                    "--tag",
                                    tag,
                                    context.toString()),
                            15);
            assertThat(build.exit()).as("the build: %s", build.out()).isZero();

            final List<String> create = new ArrayList<>(List.of("podman", "run", "--detach"));
            create.addAll(
                    new ContainerSpec(container, tag).command("sleep", "infinity").toArguments());
            final Ran created = run(create, 2);
            assertThat(created.exit()).as("the container: %s", created.out()).isZero();

            // The start as Porelo runs it, as root, with its first window writing what the pane
            // is; detached, because nobody's terminal is here.
            final Ran started =
                    run(
                            List.of(
                                    "podman",
                                    "exec",
                                    "--user",
                                    "root",
                                    container,
                                    "sh",
                                    "-c",
                                    Podman.SESSION_START.replace(
                                            "new-session -A", "new-session -d -A"),
                                    "porelo-session",
                                    "{ id -un; grep -E '^(CapPrm|CapEff|CapAmb|NoNewPrivs):'"
                                            + " /proc/self/status; echo \"TMUX=${TMUX-none}\";"
                                            + " echo \"HOME=$HOME\"; } > /tmp/pane.txt;"
                                            + " sleep 600"),
                            2);
            assertThat(started.exit()).as("the start: %s", started.out()).isZero();

            String pane = "";
            for (int i = 0; i < 50 && !pane.contains("HOME="); i++) {
                Thread.sleep(200);
                pane = exec(container, "agent", "cat /tmp/pane.txt 2>/dev/null").out();
            }
            assertThat(pane.lines().toList())
                    .as("the pane: %s", pane)
                    .containsExactly(
                            "agent",
                            "CapPrm:\t0000000000000000",
                            "CapEff:\t0000000000000000",
                            "CapAmb:\t0000000000000000",
                            "NoNewPrivs:\t1",
                            "TMUX=none",
                            "HOME=/home/agent");

            assertThat(
                            exec(
                                            container,
                                            "agent",
                                            "grep -E '^(CapPrm|CapEff|CapAmb|NoNewPrivs):'"
                                                    + " /proc/self/status")
                                    .out()
                                    .lines()
                                    .toList())
                    .as("an exec of the agent's")
                    .containsExactly(
                            "CapPrm:\t0000000000000000",
                            "CapEff:\t0000000000000000",
                            "CapAmb:\t0000000000000000",
                            "NoNewPrivs:\t1");
            assertThat(exec(container, "agent", "setpriv --reuid=0 --regid=0 --clear-groups id"))
                    .as("the agent becoming root")
                    .extracting(Ran::exit)
                    .isNotEqualTo(0);

            assertThat(
                            exec(
                                            container,
                                            "root",
                                            "tmux -S "
                                                    + Containerfile.TMUX_SOCKET
                                                    + " has-session -t porelo")
                                    .exit())
                    .as("the session runs on root's server")
                    .isZero();
            assertThat(
                            exec(
                                            container,
                                            "agent",
                                            "tmux -S "
                                                    + Containerfile.TMUX_SOCKET
                                                    + " list-sessions")
                                    .exit())
                    .as("the agent connecting to the server")
                    .isNotZero();

            for (final String change :
                    List.of(
                            "echo 'set -g default-shell /tmp/x' >> " + Containerfile.TMUX_CONF,
                            "echo x > /tmp/x && mv -f /tmp/x " + Containerfile.TMUX_CONF,
                            "echo x > /tmp/x && mv -f /tmp/x " + Containerfile.REQUIRED_TMUX_CONF,
                            "touch " + Containerfile.ACCOUNT_TMUX_CONF,
                            "echo x >> " + Containerfile.AS_AGENT,
                            "touch " + Containerfile.TMUX_DIRECTORY + "/x")) {
                assertThat(exec(container, "agent", change).exit())
                        .as("the agent: %s", change)
                        .isNotZero();
            }
        } finally {
            run(List.of("podman", "rm", "--force", "--time", "0", container), 2);
            run(List.of("podman", "rmi", "--force", tag), 2);
        }
    }
}
