package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Creating a project's repository. Harm guarded: a repository made where nobody asked for one or
 * public by default, a forge's refusal read as something else, or a first file Porelo itself cannot
 * read.
 */
class ProjectCreateRepositoryCommandTest {

    @Nested
    class ForgeTests {

        private HttpServer server;

        private int status = 201;

        private @Nullable String answering;

        private final List<String> requests = new ArrayList<>();

        private final List<String> bodies = new ArrayList<>();

        private final List<String> tokens = new ArrayList<>();

        @BeforeEach
        void forge() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        requests.add(
                                exchange.getRequestMethod()
                                        + " "
                                        + exchange.getRequestURI().getPath());
                        bodies.add(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        tokens.add(
                                String.valueOf(
                                        exchange.getRequestHeaders().getFirst("Authorization")));
                        final byte[] answer =
                                (answering != null
                                                ? answering
                                                : recorded(exchange.getRequestURI().getPath()))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, answer.length);
                        exchange.getResponseBody().write(answer);
                        exchange.close();
                    });
            server.start();
        }

        @AfterEach
        void gone() {
            server.stop(0);
        }

        /** What the forge really answers on making a repository, whole. */
        private static String recorded(final String path) throws IOException {
            final String forge = path.startsWith("/api/v1/") ? "forgejo" : "github";
            try (InputStream recording =
                    ProjectCreateRepositoryCommandTest.class.getResourceAsStream(
                            "/forge-answers/" + forge + "-create-repository.json")) {
                return new String(recording.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        private ForgeRepositoryMaker maker() {
            return new ForgeRepositoryMaker(
                    base(),
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
        }

        @Test
        void onGitHubARepositoryIsMadePrivateAndEmptyInTheOrganization() {
            maker().make(URI.create("https://github.com"), "tok", "acme", "demo");

            assertThat(requests).containsExactly("POST /orgs/acme/repos");
            assertThat(bodies.getFirst())
                    .contains("\"name\":\"demo\"")
                    .contains("\"private\":true")
                    .as("a commit nobody signed would stand before the first signed one")
                    .doesNotContain("\"auto_init\":true");
            assertThat(tokens.getFirst()).contains("tok");
        }

        @Test
        void onAnyOtherForgeItIsMadeThroughForgejosApiOfThatForge() {
            maker().make(base(), "tok", "acme", "demo");

            assertThat(requests).containsExactly("POST /api/v1/orgs/acme/repos");
            assertThat(bodies.getFirst())
                    .contains("\"name\":\"demo\"")
                    .contains("\"private\":true")
                    .contains("\"default_branch\":\"main\"")
                    .doesNotContain("\"auto_init\":true");
        }

        private void refused(final int answering, final String reason) {
            status = answering;
            for (final URI forge : List.of(URI.create("https://github.com"), base())) {
                assertThatThrownBy(() -> maker().make(forge, "tok", "acme", "demo"))
                        .isInstanceOfSatisfying(
                                Refused.class, ex -> assertThat(ex.reason()).isEqualTo(reason));
            }
        }

        @Test
        void anAnswerNoRecordCanBeReadFromIsNotTakenForMade() {
            // Counted as made, a type the native image lacks would go unseen until the first
            // commit met a repository that may not be there.
            answering = "not a repository";
            for (final URI forge : List.of(URI.create("https://github.com"), base())) {
                assertThatThrownBy(() -> maker().make(forge, "tok", "acme", "demo"))
                        .isInstanceOfSatisfying(
                                Refused.class,
                                ex -> {
                                    assertThat(ex.reason()).isEqualTo(Refused.CANNOT_SAY);
                                    assertThat(ex.getMessage())
                                            .as("it did answer, and asking again is safe")
                                            .contains("cannot read")
                                            .contains("ask again")
                                            .doesNotContain("did not answer");
                                });
            }
        }

        @Test
        void whatAForgeRefusesIsAnsweredByWhatItRefused() {
            refused(401, Refused.NOT_ALLOWED);
            refused(403, Refused.NOT_ALLOWED);
            refused(409, Refused.EXISTS);
            refused(422, Refused.EXISTS);
            refused(404, Refused.CANNOT_SAY);
            refused(500, Refused.CANNOT_SAY);
        }
    }

    @Test
    void theFirstFileIsOnePoreloReads() {
        assertThat(
                        ProjectReader.read(
                                        new java.io.StringReader(
                                                ProjectCreateRepositoryCommand.firstFile("demo")),
                                        "first")
                                .name())
                .isEqualTo("demo");
    }

    @Test
    void aProjectsRepositoryStandsBesideTheOrganizationsReachedTheSameWay() {
        assertThat(
                        ProjectCreateRepositoryCommand.beside(
                                "git@github.com:acme/organization.git", "demo"))
                .isEqualTo("git@github.com:acme/demo.git");
        assertThat(
                        ProjectCreateRepositoryCommand.beside(
                                "ssh://git@forge.example:2222/acme/org/", "demo"))
                .isEqualTo("ssh://git@forge.example:2222/acme/demo");
        assertThat(ProjectCreateRepositoryCommand.beside("https://forge.example/acme/org", "demo"))
                .isEqualTo("https://forge.example/acme/demo");
    }

    @Nested
    class CommandTests {

        private static final String ORGANIZATION = "git@github.com:acme/organization.git";

        private static final String PROJECT = "git@github.com:acme/demo.git";

        @TempDir private Path dir;

        private Path work;

        private Path organization;

        private Path project;

        private final Map<String, SigningKey> keys = new HashMap<>();

        private final Map<String, Path> files = new HashMap<>();

        private final ProcessCommandRunner processes = new ProcessCommandRunner();

        private final StringWriter out = new StringWriter();

        private final StringWriter err = new StringWriter();

        private String acting = "alice";

        private int made;

        private String asked = "demo";

        private @Nullable String forgeRefuses;

        private boolean stopAfterMaking;

        private boolean stopAfterCommitting;

        private final CommandRunner runner =
                command -> {
                    final CommandResult result = processes.run(command);
                    if (stopAfterCommitting
                            && command.arguments().contains("push")
                            && command.arguments().contains(PROJECT)
                            && result.successful()) {
                        stopAfterCommitting = false;
                        // The push reached the forge, but the organization has not been written.
                        throw new Refused(
                                Refused.CANNOT_SAY, "stopped after the first commit", null);
                    }
                    return result;
                };

        private final IdentitySource identities =
                new IdentitySource() {
                    @Override
                    public Optional<Keys> keys(final String login) {
                        return Optional.ofNullable(CommandTests.this.keys.get(login))
                                .map(key -> new Keys(List.of(PersonCard.keyLine(key)), true));
                    }

                    @Override
                    public Optional<Boolean> owns(final String organisation, final String login) {
                        return Optional.of("mia".equals(login));
                    }
                };

        private final FetchAccess access =
                address ->
                        new FetchAccess.Lease() {
                            @Override
                            public List<String> arguments() {
                                // A lease survives git's sealed configuration without a global
                                // edit.
                                return List.of(
                                        "-c",
                                        "url."
                                                + project.getParent().toUri()
                                                + ".insteadOf=git@github.com:acme/");
                            }

                            @Override
                            public Map<String, String> environment() {
                                // The file protocol keeps an unmapped address from reaching ssh.
                                return Map.of("GIT_ALLOW_PROTOCOL", "file");
                            }
                        };

        @BeforeEach
        void signedOrganization() throws Exception {
            work = Files.createDirectory(dir.resolve("work"));
            final Path forge = Files.createDirectory(dir.resolve("forge"));
            organization = forge.resolve("organization.git");
            project = forge.resolve("demo.git");
            for (final String login : List.of("mia", "alice", "bob", "carol")) {
                final SigningKey key = SigningKey.generate("project repository test");
                keys.put(login, key);
                final Path file = dir.resolve(login + ".key");
                Files.write(file, key.openSshPrivateKey());
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                files.put(PersonCard.keyLine(key), file);
            }
            git(work, "init", "-q", "-b", "main");
            git(work, "config", "user.name", "Organization");
            git(work, "config", "user.email", "organization@example.org");
            git(work, "config", "gpg.format", "ssh");
            write("owners.yml", "owners:\n  - login: mia\n    key: \"" + key("mia") + "\"\n");
            commit("mia");
            for (final String login : List.of("mia", "alice", "bob", "carol")) {
                write(
                        "people/" + login + ".card",
                        PersonCard.write(
                                login,
                                login,
                                "",
                                keys.get(login),
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2030, 1, 1),
                                null));
                commit(login);
            }
            write("projects/demo/card", "name: demo\nadmin: alice\n");
            commit("mia");
            write(
                    "projects/demo/members.yml",
                    "members:\n  - login: alice\n    role: admin\n"
                            + "  - login: bob\n    role: developer\n"
                            + "  - login: carol\n    role: admin\n    locked: true\n");
            commit("alice");
            git(dir, "clone", "-q", "--bare", work.toString(), organization.toString());
            OrgAccess.settle(context(), ORGANIZATION);
        }

        private String key(final String login) {
            return PersonCard.keyLine(keys.get(login));
        }

        private String git(final Path repository, final String... arguments) {
            final List<String> all = new ArrayList<>(List.of("git"));
            all.addAll(List.of(arguments));
            return runner.runOrFail(
                            Command.of(all)
                                    .in(repository)
                                    .withChosenEnvironment(
                                            Map.of(
                                                    "PATH", "/usr/bin:/bin",
                                                    "LC_ALL", "C",
                                                    "GIT_CONFIG_GLOBAL", "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM", "1",
                                                    "GIT_ALLOW_PROTOCOL", "file")))
                    .trimmedOutput();
        }

        private void write(final String name, final String text) throws IOException {
            final Path file = work.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        }

        private void commit(final String login) {
            git(work, "add", "-A");
            git(
                    work,
                    "-c",
                    "user.signingkey=" + files.get(key(login)),
                    "commit",
                    "-q",
                    "-S",
                    "-m",
                    "Organization change");
        }

        private PoreloContext context() {
            final XdgPaths xdg =
                    XdgPaths.of(
                            name ->
                                    switch (name) {
                                        case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                        case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                        case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                        default -> null;
                                    },
                            dir);
            return new PoreloContext(
                    runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0, name -> null);
        }

        private GitTreeStore store(final PoreloContext machine) {
            return GitTreeStore.at(
                    runner,
                    organization.toString(),
                    "acme",
                    "github.com",
                    access,
                    machine.paths().xdg().state().resolve("organization"));
        }

        private ProjectFile projectFile() {
            return new ProjectFile(
                    runner, access, new CommitSigning(Map.of(), files), dir.resolve("memory"));
        }

        private OrganizationQuestions read() throws Exception {
            return store(context()).read(identities);
        }

        private void wire(final CommandLine line) {
            if (line.getCommand() instanceof OrgReading reading) {
                reading.opener =
                        (machine, ignored) -> new OrgAccess.Opened(store(machine), identities);
            }
            if (line.getCommand() instanceof ProjectCreateRepositoryCommand
                    || line.getCommand() instanceof OrgProjectCommand.Add
                    || line.getCommand() instanceof OrgProjectCommand.Remove) {
                final OrgWriting writing = (OrgWriting) line.getCommand();
                writing.session =
                        machine ->
                                new OrgWriting.Session(
                                        new GitTreeWrites(
                                                runner,
                                                organization.toString(),
                                                "acme",
                                                access,
                                                identities,
                                                new CommitSigning(Map.of(), files)),
                                        new OrganizationWrites.Acting(acting, key(acting)),
                                        keys.get(acting),
                                        projectFile());
                final ProjectCreateRepositoryCommand.Maker making =
                        (machine, origin, name) -> {
                            assertThat(origin.organization())
                                    .as(
                                            "the forge request must stay in the configured"
                                                    + " organization")
                                    .isEqualTo("acme");
                            assertThat(name)
                                    .as("the forge must make the project the admin asked for")
                                    .isEqualTo(asked);
                            made++;
                            if (forgeRefuses != null) {
                                throw new Refused(Refused.CANNOT_SAY, forgeRefuses, null);
                            }
                            git(
                                    dir,
                                    "init",
                                    "-q",
                                    "--bare",
                                    "-b",
                                    "main",
                                    project.resolveSibling(asked + ".git").toString());
                            if (stopAfterMaking) {
                                stopAfterMaking = false;
                                throw new Refused(
                                        Refused.CANNOT_SAY,
                                        "stopped after making the repository",
                                        null);
                            }
                        };
                if (line.getCommand() instanceof ProjectCreateRepositoryCommand open) {
                    open.maker = making;
                }
                if (line.getCommand() instanceof OrgProjectCommand.Add add) {
                    add.maker = making;
                }
            }
            line.getSubcommands().values().forEach(this::wire);
        }

        private int run(final String id, final String base) {
            return execute(
                    "project", "create-repository", "demo", "--id", id, "--base", base, "--json");
        }

        private int execute(final String... arguments) {
            out.getBuffer().setLength(0);
            err.getBuffer().setLength(0);
            final CommandLine line = PoreloCli.commandLine(context());
            line.setOut(new PrintWriter(out));
            line.setErr(new PrintWriter(err));
            wire(line);
            return line.execute(arguments);
        }

        private int addingAndCreating(final String admin, final String id) {
            asked = "fresh";
            acting = "mia";
            return execute(
                    "org",
                    "project",
                    "add",
                    "fresh",
                    "--admin",
                    admin,
                    "--create-repository",
                    "--id",
                    id,
                    "--json");
        }

        private Map<?, ?> answer() {
            return (Map<?, ?>) Json.parse(out.toString().strip());
        }

        private String tip(final Path repository) {
            return git(repository, "rev-parse", "refs/heads/main");
        }

        private void opened(final String id) throws Exception {
            final OrganizationQuestions state = read();
            assertThat(state.getProjectRepository("demo"))
                    .as("the address must be accepted from the signed organization history")
                    .contains(PROJECT);
            final ProjectFile.Read file = projectFile().read(PROJECT, state.signingKeys("demo"));
            assertThat(file.state())
                    .as(
                            "the first project.yml must verify with the project's accepted admin"
                                    + " keys: %s",
                            file.why())
                    .isEqualTo(ProjectFile.READ);
            assertThat(file.project().name()).isEqualTo("demo");
            assertThat(git(project, "ls-tree", "--name-only", "HEAD"))
                    .as("an empty forge repository must start with the signed project.yml")
                    .isEqualTo("project.yml");
            assertThat(git(project, "rev-list", "--count", "HEAD"))
                    .as("a retry must reuse the first commit")
                    .isEqualTo("1");
            assertThat(git(project, "log", "-1", "--format=%B"))
                    .as("the project commit must retain the id that allows a partial run to resume")
                    .contains("Porelo-Change-Id: " + id);
            assertThat(made)
                    .as("a partial run must not make the forge repository twice")
                    .isEqualTo(1);
            assertThat(answer())
                    .as(
                            "the interface needs the organization's revision and the address that"
                                    + " was filed")
                    .isEqualTo(
                            Map.of(
                                    "revision",
                                    state.revision(),
                                    "id",
                                    id,
                                    "again",
                                    false,
                                    "of",
                                    "organization",
                                    "address",
                                    PROJECT));
        }

        private void refused(final int exit, final String reason, final String base) {
            assertThat(exit).as("the JSON refusal was %s; stderr was %s", out, err).isEqualTo(65);
            assertThat(answer().get("reason")).isEqualTo(reason);
            assertThat(answer().get("revision"))
                    .as("a role refusal must name the organization revision it rests on")
                    .isEqualTo(base);
            assertThat(err.toString())
                    .as("a JSON refusal must also explain itself to the person on stderr")
                    .contains((String) answer().get("words"));
            assertThat(made).as("a refused person must not create a forge repository").isZero();
            assertThat(project).doesNotExist();
            assertThat(tip(organization))
                    .as("a refusal must not change the organization")
                    .isEqualTo(base);
        }

        @Test
        void oneAdminNeedsNoReviewByDefault() throws Exception {
            // Harm: a sole admin's first task waits for an approval nobody can give.
            members(false, false);
            assertThat(run("solo", tip(organization))).as("creation: %s", err).isZero();
            assertThat(git(project, "show", "HEAD:project.yml"))
                    .as("a single unlocked admin must get an explicit optional review")
                    .contains("review: optional");
        }

        @Test
        void twoUnlockedAdminsKeepRequiredReviewByDefault() throws Exception {
            // Harm: a shared project's first task bypasses the other admin's review.
            members(true, false);
            assertThat(run("shared", tip(organization))).as("creation: %s", err).isZero();
            assertThat(projectFile().read(PROJECT, read().signingKeys("demo")).project().review())
                    .as("two unlocked admins must retain required review")
                    .isEqualTo("required");
        }

        @Test
        void aLockedSecondAdminCannotSupplyTheDefaultReview() throws Exception {
            // Harm: counting a locked admin strands the sole active admin's work.
            assertThat(run("locked-second", tip(organization))).as("creation: %s", err).isZero();
            assertThat(git(project, "show", "HEAD:project.yml"))
                    .as("a locked second admin must not require an unavailable review")
                    .contains("review: optional");
        }

        private void members(final boolean second, final boolean locked) throws Exception {
            write(
                    "projects/demo/members.yml",
                    "members:\n  - login: alice\n    role: admin\n"
                            + (second
                                    ? "  - login: bob\n    role: admin\n    locked: "
                                            + locked
                                            + "\n"
                                    : ""));
            commit("alice");
            git(work, "push", "-q", organization.toString(), "main");
        }

        @Test
        void aCompletedIdDoesNotTreatAnIgnoredReviewAsWritten() throws Exception {
            // Harm: a replay describes a review requirement the signed file does not hold.
            assertThat(
                            execute(
                                    "project",
                                    "create-repository",
                                    "demo",
                                    "--id",
                                    "ignored-review",
                                    "--json"))
                    .as("creation: %s", err)
                    .isZero();
            assertThat(
                            execute(
                                    "project",
                                    "create-repository",
                                    "demo",
                                    "--id",
                                    "ignored-review",
                                    "--review",
                                    "required",
                                    "--json"))
                    .as("replay: %s", err)
                    .isZero();
            assertThat(answer().get("again"))
                    .as("the existing receipt is returned")
                    .isEqualTo(true);
            assertThat(git(project, "show", "HEAD:project.yml"))
                    .as("the ignored argument cannot change the signed file")
                    .contains("review: optional");
            assertThat(answer().containsKey("note"))
                    .as("an ignored argument cannot justify a review warning")
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void aPartialCreationKeepsItsDefaultWhenTheAdminCountChanges(final boolean second)
                throws Exception {
            // Harm: a changed membership strands a first commit under an unchanged retry id.
            members(second, false);
            final String[] arguments = {
                "project", "create-repository", "demo", "--id", "membership-default", "--json"
            };
            stopAfterCommitting = true;
            assertThat(execute(arguments))
                    .as("the first commit must succeed before interruption: %s", err)
                    .isEqualTo(69);
            final String first = git(project, "show", "HEAD:project.yml");
            members(!second, false);
            assertThat(execute(arguments))
                    .as("unchanged retry must finish after membership changes: %s", err)
                    .isZero();
            assertThat(git(project, "show", "HEAD:project.yml"))
                    .as("the first file's original review choice must stand")
                    .isEqualTo(first);
            assertThat(git(project, "rev-list", "--count", "HEAD"))
                    .as("retry must reuse the first signed commit")
                    .isEqualTo("1");
        }

        private int formatted(final boolean json, final String... arguments) {
            final List<String> all = new ArrayList<>(List.of(arguments));
            if (json) {
                all.add("--json");
            }
            return execute(all.toArray(String[]::new));
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void requiredReviewWithOneAdminExplainsTheWaitAndTheManualChange(final boolean json)
                throws Exception {
            // Harm: an explicit review requirement silently leaves the creator unable to merge.
            assertThat(
                            formatted(
                                    json,
                                    "project",
                                    "create-repository",
                                    "demo",
                                    "--review",
                                    "required",
                                    "--id",
                                    "required-solo"))
                    .as("creation: %s", err)
                    .isZero();
            assertThat(git(project, "show", "HEAD:project.yml"))
                    .as("the explicit review choice must be written")
                    .contains("review: required");
            final String note = json ? String.valueOf(answer().get("note")) : out.toString();
            assertThat(note)
                    .as("creation must explain the unavailable approval and the real verb")
                    .contains(
                            "second admin's approval",
                            "porelo project review demo optional",
                            "signed");
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void oneStepRequiredReviewAlsoExplainsTheWait(final boolean json) throws Exception {
            // Harm: one-step creation hides the same impossible review from its sole admin.
            asked = "fresh";
            acting = "mia";
            assertThat(
                            formatted(
                                    json,
                                    "org",
                                    "project",
                                    "add",
                                    "fresh",
                                    "--admin",
                                    "mia",
                                    "--create-repository",
                                    "--review",
                                    "required",
                                    "--id",
                                    "required-fresh"))
                    .as("creation: %s", err)
                    .isZero();
            assertThat(git(project.resolveSibling("fresh.git"), "show", "HEAD:project.yml"))
                    .as("one-step creation must preserve an explicit requirement")
                    .contains("review: required");
            final String note = json ? String.valueOf(answer().get("note")) : out.toString();
            assertThat(note)
                    .as("one-step creation must explain how its admin can change review")
                    .contains(
                            "second admin's approval",
                            "porelo project review fresh optional",
                            "signed");
        }

        @Test
        void aPersonNamingThemselvesAddsAProjectAndCreatesItsRepositoryInOneStep()
                throws Exception {
            // Harm: one-step creation strands its sole admin behind an impossible review.
            assertThat(addingAndCreating("mia", "one-step")).as("what it said: %s", err).isZero();

            assertThat(answer().get("address")).isEqualTo("git@github.com:acme/fresh.git");
            assertThat(git(project.resolveSibling("fresh.git"), "show", "HEAD:project.yml"))
                    .as("one-step creation must also choose optional for its sole admin")
                    .contains("review: optional");
            assertThat(answer().get("id"))
                    .as("the id the person asked under, which finds both parts again")
                    .isEqualTo("one-step");
            final OrganizationQuestions state = read();
            assertThat(state.getProjectRepository("fresh"))
                    .as("the second part passed the admin check on what the first part made")
                    .contains("git@github.com:acme/fresh.git");
            assertThat(
                            projectFile()
                                    .read(
                                            "git@github.com:acme/fresh.git",
                                            state.signingKeys("fresh"))
                                    .state())
                    .as("its first file is signed by its admin, and reads")
                    .isEqualTo(ProjectFile.READ);
        }

        @Test
        void namingAnotherPersonAsAdminWritesNothing() {
            final String before = tip(organization);

            assertThat(addingAndCreating("alice", "for-another"))
                    .as("what it said: %s", err)
                    .isEqualTo(64);

            assertThat(tip(organization)).isEqualTo(before);
            assertThat(made).as("the forge is not asked either").isZero();
        }

        @Test
        void aForgeRefusingHalfwayLeavesTheProjectSaysSoAndTheSameIdFinishesIt() throws Exception {
            forgeRefuses = "the forge said no";

            assertThat(addingAndCreating("mia", "halfway")).isNotZero();

            assertThat(out.toString() + err)
                    .as("what stands, why, and how to finish")
                    .contains("fresh is added")
                    .contains("the forge said no")
                    .contains("--id halfway");
            assertThat(read().getProjectRepository("fresh")).as("not opened yet").isEmpty();
            final String added = tip(organization);

            forgeRefuses = null;
            assertThat(addingAndCreating("mia", "halfway")).as("what it said: %s", err).isZero();

            assertThat(read().getProjectRepository("fresh")).isPresent();
            assertThat(tip(organization))
                    .as("only the address was still to file")
                    .isNotEqualTo(added);
            assertThat(git(organization, "rev-list", "--count", added + "..main")).isEqualTo("1");
        }

        @Test
        void askedAgainUnderItsIdTheOneStepChangesNothing() throws Exception {
            assertThat(addingAndCreating("mia", "twice")).as("what it said: %s", err).isZero();
            final String tip = tip(organization);
            final int makes = made;

            assertThat(addingAndCreating("mia", "twice")).as("what it said: %s", err).isZero();

            assertThat(tip(organization)).isEqualTo(tip);
            assertThat(made).isEqualTo(makes);
        }

        @Test
        void anIdTooLongForItsSecondPartWritesNothing() {
            final String before = tip(organization);

            assertThat(addingAndCreating("mia", "a".repeat(54)))
                    .as("the second part's id would not fit, so the first is not made")
                    .isEqualTo(64);

            assertThat(tip(organization)).as("nothing was added").isEqualTo(before);
        }

        @Test
        void aReviewThatIsNoWordWritesNothing() {
            asked = "fresh";
            acting = "mia";
            final String before = tip(organization);

            assertThat(
                            execute(
                                    "org",
                                    "project",
                                    "add",
                                    "fresh",
                                    "--admin",
                                    "mia",
                                    "--create-repository",
                                    "--review",
                                    "strict",
                                    "--json"))
                    .as("wrong use is said before anything stands")
                    .isEqualTo(64);

            assertThat(tip(organization)).as("nothing was added").isEqualTo(before);
        }

        @Test
        void aForgeNamingTheIdAlreadyIsNotToldTwice() {
            forgeRefuses = "the forge did not answer: ask again with the same --id";

            assertThat(addingAndCreating("mia", "halfway")).isNotZero();

            assertThat(err.toString())
                    .as("one way out, once")
                    .contains("fresh is added")
                    .doesNotContain("--id halfway");
        }

        @Test
        void aRepositoryThatHoldsSomethingElseIsNotOfferedASecondAsking() throws Exception {
            final Path taken = project.resolveSibling("fresh.git");
            git(dir, "init", "-q", "--bare", "-b", "main", taken.toString());
            final Path work = dir.resolve("taken-work");
            git(dir, "init", "-q", "-b", "main", work.toString());
            Files.writeString(work.resolve("README"), "someone's\n");
            git(work, "add", "README");
            git(work, "-c", "user.name=x", "-c", "user.email=x@x", "commit", "-q", "-m", "theirs");
            git(work, "push", "-q", taken.toString(), "main");

            assertThat(addingAndCreating("mia", "taken")).isEqualTo(65);

            assertThat(err.toString())
                    .as("asking again would only say this again, so it looks instead")
                    .contains("fresh is added")
                    .contains("porelo project show fresh")
                    .doesNotContain("ask again");
        }

        @Test
        void aProjectRemovedInBetweenIsNotSaidToBeAdded() throws Exception {
            forgeRefuses = "the forge said no";
            assertThat(addingAndCreating("mia", "halfway")).isNotZero();
            forgeRefuses = null;
            assertThat(execute("org", "project", "remove", "fresh", "--json"))
                    .as("what it said: %s", err)
                    .isZero();

            assertThat(addingAndCreating("mia", "halfway")).isNotZero();

            assertThat(err.toString())
                    .as("it does not stand, and asking again cannot bring it back")
                    .doesNotContain("is added")
                    .doesNotContain("ask again");
        }

        @Test
        void aProjectRepositoryThatTakesNoCommitIsNotCalledTheOrganization() throws Exception {
            git(dir, "init", "-q", "--bare", "-b", "main", project.toString());
            final Path hook = project.resolve("hooks/pre-receive");
            Files.writeString(hook, "#!/bin/sh\nexit 1\n");
            assertThat(hook.toFile().setExecutable(true)).as("a hook that refuses").isTrue();

            assertThat(run("open-demo", tip(organization))).isEqualTo(69);

            assertThat(err.toString())
                    .as("which repository it was, and how to go on")
                    .contains("the project's repository did not take its first commit")
                    .contains("the same --id")
                    .doesNotContain("organization cannot be reached");
        }

        @Test
        void aRetryUnderAnotherReviewIsAnotherChange() throws Exception {
            final String base = tip(organization);
            stopAfterCommitting = true;
            assertThat(
                            execute(
                                    "project",
                                    "create-repository",
                                    "demo",
                                    "--review",
                                    "required",
                                    "--id",
                                    "open-demo",
                                    "--base",
                                    base,
                                    "--json"))
                    .as("the interruption must be reached: %s", err)
                    .isEqualTo(69);

            assertThat(
                            execute(
                                    "project",
                                    "create-repository",
                                    "demo",
                                    "--review",
                                    "optional",
                                    "--id",
                                    "open-demo",
                                    "--base",
                                    base,
                                    "--json"))
                    .as("the first commit says required; filing it as optional would be false")
                    .isEqualTo(64);
            assertThat(read().getProjectRepository("demo")).as("nothing filed").isEmpty();
        }

        @Test
        void aCompletedOpenAskedAgainAfterTheAddressMovedAnswersItsOwnFiling() throws Exception {
            final String base = tip(organization);
            assertThat(run("open-demo", base)).as("what it said: %s", err).isZero();
            final Object filing = answer().get("revision");
            new GitTreeWrites(
                            runner,
                            organization.toString(),
                            "acme",
                            access,
                            identities,
                            new CommitSigning(Map.of(), files))
                    .changeProjectRepository(
                            new OrganizationWrites.Change(
                                    new OrganizationWrites.Acting(acting, key(acting)),
                                    tip(organization),
                                    "moved"),
                            "demo",
                            "git@github.com:acme/moved.git");
            final String moved = tip(organization);

            assertThat(run("open-demo", base)).as("what it said: %s", err).isZero();

            assertThat(answer().get("revision")).as("the id's own filing").isEqualTo(filing);
            assertThat(answer().get("again")).as("nothing new").isEqualTo(true);
            assertThat(tip(organization)).as("no second filing").isEqualTo(moved);
        }

        @Test
        void aReviewWithoutTheRepositoryIsUsage() {
            acting = "mia";

            assertThat(
                            execute(
                                    "org",
                                    "project",
                                    "add",
                                    "fresh",
                                    "--admin",
                                    "mia",
                                    "--review",
                                    "required",
                                    "--json"))
                    .as("what it said: %s", err)
                    .isEqualTo(64);
        }

        @Test
        void theOldNameStillWorksAndIsNotInTheHelp() throws Exception {
            final String base = tip(organization);

            assertThat(
                            execute(
                                    "project", "open", "demo", "--id", "by-old", "--base", base,
                                    "--json"))
                    .as("an interface of an earlier build")
                    .isZero();
            opened("by-old");

            assertThat(execute("project", "--help")).isZero();
            assertThat(out.toString())
                    .contains("create-repository")
                    .as("hidden")
                    .doesNotContainPattern("(?m)^\\s+open\\b");
        }

        @Test
        void anAdminOpensAProjectWithAVerifiableFirstCommitAndAFilingAnswer() throws Exception {
            final String base = tip(organization);

            assertThat(run("open-demo", base)).as("what it said: %s", err).isZero();

            opened("open-demo");
            assertThat(tip(organization))
                    .as("opening must file a new address in the organization")
                    .isNotEqualTo(base);
        }

        @Test
        void aRunStoppedAfterMakingTheRepositoryCompletesWithoutMakingItTwice() throws Exception {
            final String base = tip(organization);
            stopAfterMaking = true;

            assertThat(run("open-demo", base))
                    .as("the simulated interruption must be reached: %s", err)
                    .isEqualTo(69);
            assertThat(stopAfterMaking).as("the maker must reach the interruption").isFalse();
            assertThat(project).isDirectory();
            assertThat(git(project, "for-each-ref"))
                    .as("the interruption must leave a repository with no first commit")
                    .isEmpty();
            assertThat(read().getProjectRepository("demo"))
                    .as("an interrupted open must not claim to have filed an address")
                    .isEmpty();
            assertThat(tip(organization)).isEqualTo(base);

            assertThat(run("open-demo", base)).as("what it said: %s", err).isZero();

            opened("open-demo");
        }

        @Test
        void aRunStoppedAfterTheFirstCommitFilesTheAddressWithoutASecondProjectCommit()
                throws Exception {
            final String base = tip(organization);
            stopAfterCommitting = true;

            assertThat(run("open-demo", base))
                    .as("the simulated interruption must be reached: %s", err)
                    .isEqualTo(69);
            assertThat(stopAfterCommitting)
                    .as("the first push must reach the interruption")
                    .isFalse();
            final String first = tip(project);
            assertThat(read().getProjectRepository("demo"))
                    .as("the interruption must precede the address filing")
                    .isEmpty();
            assertThat(tip(organization)).isEqualTo(base);

            assertThat(run("open-demo", base)).as("what it said: %s", err).isZero();

            opened("open-demo");
            assertThat(tip(project))
                    .as("resuming must keep the exact commit that already reached the forge")
                    .isEqualTo(first);
        }

        @Test
        void aCompletedOpenAskedAgainAnswersItsOriginalRevisionDespiteItsOldBase()
                throws Exception {
            final String base = tip(organization);
            assertThat(run("open-demo", base)).as("what it said: %s", err).isZero();
            final Map<?, ?> first = answer();
            final String commit = tip(project);

            assertThat(run("open-demo", base))
                    .as("an id must be found before comparing its old base: %s", err)
                    .isZero();

            assertThat(answer())
                    .as("a completed retry must identify the original organization change")
                    .isEqualTo(
                            Map.of(
                                    "revision",
                                    first.get("revision"),
                                    "id",
                                    "open-demo",
                                    "again",
                                    true,
                                    "of",
                                    "organization",
                                    "address",
                                    PROJECT));
            assertThat(tip(organization))
                    .as("a completed retry must not file again")
                    .isEqualTo(first.get("revision"));
            assertThat(tip(project)).as("a completed retry must not sign again").isEqualTo(commit);
            assertThat(made).as("a completed retry must not make another repository").isEqualTo(1);
        }

        @Test
        void aProjectAlreadyOpenedUnderAnotherIdIsRefusedWithoutChangingEitherRepository()
                throws Exception {
            assertThat(run("open-demo", tip(organization))).as("what it said: %s", err).isZero();
            final String before = tip(organization);
            final String first = tip(project);

            assertThat(run("another-open", before)).as("what it said: %s", err).isEqualTo(65);

            assertThat(answer().get("reason")).isEqualTo(Refused.EXISTS);
            assertThat(err.toString())
                    .as("a JSON refusal must also explain itself on stderr")
                    .contains((String) answer().get("words"));
            assertThat(made).as("an opened project must not be made twice").isEqualTo(1);
            assertThat(tip(organization))
                    .as("exists must leave the filed address alone")
                    .isEqualTo(before);
            assertThat(tip(project))
                    .as("exists must leave the signed project history alone")
                    .isEqualTo(first);
        }

        @Test
        void anOrganizationThatMovedOnIsToldBeforeAnythingIsMade() {
            final String stale = "a".repeat(40);

            assertThat(run("open-demo", stale)).as("what it said: %s", err).isEqualTo(65);

            assertThat(answer().get("reason")).isEqualTo("moved-on");
            assertThat(answer().get("words"))
                    .as("a stopped run goes on under the same id with the new revision")
                    .asString()
                    .contains("--base")
                    .contains("the same --id");
            assertThat(made)
                    .as("a repository made first leaves a new --id with nothing but exists")
                    .isZero();
            assertThat(project).doesNotExist();
        }

        @Test
        void aLockedAdminIsRefusedBeforeAnythingIsMade() {
            acting = "carol";
            final String base = tip(organization);

            refused(run("locked-open", base), Refused.LOCKED, base);
        }

        @Test
        void aDeveloperIsRefusedBeforeAnythingIsMade() {
            acting = "bob";
            final String base = tip(organization);

            refused(run("developer-open", base), Refused.NOT_ALLOWED, base);
        }

        @Test
        void aStaleOrganizationCannotDecideWhoMayOpenAProject() throws Exception {
            final String base = read().revision();
            final Path unavailable = dir.resolve("unavailable.git");
            Files.move(organization, unavailable);
            assertThat(
                            OrgAccess.read(
                                            context(),
                                            (machine, ignored) ->
                                                    new OrgAccess.Opened(
                                                            store(machine), identities))
                                    .stale())
                    .as("this refusal must exercise a cached trusted state rather than no state")
                    .isTrue();

            assertThat(run("stale-open", base)).as("what it said: %s", err).isEqualTo(69);

            assertThat(answer().get("reason")).isEqualTo(Refused.CANNOT_SAY);
            assertThat(err.toString())
                    .as(
                            "the refusal must explain why the cached admin role cannot authorize a"
                                    + " write")
                    .contains("who may");
            assertThat(made).as("a stale role must not create a forge repository").isZero();
            assertThat(project).doesNotExist();
            assertThat(tip(unavailable))
                    .as("a stale read must leave the organization unchanged")
                    .isEqualTo(base);
        }
    }
}
