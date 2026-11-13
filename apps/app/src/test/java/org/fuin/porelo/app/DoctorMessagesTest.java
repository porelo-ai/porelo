package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Whether {@code porelo doctor} says this machine can carry its projects' messages. */
class DoctorMessagesTest {

    /** A transport's name that is no real one's. */
    private static final String SCHEME = "room";

    private static final String DESCRIBED =
            "{\"scheme\":\"room\",\"poll\":true,\"lifecycle\":[\"setup\",\"enroll\"]}";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new TransportTestRunner();

    private final List<String> asked = new ArrayList<>();

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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private void project(final PoreloContext context, final String transports) throws Exception {
        project(context, "p", transports);
    }

    private void project(final PoreloContext context, final String name, final String transports)
            throws Exception {
        final Path file = dir.resolve(name + ".yml");
        Files.writeString(
                file,
                "project:\n  name: "
                        + name
                        + "\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n"
                        + "repositories:\n  frontend:\n"
                        + transports);
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember(name, file);
    }

    /** A project followed whose file no longer reads. */
    private Path unreadable(final PoreloContext context, final String name) throws Exception {
        final Path file = dir.resolve(name + ".yml");
        Files.writeString(file, "project: [no\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember(name, file);
        return file;
    }

    /** A task of the project that runs, as podman lists it. */
    private void taskRuns(final String project) throws Exception {
        final String container = "porelo-" + project + "-shell-1";
        org.fuin.porelo.wire.Sidecar.recordId(
                Files.createDirectories(dir.resolve("run/porelo").resolve(container)),
                "111111111111");
        runner.answering(
                "ps",
                container
                        + "\tUp 4 minutes\t2026-01-01T00:00:00Z\t\t"
                        + project
                        + "\tguarded\t\t\t111111111111\n");
    }

    private void installed(final PoreloContext context) throws Exception {
        final Path directory = context.paths().xdg().data().resolve("transports");
        Files.createDirectories(directory);
        final Path adapter = directory.resolve(TransportDirectory.PREFIX + SCHEME);
        Files.writeString(adapter, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
    }

    /** What a setup of the project's conversation recorded: a server on this machine. */
    private static void setUpOnThisMachine(final PoreloContext context) throws Exception {
        final Path recorded =
                context.paths()
                        .xdg()
                        .state()
                        .resolve("transport")
                        .resolve(SCHEME)
                        .resolve("p.json");
        Files.createDirectories(recorded.getParent());
        Files.writeString(
                recorded, "{\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:8008\"]}");
    }

    private Probe probe(final PoreloContext context, final boolean listening) {
        return DoctorCommand.messages(
                context,
                reach -> {
                    asked.add(reach);
                    return listening;
                });
    }

    @Test
    void aMachineWhoseProjectsNameNoTransportSaysItNeedsNone() throws Exception {
        final PoreloContext context = context();
        project(context, "");

        final Probe probe = probe(context, false);

        assertThat(probe.state()).as("needing none is fine").isEqualTo(Probe.State.OK);
        assertThat(probe.detail()).as("it says it needs none").contains("needs none");
        assertThat(asked).as("nothing is asked of a server").isEmpty();
    }

    @Test
    void aTransportAProjectNamesThatIsNotInstalledIsAStateWithTheNextAction() throws Exception {
        final PoreloContext context = context();
        project(context, "mail:\n  transports:\n    room: {}\n");

        final Probe probe = probe(context, true);

        assertThat(probe.state()).as("not installed is no OK").isNotEqualTo(Probe.State.OK);
        assertThat(probe.detail()).as("it names the transport").contains(SCHEME, "p");
        assertThat(probe.action()).as("the next action is to install it").contains("install");
    }

    @Test
    void aTransportThatDoesNotAnswerIsAStateWithTheNextAction() throws Exception {
        final PoreloContext context = context();
        project(context, "mail:\n  transports:\n    room: {}\n");
        installed(context);
        runner.failing("describe", 3, "no");

        final Probe probe = probe(context, true);

        assertThat(probe.state()).as("a silent transport is no OK").isNotEqualTo(Probe.State.OK);
        assertThat(probe.detail()).as("it says which did not answer").contains(SCHEME, "describe");
        assertThat(probe.action()).as("a next action").isNotBlank();
    }

    @Test
    void aServerOnThisMachineThatDoesNotAnswerIsAStateWithTheNextAction() throws Exception {
        final PoreloContext context = context();
        project(context, "mail:\n  transports:\n    room: {}\n");
        installed(context);
        runner.answering("describe", DESCRIBED);
        setUpOnThisMachine(context);

        final Probe probe = probe(context, false);

        assertThat(asked).as("the server's address is asked").containsExactly("127.0.0.1:8008");
        assertThat(probe.state()).as("a silent server is no OK").isNotEqualTo(Probe.State.OK);
        assertThat(probe.detail()).as("it names where").contains("127.0.0.1:8008", "p");
        assertThat(probe.action()).as("a next action").isNotBlank();
    }

    @Test
    void anInstalledTransportAndAnAnsweringServerCarryTheMessages() throws Exception {
        final PoreloContext context = context();
        project(context, "mail:\n  transports:\n    room: {}\n");
        installed(context);
        runner.answering("describe", DESCRIBED);
        setUpOnThisMachine(context);

        final Probe probe = probe(context, true);

        assertThat(probe.state()).as("all of it answers").isEqualTo(Probe.State.OK);
        assertThat(probe.detail()).as("it names what carries them").contains(SCHEME, "p");
    }

    @Test
    void aServerElsewhereIsNotAsked() throws Exception {
        final PoreloContext context = context();
        project(context, "mail:\n  transports:\n    room: {server: 'https://example.org'}\n");
        installed(context);
        runner.answering("describe", DESCRIBED);
        final Path recorded =
                context.paths()
                        .xdg()
                        .state()
                        .resolve("transport")
                        .resolve(SCHEME)
                        .resolve("p.json");
        Files.createDirectories(recorded.getParent());
        Files.writeString(
                recorded, "{\"conversation\":\"!room-p\",\"reaches\":[\"example.org:443\"]}");

        final Probe probe = probe(context, false);

        assertThat(asked).as("a server elsewhere is not asked").isEmpty();
        assertThat(probe.state()).as("the transport answers").isEqualTo(Probe.State.OK);
    }

    @Test
    void aProjectNothingOwnsAnyMoreIsNotAskedAbout() throws Exception {
        final PoreloContext context = context();
        unreadable(context, "gone");

        final Probe probe = probe(context, false);

        assertThat(probe.state())
                .as("what nothing owns has its own line under projects, not this one")
                .isEqualTo(Probe.State.OK);
        assertThat(probe.detail()).as("it is not named").doesNotContain("gone");
    }

    @Test
    void aProjectWhoseFileDoesNotReadIsNamedBesideAnotherThatDegrades() throws Exception {
        final PoreloContext context = context();
        project(context, "mail:\n  transports:\n    room: {}\n");
        unreadable(context, "q");
        taskRuns("q");

        final Probe probe = probe(context, true);

        assertThat(probe.state()).as("the transport not installed").isEqualTo(Probe.State.DEGRADED);
        assertThat(probe.detail())
                .as("the project it cannot tell for is said too, never dropped")
                .contains(SCHEME, "q", "does not read");
    }

    @Test
    void aReachIsAskedOnlyAtThisMachinesLoopback() throws Exception {
        try (java.net.ServerSocket listening =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            final int port = listening.getLocalPort();
            assertThat(DoctorCommand.answersAt("127.0.0.[1]:" + port))
                    .as("a name that is loopback only once its brackets are stripped is not asked")
                    .isFalse();
            assertThat(DoctorCommand.answersAt("[127.0.0.1]x:" + port))
                    .as("nothing may stand between the bracket and the port")
                    .isFalse();
            assertThat(DoctorCommand.answersAt("localhost:" + port))
                    .as("localhost is asked at the loopback")
                    .isTrue();
        }
    }

    @Test
    void theDoctorListsTheLine() {
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(context());

        assertThat(doctor.probes())
                .as("the daemon's Doctor and the command ask the same list")
                .extracting(Probe::name)
                .contains("messages");
    }

    @Test
    void aServerIsAskedWhetherItAcceptsAConnection() throws Exception {
        final int closed;
        try (java.net.ServerSocket listening =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThat(DoctorCommand.answersAt("127.0.0.1:" + listening.getLocalPort()))
                    .as("one that listens answers")
                    .isTrue();
            closed = listening.getLocalPort();
        }
        assertThat(DoctorCommand.answersAt("127.0.0.1:" + closed))
                .as("one that stopped does not")
                .isFalse();
        assertThat(DoctorCommand.answersAt("127.0.0.1")).as("an address with no port").isFalse();
    }
}
