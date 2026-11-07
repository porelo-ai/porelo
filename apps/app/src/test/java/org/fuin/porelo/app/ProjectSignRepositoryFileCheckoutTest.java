package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.organization.OrganizationQuestions.SigningKey;
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
 * Signing a repository's file from the checkouts a person keeps. Harm guarded: the signature goes
 * only to the repository the judged project file names for the checkout, and only for a signer the
 * organization names, with no project taken on this machine.
 */
class ProjectSignRepositoryFileCheckoutTest {

    private static final String TEXT = "egress:\n  domains:\n    - repo.example.org\n";

    private static final String PROJECT_ADDRESS = "https://forge.example.org/acme/demo.git";

    @TempDir private Path dir;

    private RegistryCheckout machine;

    private Path forge;

    private Path web;

    private Path project;

    private String acting = "alice";

    @BeforeEach
    void checkoutsOfTheOrganizationsProjectAndOfItsRepository() throws Exception {
        machine = new RegistryCheckout(dir);
        forge = dir.resolve("web.git");
        git(dir, "init", "-q", "--bare", "-b", "main", forge.toString());
        web = dir.resolve("web");
        git(dir, "clone", "-q", forge.toString(), web.toString());
        git(web, "checkout", "-q", "-b", "main");
        Files.writeString(web.resolve("porelo.yml"), TEXT);
        git(web, "add", "porelo.yml");
        git(web, "commit", "-q", "-m", "Unsigned settings");
        git(web, "push", "-q", "origin", "main");

        project = dir.resolve("demo");
        git(dir, "init", "-q", "-b", "main", project.toString());
        Files.writeString(
                project.resolve("project.yml"),
                "project:\n  name: demo\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\nrepositories:\n  web:\n    upstream: \""
                        + forge
                        + "\"\n");
        git(project, "add", "project.yml");
        git(
                project,
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + machine.keyFiles.get("alice"),
                "commit",
                "-q",
                "-S",
                "-m",
                "The project");

        new OrgCheckouts(dir.resolve("organization"), Map.of("demo", project))
                .write(machine.context());
    }

    @AfterEach
    void close() {
        machine.close();
    }

    @Test
    void aDeveloperSignsTheCheckoutTheyRunInWithNoProjectTaken() throws Exception {
        final String read = main(web);

        assertThat(run("-C", web.toString(), "--yes", "--id", "sign-web", "--json"))
                .as("signed: %s", machine.err)
                .isZero();

        assertThat(main(forge)).as("the forge holds a new commit").isNotEqualTo(read);
        assertThat(git(forge, "show", "--no-patch", "--format=%B", main(forge)))
                .contains("Porelo-Signs-Anew: porelo.yml");
        assertThat(main(web)).as("so does the checkout").isEqualTo(main(forge));
        final Map<?, ?> answer = machine.answer();
        assertThat(List.of(answer.get("project"), answer.get("repository"), answer.get("revision")))
                .as("the answer names what the checkout is of")
                .containsExactly("demo", "web", main(forge));
        assertThat(machine.paths.projects().organizationProjects())
                .as("no project was taken")
                .doesNotExist();
    }

    @Test
    void aLookShowsTheCheckoutsFileAndTheLineToSignIt() throws Exception {
        assertThat(run("-C", web.toString())).as("shown: %s", machine.err).isZero();

        assertThat(machine.out.toString())
                .contains("Repository: web")
                .contains("repo.example.org")
                .contains(
                        "Having read it: porelo project sign-repository-file -C " + web + " --yes");
        assertThat(main(web)).as("a look signs nothing").isEqualTo(main(forge));
    }

    @Test
    void aCheckoutOfNoRepositoryTheProjectsNameIsRefusedNamingBoth() throws Exception {
        final Path elsewhere = dir.resolve("elsewhere.git");
        git(dir, "clone", "-q", "--bare", forge.toString(), elsewhere.toString());
        git(web, "remote", "set-url", "origin", elsewhere.toString());
        final String read = main(forge);

        assertThat(run("-C", web.toString(), "--yes", "--json")).isEqualTo(66);

        assertThat(machine.answer().get("reason")).isEqualTo("not-found");
        assertThat(String.valueOf(machine.answer().get("words")))
                .contains(elsewhere.toString())
                .contains(forge.toString());
        assertThat(main(forge)).as("nothing is pushed").isEqualTo(read);
    }

    @Test
    void aBaseHasNoPlaceInTheCheckoutsForm() {
        assertThat(run("-C", web.toString(), "--yes", "--base", "a".repeat(40), "--json"))
                .as("the checkout's main is signed as read")
                .isEqualTo(64);
    }

    @Test
    void aKeyOfNobodyInTheProjectSignsNothing() {
        acting = "bob";
        final String read = main(forge);

        assertThat(run("-C", web.toString(), "--yes", "--json")).isEqualTo(65);

        assertThat(machine.answer().get("reason")).isEqualTo("not-allowed");
        assertThat(main(forge)).isEqualTo(read);
    }

    private int run(final String... arguments) {
        machine.out.getBuffer().setLength(0);
        machine.err.getBuffer().setLength(0);
        final PoreloContext context = machine.context();
        final CommandLine line = PoreloCli.commandLine(context);
        line.setOut(new PrintWriter(machine.out));
        line.setErr(new PrintWriter(machine.err));
        final CommandLine verb =
                line.getSubcommands().get("project").getSubcommands().get("sign-repository-file");
        if (verb.getCommand() instanceof ProjectSignRepositoryFileCommand command) {
            command.organization =
                    (ignored, checkout, projects) ->
                            Map.of(
                                    "demo",
                                    new ProjectSignRepositoryFileCommand.Signers(
                                            PROJECT_ADDRESS,
                                            List.of(
                                                    row("alice", "admin"),
                                                    row("hank", "developer"))));
            command.session =
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
        final List<String> all = new ArrayList<>(List.of("project", "sign-repository-file"));
        all.addAll(List.of(arguments));
        return line.execute(all.toArray(String[]::new));
    }

    private SigningKey row(final String login, final String role) {
        return new SigningKey(
                login,
                role,
                machine.key(login),
                LocalDate.of(2000, 1, 1),
                LocalDate.of(2999, 1, 1),
                null);
    }

    private String main(final Path in) {
        return git(in, "rev-parse", "refs/heads/main");
    }

    private String git(final Path in, final String... arguments) {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final Map<String, String> environment = new HashMap<>();
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_AUTHOR_NAME", "Person");
        environment.put("GIT_AUTHOR_EMAIL", "person@example.org");
        environment.put("GIT_COMMITTER_NAME", "Person");
        environment.put("GIT_COMMITTER_EMAIL", "person@example.org");
        return machine.runner
                .runOrFail(Command.of(command).in(in).withEnvironment(environment))
                .trimmedOutput();
    }
}
