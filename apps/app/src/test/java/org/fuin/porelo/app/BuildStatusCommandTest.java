package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildReaderException;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import picocli.CommandLine;

/** Guards a person's build query, its read-only resolution, and the credential it carries. */
class BuildStatusCommandTest {

    private static final String COMMIT = "a".repeat(40);
    private static final String UPSTREAM = "https://forge.example/acme/code.git";
    private final String token = java.util.UUID.randomUUID().toString();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final FakeCommandRunner runner = new FakeCommandRunner();
    private final FakeReader reader = new FakeReader();
    private PoreloContext context;
    private Path directory;
    private Path adapterSocket;
    private int starts;
    private boolean installed;
    private boolean failStarting;

    @BeforeEach
    void prepare() throws Exception {
        Files.createDirectories(Path.of("target"));
        directory = Files.createTempDirectory(Path.of("target"), "bs-").toAbsolutePath();
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" ->
                                            directory.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> directory.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> directory.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> directory.resolve("run").toString();
                                    default -> null;
                                },
                        directory);
        context =
                new PoreloContext(runner, new PoreloPaths(xdg, directory.resolve("bin")), args -> 0)
                        .asking(prompt -> "test passphrase".toCharArray());
        context.vault()
                .write(
                        Map.of("forge-token", VaultEntry.of(token)),
                        "test passphrase".toCharArray());
        project(true);
    }

    @AfterEach
    void forgetPassphrase() {
        PoreloContext.forgetAsked();
    }

    private void project(boolean builds) throws Exception {
        final Path file = directory.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: acme
                  security_class: online
                  upstream: https://forge.example/acme/code.git
                image:
                  base_image: ubuntu:24.04
                """
                        + (builds
                                ? """
                builds:
                  forge: test-forge
                  credential: forge-token
                """
                                : ""));
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("acme", file);
    }

    private int execute(String... args) {
        final CommandLine cli = PoreloCli.commandLine(context);
        final CommandLine build = cli.getSubcommands().get("build");
        if (build != null && !installed) {
            final BuildStatusCommand command =
                    new BuildStatusCommand(
                            (machine, forge, socket) -> {
                                starts++;
                                adapterSocket = socket;
                                try {
                                    assertThat(Files.getPosixFilePermissions(socket.getParent()))
                                            .as("the token socket is in a private directory")
                                            .isEqualTo(
                                                    java.nio.file.attribute.PosixFilePermissions
                                                            .fromString("rwx------"));
                                    Files.writeString(socket, "fake socket");
                                } catch (java.io.IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                                if (failStarting) {
                                    throw new BuildReaderException("cannot start");
                                }
                                return reader;
                            });
            command.setContext(context);
            build.getCommandSpec().removeSubcommand("status");
            build.addSubcommand("status", command);
        }
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return cli.execute(args);
    }

    @Test
    void namesTheCommitTheVerdictTheJobsAndTheFailedJobsLog() {
        // A script must see a failed build as failure and a person must receive its evidence.
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a failed build exits one")
                .isEqualTo(1);
        assertThat(out.toString())
                .as("the build and its failed job's evidence")
                .contains(COMMIT, "failure", "unit tests", "lint", "expected 1 but was 2");
        assertThat(reader.target)
                .as("the repository chosen by its key")
                .isEqualTo(new Target(UPSTREAM, "", token));
    }

    @Test
    void aProjectWithoutBuildsNamesTheMissingConfiguration() throws Exception {
        // A question must refuse absent configuration instead of inventing a forge to contact.
        project(false);
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("no builds is configuration, not a failed build")
                .isEqualTo(78);
        assertThat(err.toString())
                .as("a usable next action")
                .contains("acme", "no builds:", "security_class: online", "doc/project-file.md");
    }

    @Test
    void aFollowedCheckoutResolvesWithoutWritingADefaultEntry() throws Exception {
        // Asking about a checkout must never add it to default or change the project registry.
        final Path clone = context.paths().projects().followedClone("acme");
        Files.createDirectories(clone);
        Files.copy(directory.resolve("project.yml"), clone.resolve("project.yml"));
        new FollowedProjects(context.paths().projects().followed())
                .write(new FollowedProjects.Followed("acme", UPSTREAM, COMMIT, "", "APPLIED", ""));
        runner.answering("--show-toplevel", directory.toString())
                .answering("remote get-url origin", UPSTREAM);
        final Map<String, String> registry =
                new ProjectRegistry(context.paths().projects().projectRegistry()).all();
        assertThat(execute("build", "status", "--branch=main"))
                .as("a followed project's failed build")
                .isEqualTo(1);
        assertThat(reader.target.upstream())
                .as("the project's own repository matches too")
                .isEqualTo(UPSTREAM);
        assertThat(new ProjectRegistry(context.paths().projects().projectRegistry()).all())
                .as("the registry remains unchanged")
                .isEqualTo(registry);
        assertThat(context.paths().xdg().state().resolve("default"))
                .as("no default entry was created")
                .doesNotExist();
    }

    @Test
    void anUnfollowedCheckoutDoesNotBecomeADefaultProject() {
        // A read-only query may not silently enroll a checkout in default.
        runner.answering("--show-toplevel", directory.toString())
                .answering("remote get-url origin", UPSTREAM);
        assertThat(execute("build", "status", "--branch=main"))
                .as("no followed project matches")
                .isEqualTo(2);
        assertThat(err.toString())
                .as("a way to select a project")
                .contains("-p", "porelo project follow");
        assertThat(context.paths().xdg().state().resolve("default"))
                .as("no default file appeared")
                .doesNotExist();
    }

    @Test
    void forgeTextCannotReorderWhatTheTerminalShows() {
        // A bidirectional override in a log could show a failure's line in another order than it
        // was written.
        reader.answer =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit",
                                        "failure",
                                        "ok \u202edeliaf\u202c\u2066x\u2069\u200b"
                                                .getBytes(StandardCharsets.UTF_8))),
                        "");
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a failed answer")
                .isEqualTo(1);
        assertThat(out.toString())
                .as("format characters escaped by their code point")
                .contains("ok \\u202edeliaf\\u202c\\u2066x\\u2069\\u200b")
                .doesNotContain("\u202e", "\u202c", "\u2066", "\u2069", "\u200b");
    }

    @Test
    void forgeTextCannotClearOrRewriteTheTerminal() {
        // Untrusted logs and metadata must not hide a failure or move the terminal cursor.
        reader.answer =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit\u001b[2J",
                                        "failure",
                                        "\u001b[2J\r\u0000\u007f\u009b\n\tend"
                                                .getBytes(StandardCharsets.UTF_8))),
                        "detail\u001b]title\u0007");
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a failed answer")
                .isEqualTo(1);
        assertThat(out.toString().contains("\u001b"))
                .as("an ESC byte cannot reach a terminal")
                .isFalse();
        assertThat(out.toString())
                .as("controls escaped, newline and tab kept")
                .contains("\\x1b[2J", "\\x0d\\x00\\x7f\\x9b\n\tend", "detail\\x1b]title\\x07")
                .doesNotContain("\u001b", "\r", "\u0000", "\u007f", "\u009b");
    }

    @Test
    void aWindowsLineEndIsShownAsALineEnd() {
        // Windows lines must stay readable without allowing a lone CR to rewrite terminal output.
        reader.answer =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit",
                                        "failure",
                                        "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8))),
                        "");
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a failed answer")
                .isEqualTo(1);
        assertThat(out.toString())
                .as("Windows line ends are shown as plain lines")
                .contains("one\ntwo\n")
                .doesNotContain("\\x0d", "\r");

        out.getBuffer().setLength(0);
        assertThat(execute("build", "status", "-p", "acme", "--branch=main", "--json"))
                .as("a failed JSON answer")
                .isEqualTo(1);
        assertThat(out.toString())
                .as("JSON preserves the forge's carriage returns as escapes")
                .contains("\"log\":\"one\\r\\ntwo\\r\\n\"")
                .doesNotContain("\r");

        reader.answer =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit",
                                        "failure",
                                        "one\rtwo\r".getBytes(StandardCharsets.UTF_8))),
                        "");
        out.getBuffer().setLength(0);
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a failed answer with lone carriage returns")
                .isEqualTo(1);
        assertThat(out.toString())
                .as("carriage returns before text or at the end cannot move the cursor")
                .contains("one\\x0dtwo\\x0d")
                .doesNotContain("\r");
    }

    @Test
    void aReaderFailureCannotPrintOrRecordTheToken() throws Exception {
        // Adapter exception text and its causes may echo the credential into failures.log.
        reader.problem =
                new BuildReaderException(
                        "reader echoed " + token,
                        new IllegalStateException("cause echoed " + token));
        assertThat(execute("build", "status", "-p", "acme", "--commit=" + COMMIT))
                .as("no answer is a software failure")
                .isEqualTo(70);
        final String recorded = Files.readString(context.paths().failureLog());
        assertThat((out.toString() + err + recorded).contains(token))
                .as("no stream or failure trace contains the token")
                .isFalse();
        assertThat(adapterSocket.getParent())
                .as("a failed query removed its private directory")
                .doesNotExist();
    }

    @ParameterizedTest
    @EnumSource(BuildRefused.Reason.class)
    void aForgeRefusalNamesAnAvailableNextAction(BuildRefused.Reason reason) {
        // Scripts must not mistake an unavailable forge for a failed build or a successful query.
        reader.problem = new BuildRefused(reason, "forge detail", 37);
        assertThat(execute("build", "status", "-p", "acme", "--commit=" + COMMIT))
                .as("a forge refusal")
                .isEqualTo(69);
        final String action =
                switch (reason) {
                    case RATE_LIMITED -> "37 seconds";
                    case CREDENTIAL_REFUSED -> "porelo vault put forge-token";
                    case NO_SUCH_REPOSITORY -> "upstream";
                    case UNREACHABLE -> "retry";
                };
        assertThat(err.toString())
                .as("the reason, detail and action")
                .contains(reason.wire(), "forge detail", action);
        assertThat(reader.closed).as("the adapter was closed on refusal").isTrue();
        assertThat(adapterSocket.getParent())
                .as("the refused query removed its private directory")
                .doesNotExist();
    }

    @Test
    void anAbsentBranchIsRefusedWithoutLookingUpABuild() {
        // An absent branch must not become an empty commit query with a misleading unknown answer.
        reader.head = "";
        assertThat(execute("build", "status", "-p", "acme", "--branch=missing"))
                .as("no such branch")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("the missing branch and a next action")
                .contains("no branch missing in " + UPSTREAM, "--branch");
        assertThat(reader.looks).as("no build was queried").isZero();
    }

    @Test
    void aShortCommitIsRefusedBeforeStartingAnAdapter() {
        // An abbreviated object name could ask the forge about the wrong commit.
        assertThat(execute("build", "status", "-p", "acme", "--commit=abc123"))
                .as("a full object name is required")
                .isEqualTo(64);
        assertThat(starts).as("no adapter started").isZero();
        assertThat(err.toString())
                .as("how to supply an unambiguous object name")
                .contains("40 or 64", "--commit");
    }

    @Test
    void aLockedVaultNamesUnlockAndDoesNotStartAnAdapter() {
        // A script must not continue as if it queried a forge when its credential was unreadable.
        context = context.asking(VaultPrompt.NOBODY);
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("vault access refused")
                .isEqualTo(77);
        assertThat(err.toString())
                .as("the action this vault supports")
                .contains("porelo vault unlock");
        assertThat(starts).as("no adapter started").isZero();
    }

    @Test
    void aMissingCredentialNamesPutInsteadOfUnlock() {
        // Unlocking a readable vault will not add its missing credential.
        context.vault().write(Map.of(), "test passphrase".toCharArray());
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("missing credential")
                .isEqualTo(77);
        assertThat(err.toString())
                .as("a way to add the missing entry")
                .contains("porelo vault put forge-token")
                .doesNotContain("porelo vault unlock");
    }

    @Test
    void anAbsentVaultNamesInitializationBeforeStoringTheEntry() throws Exception {
        // Unlocking or putting an entry cannot work until a missing vault is initialized.
        Files.delete(context.vault().path());
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a vault must exist before its credential can be read")
                .isEqualTo(77);
        assertThat(err.toString())
                .as("actions available for an absent vault")
                .contains("porelo vault init", "porelo vault put forge-token")
                .doesNotContain("porelo vault unlock");
    }

    @Test
    void anUninstalledReaderNamesThePackageToInstall() {
        // A script must fail when no installed adapter can answer its question.
        installed = true;
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("adapter unavailable")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("an installation action")
                .contains("test-forge", "Install the package");
    }

    @Test
    void anUnknownProjectIsRefusedBeforeTheForgeIsAsked() {
        // A mistyped project must never query some other project's forge.
        assertThat(execute("build", "status", "-p", "absent", "--branch=main"))
                .as("unknown project")
                .isEqualTo(2);
        assertThat(err.toString()).as("available project keys").contains("acme");
        assertThat(starts).as("no adapter started").isZero();
    }

    @ParameterizedTest
    @CsvSource({"failure,1", "cancelled,1", "queued,0", "running,0", "unknown,0", "success,0"})
    void theBuildVerdictSetsTheScriptExitCode(String verdict, int expected) {
        // A script must branch on the build verdict, including an answer that is not final.
        reader.answer = new Build(verdict, "detail");
        assertThat(execute("build", "status", "-p", "acme", "--commit=" + COMMIT))
                .as("exit for %s", verdict)
                .isEqualTo(expected);
        assertThat(reader.commit).as("the exact commit was queried").isEqualTo(COMMIT);
        assertThat(reader.heads).as("a commit query never asks head").isZero();
    }

    @Test
    void jsonCarriesTheSameFieldsAndEscapesTheLog() {
        // A script needs structured log text whose control characters cannot break its JSON input.
        reader.answer =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit",
                                        "failure",
                                        "\u001b[2J\n".getBytes(StandardCharsets.UTF_8))),
                        "failed");
        assertThat(execute("build", "status", "-p", "acme", "--commit=" + COMMIT, "--json"))
                .as("failed JSON answer")
                .isEqualTo(1);
        assertThat(Json.parse(out.toString()))
                .as("the public fields and text log")
                .isEqualTo(
                        Json.parse(
                                "{\"project\":\"acme\",\"repository\":\"acme\",\"branch\":null,\"commit\":\""
                                        + COMMIT
                                        + "\",\"verdict\":\"failure\",\"detail\":\"failed\",\"jobs\":[{\"name\":\"unit\",\"result\":\"failure\",\"log\":\"\\u001b[2J\\n\"}]}"));
        assertThat(out.toString())
                .as("JSON escapes terminal control characters")
                .doesNotContain("\u001b")
                .contains("\\u001b");
    }

    @Test
    void noLogsCanBeRequestedForABranch() {
        // A caller choosing no logs must not download or print forge log text.
        assertThat(execute("build", "status", "-p", "acme", "--branch=main", "--logs=none"))
                .as("the build still answers")
                .isEqualTo(1);
        assertThat(reader.logs.wire()).as("the policy reaches the adapter").isEqualTo("none");
        assertThat(out.toString())
                .as("no log evidence was requested")
                .doesNotContain("expected 1 but was 2");
    }

    @Test
    void noLogsCanBeRequestedForACommitAsJson() {
        // A script choosing no logs must receive job results without log contents.
        assertThat(
                        execute(
                                "build",
                                "status",
                                "-p",
                                "acme",
                                "--commit=" + COMMIT,
                                "--logs=none",
                                "--json"))
                .as("the build still answers")
                .isEqualTo(1);
        assertThat(reader.logs.wire()).as("the policy reaches the adapter").isEqualTo("none");
        assertThat(out.toString()).as("no log text").doesNotContain("expected 1 but was 2");
    }

    @Test
    void bothRevisionOptionsAreRefusedBeforeAnAdapterStarts() {
        // Conflicting revisions must not silently choose a different commit from the caller's.
        assertThat(execute("build", "status", "-p", "acme", "--branch=main", "--commit=" + COMMIT))
                .as("conflicting revisions are invalid input")
                .isEqualTo(64);
        assertThat(starts).as("no adapter started").isZero();
    }

    @Test
    void aMissingRevisionIsRefusedBeforeAnAdapterStarts() {
        // Omitting the revision must not ask an arbitrary branch or an empty commit.
        assertThat(execute("build", "status", "-p", "acme"))
                .as("a revision is required")
                .isEqualTo(64);
        assertThat(starts).as("no adapter started").isZero();
    }

    @Test
    void multipleRepositoriesNeedAKeyInsteadOfAnImplicitChoice() throws Exception {
        // A query must not silently ask the project's own repository when it names several.
        Files.writeString(
                directory.resolve("project.yml"),
                """
                repositories:
                  backend:
                    upstream: https://forge.example/acme/backend.git
                """,
                java.nio.file.StandardOpenOption.APPEND);
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a repository choice is required")
                .isEqualTo(64);
        assertThat(err.toString())
                .as("the available keys and how to select one")
                .contains("acme", "backend", "-r");
        assertThat(starts).as("no adapter started").isZero();
    }

    @Test
    void anUnknownRepositoryNamesTheKeysTheProjectHas() {
        // A mistyped repository must never become a question about the project's own repository.
        assertThat(execute("build", "status", "-p", "acme", "-r", "absent", "--branch=main"))
                .as("a repository key is required")
                .isEqualTo(64);
        assertThat(err.toString())
                .as("how to choose an existing repository")
                .contains("no repository 'absent'", "acme", "-r");
    }

    @Test
    void aRepositoryKeyAsksItsOwnUpstreamWithAllLogs() throws Exception {
        // Looking up the project upstream would report a different repository's build.
        Files.writeString(
                directory.resolve("project.yml"),
                """
                repositories:
                  backend:
                    upstream: https://forge.example/acme/backend.git
                """,
                java.nio.file.StandardOpenOption.APPEND);
        assertThat(
                        execute(
                                "build",
                                "status",
                                "-p",
                                "acme",
                                "-r",
                                "backend",
                                "--branch=main",
                                "--logs=all"))
                .as("the selected build failed")
                .isEqualTo(1);
        assertThat(reader.target.upstream())
                .as("the selected repository upstream")
                .isEqualTo("https://forge.example/acme/backend.git");
        assertThat(reader.logs).as("all logs were requested").isEqualTo(Build.Logs.ALL);
        assertThat(out.toString()).as("even a successful job's log is printed").contains("lint ok");
    }

    @Test
    void defaultIsRefusedWithoutCreatingItsProjectFile() {
        // The built-in project's fixed settings cannot support builds or be changed by a query.
        assertThat(execute("build", "status", "-p", "default", "--branch=main"))
                .as("default has no builds")
                .isEqualTo(78);
        assertThat(err.toString())
                .as("an available way to obtain builds")
                .contains("default", "no builds:", "Follow a project");
        assertThat(context.paths().xdg().state().resolve("default"))
                .as("the question wrote no default file")
                .doesNotExist();
    }

    @Test
    void aReaderThatCannotStartLeavesNoPrivateSocketDirectory() {
        // A refused adapter start must not leave a credential-bearing socket location behind.
        failStarting = true;
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("no adapter answer")
                .isEqualTo(70);
        assertThat(adapterSocket.getParent())
                .as("the private directory was removed")
                .doesNotExist();
    }

    @Test
    void aBuildAnswerCannotEchoTheTokenInEitherRendering() {
        // A forge may echo request credentials in job metadata or log text.
        reader.answer =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        token, "failure", token.getBytes(StandardCharsets.UTF_8))),
                        token);
        execute("build", "status", "-p", "acme", "--branch=main");
        execute("build", "status", "-p", "acme", "--branch=main", "--json");
        assertThat((out.toString() + err).contains(token))
                .as("neither rendering exposes the token")
                .isFalse();
    }

    @Test
    void aForgeRefusalCannotEchoTheToken() {
        // Error details from an adapter may contain the credential as well as the reason.
        reader.problem = new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, token);
        assertThat(execute("build", "status", "-p", "acme", "--branch=main"))
                .as("a refused credential")
                .isEqualTo(69);
        assertThat((out.toString() + err).contains(token))
                .as("no refusal prints the token")
                .isFalse();
    }

    private static final class FakeReader implements BuildStatusCommand.Reader {
        private Target target;
        private String head = COMMIT;
        private String commit;
        private Build.Logs logs;
        private RuntimeException problem;
        private boolean closed;
        private int looks;
        private int heads;
        private Build answer =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit tests",
                                        "failure",
                                        "expected 1 but was 2\n".getBytes(StandardCharsets.UTF_8)),
                                new Build.Job(
                                        "lint",
                                        "success",
                                        "lint ok\n".getBytes(StandardCharsets.UTF_8))),
                        "tests failed");

        @Override
        public String forge() {
            return "test-forge";
        }

        @Override
        public String head(Target asked, String branch) {
            target = asked;
            heads++;
            if (problem != null) {
                throw problem;
            }
            return head;
        }

        @Override
        public Build build(Target asked, String commit, Build.Logs logs) {
            target = asked;
            looks++;
            this.commit = commit;
            this.logs = logs;
            if (problem != null) {
                throw problem;
            }
            return new Build(
                    answer.verdict(),
                    answer.jobs().stream()
                            .map(
                                    job ->
                                            "all".equals(logs.wire())
                                                            || ("failure".equals(logs.wire())
                                                                    && "failure"
                                                                            .equals(job.result()))
                                                    ? job
                                                    : new Build.Job(job.name(), job.result()))
                            .toList(),
                    answer.detail());
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
