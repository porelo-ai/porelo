package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

/**
 * The commands that change the organization. Harm guarded: a command that makes another change than
 * it names, or that hides that a change may have been made.
 */
class OrgWritingCommandsTest {

    private static final String REVISION = "a".repeat(40);

    private static final SigningKey KEY = SigningKey.generate("mia");

    @TempDir private Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final List<String> calls = new ArrayList<>();

    private final List<OrganizationWrites.Change> changes = new ArrayList<>();

    private @Nullable RuntimeException refusing;

    private @Nullable IOException failing;

    private boolean again;

    private OrganizationWrites writes() {
        return (OrganizationWrites)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {OrganizationWrites.class},
                        (proxy, method, arguments) -> {
                            if (refusing != null) {
                                throw refusing;
                            }
                            if (failing != null) {
                                throw failing;
                            }
                            changes.add((OrganizationWrites.Change) arguments[0]);
                            calls.add(
                                    method.getName()
                                            + Arrays.asList(arguments)
                                                    .subList(1, arguments.length));
                            return new OrganizationWrites.Written(
                                    REVISION, changes.getLast().id(), again);
                        });
    }

    private PoreloContext context() {
        return contextWith(Map.of());
    }

    private PoreloContext contextWith(final Map<String, String> environment) {
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
                new ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0,
                environment::get);
    }

    private CommandLine line() {
        final CommandLine line = PoreloCli.commandLine(context());
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        return line;
    }

    private static void seam(final CommandLine line, final OrgWriting.Opener opener) {
        if (line.getCommand() instanceof OrgWriting writing) {
            writing.session = opener;
        }
        if (line.getCommand() instanceof ProjectAddressCommand address) {
            address.filed = (machine, project) -> !"new".equals(project);
        }
        if (line.getCommand() instanceof OrgHostCommand.Change change) {
            change.reader =
                    (machine, host) ->
                            "fresh".equals(host)
                                    ? null
                                    : new Organization.Card(
                                            "held.example.org",
                                            2022,
                                            PersonCard.keyLine(KEY),
                                            3000,
                                            2222,
                                            8448,
                                            "");
        }
        line.getSubcommands().values().forEach(each -> seam(each, opener));
    }

    private int run(final String... arguments) {
        final CommandLine line = line();
        seam(
                line,
                machine ->
                        new OrgWriting.Session(
                                writes(),
                                new OrganizationWrites.Acting("mia", PersonCard.keyLine(KEY)),
                                KEY));
        return line.execute(arguments);
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    static Stream<Arguments> everyCommand() {
        return Stream.of(
                Arguments.of("org owner add alice", "addOrganizationOwner[alice]"),
                Arguments.of("org owner remove alice", "removeOrganizationOwner[alice]"),
                Arguments.of("org person revoke alice", "revokeCard[alice]"),
                Arguments.of("org project add demo --admin alice", "addProject[demo, alice]"),
                Arguments.of("org project remove demo", "removeProject[demo]"),
                Arguments.of("org project admin demo alice", "changeProjectAdmin[demo, alice]"),
                Arguments.of("org host add build --admin alice", "addHost[build, alice]"),
                Arguments.of("org host remove build", "removeHost[build]"),
                Arguments.of("org host admin add build alice", "addHostAdmin[build, alice]"),
                Arguments.of("org host admin remove build alice", "removeHostAdmin[build, alice]"),
                Arguments.of("org host carry build demo", "addProjectToHost[build, demo]"),
                Arguments.of("org host uncarry build demo", "removeProjectFromHost[build, demo]"),
                Arguments.of(
                        "project member add demo alice --role developer",
                        "addProjectMember[demo, alice, developer]"),
                Arguments.of(
                        "project member remove demo alice", "removeProjectMember[demo, alice]"),
                Arguments.of(
                        "project member role demo alice admin",
                        "changeProjectMemberRole[demo, alice, admin]"),
                Arguments.of("project member lock demo alice", "lockProjectMember[demo, alice]"),
                Arguments.of(
                        "project member unlock demo alice", "unlockProjectMember[demo, alice]"),
                Arguments.of(
                        "project address demo ssh://git@forge.example/acme/demo",
                        "changeProjectRepository[demo, ssh://git@forge.example/acme/demo]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyCommand")
    void everyCommandMakesTheChangeItNames(final String command, final String made) {
        final List<String> all = new ArrayList<>(List.of(command.split(" ")));
        all.addAll(List.of("--json", "--id", "one", "--base", REVISION));

        assertThat(run(all.toArray(String[]::new))).as("what it said: %s", err).isZero();

        assertThat(calls).containsExactly(made);
        assertThat(changes.getFirst().base()).isEqualTo(REVISION);
        assertThat(changes.getFirst().id()).isEqualTo("one");
        assertThat(changes.getFirst().acting().login()).isEqualTo("mia");
        assertThat(answer().get("revision")).isEqualTo(REVISION);
        assertThat(answer().get("id")).isEqualTo("one");
        assertThat(answer().get("again")).isEqualTo(false);
        assertThat(answer().get("of"))
                .as("whose revision this is, which a project's own commit answers differently")
                .isEqualTo("organization");
    }

    static Stream<Arguments> everyReason() {
        return Stream.of(
                Arguments.of(Refused.USAGE, 64),
                Arguments.of(Refused.NOT_ALLOWED, 65),
                Arguments.of(Refused.MOVED_ON, 65),
                Arguments.of(Refused.LOCKED, 65),
                Arguments.of(Refused.EXISTS, 65),
                Arguments.of(Refused.LAST_OF_ITS_KIND, 65),
                Arguments.of(Refused.RESERVED_NAME, 65),
                Arguments.of(Refused.ON_ANOTHER_HOST, 65),
                Arguments.of(Refused.NO_CARD, 65),
                Arguments.of(Refused.REFUSED_STATE, 65),
                Arguments.of(Refused.NOT_FOUND, 66),
                Arguments.of(Refused.NOT_OPENED, 66),
                Arguments.of(Refused.NO_REPOSITORY, 66),
                Arguments.of(Refused.CANNOT_SAY, 69),
                Arguments.of(Refused.NO_KEY, 69),
                Arguments.of(Refused.NO_ORGANIZATION, 78));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyReason")
    void everyReasonEndsAtTheExitTheContractGivesIt(final String reason, final int exit) {
        refusing = new Refused(reason, "said", REVISION);

        assertThat(run("org", "owner", "add", "alice", "--json"))
                .as("a script acts on the exit alone")
                .isEqualTo(exit);
        assertThat(answer().get("reason")).isEqualTo(reason);
    }

    @Test
    void anUnexpectedFailureIsFailedAndNeverARefusal() {
        failing = new IOException("disk full");

        assertThat(run("org", "owner", "add", "alice", "--json")).isEqualTo(70);
    }

    @Test
    void withNoIdANewOneIsMadeAndAnswered() {
        assertThat(run("org", "owner", "add", "alice", "--json")).isZero();
        final String first = String.valueOf(answer().get("id"));
        out.getBuffer().setLength(0);
        assertThat(run("org", "owner", "add", "alice", "--json")).isZero();

        assertThat(first).as("the id to ask again with").isNotBlank();
        assertThat(answer().get("id")).isNotEqualTo(first);
        assertThat(changes.getFirst().base()).as("a store's revision read now").isNull();
    }

    @Test
    void anIdNoChangeMayCarryIsUsage() {
        assertThat(run("org", "owner", "add", "alice", "--json", "--id", "no spaces"))
                .isEqualTo(64);

        assertThat(calls).isEmpty();
    }

    @Test
    void askedAgainTheAnswerSaysSo() {
        again = true;

        assertThat(run("org", "owner", "add", "alice", "--json", "--id", "one")).isZero();

        assertThat(answer().get("again")).isEqualTo(true);
    }

    @Test
    void aRefusalIsAnsweredByItsReason() {
        refusing = new Refused(Refused.MOVED_ON, "it moved", REVISION);

        assertThat(run("org", "owner", "add", "alice", "--json")).isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("moved-on");
    }

    @Test
    void aStoreThatCannotBeReachedSaysTheIdToAskAgainWith() throws Exception {
        failing = unreachable();

        assertThat(run("org", "owner", "add", "alice", "--json", "--id", "keep-me")).isEqualTo(69);

        assertThat(answer().get("reason")).isEqualTo("cannot-say");
        assertThat(out.toString()).as("the change may have been made").contains("keep-me");
    }

    private IOException unreachable() {
        try {
            org.fuin.porelo.organization.gittree.GitTreeStore.at(
                            new ProcessCommandRunner(),
                            dir.resolve("gone").toString(),
                            "acme",
                            "forge.example",
                            org.fuin.porelo.organization.gittree.FetchAccess.NONE,
                            dir.resolve("gone-state"))
                    .read(new NoForge());
        } catch (IOException ex) {
            return ex;
        }
        throw new AssertionError("that address must not answer");
    }

    private static final class NoForge implements org.fuin.porelo.organization.IdentitySource {
        @Override
        public java.util.Optional<Keys> keys(final String login) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Boolean> owns(final String organisation, final String login) {
            return java.util.Optional.empty();
        }
    }

    @Test
    void aPersonsReplacementCarriesTheFilesCardAndAFileThatIsNoneIsUsage() throws Exception {
        final Path card = Files.writeString(dir.resolve("card"), "the card's text");

        assertThat(run("org", "person", "replace", "alice", "--card", card.toString(), "--json"))
                .isZero();
        assertThat(calls).containsExactly("replaceCard[alice, the card's text]");

        assertThat(run("org", "person", "replace", "alice", "--card", dir.toString(), "--json"))
                .isEqualTo(64);
    }

    @Test
    void anAddressOfAProjectNeverOpenedIsNotOpened() {
        assertThat(run("project", "address", "new", "ssh://git@forge.example/acme/new", "--json"))
                .isEqualTo(66);

        assertThat(answer().get("reason")).isEqualTo("not-opened");
        assertThat(calls).as("only project open opens a project").isEmpty();
    }

    private HostCard filedCard() {
        final String text = calls.getLast();
        return HostCard.read(
                text.substring(text.indexOf(", ") + 2, text.length() - 1), "the card filed");
    }

    @Test
    void aHostsCardKeepsWhatIsNotNamedAndIsSignedByTheActingKey() {
        assertThat(run("org", "host", "change", "build", "--address", "new.example.org", "--json"))
                .as("what it said: %s", err)
                .isZero();

        final HostCard card = filedCard();
        assertThat(card.address()).isEqualTo("new.example.org");
        assertThat(card.sshPort()).as("not named, so kept").isEqualTo(2022);
        assertThat(card.matrixPort()).isEqualTo(8448);
    }

    @Test
    void noneTakesAServiceOffAHostsCard() {
        assertThat(run("org", "host", "change", "build", "--matrix-port", "none", "--json"))
                .as("what it said: %s", err)
                .isZero();

        assertThat(filedCard().matrixPort()).isEqualTo(HostCard.NO_SERVICE);
        assertThat(filedCard().forgeWebPort()).isEqualTo(3000);
        assertThat(filedCard().address()).as("not named, so kept").isEqualTo("held.example.org");
    }

    @Test
    void aPortThatIsNoneIsUsageAndAFirstCardNamesWhereTheHostIs() {
        assertThat(run("org", "host", "change", "build", "--matrix-port", "99999", "--json"))
                .isEqualTo(64);
        assertThat(run("org", "host", "change", "fresh", "--address", "a.example.org", "--json"))
                .isEqualTo(64);
        assertThat(calls).isEmpty();
    }

    private void organization() throws IOException {
        final Path file = dir.resolve("config/porelo/organization.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "adapter: git\nsettings:\n  repository: git@github.com:acme/organization.git\n");
    }

    private int runReal(final String... arguments) {
        return line().execute(arguments);
    }

    @Test
    void aMachineWithNoOrganizationIsTold() {
        assertThat(runReal("org", "owner", "add", "alice", "--json")).isEqualTo(78);

        assertThat(out.toString()).contains("no-organization");
    }

    @Test
    void aMachineWithNoCardOfItsOwnIsTold() throws Exception {
        organization();

        assertThat(runReal("org", "owner", "add", "alice", "--json")).isEqualTo(65);

        assertThat(out.toString()).contains("no-card");
    }

    @Test
    void anAgentNotServingTheCardsKeyIsNoKey() throws Exception {
        organization();
        final Path card = dir.resolve("config/porelo/identity/card");
        Files.createDirectories(card.getParent());
        Files.writeString(
                card,
                PersonCard.write(
                        "mia",
                        "mia",
                        "",
                        KEY,
                        java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2030, 1, 1),
                        null));

        assertThat(runReal("org", "owner", "add", "alice", "--json")).isEqualTo(69);

        assertThat(out.toString()).contains("no-key");
    }

    @Test
    void anAgentServingOnlyAnotherKeyIsNoKey() throws Exception {
        organization();
        final Path card = dir.resolve("config/porelo/identity/card");
        Files.createDirectories(card.getParent());
        Files.writeString(
                card,
                PersonCard.write(
                        "mia",
                        "mia",
                        "",
                        KEY,
                        java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2030, 1, 1),
                        null));
        final Path socket = dir.resolve("agent.sock");
        try (org.fuin.porelo.vault.SshAgentServer agent =
                new org.fuin.porelo.vault.SshAgentServer(
                        socket, List.of(SigningKey.generate("someone")))) {
            Thread.ofVirtual().start(agent);
            final CommandLine line =
                    PoreloCli.commandLine(contextWith(Map.of("SSH_AUTH_SOCK", socket.toString())));
            line.setOut(new PrintWriter(out));
            line.setErr(new PrintWriter(err));

            assertThat(line.execute("org", "owner", "add", "alice", "--json"))
                    .as("signing with whatever key the agent holds would act as someone else")
                    .isEqualTo(69);
        }

        assertThat(out.toString()).contains("no-key");
    }
}
