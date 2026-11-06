package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Harm guarded: only a current project signer may take responsibility for repository settings, only
 * at the revision read, and a lost answer must not produce a second signed commit.
 */
class ProjectSignRepositoryFileTest {

    private static final String TEXT = "egress:\n  domains:\n    - repo.example.org\n";

    @TempDir private Path dir;
    private RegistryCheckout machine;
    private Path upstream;
    private Path projectFile;
    private String acting = "alice";
    private final List<Command> pushes = new ArrayList<>();

    @BeforeEach
    void anOrganizationsProjectAndAnUnsignedRepositoryFile() throws Exception {
        machine = new RegistryCheckout(dir);
        machine.git("checkout", "-q", "-b", "trunk");
        machine.write("porelo.yml", TEXT);
        machine.git("add", "porelo.yml");
        machine.git("-c", "commit.gpgsign=false", "commit", "-q", "-m", "Unsigned settings");
        upstream = dir.resolve("web.git");
        git(dir, "clone", "-q", "--bare", machine.repo.toString(), upstream.toString());
        projectFile = machine.paths.projects().organizationClone("demo").resolve("project.yml");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(
                projectFile,
                "project:\n  name: demo\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\nrepositories:\n  web:\n    upstream: \""
                        + upstream
                        + "\"\n");
        OrganizationProjects.of(machine.context())
                .write(
                        new OrganizationProjects.Placed(
                                "demo",
                                "a".repeat(40),
                                "https://forge.example.org/acme/demo.git",
                                null,
                                List.of(
                                        new OrganizationProjects.Key(
                                                "alice",
                                                "developer",
                                                machine.key("alice"),
                                                LocalDate.of(2000, 1, 1),
                                                LocalDate.of(2999, 1, 1),
                                                null)),
                                null,
                                null,
                                null));
    }

    @AfterEach
    void close() {
        machine.close();
    }

    @Test
    void aDeveloperSignsTheFileAndAStartTakesItsSettings() throws Exception {
        final String base = tip();
        final Path mirror = GateSupport.mirror(machine.context(), "demo", "web");
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", upstream.toString(), mirror.toString());
        assertThatThrownBy(() -> settings())
                .as("unsigned settings cannot reach a start")
                .isInstanceOf(RepositorySettingsSignature.Unsigned.class);

        assertThat(sign(base, "sign-web")).as("the developer may sign: %s", machine.err).isZero();

        final String signed = tip();
        assertThat(signed).as("a signed commit was pushed").isNotEqualTo(base);
        assertThat(git(upstream, "show", "--format=%B", "--no-patch", signed))
                .as("the repository file is the one taken on")
                .contains("Porelo-Signs-Anew: porelo.yml");
        assertThat(git(upstream, "diff", base, signed)).as("signing changes no bytes").isEmpty();
        git(mirror, "fetch", "-q", upstream.toString(), "refs/heads/trunk:refs/heads/trunk");
        assertThat(settings().project().egress().domains())
                .as("the start reads the settings under the developer's verified signature")
                .contains("repo.example.org");
    }

    @Test
    void aKeyOfNobodyInTheProjectIsRefusedBeforeAnyPush() {
        acting = "bob";
        final String base = tip();

        assertThat(sign(base, "outsider")).as("an outsider cannot sign").isEqualTo(65);
        assertThat(machine.answer().get("reason"))
                .as("the refusal is a named value")
                .isEqualTo("not-allowed");
        assertThat(pushes).as("the refusal precedes every push").isEmpty();
        assertThat(tip()).as("the repository is unchanged").isEqualTo(base);
    }

    @Test
    void aMovedTipIsRefusedWithoutSigningOrPushing() {
        final String base = tip();
        machine.git("-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "Moved");
        machine.git("push", "-q", upstream.toString(), "trunk");
        final String moved = tip();

        assertThat(sign(base, "old-base")).as("only the revision read may be signed").isEqualTo(65);
        assertThat(machine.answer().get("reason"))
                .as("the caller can recognize a moved tip")
                .isEqualTo("moved-on");
        assertThat(pushes).as("nothing is pushed on the wrong base").isEmpty();
        assertThat(tip()).as("the moved tip stays as it was").isEqualTo(moved);
    }

    @Test
    void aRetryWithTheSameIdFindsItsSignedCommit() {
        final String base = tip();
        assertThat(sign(base, "retry-web"))
                .as("the first signing succeeds: %s", machine.err)
                .isZero();
        final String signed = tip();
        pushes.clear();

        assertThat(sign(base, "retry-web")).as("the original base is safe to retry").isZero();
        assertThat(machine.answer().get("revision"))
                .as("the retry identifies the original commit")
                .isEqualTo(signed);
        assertThat(machine.answer().get("again"))
                .as("the retry says it was made before")
                .isEqualTo(true);
        assertThat(tip()).as("retry creates no duplicate commit").isEqualTo(signed);
        assertThat(pushes).as("a retry does not push again").isEmpty();
    }

    private RepositoryOwnSettings.Found settings() {
        return RepositoryOwnSettings.ofOrganization(
                machine.context(),
                projectFile,
                "demo",
                ProjectReader.read(projectFile).repository("web"));
    }

    private int sign(final String base, final String id) {
        machine.out.getBuffer().setLength(0);
        machine.err.getBuffer().setLength(0);
        final PoreloContext context =
                new PoreloContext(
                        command -> {
                            if (command.arguments().contains("push")) {
                                pushes.add(command);
                            }
                            return machine.runner.run(command);
                        },
                        machine.paths,
                        arguments -> 0,
                        machine.environment());
        final CommandLine line = PoreloCli.commandLine(context);
        line.setOut(new PrintWriter(machine.out));
        line.setErr(new PrintWriter(machine.err));
        final CommandLine verb =
                line.getSubcommands().get("project").getSubcommands().get("sign-repository-file");
        if (verb != null && verb.getCommand() instanceof OrgWriting writing) {
            writing.session =
                    ignored ->
                            new OrgWriting.Session(
                                    null,
                                    new OrganizationWrites.Acting(acting, machine.key(acting)),
                                    null,
                                    new ProjectFile(
                                            context.runner(),
                                            FetchAccess.NONE,
                                            new CommitSigning(
                                                    Map.of(),
                                                    Map.of(
                                                            machine.key(acting),
                                                            machine.keyFiles.get(acting))),
                                            dir.resolve("memory")));
        }
        return line.execute(
                "project",
                "sign-repository-file",
                "demo",
                "web",
                "--yes",
                "--base",
                base,
                "--id",
                id,
                "--json");
    }

    private String tip() {
        return git(upstream, "rev-parse", "refs/heads/trunk");
    }

    private String git(final Path in, final String... arguments) {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        return machine.runner
                .runOrFail(
                        Command.of(command)
                                .in(in)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }
}
