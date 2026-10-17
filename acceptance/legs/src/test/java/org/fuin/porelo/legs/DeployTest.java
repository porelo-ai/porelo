package org.fuin.porelo.legs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.machines.Ssh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeployTest {

    /** Where the fake machine's mktemp says a deploy stages its files. */
    static final String STAGING = "/tmp/porelo-deploy.test";

    @TempDir Path repository;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    private final Machine machine = new Machine();

    @Test
    void outranksTheHighestRunTheMachineHasOrIsOffered() throws IOException {
        // Installed 157, offered 162: beating only what was installed lost within the hour.
        machine.answer("dpkg-query -W", "0.1.0~snapshot.157\n");
        machine.answer(
                "LC_ALL=C apt-cache policy",
                """
                porelo:
                  Installed: 0.1.0~snapshot.157
                  Candidate: 0.1.0~snapshot.162
                """);
        machine.answer(
                "LC_ALL=C apt-cache madison",
                """
                     porelo | 0.1.0~snapshot.162 | https://fuinorg.jfrog.io snapshots/main amd64 Packages
                     porelo | 0.1.0~snapshot.161 | https://fuinorg.jfrog.io snapshots/main amd64 Packages
                """);

        assertThat(Deploy.outranking(machine)).isEqualTo("162.1");
        assertThat(machine.commands.getFirst()).contains("apt-get update");
    }

    @Test
    void comparesRunNumbersAsNumbersNotAsText() {
        assertThat(
                        Deploy.highestRun(
                                List.of(
                                        "0.1.0~snapshot.99",
                                        "0.1.0~snapshot.162.1+local.20260919T1000",
                                        "0.1.0~snapshot.162")))
                .contains("162.1");
        assertThat(Deploy.highestRun(List.of("0.1.0~snapshot.9", "0.1.0~snapshot.10")))
                .contains("10");
    }

    @Test
    void startsAboveNothingOnAMachineThatHasNeverSeenPorelo() throws IOException {
        assertThat(Deploy.outranking(machine)).isEqualTo("0.1");
    }

    @Test
    void installsLingersAndHandsOverTheSocket() throws IOException {
        final Path deb = deb();
        machine.answer("bash -lc 'command -v porelo'", "/usr/bin/porelo\n");
        machine.answer("echo \"/run/user", "/run/user/1001/porelo/porelod.sock\n");
        machine.answer(
                "porelo agents", "NAME  FROM\nstub  /home/somebody/.local/share/porelo/agents\n");

        final int code =
                Deploy.deploy(
                        "somebody@192.0.2.10", repository, "7.1", false, machine, run -> 0, out);

        assertThat(code).as(text()).isZero();
        assertThat(machine.uploads).containsEntry(deb, STAGING + "/" + deb.getFileName());
        assertThat(machine.commands)
                .contains(
                        "sudo dpkg -i --force-downgrade '"
                                + STAGING
                                + "/"
                                + deb.getFileName()
                                + "'",
                        Deploy.lingerCommand("somebody"));
        assertThat(text())
                .contains("socket   /run/user/1001/porelo/porelod.sock")
                .contains("agents it can run: stub");
    }

    @Test
    void refusesToGoOnWhenAnotherPoreloShadowsThePackage() throws IOException {
        deb();
        machine.answer("bash -lc 'command -v porelo'", "/home/somebody/.local/bin/porelo\n");

        final int code =
                Deploy.deploy("somebody@host", repository, "7.1", true, machine, run -> 0, out);

        assertThat(code).isEqualTo(1);
        assertThat(text())
                .contains(
                        "PATH finds /home/somebody/.local/bin/porelo, not the package at /usr/bin/porelo");
        assertThat(machine.commands).noneMatch(command -> command.contains("systemctl"));
    }

    @Test
    void installsNothingWhenTheBuildFails() throws IOException {
        deb();

        final int code =
                Deploy.deploy("somebody@host", repository, "7.1", false, machine, run -> 1, out);

        assertThat(code).isEqualTo(1);
        assertThat(machine.uploads).isEmpty();
        assertThat(text()).contains("the build failed with exit code 1; nothing was installed");
    }

    @Test
    void buildsWithTheRunNumberItChose() throws IOException {
        deb();
        final List<String> built = new ArrayList<>();

        Deploy.deploy(
                "somebody@host",
                repository,
                null,
                false,
                machine,
                run -> {
                    built.add(run);
                    return 1;
                },
                out);

        assertThat(built).containsExactly("0.1");
    }

    @Test
    void stopsWhenTheInstallIsRefused() throws IOException {
        deb();
        machine.fail("sudo dpkg -i");

        final int code =
                Deploy.deploy("somebody@host", repository, "7.1", true, machine, run -> 0, out);

        assertThat(code).isEqualTo(1);
        assertThat(text()).contains("failed (1): sudo dpkg -i");
        assertThat(machine.commands).noneMatch(command -> command.contains("loginctl"));
    }

    @Test
    void refusesToInstallAnEarlierBuildThatNoLongerOutranksWhatIsOffered() throws IOException {
        deb();
        machine.answer(
                "LC_ALL=C apt-cache madison",
                "     porelo | 0.1.0~snapshot.180 | https://x snapshots/main amd64 Packages\n");

        final int code =
                Deploy.deploy("somebody@host", repository, null, true, machine, run -> 0, out);

        assertThat(code).isEqualTo(1);
        assertThat(machine.uploads).isEmpty();
        assertThat(text()).contains("is run 7.1, below 180.1, so apt would replace it");
    }

    @Test
    void installsIntoOneAccountWithoutDpkgAndMeasuresWhatItNowRuns() throws IOException {
        final Path deb = deb();
        accountRunsItsOwn();

        final int code =
                Deploy.deploy(
                        "alice@host",
                        repository,
                        "7.1",
                        true,
                        Deploy.Scope.ACCOUNT,
                        machine,
                        run -> 0,
                        out);

        assertThat(code).as(text()).isZero();
        assertThat(machine.uploads).containsEntry(deb, STAGING + "/" + deb.getFileName());
        // Nothing machine-wide: no package manager, and so nothing another account runs changes.
        assertThat(machine.commands)
                .noneMatch(command -> command.contains("dpkg -i") || command.contains("dpkg -r"));
        assertThat(machine.commands)
                .anyMatch(command -> command.startsWith("set -e; d=$(mktemp -d); dpkg-deb -x"));
        assertThat(text())
                .contains("PATH finds  /home/alice/.local/bin/porelo")
                .contains("daemon runs /home/alice/.local/bin/porelod")
                .contains("hook        /home/alice/.local/bin/porelo-hook-nft")
                .contains("the package: 0.1.0~snapshot.182.1");
    }

    @Test
    void refusesAnAccountInstallWhoseDaemonStillRunsThePackage() throws IOException {
        deb();
        accountRunsItsOwn();
        daemonAnswers(4242, "/usr/bin/porelod");

        final int code =
                Deploy.deploy(
                        "alice@host",
                        repository,
                        "7.1",
                        true,
                        Deploy.Scope.ACCOUNT,
                        machine,
                        run -> 0,
                        out);

        assertThat(code).isEqualTo(1);
        assertThat(text())
                .contains("daemon runs /usr/bin/porelod")
                .contains("not everything names /home/alice/.local/bin/");
    }

    @Test
    void refusesAnAccountInstallWhenTheProcessOnTheSocketIsNotTheUnits() throws IOException {
        // A daemon started by hand still holds the socket: what it says it runs is not what the
        // account's unit now runs.
        deb();
        accountRunsItsOwn();
        daemonAnswers(977, "/home/alice/.local/bin/porelod");

        final int code =
                Deploy.deploy(
                        "alice@host",
                        repository,
                        "7.1",
                        true,
                        Deploy.Scope.ACCOUNT,
                        machine,
                        run -> 0,
                        out);

        assertThat(code).as(text()).isEqualTo(1);
        assertThat(text()).contains("process 977 answers on the socket, and the unit's is 4242");
    }

    @Test
    void installsAnAccountsCopyEvenBelowWhatAptOffers() throws IOException {
        // apt does not know an account's copy, so it cannot replace it.
        deb();
        accountRunsItsOwn();
        machine.answer(
                "LC_ALL=C apt-cache madison",
                "     porelo | 0.1.0~snapshot.180 | https://x snapshots/main amd64 Packages\n");

        final int code =
                Deploy.deploy(
                        "alice@host",
                        repository,
                        null,
                        true,
                        Deploy.Scope.ACCOUNT,
                        machine,
                        run -> 0,
                        out);

        assertThat(code).as(text()).isZero();
    }

    @Test
    void pointsTheAccountsUnitAtTheAccountsDaemonAndChecksItDid() {
        final String script = Deploy.accountInstall("/tmp/porelo_1_amd64.deb");

        assertThat(script)
                .startsWith("set -e;")
                .doesNotContain("sudo")
                .contains("dpkg-deb -x '/tmp/porelo_1_amd64.deb'")
                .contains("s|^ExecStart=/usr/bin/porelod|ExecStart=%h/.local/bin/porelod|")
                .contains("grep -q '^ExecStart=%h/.local/bin/porelod$'")
                .contains("\"$HOME/.local/bin\"/porelo setup");
    }

    @Test
    void readsTheExecutablesTheHookDescriptorsName() {
        assertThat(
                        Deploy.hookPaths(
                                """
                {"version":"1.0.0","hook":{"path":"/home/alice/.local/bin/porelo-hook-nft","args":["x"]}}
                {"version": "1.0.0", "hook": {"path" : "/usr/libexec/porelo/hooks/porelo-hook-reader"}}
                """))
                .containsExactly(
                        "/home/alice/.local/bin/porelo-hook-nft",
                        "/usr/libexec/porelo/hooks/porelo-hook-reader");
    }

    private void accountRunsItsOwn() {
        machine.answer("dpkg-query -W", "0.1.0~snapshot.182.1");
        machine.answer("echo \"$HOME\"", "/home/alice\n");
        machine.answer("bash -lc 'command -v porelo'", "/home/alice/.local/bin/porelo\n");
        machine.answer("systemctl --user show -p MainPID", "4242\n");
        daemonAnswers(4242, "/home/alice/.local/bin/porelod");
        machine.answer(
                "cat \"$HOME\"/.config/containers/oci/hooks.d",
                "{\"hook\":{\"path\":\"/home/alice/.local/bin/porelo-hook-nft\"}}");
        machine.answer("echo \"/run/user", "/run/user/1008/porelo/porelod.sock\n");
    }

    /** Answers the daemon's {@code Process} call as {@code porelo daemon connect} prints it. */
    private void daemonAnswers(long pid, String executable) {
        machine.answer(
                "printf",
                "{\"parameters\":{\"pid\":" + pid + ",\"executable\":\"" + executable + "\"}}\0");
    }

    @Test
    void readsTheAgentNamesUnderTheHeader() {
        assertThat(Deploy.agents("NAME   FROM\nfirst  /usr/libexec\nsecond /home/x\n\n"))
                .containsExactly("first", "second");
    }

    @Test
    void installsTheNewestPackageWhenTargetHoldsSeveral() throws IOException {
        final Path target = Files.createDirectories(repository.resolve(Deploy.PACKAGES));
        final Path older =
                Files.writeString(target.resolve("porelo_0.1.0~snapshot.1_amd64.deb"), "");
        final Path newer =
                Files.writeString(target.resolve("porelo_0.1.0~snapshot.2_amd64.deb"), "");
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(2000));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1000));

        assertThat(Deploy.newest(target, "porelo_*.deb")).contains(older);
    }

    private Path deb() throws IOException {
        final Path target = Files.createDirectories(repository.resolve(Deploy.PACKAGES));
        return Files.writeString(target.resolve("porelo_0.1.0~snapshot.7.1_amd64.deb"), "");
    }

    private String text() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * A machine that succeeds at everything unless told otherwise, and remembers what it was asked.
     */
    private static final class Machine implements Deploy.Remote {

        private final Map<String, String> answers = new LinkedHashMap<>();

        private final List<String> failing = new ArrayList<>();

        final List<String> commands = new ArrayList<>();

        final Map<Path, String> uploads = new LinkedHashMap<>();

        {
            // Each deploy stages in a directory of its own, as mktemp names it.
            answers.put("mktemp -d", STAGING + "\n");
        }

        void answer(String prefix, String output) {
            answers.put(prefix, output);
        }

        void fail(String prefix) {
            failing.add(prefix);
        }

        @Override
        public Ssh.Output run(String command) {
            commands.add(command);
            if (failing.stream().anyMatch(command::startsWith)) {
                return new Ssh.Output("", "refused", 1);
            }
            return answers.entrySet().stream()
                    .filter(entry -> command.startsWith(entry.getKey()))
                    .findFirst()
                    .map(entry -> new Ssh.Output(entry.getValue(), "", 0))
                    .orElse(new Ssh.Output("", "", 0));
        }

        @Override
        public void upload(Path local, String remote) {
            uploads.put(local, remote);
        }
    }

    @org.junit.jupiter.api.Test
    void asksForLingerOnlyWhereTheAccountDoesNotHaveIt() {

        // It ran 'sudo loginctl enable-linger' every time: an account without sudo, lingering
        // already, stopped there.
        final String command = Deploy.lingerCommand("tester");

        org.assertj.core.api.Assertions.assertThat(command)
                .startsWith(
                        "[ \"$(loginctl show-user 'tester' -p Linger --value 2>/dev/null)\" = yes ] ||")
                .endsWith("sudo loginctl enable-linger 'tester'");
    }

    @org.junit.jupiter.api.Test
    void stagesInADirectoryOfItsOwnAndRemovesItEvenWhenTheInstallFails() throws IOException {

        // The package went to a fixed name in /tmp: the second account to deploy the same handover
        // could not overwrite the first one's file, and stopped - "scp: /tmp/porelo_…292….deb:
        // Permission denied".
        final Path deb = deb();
        machine.fail("set -e; d=$(mktemp -d); dpkg-deb -x");

        Deploy.deploy(
                "bob@host", repository, "7.1", true, Deploy.Scope.ACCOUNT, machine, run -> 0, out);

        assertThat(machine.uploads).containsEntry(deb, STAGING + "/" + deb.getFileName());
        assertThat(machine.commands).contains("rm -rf '" + STAGING + "'");
    }

    @Test
    void everyFileADeployCopiesGoesIntoItsOwnDirectoryNeverAFixedName() throws IOException {
        // Several accounts deploy the same handover on one machine; a fixed name in /tmp is the
        // first one's alone.
        final Path deb = deb();
        final Path stub =
                Files.createDirectories(repository.resolve("agents/stub/target"))
                        .resolve("porelo-agent-stub");
        Files.writeString(stub, "");
        stub.toFile().setExecutable(true);
        accountRunsItsOwn();

        Deploy.deploy(
                "alice@host",
                repository,
                "7.1",
                true,
                Deploy.Scope.ACCOUNT,
                machine,
                run -> 0,
                out);
        Deploy.deploy("alice@host", repository, "7.1", true, machine, run -> 0, out);

        assertThat(machine.commands)
                .as("each deploy asks for a directory of its own")
                .filteredOn(command -> command.startsWith("mktemp -d"))
                .hasSize(2)
                .allMatch(command -> command.equals("mktemp -d /tmp/porelo-deploy.XXXXXXXX"));
        assertThat(machine.uploads.values())
                .as("what was copied, and where to")
                .isNotEmpty()
                .allMatch(target -> target.startsWith(STAGING + "/"));
        assertThat(machine.uploads).as("the stub too").containsKey(stub);
    }

    @Test
    void theBuildReadersAHandoverCarriesGoIntoTheAccountToo() throws IOException {

        // A handover's builds/github and builds/stub-forge were left out, and each walk had them
        // put in by hand.
        final Path deb = deb();
        final Path builds = Files.createDirectories(repository.resolve("builds"));
        for (final String reader : java.util.List.of("github", "stub-forge")) {
            Files.writeString(builds.resolve(reader), "");
            builds.resolve(reader).toFile().setExecutable(true);
        }
        Files.writeString(builds.resolve("README.md"), "not a reader");
        accountRunsItsOwn();

        assertThat(
                        Deploy.deploy(
                                "alice@host",
                                repository,
                                "7.1",
                                true,
                                Deploy.Scope.ACCOUNT,
                                machine,
                                run -> 0,
                                out))
                .as(text())
                .isZero();

        assertThat(machine.uploads)
                .containsKeys(builds.resolve("github"), builds.resolve("stub-forge"))
                .doesNotContainKey(builds.resolve("README.md"));
        assertThat(machine.commands)
                .anyMatch(
                        command ->
                                command.contains("install -D -m 0755")
                                        && command.contains(
                                                "\"$HOME/.local/share/porelo/builds/github\""))
                .anyMatch(
                        command ->
                                command.contains(
                                        "\"$HOME/.local/share/porelo/builds/stub-forge\""));
    }

    @Test
    void refusesToStageInADirectoryItDidNotMake() {
        machine.answer("mktemp -d", "/tmp/somebody-elses\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Deploy.staging(machine))
                .as("a directory not named porelo-deploy.* is not this deploy's")
                .isInstanceOf(IOException.class);
    }
}
