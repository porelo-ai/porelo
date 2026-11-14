package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.AppArmorProfile;
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
                new TransportTestRunner(),
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
    void theNftsetLineSaysWhetherTheHooksDnsmasqIsMissingRefusedOrUnableToRun(@TempDir Path dir) {

        // The hooks take dnsmasq only from the fixed directories: the doctor judges that one, and
        // says which of three things is wrong, each with its own remedy.
        final FakeCommandRunner runner = new TransportTestRunner();
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0));
        final java.util.function.Function<
                        java.io.IOException, java.util.function.Supplier<List<String>>>
                refusing =
                        cause ->
                                () -> {
                                    throw new java.io.UncheckedIOException(
                                            cause.getMessage(), cause);
                                };

        final Probe missing =
                doctor.nftSetSupport(
                        refusing.apply(
                                new org.fuin.porelo.wire.SystemProgram.Missing(
                                        "dnsmasq", List.of(Path.of("/usr/sbin")))));
        final Probe refused =
                doctor.nftSetSupport(
                        refusing.apply(
                                new java.io.IOException(
                                        "Refusing /usr/sbin/dnsmasq: writable by group or others")));
        runner.answering("/usr/sbin/dnsmasq --version", "Compile time options: IPv6 nftset\n");
        final Probe found = doctor.nftSetSupport(() -> List.of("/usr/sbin/dnsmasq", "--version"));

        assertThat(missing.detail()).as("missing").contains("no dnsmasq in /usr/sbin");
        assertThat(missing.action()).contains("install a dnsmasq");
        assertThat(refused.detail())
                .as("refused, not reported as missing")
                .contains("the hooks refuse this dnsmasq")
                .contains("writable by group or others");
        assertThat(refused.action()).contains("root's own");
        assertThat(found.state()).as("the fixed path is the one asked").isEqualTo(Probe.State.OK);
        assertThat(runner.invocations())
                .anyMatch(command -> command.describe().startsWith("/usr/sbin/dnsmasq --version"));
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
                        "apparmor profile",
                        "transports",
                        "message filter");
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
                        new TransportTestRunner(),
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
                        new TransportTestRunner(),
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
                        new TransportTestRunner().failing("describe", 3, "no"),
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
                        new TransportTestRunner()
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
                        new TransportTestRunner()
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
        final FakeCommandRunner runner = new TransportTestRunner();
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
                new TransportTestRunner().answering("RootlessNetworkCmd", "slirp4netns\n");
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

    @Test
    void needsNoAppArmorProfileWhereAppArmorDoesNotRun() {
        final Probe probe = DoctorCommand.appArmor(AppArmorProfile.State.NOT_NEEDED, "");

        assertThat(probe.name()).isEqualTo("apparmor profile");
        assertThat(probe.state()).isEqualTo(Probe.State.OK);
        assertThat(probe.detail()).contains("not needed").contains("AppArmor");
    }

    @Test
    void namesTheInstallLineWhereTheTaskProfileIsMissing() {

        // A task runs as before there, so the machine stays ready: the line says what is not held.
        final Probe probe = DoctorCommand.appArmor(AppArmorProfile.State.MISSING, "unconfined");

        assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
        assertThat(probe.detail()).contains("porelo-task").contains("not loaded");
        assertThat(probe.action()).isEqualTo(AppArmorProfile.INSTALL);
        assertThat(DoctorCommand.ready(java.util.List.of(probe))).isTrue();
    }

    @Test
    void saysTheLockIsAbsentOnAKernelThatDoesNotMediateUnixSockets() {

        // Seen to fail: 'installed' where the kernel would not hold the rule.
        final Probe probe =
                DoctorCommand.appArmor(AppArmorProfile.State.ABSENT_ON_KERNEL, "porelo");

        assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
        assertThat(probe.detail()).contains("absent on this kernel").doesNotContain("installed");
    }

    @Test
    void saysSoWhenThisBinaryIsNotInTheHostProfile() {
        final Probe probe = DoctorCommand.appArmor(AppArmorProfile.State.OUTSIDE, "unconfined");

        assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
        assertThat(probe.detail())
                .as("an account binary has no profile under enforced stacking")
                .contains("no profile: unconfined", "lock is not held");
    }

    @Test
    void saysTheLockIsNotHeldForAnAccountInstall() {
        final Probe probe =
                DoctorCommand.appArmor(AppArmorProfile.State.OUTSIDE, "porelo//&unconfined");

        assertThat(probe.state())
                .as("tasks still work without the second lock")
                .isEqualTo(Probe.State.DEGRADED);
        assertThat(probe.detail())
                .as("the report must not promise socket protection here")
                .startsWith("loaded")
                .contains(
                        "lock is not held",
                        "account install",
                        "developer's build",
                        "porelo//&unconfined");
        assertThat(probe.action())
                .as("loading an already loaded profile cannot fix self-entry")
                .contains("machine-wide");
        assertThat(DoctorCommand.ready(java.util.List.of(probe)))
                .as("lack of the optional lock must not prevent tasks")
                .isTrue();
    }

    @Test
    void callsTheProfileInstalledOnlyWhenEverythingHolds() {
        final Probe probe = DoctorCommand.appArmor(AppArmorProfile.State.INSTALLED, "porelo");

        assertThat(probe.state()).isEqualTo(Probe.State.OK);
        assertThat(probe.detail())
                .as("keep the status word while stating where the lock holds")
                .startsWith("installed")
                .contains("plain porelo", "lock holds here", "machine-wide")
                .as("a plain label can also result from legacy account self-entry")
                .doesNotContain("for the machine-wide install");
    }

    @Test
    void doesNotMistakeAnUnreadableLabelForNoProfile() {
        final Probe probe = DoctorCommand.appArmor(AppArmorProfile.State.OUTSIDE, "");

        assertThat(probe.detail())
                .as("failure to read cannot prove an unconfined process")
                .contains("label could not be read", "lock is not held")
                .doesNotContain("no profile", "for this account install");
    }
}
