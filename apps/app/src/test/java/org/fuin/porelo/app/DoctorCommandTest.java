package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link DoctorCommand}, mostly about which binaries it says are in use.
 *
 * <p>The packaged locations are injected: read from the machine, these would pass or fail depending
 * on whether Porelo happens to be installed on it.
 */
class DoctorCommandTest {

    private final StringWriter out = new StringWriter();

    /**
     * An agent name that is not a real one - no name of a real agent may appear outside agents/.
     */
    private static final String AGENT = AgentDirectory.PREFIX + "example";

    private PoreloContext context(Path dir, Path hooks, Path packagedHooks, Path packagedAgents) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(xdg, hooks, packagedHooks, packagedAgents),
                arguments -> 0);
    }

    private String doctor(PoreloContext context) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        cmd.execute("doctor");
        return out.toString();
    }

    /**
     * Writes an executable agent binary.
     *
     * @param directory Where to write it.
     * @return The file.
     * @throws IOException If it cannot be written.
     */
    private static Path agent(Path directory) throws IOException {
        Files.createDirectories(directory);
        final Path file = Files.createFile(directory.resolve(AGENT));
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    @Test
    void reportsTheDaemonsHardeningRatherThanItsOwnProcesss(@TempDir Path dir) {
        // Its own process holds no secret and nothing hardens it, so "dumpable true" there was a
        // finding about nothing; the daemon is the process that holds the vault's secrets.
        final String said =
                doctor(
                        context(
                                dir,
                                dir.resolve("bin"),
                                dir.resolve("libexec"),
                                dir.resolve("agents")));

        assertThat(said).contains("daemon dumpable").doesNotContain("\ndumpable ");
        assertThat(said).doesNotContain("no new privileges");
    }

    @Test
    void everyFailureNamesTheOneThingToDoAboutIt(@TempDir Path dir) {

        // The rule this report lives or dies by, checked over the whole list rather than trusted
        // per line. Nothing is faked here, so which probes fail depends on the machine - what is
        // asserted is that whichever ones do, they say what to do.
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                context(
                        dir,
                        dir.resolve("bin"),
                        dir.resolve("libexec-hooks"),
                        dir.resolve("agents")));

        assertThat(doctor.probes())
                .isNotEmpty()
                .allSatisfy(
                        probe -> assertThat(probe.healthy() || !probe.action().isBlank()).isTrue());
    }

    @Test
    void namesAMissingVaultAsAStateOfItsOwnAndAPresentOneAsThere(@TempDir Path dir)
            throws java.io.IOException {

        // A machine just prepared has none, and an empty credential list cannot tell that from a
        // shut vault.
        final PoreloContext context =
                context(
                        dir,
                        dir.resolve("bin"),
                        dir.resolve("libexec-hooks"),
                        dir.resolve("agents"));
        final java.util.function.Function<PoreloContext, Probe> vault =
                each ->
                        DoctorCommand.probesFor(each).stream()
                                .filter(probe -> "vault".equals(probe.name()))
                                .findFirst()
                                .orElseThrow();

        assertThat(vault.apply(context).state()).isEqualTo(Probe.State.DEGRADED);
        assertThat(vault.apply(context).detail()).contains("there is no vault here yet");
        assertThat(vault.apply(context).action()).contains("porelo setup").contains("ssh -t");

        java.nio.file.Files.createDirectories(context.vault().path().getParent());
        java.nio.file.Files.writeString(context.vault().path(), "found, not read");
        assertThat(vault.apply(context).state()).isEqualTo(Probe.State.OK);
    }

    @Test
    void refusesAProbeThatFailsWithNothingToDoAboutIt() {

        // Enforced in the type, because the line somebody forgets is the line an operator is
        // reading at their worst moment.
        assertThatThrownBy(() -> new Probe("nft", Probe.State.MISSING, "not installed", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no next action");
    }

    @Test
    void probesEveryExternalDependencyByName(@TempDir Path dir) {

        // Each of these fails somewhere far from itself: no ruleset, no mirror, no cached
        // passphrase, a name that resolves and then does not connect.
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                context(
                        dir,
                        dir.resolve("bin"),
                        dir.resolve("libexec-hooks"),
                        dir.resolve("agents")));

        assertThat(doctor.probes())
                .extracting(Probe::name)
                .contains(
                        "podman",
                        "hooks registered",
                        "rootless network",
                        "dnsmasq nftset",
                        "nft",
                        "git",
                        "nsenter",
                        "keyring",
                        "selinux policy",
                        "transports",
                        "message filter",
                        "configuration key",
                        "following");
    }

    /**
     * A machine with no transport carries no message anywhere. That is a state worth reporting
     * rather than a fault: nothing is broken, and nothing will arrive either.
     */
    @Test
    void saysWhenNoTransportIsInstalled(@TempDir Path dir) {

        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0));

        assertThat(doctor.probes())
                .filteredOn(probe -> "transports".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail()).contains("none installed");
                        });
    }

    /**
     * Its own line, because the two silences are different: with no transport a message reaches no
     * peer, and with no filter nothing is sent at all. A machine with every transport installed and
     * no filter is mute, and a list of green lines must not read as ready.
     */
    @Test
    void saysWhenNoMessageFilterIsInstalled(@TempDir Path dir) {

        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0));

        assertThat(doctor.probes())
                .filteredOn(probe -> "message filter".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail())
                                    .contains("nothing a task writes ever leaves");
                            assertThat(probe.action()).contains(PoreloPaths.MESSAGE_FILTER);
                        });
    }

    /**
     * A record of followed projects that cannot be read is reported, not thrown: the probe once
     * named no next action, which the probe itself refuses, so doctor failed where it should
     * report.
     */
    @Test
    void reportsFollowedProjectsItCannotReadWithSomethingToDo(@TempDir Path dir)
            throws java.io.IOException {

        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloPaths paths = new PoreloPaths(xdg, dir.resolve("bin"));
        final Path followed = java.nio.file.Files.createDirectories(paths.projects().followed());
        java.nio.file.Files.setPosixFilePermissions(followed, java.util.Set.of());
        try {
            final DoctorCommand doctor = new DoctorCommand();
            doctor.setContext(new PoreloContext(new FakeCommandRunner(), paths, arguments -> 0));

            assertThat(doctor.probes())
                    .filteredOn(probe -> "following".equals(probe.name()))
                    .singleElement()
                    .satisfies(
                            probe -> {
                                assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                                assertThat(probe.detail()).contains("cannot be read");
                                assertThat(probe.action()).contains(followed.toString());
                            });
        } finally {
            java.nio.file.Files.setPosixFilePermissions(
                    followed, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * A machine with nothing pinned does not break - it stops following its projects, which is the
     * kind of failure that looks like nothing at all.
     */
    @Test
    void saysWhenNoConfigurationKeyIsPinnedForAFollowedProjectAndNeedsNoneWithoutOne(
            @TempDir Path dir) throws java.io.IOException {

        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(context);

        // A machine that works only in 'default' needs no key: DEGRADED there was read by a
        // newcomer before the guide had said a word about keys.
        assertThat(doctor.probes())
                .filteredOn(probe -> "configuration key".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.OK);
                            assertThat(probe.detail()).contains("none needed yet");
                        });

        new FollowedProjects(context.paths().projects().followed())
                .follow("demo", "https://forge.example/demo.git");
        assertThat(doctor.probes())
                .filteredOn(probe -> "configuration key".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail()).contains("none pinned");
                        });
    }

    /**
     * An adapter that is installed and does not answer is worse than one that is absent: a peer
     * configured against it looks configured.
     */
    @Test
    void saysWhenAnInstalledTransportDoesNotAnswer(@TempDir Path dir) throws java.io.IOException {

        final java.nio.file.Path transports =
                dir.resolve("data").resolve("porelo").resolve("transports");
        java.nio.file.Files.createDirectories(transports);
        final java.nio.file.Path adapter = transports.resolve(TransportDirectory.PREFIX + "local");
        java.nio.file.Files.writeString(adapter, "#!/bin/sh\nexit 3\n");
        java.nio.file.Files.setPosixFilePermissions(
                adapter, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        new FakeCommandRunner().failing("describe", 3, "no"),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0));

        assertThat(doctor.probes())
                .filteredOn(probe -> "transports".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail()).contains("local did not answer");
                        });
    }

    /**
     * A transport handed its secrets in its environment is named, until every published transport
     * takes them on its input: another process of the account reads them there.
     */
    @Test
    void namesATransportThatTakesItsSecretsInItsEnvironment(@TempDir Path dir)
            throws java.io.IOException {
        final java.nio.file.Path transports = dir.resolve("data/porelo/transports");
        java.nio.file.Files.createDirectories(transports);
        for (final String transport : java.util.List.of("local", "room", "plain", "broken")) {
            final java.nio.file.Path adapter =
                    transports.resolve(TransportDirectory.PREFIX + transport);
            java.nio.file.Files.writeString(adapter, "#!/bin/sh\n");
            java.nio.file.Files.setPosixFilePermissions(
                    adapter, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        new FakeCommandRunner()
                                .answering(
                                        "local describe",
                                        "{\"scheme\":\"local\",\"lifecycle\":[\"setup\"]}")
                                .answering(
                                        "room describe",
                                        "{\"scheme\":\"room\",\"lifecycle\":[\"setup\"],"
                                                + "\"secrets\":\"input\"}")
                                .answering("plain describe", "{\"scheme\":\"plain\"}")
                                .failing("broken describe", 3, "no"),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0));

        assertThat(doctor.probes())
                .filteredOn(probe -> "transports".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state())
                                    .as("degraded by the one that did not answer, not by this")
                                    .isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail())
                                    .as("said beside a transport that did not answer, too")
                                    .contains("local takes its secrets in its environment")
                                    .doesNotContain("room takes")
                                    .as("one that keeps no conversation is handed no secret")
                                    .doesNotContain("plain takes");
                        });
    }

    /** Named while every transport answers too, and then only said, not degraded. */
    @Test
    void namesAnOldTransportAndStaysOkWhenEveryTransportAnswers(@TempDir Path dir)
            throws java.io.IOException {
        final java.nio.file.Path transports = dir.resolve("data/porelo/transports");
        java.nio.file.Files.createDirectories(transports);
        for (final String transport : java.util.List.of("local", "room")) {
            final java.nio.file.Path adapter =
                    transports.resolve(TransportDirectory.PREFIX + transport);
            java.nio.file.Files.writeString(adapter, "#!/bin/sh\n");
            java.nio.file.Files.setPosixFilePermissions(
                    adapter, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        new FakeCommandRunner()
                                .answering(
                                        "local describe",
                                        "{\"scheme\":\"local\",\"lifecycle\":[\"setup\"]}")
                                .answering(
                                        "room describe",
                                        "{\"scheme\":\"room\",\"lifecycle\":[\"setup\"],"
                                                + "\"secrets\":\"input\"}"),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0));

        assertThat(doctor.probes())
                .filteredOn(probe -> "transports".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state())
                                    .as("an accepted risk until every transport has moved")
                                    .isEqualTo(Probe.State.OK);
                            assertThat(probe.detail())
                                    .contains("local takes its secrets in its environment")
                                    .doesNotContain("room takes");
                        });
    }

    @Test
    void asksATransportWithTheEnvironmentEveryHelperGets(@TempDir Path dir) throws Exception {

        // The same chosen environment the message area starts it with: nothing of the caller's that
        // steers a helper, and a UTF-8 locale where the caller had none.
        final java.nio.file.Path transports = dir.resolve("data/porelo/transports");
        java.nio.file.Files.createDirectories(transports);
        final java.nio.file.Path adapter = transports.resolve(TransportDirectory.PREFIX + "local");
        java.nio.file.Files.writeString(adapter, "#!/bin/sh\nexit 0\n");
        java.nio.file.Files.setPosixFilePermissions(
                adapter, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        final FakeCommandRunner runner = new FakeCommandRunner();
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0));

        doctor.probes();

        assertThat(runner.invocations())
                .filteredOn(command -> command.arguments().contains("describe"))
                .as("the transport is asked once, from the chosen environment")
                .singleElement()
                .satisfies(
                        command -> {
                            assertThat(command.inheritsEnvironment())
                                    .as("nothing of the caller's is inherited")
                                    .isFalse();
                            assertThat(command.environment())
                                    .as("only what passes, with a locale")
                                    .containsKey("LANG")
                                    .doesNotContainKey("CLAUDECODE");
                        });
    }

    @Test
    void saysWhenABackendCannotMapTheHostsLoopback(@TempDir Path dir) {

        // It works, and the git gate ends up on the operator's network with only its per-task token
        // in front of it. Degraded rather than missing: the machine runs tasks.
        final FakeCommandRunner runner =
                new FakeCommandRunner().answering("RootlessNetworkCmd", "slirp4netns\n");
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0));

        assertThat(doctor.probes())
                .filteredOn(probe -> "rootless network".equals(probe.name()))
                .singleElement()
                .satisfies(
                        probe -> {
                            assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                            assertThat(probe.detail()).contains("binds every interface");
                            assertThat(probe.action()).contains("passt");
                        });
    }

    @Test
    void namesWhereTheHooksAndAgentsComeFrom(@TempDir Path dir) throws IOException {

        final Path hooks = Files.createDirectory(dir.resolve("bin"));
        final Path packagedAgents = Files.createDirectory(dir.resolve("libexec-agents"));

        final String report =
                doctor(context(dir, hooks, dir.resolve("libexec-hooks"), packagedAgents));

        assertThat(report).contains("hooks    " + hooks);
        assertThat(report).contains("agents   " + dir.resolve(".local/share/porelo/agents"));
        assertThat(report).contains("agents   " + packagedAgents);
    }

    @Test
    void saysNothingAboutShadowingOnAPlainInstall(@TempDir Path dir) throws IOException {

        // A line that is always printed is a line nobody reads.
        final Path packaged = Files.createDirectory(dir.resolve("libexec-hooks"));
        Files.writeString(packaged.resolve("porelo-hook-nft"), "");

        final String report =
                doctor(
                        context(
                                dir,
                                packaged,
                                packaged,
                                Files.createDirectory(dir.resolve("agents"))));

        assertThat(report).doesNotContain("not used");
    }

    @Test
    void namesThePackagedHooksALocalBuildHides(@TempDir Path dir) throws IOException {

        final Path own = Files.createDirectory(dir.resolve("bin"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec-hooks"));
        Files.writeString(own.resolve("porelo-hook-nft"), "");
        Files.writeString(packaged.resolve("porelo-hook-nft"), "");

        final String report =
                doctor(context(dir, own, packaged, Files.createDirectory(dir.resolve("agents"))));

        // Both halves: an operator told only that a copy is unused goes looking for the one that is
        // used.
        assertThat(report).contains("not used " + packaged);
        assertThat(report).contains("hidden by " + own);
    }

    @Test
    void namesThePackagedAgentALocalBuildHides(@TempDir Path dir) throws IOException {

        // Found on a real machine: yesterday's build in the data directory, and nothing said so.
        agent(dir.resolve(".local/share/porelo/agents"));
        final Path packagedAgents = dir.resolve("libexec-agents");
        final Path theirs = agent(packagedAgents);

        final String report =
                doctor(
                        context(
                                dir,
                                dir.resolve("bin"),
                                dir.resolve("libexec-hooks"),
                                packagedAgents));

        assertThat(report).contains("not used " + theirs);
        assertThat(report)
                .contains("hidden by " + dir.resolve(".local/share/porelo/agents").resolve(AGENT));
    }
}
