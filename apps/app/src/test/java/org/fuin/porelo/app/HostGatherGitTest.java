package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostGatherGitTest {
    @TempDir Path dir;
    private final ProcessCommandRunner processes = new ProcessCommandRunner();
    private Path local;
    private Path central;
    private Path work;
    private Path scratch;

    @BeforeEach
    void forges() throws Exception {
        local = dir.resolve("local.git");
        central = dir.resolve("central.git");
        work = dir.resolve("work");
        scratch = Files.createDirectory(dir.resolve("scratch"));
        git("init", "--quiet", "--initial-branch=main", work.toString());
        commit("base");
        git("clone", "--quiet", "--bare", work.toString(), local.toString());
        git("clone", "--quiet", "--bare", work.toString(), central.toString());
    }

    private String git(String... args) {
        final var command = new ArrayList<>(List.of("/usr/bin/git"));
        command.addAll(List.of(args));
        return processes
                .run(
                        Command.of(command)
                                .in(dir)
                                .withChosenEnvironment(
                                        Map.of(
                                                "PATH",
                                                "/usr/bin:/bin",
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .orFail()
                .trimmedOutput();
    }

    private String commit(String name) throws Exception {
        Files.writeString(work.resolve(name), name);
        git("-C", work.toString(), "add", name);
        git(
                "-C",
                work.toString(),
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.org",
                "commit",
                "--quiet",
                "-m",
                name);
        return git("-C", work.toString(), "rev-parse", "HEAD");
    }

    private CommandResult routed(Command command) {
        if (command.program().endsWith("/curl"))
            return new CommandResult(command, 0, "001f# service=git-receive-pack\n0000\n200", "");
        final var args = new ArrayList<>(command.arguments());
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("http://127.0.0.1:3001/acme/first.git"))
                args.set(i, local.toString());
            else if (args.get(i).equals("https://central.example/acme/first.git"))
                args.set(i, central.toString());
        }
        return processes.run(
                new Command(
                        args,
                        command.workingDirectory(),
                        command.environment(),
                        command.input(),
                        command.inheritsEnvironment(),
                        command.binaryInput()));
    }

    private int transfer(boolean pull) {
        final var config =
                new HostGatherConfig(
                        "http://127.0.0.1:3001",
                        "acme",
                        dir.resolve("ca.pem"),
                        List.of(
                                new HostGatherConfig.Repository(
                                        "first", "https://central.example/acme/first.git")));
        return new HostGatherFlow(this::routed, Path.of("/usr/bin/git"), scratch)
                .run(
                        pull,
                        config,
                        List.of(),
                        "local-fixture-token".toCharArray(),
                        "central-fixture-token".toCharArray(),
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(new StringWriter()));
    }

    private String ref(Path repo, String ref) {
        return git("--git-dir=" + repo, "rev-parse", ref);
    }

    @Test
    void approvedLocalMainPushesWithoutForce() throws Exception {
        // Push must send the approved commit and preserve local main.
        final String approved = commit("approved");
        git("-C", work.toString(), "push", "--quiet", local.toString(), "main:main");
        assertThat(transfer(false)).isZero();
        assertThat(ref(central, "main")).isEqualTo(approved);
        assertThat(ref(local, "main")).isEqualTo(approved);
        try (var children = Files.list(scratch)) {
            assertThat(children.toList()).isEmpty();
        }
    }

    @Test
    void centralRejectionPreservesBothMainsAndBringsBackItsCommits() throws Exception {
        // Divergence must keep every approved and central commit and require task reconciliation.
        final String base = ref(local, "main");
        final String advanced = commit("central-change");
        git("-C", work.toString(), "push", "--quiet", central.toString(), "main:main");
        git("-C", work.toString(), "reset", "--hard", base);
        final String approved = commit("local-change");
        git("-C", work.toString(), "push", "--quiet", local.toString(), "main:main");
        assertThat(transfer(false)).isEqualTo(1);
        assertThat(ref(central, "main")).isEqualTo(advanced);
        assertThat(ref(local, "main")).isEqualTo(approved);
        assertThat(ref(local, "central/main")).isEqualTo(advanced);
    }

    @Test
    void pullFollowsRewrittenCentralMainOnItsOwnBranchOnly() throws Exception {
        // A rewritten central must update its mirror branch while preserving both approved mains.
        final String approved = ref(local, "main");
        final String advanced = commit("central-change");
        git("-C", work.toString(), "push", "--quiet", central.toString(), "main:main");
        git("--git-dir=" + local, "update-ref", "refs/heads/central/main", approved);
        assertThat(transfer(true)).isZero();
        assertThat(ref(local, "main")).isEqualTo(approved);
        assertThat(ref(local, "central/main")).isEqualTo(advanced);
        assertThat(ref(central, "main")).isEqualTo(advanced);
        git("-C", work.toString(), "reset", "--hard", approved);
        final String rewritten = commit("rewritten");
        git("-C", work.toString(), "push", "--force", "--quiet", central.toString(), "main:main");
        assertThat(transfer(true)).isZero();
        assertThat(ref(local, "main")).isEqualTo(approved);
        assertThat(ref(local, "central/main")).isEqualTo(rewritten);
    }
}
