package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start of an organization's project. Harm guarded: what a task runs under is the file a project
 * admin signed, judged with keys the organization answers now, and nothing older or unjudged.
 */
class ProjectTakingTest {

    private static final String FILE =
            """
            project:
              name: "demo"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    /** What the organization files; git is sent to the test's own repository in its place. */
    private static final String ADDRESS = "https://forge.example.org/acme/demo.git";

    /** A token: seen in no message, whatever way the helper's output goes. */
    private static final String SECRET = "token-of-the-person";

    @TempDir private Path dir;

    private RegistryCheckout machine;

    private TestOrganization organization;

    private final Deque<CommandResult> helper = new ArrayDeque<>();

    private final List<List<String>> asked = new ArrayList<>();

    private @Nullable HttpServer server;

    private PoreloContext context;

    private Path repository;

    private Path hostFiles;

    @BeforeEach
    void anOrganizationWithAProject() throws Exception {
        machine = new RegistryCheckout(dir);
        organization = new TestOrganization(machine);
        context =
                new PoreloContext(
                        command ->
                                isTheHostsHelper(command)
                                        ? helperAnswers(command)
                                        : machine.runner.run(
                                                new Command(
                                                        command.arguments().stream()
                                                                .map(this::toTheTestsRepository)
                                                                .toList(),
                                                        command.workingDirectory(),
                                                        command.environment(),
                                                        command.input(),
                                                        command.inheritsEnvironment(),
                                                        command.binaryInput())),
                        machine.paths,
                        args -> 0,
                        machine.environment());
        hostFiles = Files.createDirectory(dir.resolve("host-files"));
        repository = Files.createDirectory(dir.resolve("demo"));
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Project");
        git("config", "user.email", "project@example.org");
        git("config", "gpg.format", "ssh");
        commit("michael", FILE);
        organization.filed("michael");
        organization
                .writes()
                .addProject(
                        organization.change("michael", "michael", "add-demo"), "demo", "michael");
        organization
                .writes()
                .changeProjectRepository(
                        organization.change("michael", "michael", "address-demo"), "demo", ADDRESS);
    }

    @AfterEach
    void close() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        machine.close();
    }

    private boolean isTheHostsHelper(final Command command) {
        return "sudo".equals(command.arguments().getFirst());
    }

    private CommandResult helperAnswers(final Command command) {
        asked.add(List.copyOf(command.arguments()));
        final CommandResult answer = helper.poll();
        assertThat(answer).as("what the helper answers the call %s", asked.size()).isNotNull();
        return new CommandResult(
                command, answer.exitCode(), answer.standardOutput(), answer.standardError());
    }

    private void helperGivesToken(final String token) {
        helper.add(new CommandResult(Command.of("sudo"), 0, token + "\n", ""));
    }

    private void helperFails(final int code) {
        helper.add(new CommandResult(Command.of("sudo"), code, SECRET, SECRET));
    }

    /** The project's repository behind a gate that lets these tokens through. */
    private String gate(final String... tokens) throws Exception {
        git("update-server-info");
        final Set<String> wanted =
                java.util.Arrays.stream(tokens)
                        .map(
                                token ->
                                        "Basic "
                                                + Base64.getEncoder()
                                                        .encodeToString(
                                                                ("x-access-token:" + token)
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)))
                        .collect(java.util.stream.Collectors.toSet());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/acme/demo.git/",
                exchange -> {
                    final String said = exchange.getRequestHeaders().getFirst("Authorization");
                    final Path file =
                            repository
                                    .resolve(".git")
                                    .resolve(
                                            exchange.getRequestURI()
                                                    .getPath()
                                                    .substring("/acme/demo.git/".length()));
                    if (!wanted.contains(said)) {
                        exchange.getResponseHeaders()
                                .add("WWW-Authenticate", "Basic realm=\"gate\"");
                        exchange.sendResponseHeaders(401, -1);
                    } else if (!Files.isRegularFile(file)) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, Files.size(file));
                        try (var body = exchange.getResponseBody()) {
                            Files.copy(file, body);
                        }
                    }
                    exchange.close();
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/acme/demo.git";
    }

    private void refusedHoldingNoToken(final int code, final String reason) {
        assertThatThrownBy(() -> taking().taken("demo"))
                .isInstanceOfSatisfying(
                        ProjectTaking.Refused.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo(code);
                            assertThat(ex.reason()).isEqualTo(reason);
                            assertThat(ex.getMessage()).doesNotContain(SECRET);
                        });
    }

    private String toTheTestsRepository(final String argument) {
        return ADDRESS.equals(argument) ? repository.toString() : argument;
    }

    private String git(final String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return machine.runner
                .runOrFail(
                        Command.of(all)
                                .in(repository)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }

    private String commit(final String signer, final String text) throws IOException {
        Files.writeString(repository.resolve("project.yml"), text);
        git("add", "-A");
        git(
                "-c",
                "user.signingkey=" + machine.keyFiles.get(signer),
                "commit",
                "-q",
                "-S",
                "-m",
                "a change");
        return git("rev-parse", "HEAD");
    }

    private ProjectTaking taking() {
        return new ProjectTaking(
                context,
                organization.opener(),
                FetchAccess.NONE,
                new OrganizationProjects(
                        context.paths().projects().organizationProjects(), hostFiles));
    }

    private void hostFile(final String homeserver, final String room) throws IOException {
        hostFile(homeserver, room, "file://" + repository);
    }

    private void hostFile(final String homeserver, final String room, final String fetchFrom)
            throws IOException {
        final Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("login", "michael");
        row.put("role", "admin");
        row.put("key", machine.key("michael"));
        row.put("valid_from", "2000-01-01");
        row.put("valid_until", "2999-01-01");
        row.put("revoked_since", null);
        final Map<String, Object> file = new java.util.LinkedHashMap<>();
        file.put("project", "demo");
        file.put("revision", "the-organizations");
        file.put("address", "git@forge.example.org:acme/demo.git");
        file.put("fetch_from", fetchFrom);
        file.put("signing_keys", List.of(row));
        file.put("homeserver", homeserver);
        file.put("room", room);
        Files.writeString(hostFiles.resolve("demo.json"), Json.write(file));
    }

    private void refused(final int code, final String reason) {
        assertThatThrownBy(() -> taking().taken("demo"))
                .isInstanceOfSatisfying(
                        ProjectTaking.Refused.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo(code);
                            assertThat(ex.reason()).isEqualTo(reason);
                        });
    }

    @Test
    void a_start_places_the_file_and_the_clone_at_the_revision_it_judged() throws Exception {
        final String tip = git("rev-parse", "HEAD");

        taking().taken("demo");

        final ProjectSource.Found found = ProjectSource.resolve(context, "demo");
        assertThat(found.outcome()).isEqualTo(ProjectSource.Outcome.ORGANIZATION);
        assertThat(found.commit()).isEqualTo(tip);
        assertThat(found.file()).hasContent(FILE);
        final Path clone = context.paths().projects().organizationClone("demo");
        assertThat(clone.resolve(".git")).isDirectory();
        assertThat(ProjectMembers.certifiers(context, "demo"))
                .as("the keys it judged with are what certifies")
                .containsExactly(machine.key("michael"));
    }

    @Test
    void every_start_reads_again() throws Exception {
        taking().taken("demo");
        final String tip = commit("michael", FILE + "# a later change\n");

        taking().taken("demo");

        assertThat(ProjectSource.resolve(context, "demo").commit()).isEqualTo(tip);
        assertThat(ProjectSource.resolve(context, "demo").file()).content().contains("later");
    }

    @Test
    void a_file_signed_by_nobody_it_may_be_is_refused_and_nothing_is_placed() throws Exception {
        commit("alice", FILE + "# nobody may\n");

        refused(65, "refused-state");

        assertThat(ProjectSource.resolve(context, "demo").outcome())
                .isNotEqualTo(ProjectSource.Outcome.ORGANIZATION);
    }

    @Test
    void the_repository_out_of_reach_starts_nothing_though_a_clone_is_here() throws Exception {
        taking().taken("demo");
        Files.move(repository, dir.resolve("away"));

        refused(69, "cannot-say");
    }

    @Test
    void a_stale_organization_reading_decides_nothing_with_the_repository_in_reach()
            throws Exception {
        taking().taken("demo");
        organization.outOfReach();

        refused(69, "cannot-say");
    }

    @Test
    void a_stale_reading_that_names_no_such_project_leaves_a_local_project_alone()
            throws Exception {
        taking().taken("demo");
        organization.outOfReach();

        taking().taken("mine");

        assertThat(ProjectSource.resolve(context, "mine").outcome())
                .isEqualTo(ProjectSource.Outcome.UNKNOWN);
    }

    @Test
    void a_project_with_no_address_is_not_opened() throws Exception {
        organization
                .writes()
                .addProject(
                        organization.change("michael", "michael", "add-other"), "other", "michael");

        assertThatThrownBy(() -> taking().taken("other"))
                .isInstanceOfSatisfying(
                        ProjectTaking.Refused.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo(66);
                            assertThat(ex.reason()).isEqualTo("not-opened");
                        });
    }

    @Test
    void a_project_the_organization_no_longer_names_is_this_machines_no_more() throws Exception {
        taking().taken("demo");
        organization
                .writes()
                .removeProject(organization.change("michael", "michael", "drop-demo"), "demo");

        taking().taken("demo");

        assertThat(ProjectSource.resolve(context, "demo").outcome())
                .isEqualTo(ProjectSource.Outcome.UNKNOWN);
    }

    @Test
    void the_organization_wins_over_a_local_file_of_the_same_name() throws Exception {
        final Path written = context.paths().projects().defaultProjectFile("demo");
        Files.createDirectories(written.getParent());
        Files.writeString(written, "project:\n  name: \"demo\"\n");

        taking().taken("demo");

        assertThat(ProjectSource.resolve(context, "demo").outcome())
                .isEqualTo(ProjectSource.Outcome.ORGANIZATION);
    }

    @Test
    void on_a_host_its_file_gives_the_keys_and_the_forge_without_the_organization()
            throws Exception {
        hostFile("unix:/run/porelo-matrix/client.sock", "#demo:matrix.example.org");
        organization.outOfReach();

        taking().taken("demo");

        final OrganizationProjects.Placed placed =
                new OrganizationProjects(
                                context.paths().projects().organizationProjects(), hostFiles)
                        .find("demo");
        assertThat(placed.homeserver()).isEqualTo("unix:/run/porelo-matrix/client.sock");
        assertThat(placed.address()).isEqualTo("git@forge.example.org:acme/demo.git");
    }

    @Test
    void a_room_with_no_homeserver_from_either_place_starts_nothing() throws Exception {
        commit(
                "michael",
                FILE
                        + "mail:\n  peers:\n    people: {address: \"matrix:\", trust: \"external\"}\n");

        refused(78, "no-homeserver");
    }

    @Test
    void a_homeserver_in_the_project_file_wins_over_the_hosts() throws Exception {
        commit(
                "michael",
                FILE
                        + "mail:\n  transports:\n    matrix: {homeserver: \"https://own.example.org\"}\n");
        taking().taken("demo");

        assertThat(ProjectMembers.settings(context, GateSupport.byName(context, "demo"), "matrix"))
                .containsEntry("homeserver", "https://own.example.org")
                .doesNotContainKey("room");
    }

    /** Red until the store answers only a trusted card's key; the fix is at the source. */
    private void aliceAdministersAndSigns() throws Exception {
        machine.forge.keys.put("alice", new ArrayList<>(List.of(machine.key("alice"))));
        organization.filed("alice");
        organization.git("config", "user.name", "Organization");
        organization
                .writes()
                .changeProjectAdmin(
                        organization.change("michael", "michael", "admin-alice"), "demo", "alice");
        commit("alice", FILE + "# alice signs\n");
    }

    @Test
    void a_trusted_cards_key_judges_a_file() throws Exception {
        aliceAdministersAndSigns();

        taking().taken("demo");

        assertThat(ProjectSource.resolve(context, "demo").file()).content().contains("alice signs");
    }

    @Test
    void only_a_trusted_cards_key_judges_a_file() throws Exception {
        aliceAdministersAndSigns();
        // The identity source says nothing of this login: the card is first seen, not refused.
        machine.forge.keys.remove("alice");

        refused(65, "refused-state");
    }

    @Test
    void a_start_on_a_host_fetches_through_its_gate_with_the_persons_token() throws Exception {
        hostFile("", "", gate(SECRET));
        helperGivesToken(SECRET);
        helperGivesToken(SECRET);

        taking().taken("demo");

        assertThat(ProjectSource.resolve(context, "demo").outcome())
                .isEqualTo(ProjectSource.Outcome.ORGANIZATION);
        assertThat(asked)
                .as("through sudo, for each fetch")
                .containsOnly(
                        List.of("sudo", "-n", "/usr/local/sbin/porelo-host-person", "forge-token"));
        assertThat(
                        Files.readString(
                                context.paths()
                                        .projects()
                                        .organizationClone("demo")
                                        .resolve(".git/config")))
                .as("what the clone keeps")
                .doesNotContain(SECRET);
    }

    @Test
    void a_fetch_the_gate_refused_is_tried_once_more_with_a_fresh_token() throws Exception {
        hostFile("", "", gate(SECRET));
        helperGivesToken("taken-back-by-a-push");
        helperGivesToken(SECRET);
        helperGivesToken(SECRET);

        taking().taken("demo");

        assertThat(ProjectSource.resolve(context, "demo").outcome())
                .isEqualTo(ProjectSource.Outcome.ORGANIZATION);
    }

    @Test
    void a_gate_that_goes_on_refusing_is_cannot_say_after_one_more_and_names_no_token()
            throws Exception {
        hostFile("", "", gate("another"));
        helperGivesToken(SECRET);
        helperGivesToken(SECRET);

        refusedHoldingNoToken(69, "cannot-say");

        assertThat(asked).as("one more, not more").hasSize(2);
    }

    @Test
    void a_locked_person_is_refused_and_a_helper_that_cannot_say_is_cannot_say() throws Exception {
        hostFile("", "", gate(SECRET));
        helperFails(65);
        refusedHoldingNoToken(65, "refused-state");
        helperFails(69);
        refusedHoldingNoToken(69, "cannot-say");
        helperFails(1);
        refusedHoldingNoToken(69, "cannot-say");

        assertThat(asked).as("a refusal is not tried again").hasSize(3);
    }

    @Test
    void a_gate_address_the_host_file_does_not_name_as_fetch_from_never_asks() throws Exception {
        final String gate = gate(SECRET);
        hostFile("", "", "file://" + repository);
        Files.writeString(
                hostFiles.resolve("demo.json"),
                Files.readString(hostFiles.resolve("demo.json"))
                        .replace("git@forge.example.org:acme/demo.git", gate));

        taking().taken("demo");

        assertThat(asked).isEmpty();
    }

    @Test
    void a_host_file_root_took_the_read_off_reads_as_no_host_file() throws Exception {
        hostFile("unix:/run/porelo-matrix/client.sock", "#demo:matrix.example.org");
        final Path file = hostFiles.resolve("demo.json");
        Files.setPosixFilePermissions(file, Set.of());
        assumeThat(Files.isReadable(file)).as("nobody here reads past the mode").isFalse();

        taking().taken("demo");

        assertThat(
                        new OrganizationProjects(
                                        context.paths().projects().organizationProjects(),
                                        hostFiles)
                                .find("demo")
                                .address())
                .as("what the organization answered, not the host")
                .isEqualTo(ADDRESS);
    }
}
