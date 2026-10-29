package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link ProjectVerifyCommand}, against real git and ssh-keygen. Harm guarded: a checker
 * of root's or a laptop's own drifts from the follow's rules, and its drift is where a forged
 * developer list passes; so each refusal here is the one a follow gives.
 */
class ProjectVerifyCommandTest {

    private final CommandRunner runner =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1")));

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    @TempDir Path dir;

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
        return new PoreloContext(
                command ->
                        command.arguments().getFirst().equals("logger")
                                ? new CommandResult(command, 0, "", "")
                                : runner.run(command),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private int verify(final String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new ArrayList<>(List.of("project", "verify"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(String[]::new));
    }

    private static final String ACCESS_URL = "git@forge.example.org:demo/access.git";

    private static final String WIREGUARD = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private static final String ACCESS =
            "transports: {matrix: {homeserver: \"https://chat.example.org\"}}\n";

    @Test
    void aGoodProjectAndAccessFileAreAnsweredForAProgram() throws Exception {
        final Path key = key("operator");
        final Path access = access(key);
        final Path project = project(ACCESS_URL, key);

        final int code =
                verify(
                        "--repository",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        access.toString(),
                        "--json");

        assertThat(code).as("a good project and access file pass: " + err).isZero();
        @SuppressWarnings("unchecked")
        final Map<String, Object> answer =
                (Map<String, Object>) org.fuin.porelo.wire.Json.parse(out.toString().strip());
        assertThat(answer)
                .as("the names a program reads")
                .containsOnlyKeys(
                        "project",
                        "commit",
                        "access_commit",
                        "signer",
                        "signers",
                        "host",
                        "developers",
                        "transports")
                .containsEntry("project", "demo")
                .containsEntry("commit", git(project, "rev-parse", "HEAD"))
                .containsEntry("access_commit", git(access, "rev-parse", "HEAD"))
                .containsEntry("signer", publicKey(key))
                .containsEntry("signers", List.of(publicKey(key)));
        assertThat(answer.get("transports"))
                .as("the settings of the verified access file")
                .asString()
                .contains("matrix")
                .contains("https://chat.example.org");
        assertThat(dir.resolve("state")).as("nothing is recorded").doesNotExist();
        assertThat(dir.resolve("config")).as("no key is pinned").doesNotExist();
    }

    @Test
    void bundlesAreCheckedAsTheirRepositoriesAre() throws Exception {
        final Path key = key("operator");
        final Path access = access(key);
        final Path project = project(ACCESS_URL, key);
        final Path projectBundle = dir.resolve("project.bundle");
        final Path accessBundle = dir.resolve("access.bundle");
        git(project, "bundle", "create", "-q", projectBundle.toString(), "HEAD", "main");
        git(access, "bundle", "create", "-q", accessBundle.toString(), "HEAD", "main");

        final int code =
                verify(
                        "--repository",
                        projectBundle.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        accessBundle.toString(),
                        "--json");

        assertThat(code).as("bundles pass as their repositories do: " + err).isZero();
        assertThat(out.toString())
                .as("the access commit of the bundle given, not of a repository fetched")
                .contains(git(access, "rev-parse", "HEAD"));
    }

    @Test
    void anAccessCommitByAKeyTheProjectDoesNotHoldIsRefused() throws Exception {
        final Path key = key("operator");
        final Path access = access(key);
        final Path project = project(ACCESS_URL, key);
        Files.writeString(
                access.resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://forged.example\"}}\n");
        commit(access, key("stranger"));

        final int code =
                verify(
                        "--repository",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        access.toString(),
                        "--json");

        assertThat(code).as("refused, as a follow refuses it").isEqualTo(65);
        assertThat(err.toString())
                .as("names the access file's outcome")
                .contains("the access file of demo did not pass its check (unknown_key");
        assertThat(out.toString()).as("no settings are answered").isEmpty();
    }

    @Test
    void anAccessCommitThatDoesNotDescendFromTheOneInForceIsRefused() throws Exception {
        final Path key = key("operator");
        final Path access = access(key);
        final String inForce = git(access, "rev-parse", "HEAD");
        final Path project = project(ACCESS_URL, key);
        git(access, "checkout", "-q", "--orphan", "other");
        Files.writeString(
                access.resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://older.example\"}}\n");
        commit(access, key);

        final int code =
                verify(
                        "--repository", project.toString(),
                        "--signed-by", publicKey(key),
                        "--access-repository", access.toString(),
                        "--access-since", inForce);

        assertThat(code).as("an older access history is refused: " + err).isEqualTo(65);
        assertThat(err.toString())
                .as("named as a rewrite of the commit in force")
                .contains("(rewritten")
                .contains(inForce);
    }

    @Test
    void aProjectCommitThatDoesNotDescendFromTheOneInForceIsRefused() throws Exception {
        final Path key = key("operator");
        final Path project = project(null, key);
        final String inForce = git(project, "rev-parse", "HEAD");
        git(project, "checkout", "-q", "--orphan", "other");
        Files.writeString(project.resolve("README.md"), "another history\n");
        commit(project, key);

        final int code =
                verify(
                        "--repository", project.toString(),
                        "--signed-by", publicKey(key),
                        "--since", inForce);

        assertThat(code).as("an older project history is refused: " + err).isEqualTo(65);
        assertThat(err.toString()).as("named as a rewrite").contains("(rewritten");
    }

    @Test
    void aKeyHandedOverInTheProjectsChainSignsTheNextCommit() throws Exception {
        // The keys named are the ones in force at --since; a handover after it is walked as a
        // follow walks it.
        final Path key = key("operator");
        final Path successor = key("successor");
        final Path project = project(null, key);
        final String inForce = git(project, "rev-parse", "HEAD");
        Files.writeString(
                project.resolve("project.yml"),
                Files.readString(project.resolve("project.yml"))
                        .replace(
                                "  security_class: guarded\n",
                                "  security_class: guarded\n  signers:\n    - \""
                                        + publicKey(successor)
                                        + "\"\n"));
        commit(project, key);
        Files.writeString(project.resolve("README.md"), "signed by the successor\n");
        commit(project, successor);

        final int code =
                verify(
                        "--repository",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--since",
                        inForce,
                        "--json");

        assertThat(code).as("the successor's commit passes: " + err).isZero();
        assertThat(out.toString())
                .as("the successor's commit, after the handover the key in force signed")
                .contains(git(project, "rev-parse", "HEAD"))
                .contains(publicKey(successor));
    }

    @Test
    void anAccessRepositoryNotGivenIsNeverFetched() throws Exception {
        final Path key = key("operator");
        final Path project = project(ACCESS_URL, key);

        final int code = verify("--repository", project.toString(), "--signed-by", publicKey(key));

        assertThat(code).as("an access file nobody gave is not fetched: " + err).isEqualTo(65);
        assertThat(err.toString())
                .as("says why")
                .contains("no copy of that repository was given to check");
    }

    @Test
    void aUrlIsRefusedAndAMissingPathIsNamed() throws Exception {
        assertThat(
                        verify(
                                "--repository",
                                "git@forge.example.org:demo/project.git",
                                "--signed-by",
                                "ssh-ed25519 AAAA"))
                .as("nothing is fetched over a network")
                .isEqualTo(65);
        assertThat(err.toString()).as("says why").contains("is not a local path or bundle");
        assertThat(
                        verify(
                                "--repository",
                                "git@forge.example.org:demo/\nproject.git",
                                "--signed-by",
                                "ssh-ed25519 AAAA"))
                .as("a URL broken over two lines is still one")
                .isEqualTo(65);

        assertThat(
                        verify(
                                "--repository",
                                dir.resolve("missing").toString(),
                                "--signed-by",
                                "ssh-ed25519 AAAA"))
                .as("a path that is not there is named as missing")
                .isEqualTo(66);
        assertThat(err.toString()).as("says where").contains("there is nothing at");
    }

    @Test
    void aKeyThisMachinePinnedIsNeverOneTheCheckTrusts() throws Exception {
        final Path key = key("operator");
        final Path stranger = key("stranger");
        final Path project = project(null, stranger);
        final Path signers = context().paths().projects().configurationSigners();
        Files.createDirectories(signers.getParent());
        Files.writeString(signers, "demo " + publicKey(stranger) + "\n");

        final int code = verify("--repository", project.toString(), "--signed-by", publicKey(key));

        assertThat(code).as("only the keys named count: " + err).isEqualTo(65);
        assertThat(err.toString()).as("named as a key not given").contains("(unknown_key");
    }

    @Test
    void theHostAndDevelopersOfTheAccessFileAreAnsweredByTheirNames() throws Exception {
        final Path key = key("operator");
        final Path access = repository("access");
        Files.writeString(
                access.resolve("access.yml"),
                """
                host:
                  address: host.example.org
                  endpoint: host.example.org:51820
                  wireguard_public_key: %s
                  network: 10.77.0.0/24
                  ssh_host_key: ssh-ed25519 AAAA host@example.org
                  ca_sha256: %s
                developers:
                  alice:
                    laptops:
                      - ssh_public_key: ssh-ed25519 AAAA alice@laptop
                        wireguard_public_key: %s
                        vpn_address: 10.77.0.10
                    forge_login: alice-gh
                """
                        .formatted(WIREGUARD, "0123456789abcdef".repeat(4), WIREGUARD));
        commit(access, key);
        final Path project = project(ACCESS_URL, key);

        final int code =
                verify(
                        "--repository",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        access.toString(),
                        "--json");

        assertThat(code).as("a full access file passes: " + err).isZero();
        @SuppressWarnings("unchecked")
        final Map<String, Object> answer =
                (Map<String, Object>) org.fuin.porelo.wire.Json.parse(out.toString().strip());
        assertThat(answer.get("host"))
                .as("the host by the names a program reads")
                .isEqualTo(
                        Map.of(
                                "address", "host.example.org",
                                "endpoint", "host.example.org:51820",
                                "wireguard_public_key", WIREGUARD,
                                "network", "10.77.0.0/24",
                                "ssh_host_key", "ssh-ed25519 AAAA",
                                "ca_sha256", "0123456789abcdef".repeat(4)));
        assertThat(answer.get("developers"))
                .as("each developer's laptops and forge login")
                .isEqualTo(
                        Map.of(
                                "alice",
                                Map.of(
                                        "laptops",
                                        List.of(
                                                Map.of(
                                                        "ssh_public_key",
                                                        "ssh-ed25519 AAAA",
                                                        "wireguard_public_key",
                                                        WIREGUARD,
                                                        "vpn_address",
                                                        "10.77.0.10")),
                                        "forge_login",
                                        "alice-gh")));
    }

    @Test
    void theKeysInForceAfterAHandoverAreTheOnesTheNextCheckNames() throws Exception {
        final Path key = key("operator");
        final Path successor = key("successor");
        final Path project = project(null, key);
        final String first = git(project, "rev-parse", "HEAD");
        Files.writeString(
                project.resolve("project.yml"),
                Files.readString(project.resolve("project.yml"))
                        .replace(
                                "  security_class: guarded\n",
                                "  security_class: guarded\n  signers:\n    - \""
                                        + publicKey(successor)
                                        + "\"\n"));
        commit(project, key);
        final String handover = git(project, "rev-parse", "HEAD");

        final int code =
                verify(
                        "--repository",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--since",
                        first,
                        "--json");

        assertThat(code).as("the handover passes: " + err).isZero();
        assertThat(out.toString())
                .as("the handover's commit was signed by the key in force before it")
                .contains("\"signer\":\"" + publicKey(key) + "\"")
                .as("and hands the next check the successor")
                .contains("\"signers\":[\"" + publicKey(successor) + "\"]");

        Files.writeString(project.resolve("README.md"), "signed by the successor\n");
        commit(project, successor);
        out.getBuffer().setLength(0);

        final int next =
                verify(
                        "--repository",
                        project.toString(),
                        "--signed-by",
                        publicKey(successor),
                        "--since",
                        handover,
                        "--json");

        assertThat(next).as("the next check, with the keys it was handed: " + err).isZero();
        assertThat(out.toString())
                .as("the successor's own commit")
                .contains(git(project, "rev-parse", "HEAD"));
    }

    @Test
    void aProjectThatRenamedItselfAfterTheCommitInForceIsRefused() throws Exception {
        final Path key = key("operator");
        final Path project = project(null, key);
        final String inForce = git(project, "rev-parse", "HEAD");
        Files.writeString(
                project.resolve("project.yml"),
                Files.readString(project.resolve("project.yml"))
                        .replace("name: \"demo\"", "name: \"other\""));
        commit(project, key);

        final int code =
                verify(
                        "--repository", project.toString(),
                        "--signed-by", publicKey(key),
                        "--since", inForce);

        assertThat(code).as("a follow refuses the rename: " + err).isEqualTo(65);
        assertThat(err.toString())
                .as("named by the name in force")
                .contains("followed as 'demo'")
                .contains("'other'");
    }

    @Test
    void aProjectUnderAnotherNameThanTheOneGivenIsRefused() throws Exception {
        final Path key = key("operator");
        final Path project = project(null, key);

        final int code =
                verify(
                        "--project", "prod",
                        "--repository", project.toString(),
                        "--signed-by", publicKey(key));

        assertThat(code).as("another project's bundle under the same key: " + err).isEqualTo(65);
        assertThat(err.toString()).as("names both").contains("'demo'").contains("'prod'");

        assertThat(
                        verify(
                                "--project", "prod",
                                "--repository", project.toString(),
                                "--signed-by", publicKey(key),
                                "--since", git(project, "rev-parse", "HEAD")))
                .as(
                        "refused as well when the commit is the one in force, which is not applied again")
                .isEqualTo(65);
    }

    @Test
    void nothingOfThisAccountIsReadSoTheAnswerIsTheSameOnAnyMachine() throws Exception {
        // An egress set only this account defines stands for everything of its home: credentials,
        // signers, sets. A check that read them would answer differently on root's throwaway user.
        final Path key = key("operator");
        final Path project = project("demo", null, key, "egress: {sets: [corp]}");
        final Path sets = Files.createDirectories(dir.resolve("data/porelo/egress"));
        Files.writeString(sets.resolve("corp.yaml"), "name: corp\ndomains:\n  - corp.example\n");

        final int code = verify("--repository", project.toString(), "--signed-by", publicKey(key));

        assertThat(code)
                .as("a set of this account's is not one the check knows: " + err)
                .isEqualTo(65);
        assertThat(err.toString())
                .as("named as a set the check does not have")
                .contains("names an egress set");
    }

    @Test
    void aNameThatIsNotOneNeverReachesTheKeysTheCheckTrusts() throws Exception {
        final Path key = key("operator");
        final Path attacker = key("attacker");
        final Path project = project("demo " + publicKey(attacker) + " #", null, attacker, "");

        final int code = verify("--repository", project.toString(), "--signed-by", publicKey(key));

        assertThat(code).as("refused before any key is written: " + err).isEqualTo(65);
        assertThat(err.toString()).as("says why").contains("is not a project name");
        assertThat(out.toString()).as("nothing is answered").isEmpty();
    }

    @Test
    void anAccessOptionTheProjectFileHasNoUseForIsRefused() throws Exception {
        final Path key = key("operator");
        final Path access = access(key);
        final Path project = project(null, key);

        final int code =
                verify(
                        "--repository", project.toString(),
                        "--signed-by", publicKey(key),
                        "--access-repository", access.toString());

        assertThat(code).as("never passed as if it had been checked: " + err).isEqualTo(65);
        assertThat(err.toString())
                .as("says it was not checked")
                .contains("names no access repository");

        final Path beside = repository("beside");
        Files.writeString(beside.resolve("access.yml"), ACCESS);
        Files.writeString(
                beside.resolve("project.yml"),
                Files.readString(project.resolve("project.yml"))
                        + "access: {file: \"access.yml\"}\n");
        commit(beside, key);
        err.getBuffer().setLength(0);

        assertThat(
                        verify(
                                "--repository", beside.toString(),
                                "--signed-by", publicKey(key),
                                "--access-since", git(beside, "rev-parse", "HEAD")))
                .as("an access file beside the project file has the project's commit")
                .isEqualTo(65);
        assertThat(err.toString()).as("says which option to give").contains("give it as --since");
    }

    @Test
    void aCommitInForceIsAFullHashAndNothingGitWouldReadOtherwise() throws Exception {
        final Path key = key("operator");
        final Path project = project(null, key);

        for (final String since : List.of("FETCH_HEAD", "--output=" + dir.resolve("out"))) {
            assertThat(
                            verify(
                                    "--repository", project.toString(),
                                    "--signed-by", publicKey(key),
                                    "--since", since))
                    .as("'" + since + "' is refused before git sees it")
                    .isEqualTo(65);
        }
        assertThat(err.toString()).as("says why").contains("is not a commit's full hash");
        assertThat(dir.resolve("out:project.yml")).as("git wrote nothing").doesNotExist();
    }

    @Test
    void aSnippetFileIsReadFromTheCommitAndNeverFromWhereTheCheckRuns() throws Exception {
        // The commit in force is answered without being applied again, so its file is read only
        // here: from the commit, never relative to wherever the check was started.
        final Path key = key("operator");
        final Path project = project(null, key);
        Files.writeString(project.resolve("extra.dockerfile"), "RUN true\n");
        Files.writeString(
                project.resolve("project.yml"),
                Files.readString(project.resolve("project.yml"))
                        .replace(
                                "  base_image: ubuntu:24.04\n",
                                "  base_image: ubuntu:24.04\n  snippet_file: extra.dockerfile\n"));
        commit(project, key);

        final int code =
                verify(
                        "--repository", project.toString(),
                        "--signed-by", publicKey(key),
                        "--since", git(project, "rev-parse", "HEAD"));

        assertThat(code).as("the snippet the commit holds is found: " + err).isZero();
    }

    @Test
    void aSettingJsonHasNoFormForIsRefusedAndNeverAFailure() throws Exception {
        final Path key = key("operator");
        final Path access = repository("access");
        Files.writeString(
                access.resolve("access.yml"),
                "transports: {matrix: {homeserver: \"https://chat.example.org\", since:"
                        + " 2026-10-29}}\n");
        commit(access, key);
        final Path project = project(ACCESS_URL, key);

        final int code =
                verify(
                        "--repository",
                        project.toString(),
                        "--signed-by",
                        publicKey(key),
                        "--access-repository",
                        access.toString(),
                        "--json");

        assertThat(code).as("refused, not a crash: " + err).isEqualTo(65);
        assertThat(err.toString()).as("says why").contains("cannot be answered");
        assertThat(dir.resolve("state")).as("no failure log was written").doesNotExist();
    }

    private Path access(final Path key) throws IOException {
        final Path access = repository("access");
        Files.writeString(access.resolve("access.yml"), ACCESS);
        commit(access, key);
        return access;
    }

    private Path project(final String accessUrl, final Path key) throws IOException {
        return project("demo", accessUrl, key, "");
    }

    private Path project(
            final String name, final String accessUrl, final Path key, final String more)
            throws IOException {
        final Path repo = repository("project");
        Files.writeString(
                repo.resolve("project.yml"),
                """
                project:
                  name: "%s"
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                %s
                %s
                """
                        .formatted(
                                name,
                                accessUrl == null
                                        ? ""
                                        : "access: {repository: \"" + accessUrl + "\"}",
                                more));
        commit(repo, key);
        return repo;
    }

    private Path repository(final String name) throws IOException {
        final Path repo = Files.createDirectories(dir.resolve(name));
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.name", "Operator");
        git(repo, "config", "user.email", "operator@example.org");
        return repo;
    }

    private void commit(final Path repo, final Path signingKey) {
        git(repo, "add", "--all");
        git(
                repo,
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + signingKey,
                "commit",
                "-q",
                "-S",
                "-m",
                "configuration");
    }

    private Path key(final String name) {
        final Path key = dir.resolve(name);
        final CommandResult made =
                runner.run(
                        Command.of(
                                "ssh-keygen",
                                "-q",
                                "-t",
                                "ed25519",
                                "-N",
                                "",
                                "-C",
                                name,
                                "-f",
                                key.toString()));
        assertThat(made.exitCode()).as("create a test key: " + made.standardError()).isZero();
        return key;
    }

    private String publicKey(final Path key) throws IOException {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        return fields[0] + " " + fields[1];
    }

    private String git(final Path repo, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", repo.toString()));
        all.addAll(List.of(arguments));
        final CommandResult result = runner.run(Command.of(all));
        assertThat(result.exitCode())
                .as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
        return result.standardOutput().strip();
    }
}
