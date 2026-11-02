package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The organization's reading commands, on a real signed history and throwaway keys. Harm guarded:
 * the interface reads nothing else about the organization, so a wrong field, a stale state passed
 * off as current or a project shown to a stranger is what its person then sees and acts on.
 */
class OrgReadingCommandsTest {

    private static final String ORGANIZATION_YML =
            "adapter: git\nsettings:\n  repository: git@github.com:acme/organization.git\n";

    private static final String LOCAL_PROJECT =
            """
            project:
              name: "mine"
              description: "This machine's"
              security_class: "online"
              upstream: "git@github.com:example/mine.git"

            image:
              base_image: "ubuntu:24.04"
            """;

    private static final String MATRIX =
            """

            mail:
              transports:
                matrix:
                  homeserver: "%s"
            """;

    @TempDir private Path dir;

    private Path repo = Path.of("");

    private String address = "";

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final Map<String, Path> keyFiles = new HashMap<>();

    private final Map<String, SigningKey> keys = new HashMap<>();

    private final Forge forge = new Forge();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    /** Answers what a test sets, and "could not be asked" otherwise. */
    private static final class Forge implements IdentitySource {
        final Map<String, List<String>> keys = new HashMap<>();

        @Override
        public Optional<Keys> keys(final String login) {
            return Optional.ofNullable(keys.get(login)).map(listed -> new Keys(listed, true));
        }

        @Override
        public Optional<Boolean> owns(final String organisation, final String login) {
            return Optional.of("mia".equals(login));
        }
    }

    @BeforeEach
    void organization() throws Exception {
        repo = Files.createDirectory(dir.resolve("organization"));
        address = repo.toString();
        for (final String login : List.of("mia", "alice", "bob", "carol", "stranger")) {
            final Path file = dir.resolve(login);
            runner.runOrFail(
                    Command.of(
                            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", file.toString()));
            keyFiles.put(login, file);
            keys.put(
                    login,
                    new SigningKey(
                            Base64.getDecoder()
                                    .decode(OpenSshPrivateKey.seedBase64(Files.readString(file))),
                            "test"));
        }
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Organization");
        git("config", "user.email", "organization@example.org");
        git("config", "gpg.format", "ssh");
        forge.keys.put("mia", List.of(key("mia")));
        write("owners.yml", "owners:\n  - login: mia\n    key: \"" + key("mia") + "\"\n");
        commit("mia");
        person("alice");
        person("bob");
        person("carol");
        write("projects/demo/card", "name: demo\nadmin: alice\n");
        commit("mia");
        write(
                "projects/demo/members.yml",
                "members:\n  - login: alice\n    role: admin\n  - login: bob\n    role: developer\n"
                        + "  - login: carol\n    role: developer\n    locked: true\n");
        commit("alice");
    }

    private String git(final String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return runner.runOrFail(
                        Command.of(all)
                                .in(repo)
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }

    private void write(final String path, final String text) throws IOException {
        final Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private String commit(final String signer) {
        git("add", "-A");
        git(
                "-c",
                "user.signingkey=" + keyFiles.get(signer),
                "commit",
                "-q",
                "--allow-empty",
                "-S",
                "-m",
                "change");
        return git("rev-parse", "HEAD");
    }

    private String key(final String login) {
        return PersonCard.keyLine(keys.get(login));
    }

    private String card(final String login) {
        return PersonCard.write(
                login,
                login,
                "",
                keys.get(login),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 1, 1),
                null);
    }

    private void person(final String login) throws IOException {
        forge.keys.put(login, List.of(key(login)));
        write("people/" + login + ".card", card(login));
        commit(login);
    }

    private void host() throws IOException {
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: []\n");
        commit("mia");
        write("hosts/build/host.yml", "admins:\n  - login: alice\nprojects: [demo]\n");
        commit("alice");
        write(
                "hosts/build/card",
                HostCard.write(
                        "build",
                        "build.example.org",
                        22,
                        key("carol"),
                        3000,
                        2222,
                        8448,
                        keys.get("alice")));
        commit("alice");
    }

    private void iAm(final String login) throws IOException {
        final Path card = dir.resolve("config/porelo/identity/card");
        Files.createDirectories(card.getParent());
        Files.writeString(card, card(login));
    }

    private void organizationYml(final String text) throws IOException {
        final Path file = dir.resolve("config/porelo/organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private ProjectFile.Read projectFile =
            new ProjectFile.Read(ProjectFile.CANNOT_SAY, null, null, null);

    private void wire(final CommandLine line) {
        if (line.getCommand() instanceof OrgReading reading) {
            reading.opener =
                    (machine, ignored) ->
                            new OrgAccess.Opened(
                                    GitTreeStore.at(
                                            runner,
                                            address,
                                            "acme",
                                            "github.com",
                                            FetchAccess.NONE,
                                            machine.paths().xdg().state().resolve("organization")),
                                    forge);
        }
        if (line.getCommand() instanceof ProjectShowCommand show) {
            show.files = (machine, at, keys) -> projectFile;
        }
        line.getSubcommands().values().forEach(this::wire);
    }

    private int run(final String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine line = PoreloCli.commandLine(context());
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        wire(line);
        return line.execute(args);
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    private static @Nullable Object field(final Object map, final String name) {
        return ((Map<?, ?>) map).get(name);
    }

    @Test
    void theOrganizationIsAnsweredInTheFieldsTheInterfaceReads() throws Exception {
        organizationYml(ORGANIZATION_YML);
        host();
        iAm("alice");
        final String tip = git("rev-parse", "HEAD");

        final int exit = run("org", "--json");

        assertThat(exit).as("what it said: %s", err).isZero();
        final Map<?, ?> answer = answer();
        assertThat(answer.get("revision")).as("the --base of what follows").isEqualTo(tip);
        assertThat(answer.get("stale")).as("it reached the store").isEqualTo(false);
        assertThat(String.valueOf(answer.get("address")))
                .as("no user part")
                .isEqualTo("github.com:acme/organization.git");
        assertThat(answer.get("forge")).as("for known_hosts").isEqualTo("github.com");
        assertThat(answer.get("settings"))
                .as("handed on unparsed to a host")
                .isEqualTo(ORGANIZATION_YML);
        assertThat(field(answer.get("me"), "login")).isEqualTo("alice");
        assertThat(field(answer.get("me"), "card")).isEqualTo("trusted");
        assertThat(field(answer.get("me"), "admin_of_hosts")).isEqualTo(List.of("build"));
        assertThat(field(answer.get("me"), "projects"))
                .isEqualTo(List.of(Map.of("project", "demo", "role", "admin")));
        final Object demo = ((List<?>) answer.get("projects")).getFirst();
        assertThat(field(demo, "first_admin")).isEqualTo("alice");
        assertThat(field(demo, "opened")).as("no address filed").isEqualTo(false);
        assertThat(field(demo, "host")).isEqualTo("build");
        final Object build = ((List<?>) answer.get("hosts")).getFirst();
        assertThat(field(field(build, "services"), "forge"))
                .isEqualTo(Map.of("web_port", 3000.0, "git_port", 2222.0));
        assertThat(((List<?>) field(build, "admins")))
                .as("the pin of a first up holds login and key")
                .singleElement()
                .satisfies(row -> assertThat(field(row, "key")).isEqualTo(key("alice")));
    }

    @Test
    void aServiceTakenOffAHostsCardIsNullNotAPortNobodyListensOn() throws Exception {
        organizationYml(ORGANIZATION_YML);
        host();
        write(
                "hosts/build/card",
                HostCard.writeOffered(
                        "build",
                        "build.example.org",
                        22,
                        key("carol"),
                        HostCard.NO_SERVICE,
                        HostCard.NO_SERVICE,
                        8448,
                        keys.get("alice")));
        commit("alice");

        assertThat(run("org", "--json")).as("what it said: %s", err).isZero();

        final Object services = field(((List<?>) answer().get("hosts")).getFirst(), "services");
        assertThat(((Map<?, ?>) services).containsKey("forge")).as("null, never left out").isTrue();
        assertThat(field(services, "forge")).isNull();
        assertThat(field(services, "matrix")).isEqualTo(Map.of("port", 8448.0));
    }

    @Test
    void whatTheForgeSaysNowIsShownBesideTheCardAndCannotSayIsNeverYes() throws Exception {
        organizationYml(ORGANIZATION_YML);
        run("org", "--json");
        assertThat(vouched("alice")).isEqualTo("yes");

        forge.keys.put("alice", List.of(key("stranger")));
        run("org", "--json");
        assertThat(vouched("alice")).as("the key left the forge").isEqualTo("no");

        forge.keys.remove("alice");
        run("org", "--json");
        assertThat(vouched("alice")).as("the forge did not answer").isEqualTo("cannot-say");
    }

    private @Nullable Object vouched(final String login) {
        return ((List<?>) answer().get("people"))
                .stream()
                        .filter(person -> login.equals(field(person, "login")))
                        .map(person -> field(person, "vouched"))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void aPersonWithNoCardIsAnsweredWithNoMe() throws Exception {
        organizationYml(ORGANIZATION_YML);

        assertThat(run("org", "--json")).isZero();

        assertThat(answer().containsKey("me")).as("null, never left out").isTrue();
        assertThat(answer().get("me")).as("the interface shows Add me").isNull();
    }

    @Test
    void aStoreThatCannotBeReachedAnswersItsLastStateMarkedStale() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("alice");
        assertThat(run("org", "--json")).isZero();
        final Object revision = answer().get("revision");

        Files.move(repo, dir.resolve("gone"));
        final int exit = run("org", "--json");

        assertThat(exit).as("what it said: %s", err).isZero();
        assertThat(answer().get("stale")).as("the interface says so").isEqualTo(true);
        assertThat(answer().get("revision")).isEqualTo(revision);
        assertThat(vouched("alice")).as("nobody was asked").isEqualTo("cannot-say");
    }

    @Test
    void aStoreThatCannotBeReachedWithNothingKeptCannotSay() throws Exception {
        organizationYml(ORGANIZATION_YML);
        address = dir.resolve("gone").toString();

        assertThat(run("org", "--json")).isEqualTo(69);

        assertThat(answer().get("reason")).as("never read as no").isEqualTo("cannot-say");
        assertThat(err.toString()).startsWith("porelo: ");
    }

    @Test
    void aTipThatDoesNotContinueWhatWasTrustedIsRefusedNotShown() throws Exception {
        organizationYml(ORGANIZATION_YML);
        assertThat(run("org", "--json")).isZero();

        git("reset", "-q", "--hard", "HEAD~1");

        assertThat(run("org", "--json")).as("an older state served again").isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("refused-state");
    }

    @Test
    void aMachineWithNoOrganizationIsToldToSetOneUp() {
        assertThat(run("org", "--json")).isEqualTo(78);

        assertThat(answer().get("reason")).isEqualTo("no-organization");
        assertThat(answer().containsKey("revision")).isTrue();
    }

    @Test
    void settingsThatDoNotReadAreRefusedWithoutQuotingThem() throws Exception {
        organizationYml("adapter: [ git@secret-line\n");

        assertThat(run("org", "--json")).isEqualTo(64);

        assertThat(out + err.toString())
                .as("the parser quotes the line")
                .doesNotContain("secret-line");
    }

    @Test
    void anAdapterThisPoreloDoesNotHaveIsRefused() throws Exception {
        organizationYml("adapter: other\nsettings:\n  repository: x\n");

        assertThat(run("org", "--json")).isEqualTo(64);

        assertThat(answer().get("reason")).isEqualTo("usage");
    }

    @Test
    void aCardIsAnsweredWholeAsFiledWithItsState() throws Exception {
        organizationYml(ORGANIZATION_YML);

        assertThat(run("org", "card", "bob", "--json")).isZero();

        assertThat(answer().get("card"))
                .as("a reader checks its signature, so its lines must survive")
                .isEqualTo(card("bob"));
        assertThat(answer().get("state")).isEqualTo("trusted");
        assertThat(answer().get("login")).isEqualTo("bob");
    }

    @Test
    void aCardNobodyFiledIsNotFound() throws Exception {
        organizationYml(ORGANIZATION_YML);

        assertThat(run("org", "card", "nobody", "--json")).isEqualTo(66);

        assertThat(answer().get("reason")).isEqualTo("not-found");
        assertThat(answer().get("revision")).isEqualTo(git("rev-parse", "HEAD"));
    }

    @Test
    void aHostCardIsAnsweredWholeAsFiled() throws Exception {
        organizationYml(ORGANIZATION_YML);
        host();

        assertThat(run("org", "host-card", "build", "--json")).isZero();

        assertThat(answer().get("host")).isEqualTo("build");
        assertThat(String.valueOf(answer().get("card"))).contains("build.example.org");
        assertThat(run("org", "host-card", "other", "--json")).isEqualTo(66);
    }

    @Test
    void theProjectsAreThisPersonsThenThisMachinesThenDefault() throws Exception {
        organizationYml(ORGANIZATION_YML);
        host();
        write("projects/hidden/card", "name: hidden\nadmin: bob\n");
        commit("mia");
        iAm("carol");
        final Path file = dir.resolve("mine.yml");
        Files.writeString(file, LOCAL_PROJECT);
        new ProjectRegistry(context().paths().projects().projectRegistry()).remember("mine", file);

        assertThat(run("org", "projects", "--json")).as("what it said: %s", err).isZero();

        final List<?> rows = (List<?>) answer().get("projects");
        assertThat(rows.stream().map(row -> field(row, "project")))
                .as("no project this person is not in")
                .containsExactly("demo", "mine", "default");
        assertThat(field(rows.getFirst(), "locked")).as("listed, and says so").isEqualTo(true);
        assertThat(field(rows.getFirst(), "role")).isEqualTo("developer");
        assertThat(field(rows.getFirst(), "host")).isEqualTo("build");
        assertThat(field(rows.get(1), "source")).isEqualTo("local");
        assertThat(field(rows.get(2), "source")).isEqualTo("built-in");
    }

    @Test
    void aProjectBothOfTheOrganizationAndThisMachineIsListedOnceAndDefaultOnce() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("alice");
        final Path file = dir.resolve("mine.yml");
        Files.writeString(file, LOCAL_PROJECT);
        final ProjectRegistry registry =
                new ProjectRegistry(context().paths().projects().projectRegistry());
        registry.remember("demo", file);
        registry.remember("default", file);

        assertThat(run("org", "projects", "--json")).as("what it said: %s", err).isZero();

        assertThat(((List<?>) answer().get("projects")).stream().map(row -> field(row, "project")))
                .containsExactly("demo", "default");
    }

    @Test
    void aRevokedKeyIsSaidOfMyOwnCard() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("carol");
        forge.keys.put("carol", List.of(key("alice")));
        write(
                "people/carol.card",
                PersonCard.write(
                        "carol",
                        "carol",
                        "",
                        keys.get("alice"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        null));
        commit("alice");
        forge.keys.put("alice", List.of(key("stranger")));
        write(
                "people/alice.card",
                PersonCard.write(
                        "alice",
                        "alice",
                        "",
                        keys.get("stranger"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        null));
        commit("mia");

        assertThat(run("org", "--json")).as("what it said: %s", err).isZero();

        assertThat(field(answer().get("me"), "card")).isEqualTo("revoked");
    }

    @Test
    void theRegistrationEntryIsNamedByThePlainHttpsServerAndOtherwiseNone() throws Exception {
        final Path file = dir.resolve("mine.yml");
        Files.writeString(file, LOCAL_PROJECT + MATRIX.formatted("https://Matrix.Example.org"));
        new ProjectRegistry(context().paths().projects().projectRegistry()).remember("mine", file);
        assertThat(run("project", "show", "mine", "--json")).as("what it said: %s", err).isZero();
        assertThat(answer().get("matrix_registration_entry"))
                .as("the name the lending looks up")
                .isEqualTo("matrix-registration-token.matrix.example.org");

        Files.writeString(file, LOCAL_PROJECT);
        assertThat(run("project", "show", "mine", "--json")).isZero();
        assertThat(answer().containsKey("matrix_registration_entry")).isTrue();
        assertThat(answer().get("matrix_registration_entry")).as("no homeserver").isNull();
    }

    @Test
    void anAddressNotPlainHttpsLendsNothingThere() {
        assertThat(LentEntry.name("t", "http://matrix.example.org")).isNull();
        assertThat(LentEntry.name("t", "https://user@matrix.example.org")).isNull();
        assertThat(LentEntry.name("t", 42)).isNull();
        assertThat(LentEntry.name("t", null)).isNull();
        assertThat(LentEntry.name("t", "https://matrix.example.org"))
                .isEqualTo("t.matrix.example.org");
    }

    private void declareToken(final String match, final Path token) throws IOException {
        declare(match, "token", token);
    }

    private void declare(final String match, final String kind, final Path token)
            throws IOException {
        final Path declared = context().paths().vault().credentialRegistry();
        Files.createDirectories(declared.getParent());
        Files.writeString(
                declared,
                "credentials:\n  - match: "
                        + match
                        + "\n    kind: "
                        + kind
                        + "\n    source: file\n    file: "
                        + token
                        + "\n");
    }

    @Test
    void theForgeIsAskedWithTheTokenDeclaredForTheOrganizationAndNoOther() throws Exception {
        final org.fuin.porelo.organization.git.RegistryOrigin origin =
                org.fuin.porelo.organization.git.RegistryOrigin.of(
                        "git@forge.example.org:acme/organization.git");
        assertThat(OrgForgeToken.of(context(), origin)).as("none declared or held").isNull();

        final Path token = dir.resolve("installation-token");
        Files.writeString(token, "tok-installation\n");
        declareToken("https://forge.example.org/acme/", token);
        assertThat(OrgForgeToken.of(context(), origin))
                .as("found however the repository is cloned")
                .isEqualTo("tok-installation");

        declareToken("https://elsewhere.example.org/acme/", token);
        assertThat(OrgForgeToken.of(context(), origin))
                .as("another forge's token is not sent here")
                .isNull();

        declareToken("https://forge.example.org/acme/", token);
        assertThat(
                        OrgForgeToken.of(
                                context(),
                                org.fuin.porelo.organization.git.RegistryOrigin.of(
                                        "ssh://git@forge.example.org:2222/acme/organization.git")))
                .as("an address with a port is another server")
                .isNull();

        declare("https://forge.example.org/acme/", "ssh-key", token);
        assertThat(OrgForgeToken.of(context(), origin))
                .as("what is declared as something else is no token to send")
                .isNull();

        declareToken("https://forge.example.org/acme/", token);
        Files.writeString(token, "  \n");
        assertThat(OrgForgeToken.of(context(), origin)).as("a blank file holds none").isNull();
    }

    @Test
    void theProjectsOfAMachineWithNoOrganizationAreStillListed() {
        assertThat(run("org", "projects", "--json")).as("what it said: %s", err).isZero();

        assertThat(answer().get("revision")).isNull();
        assertThat(((List<?>) answer().get("projects")).stream().map(row -> field(row, "project")))
                .containsExactly("default");
    }

    @Test
    void aMemberSeesAProjectWithItsRepositoriesSaidAsUnread() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("carol");

        assertThat(run("project", "show", "demo", "--json")).as("what it said: %s", err).isZero();

        assertThat(answer().get("first_admin")).isEqualTo("alice");
        assertThat(answer().get("me")).isEqualTo(Map.of("role", "developer", "locked", true));
        assertThat(((List<?>) answer().get("members"))).hasSize(3);
        assertThat(field(answer().get("repositories"), "state"))
                .as("not opened, so never an empty list read")
                .isEqualTo("not-opened");
        assertThat(field(answer().get("repositories"), "revision")).isNull();
    }

    @Test
    void anOpenedProjectWhoseFileIsNotReadCannotSayRatherThanSayEmpty() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("alice");
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");

        assertThat(run("project", "show", "demo", "--json")).as("what it said: %s", err).isZero();

        assertThat(answer().get("opened")).isEqualTo(true);
        assertThat(answer().get("address")).isEqualTo("git@github.com:acme/demo.git");
        assertThat(field(answer().get("repositories"), "state")).isEqualTo("cannot-say");
    }

    @Test
    void aStrangerIsNotShownAProject() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("stranger");

        assertThat(run("project", "show", "demo", "--json")).isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("not-allowed");
        assertThat(out.toString()).as("no member's name").doesNotContain("carol");
    }

    @Test
    void aProjectNobodyKnowsIsNotFound() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("alice");

        assertThat(run("project", "show", "nothing", "--json")).isEqualTo(66);

        assertThat(answer().get("reason")).isEqualTo("not-found");
    }

    @Test
    void aProjectOfThisMachineAloneAnswersWhatItHas() throws Exception {
        final Path file = dir.resolve("mine.yml");
        Files.writeString(file, LOCAL_PROJECT);
        new ProjectRegistry(context().paths().projects().projectRegistry()).remember("mine", file);

        assertThat(run("project", "show", "mine", "--json")).as("what it said: %s", err).isZero();

        assertThat(answer().get("revision")).isNull();
        assertThat(answer().containsKey("me")).isTrue();
        assertThat(answer().get("members")).isEqualTo(List.of());
        assertThat(field(answer().get("repositories"), "state")).isEqualTo("read");
        assertThat(field(answer().get("repositories"), "names"))
                .as("what its file says")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "name",
                                        "mine",
                                        "upstream",
                                        "git@github.com:example/mine.git")));
    }

    @Test
    void whatAFileHoldsCannotMoveATerminalsCursorButACardKeepsItsLines() {
        final Object safe = OrgOutput.safe(Map.of("people", List.of("Mal\u001b[2Jlory\nsecond")));

        assertThat(Json.write(safe))
                .as("a reader prints what it is given")
                .doesNotContain("u001b")
                .contains("lory\\nsecond");
    }

    private int setup(final String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine line = PoreloCli.commandLine(context());
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        final CommandLine setup = line.getSubcommands().get("setup");
        ((OrgSetup) setup.getMixins().get("organization")).opener =
                (machine, ignored) ->
                        new OrgAccess.Opened(
                                GitTreeStore.at(
                                        runner,
                                        address,
                                        "acme",
                                        "github.com",
                                        FetchAccess.NONE,
                                        machine.paths().xdg().state().resolve("organization")),
                                forge);
        return line.execute(args);
    }

    private static final String NAMED = "git@github.com:acme/organization.git";

    private Path settings() {
        return dir.resolve("config/porelo/organization.yml");
    }

    @Test
    void setupWritesTheSettingsOnceTheOrganizationAnswersAndOrgThenReadsThem() throws Exception {
        assertThat(settings()).doesNotExist();

        assertThat(setup("setup", "--organization", NAMED, "--json"))
                .as("what it said: %s", err)
                .isZero();

        assertThat(answer().get("address"))
                .as("as org answers it, with nothing of who connects")
                .isEqualTo("github.com:acme/organization.git");
        assertThat(answer().get("name")).isEqualTo("acme");
        assertThat(OrgAccess.repository(context())).contains(NAMED);
        assertThat(run("org", "--json")).as("what it said: %s", err).isZero();
    }

    @Test
    void setupOfAnOrganizationThatDoesNotAnswerWritesNothing() throws Exception {
        address = dir.resolve("gone").toString();

        assertThat(setup("setup", "--organization", NAMED, "--json"))
                .as("a machine pointed at nothing fails later, where it is harder to see")
                .isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(settings()).doesNotExist();
    }

    @Test
    void aSecondOrganizationTakesReplaceAndTheSameOneIsAskedAgainFreely() throws Exception {
        organizationYml(ORGANIZATION_YML);
        final String other = "git@github.com:other/organization.git";

        assertThat(setup("setup", "--organization", other, "--json")).isEqualTo(65);
        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(OrgAccess.repository(context()))
                .as("the machine has one organization")
                .isNotEqualTo(java.util.Optional.of(other));

        assertThat(setup("setup", "--organization", other, "--replace", "--json")).isZero();
        assertThat(OrgAccess.repository(context())).contains(other);
        assertThat(setup("setup", "--organization", other, "--json")).isZero();
    }

    private void opened() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("alice");
        write("projects/demo/repository.yml", "address: git@github.com:acme/demo.git\n");
        commit("alice");
    }

    @Test
    void anOpenedProjectAnswersItsFilesRepositoriesAtItsOwnRevision() throws Exception {
        opened();
        projectFile =
                new ProjectFile.Read(
                        ProjectFile.READ,
                        "b".repeat(40),
                        org.fuin.porelo.core.project.ProjectReader.read(
                                new java.io.StringReader(
                                        LOCAL_PROJECT.replace("mine", "demo")
                                                + "\nrepositories:\n  app:\n    upstream:"
                                                + " git@github.com:acme/app.git\n"),
                                "test"),
                        null);

        assertThat(run("project", "show", "demo", "--json")).as("what it said: %s", err).isZero();

        final Object repositories = answer().get("repositories");
        assertThat(field(repositories, "state")).isEqualTo("read");
        assertThat(field(repositories, "revision"))
                .as("the --base of project repository, and not the organization's")
                .isEqualTo("b".repeat(40));
        assertThat(answer().get("revision")).isNotEqualTo("b".repeat(40));
        assertThat(((List<?>) field(repositories, "names"))).hasSize(1);
    }

    @Test
    void aRefusedProjectFileIsSaidWithWhyAndNamesNothing() throws Exception {
        opened();
        projectFile = new ProjectFile.Read(ProjectFile.REFUSED, null, null, "signed by nobody");

        assertThat(run("project", "show", "demo", "--json")).as("what it said: %s", err).isZero();

        final Object repositories = answer().get("repositories");
        assertThat(field(repositories, "state")).isEqualTo("refused");
        assertThat(field(repositories, "why")).isEqualTo("signed by nobody");
        assertThat(((List<?>) field(repositories, "names"))).isEmpty();
        assertThat(answer().get("matrix_registration_entry")).isNull();
    }

    @Test
    void onlyAnUnlockedAdminOfAnOpenedProjectMayChangeItsFile() throws Exception {
        opened();
        final org.fuin.porelo.organization.OrganizationQuestions state =
                OrgAccess.read(
                                context(),
                                (machine, ignored) ->
                                        new OrgAccess.Opened(
                                                GitTreeStore.at(
                                                        runner,
                                                        address,
                                                        "acme",
                                                        "github.com",
                                                        FetchAccess.NONE,
                                                        machine.paths()
                                                                .xdg()
                                                                .state()
                                                                .resolve("organization")),
                                                forge))
                        .answers();

        assertThat(ProjectRepositoryCommand.place(state, "demo", "alice").address())
                .isEqualTo("git@github.com:acme/demo.git");
        assertThatThrownBy(() -> ProjectRepositoryCommand.place(state, "demo", "bob"))
                .as("a developer may not")
                .isInstanceOfSatisfying(
                        Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Refused.NOT_ALLOWED));
        assertThatThrownBy(() -> ProjectRepositoryCommand.place(state, "demo", "stranger"))
                .isInstanceOfSatisfying(
                        Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Refused.NOT_ALLOWED));
        assertThatThrownBy(() -> ProjectRepositoryCommand.place(state, "demo", "carol"))
                .as("carol is the fixture's locked member")
                .isInstanceOfSatisfying(
                        Refused.class, ex -> assertThat(ex.reason()).isEqualTo(Refused.LOCKED));
        assertThatThrownBy(() -> ProjectRepositoryCommand.place(state, "none", "alice"))
                .isInstanceOfSatisfying(
                        Refused.class, ex -> assertThat(ex.reason()).isEqualTo(Refused.NOT_FOUND));
    }

    private org.fuin.porelo.organization.OrganizationQuestions read() throws Exception {
        return OrgAccess.read(
                        context(),
                        (machine, ignored) ->
                                new OrgAccess.Opened(
                                        GitTreeStore.at(
                                                runner,
                                                address,
                                                "acme",
                                                "github.com",
                                                FetchAccess.NONE,
                                                machine.paths()
                                                        .xdg()
                                                        .state()
                                                        .resolve("organization")),
                                        forge))
                .answers();
    }

    @Test
    void aProjectNotOpenedHasNoFileToChange() throws Exception {
        organizationYml(ORGANIZATION_YML);
        iAm("alice");

        final org.fuin.porelo.organization.OrganizationQuestions state = read();

        assertThatThrownBy(() -> ProjectRepositoryCommand.place(state, "demo", "alice"))
                .isInstanceOfSatisfying(
                        Refused.class, ex -> assertThat(ex.reason()).isEqualTo(Refused.NOT_OPENED));
    }

    @Test
    void whoMayChangeAProjectsFileIsNotDecidedByAStaleOrganization() throws Exception {
        opened();
        assertThat(run("org", "--json")).as("what it said: %s", err).isZero();
        Files.move(repo, dir.resolve("moved-away"));
        final CommandLine line = PoreloCli.commandLine(context());
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        wire(line);
        final CommandLine add =
                line.getSubcommands()
                        .get("project")
                        .getSubcommands()
                        .get("repository")
                        .getSubcommands()
                        .get("add");
        ((OrgWriting) add.getCommand()).session =
                machine ->
                        new OrgWriting.Session(
                                (org.fuin.porelo.organization.OrganizationWrites)
                                        java.lang.reflect.Proxy.newProxyInstance(
                                                getClass().getClassLoader(),
                                                new Class<?>[] {
                                                    org.fuin.porelo.organization.OrganizationWrites
                                                            .class
                                                },
                                                (proxy, method, arguments) -> {
                                                    throw new AssertionError(
                                                            "the organization is not written to");
                                                }),
                                new org.fuin.porelo.organization.OrganizationWrites.Acting(
                                        "alice", key("alice")),
                                keys.get("alice"),
                                new ProjectFile(
                                        runner,
                                        FetchAccess.NONE,
                                        org.fuin.porelo.organization.gittree.CommitSigning.agent(
                                                ""),
                                        dir.resolve("memory")));
        out.getBuffer().setLength(0);

        final int exit =
                line.execute(
                        "project",
                        "repository",
                        "add",
                        "demo",
                        "app",
                        "--upstream",
                        "git@github.com:acme/app.git",
                        "--json");

        assertThat(exit).as("what it said: %s", out).isEqualTo(69);
        assertThat(out.toString()).contains("who may");
    }

    private OrgAccess.Opener opening() {
        return (machine, ignored) ->
                new OrgAccess.Opened(
                        GitTreeStore.at(
                                runner,
                                address,
                                "acme",
                                "github.com",
                                FetchAccess.NONE,
                                machine.paths().xdg().state().resolve("organization")),
                        forge);
    }

    @Test
    void aWriteStandsOnTheRevisionReadNowAndAForgeGoneBackIsRefused() throws Exception {
        organizationYml(ORGANIZATION_YML);
        final String older = git("rev-parse", "HEAD");
        write("people/note.txt", "later");
        commit("alice");
        final String tip = git("rev-parse", "HEAD");

        assertThat(OrgWriting.current(context(), opening()))
                .as("without --base a write takes the revision read now")
                .isEqualTo(tip);

        git("reset", "-q", "--hard", older);

        assertThatThrownBy(() -> OrgWriting.current(context(), opening()))
                .as("a malicious forge must not get a change signed on an older state")
                .isInstanceOfSatisfying(
                        Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Refused.REFUSED_STATE));
    }

    @Test
    void aStaleOrganizationIsNoGroundForAWrite() throws Exception {
        organizationYml(ORGANIZATION_YML);
        OrgWriting.current(context(), opening());
        Files.move(repo, dir.resolve("moved-away"));

        assertThatThrownBy(() -> OrgWriting.current(context(), opening()))
                .isInstanceOfSatisfying(
                        Refused.class, ex -> assertThat(ex.reason()).isEqualTo(Refused.CANNOT_SAY));
    }

    @Test
    void aStaleReadingJudgesNoProjectFileThoughTheFileWouldRead() throws Exception {
        opened();
        projectFile =
                new ProjectFile.Read(
                        ProjectFile.READ,
                        "b".repeat(40),
                        org.fuin.porelo.core.project.ProjectReader.read(
                                new java.io.StringReader(LOCAL_PROJECT.replace("mine", "demo")),
                                "test"),
                        null);
        assertThat(run("project", "show", "demo", "--json")).as("what it said: %s", err).isZero();
        assertThat(field(answer().get("repositories"), "state")).isEqualTo("read");

        Files.move(repo, dir.resolve("gone"));

        assertThat(run("project", "show", "demo", "--json")).as("what it said: %s", err).isZero();
        assertThat(answer().get("stale")).isEqualTo(true);
        assertThat(field(answer().get("repositories"), "state"))
                .as("an admin removed since the keys were read could have signed it")
                .isEqualTo("cannot-say");
        assertThat(answer().get("matrix_registration_entry")).isNull();
    }

    @Test
    void aPersonReadingTextIsToldOfAStaleStateAndOfAFileNotRead() throws Exception {
        opened();
        projectFile = new ProjectFile.Read(ProjectFile.REFUSED, null, null, "signed by nobody");

        assertThat(run("project", "show", "demo")).as("what it said: %s", err).isZero();
        assertThat(out.toString())
                .as("a refused file printing as a good one would be believed")
                .contains("refused")
                .contains("signed by nobody")
                .doesNotContain("Stale");

        Files.move(repo, dir.resolve("gone"));

        assertThat(run("project", "show", "demo")).as("what it said: %s", err).isZero();
        assertThat(out.toString()).contains("Stale").contains("cannot-say");
        assertThat(run("org", "card", "alice")).as("what it said: %s", err).isZero();
        assertThat(out.toString()).contains("Stale");
    }

    @Test
    void aTokenDeclaredForAnHttpsAddressIsLentToGit() throws Exception {
        final Path token = Files.writeString(dir.resolve("token"), "hunter2");
        declareToken("https://forge.example.org/acme/", token);

        try (var lease =
                OrgAccess.lent(context()).lend("https://forge.example.org/acme/organization")) {
            assertThat(lease.arguments())
                    .as("a private repository could not be fetched or pushed to without it")
                    .isNotEmpty();
            assertThat(lease.environment().toString()).doesNotContain("hunter2");
        }
    }

    @Test
    void aSetupThatCannotWriteItsSettingsFailsAndSaysSo() throws Exception {
        Files.createDirectories(settings().resolve("in-the-way"));

        final int exit = setup("setup", "--organization", NAMED, "--json");

        assertThat(exit).as("what it said: %s", err).isEqualTo(70);
        assertThat(out.toString()).contains("\"reason\":\"failed\"");
        assertThat(err.toString())
                .as("what failed goes through the handler that keeps it in the failure log")
                .isNotBlank();
    }
}
