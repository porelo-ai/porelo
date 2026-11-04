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
import org.fuin.porelo.organization.gittree.ProjectFile;
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

    private OrgWriting.Ground standing = (machine, opener) -> null;

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

    private void seam(final CommandLine line, final OrgWriting.Opener opener) {
        if (line.getCommand() instanceof OrgWriting writing) {
            writing.session = opener;
            writing.ground = standing;
        }
        if (line.getCommand() instanceof OrgCreateCommand create) {
            create.founder = (machine, address) -> opener.open(machine);
        }
        if (line.getCommand() instanceof ProjectRepositoryCommand.Changing changing) {
            changing.places =
                    (machine, project, login) -> {
                        if ("unknown".equals(project)) {
                            throw new Refused(Refused.NOT_FOUND, "no such project", REVISION);
                        }
                        return new ProjectRepositoryCommand.Place("/nowhere/" + project, List.of());
                    };
        }
        if (line.getCommand() instanceof ProjectSignCommand sign) {
            sign.looks = (machine, at, place) -> look;
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
                                KEY,
                                new org.fuin.porelo.organization.gittree.ProjectFile(
                                        new ProcessCommandRunner(),
                                        org.fuin.porelo.organization.gittree.FetchAccess.NONE,
                                        org.fuin.porelo.organization.gittree.CommitSigning.agent(
                                                ""),
                                        dir.resolve("memory"))));
        return line.execute(arguments);
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    static Stream<Arguments> everyCommand() {
        return Stream.of(
                Arguments.of("org admin add alice", "addOrganizationAdmin[alice]"),
                Arguments.of("org admin remove alice", "removeOrganizationAdmin[alice]"),
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
                Arguments.of(Refused.VAULT_SHUT, 77),
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
    void withoutBaseAChangeStandsOnWhatAReadTrustedSoAForgeGoneBackIsNotSignedOn() {
        standing = (machine, opener) -> REVISION;

        assertThat(run("org", "owner", "add", "alice", "--json", "--id", "one"))
                .as("what it said: %s", err)
                .isZero();

        assertThat(changes.getFirst().base()).isEqualTo(REVISION);
    }

    @Test
    void aStateThatWentBackMakesNoChange() {
        standing =
                (machine, opener) -> {
                    throw new Refused(Refused.REFUSED_STATE, "an older state", null);
                };

        assertThat(run("org", "owner", "add", "alice", "--json", "--id", "one")).isEqualTo(65);

        assertThat(calls).isEmpty();
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
        assertThat(calls).as("only project create-repository opens a project").isEmpty();
    }

    /** A lost key's address is refused for a new reader, and filing it again is the way back. */
    @Test
    void anAddressStandingRefusedMayBeFiledAgainAndOneNeverFiledMayNot() {
        final org.fuin.porelo.organization.OrganizationQuestions.Project project =
                new org.fuin.porelo.organization.OrganizationQuestions.Project(
                        "demo", List.of(), List.of(), "alice", false, null);
        final List<org.fuin.porelo.organization.Organization.Refusal> refusals =
                new java.util.ArrayList<>();
        final org.fuin.porelo.organization.OrganizationQuestions state =
                (org.fuin.porelo.organization.OrganizationQuestions)
                        java.lang.reflect.Proxy.newProxyInstance(
                                org.fuin.porelo.organization.OrganizationQuestions.class
                                        .getClassLoader(),
                                new Class<?>[] {
                                    org.fuin.porelo.organization.OrganizationQuestions.class
                                },
                                (self, method, args) ->
                                        method.getName().equals("getProjects")
                                                ? List.of(project)
                                                : refusals);

        assertThat(ProjectAddressCommand.mayBeFiled(state, "demo")).isFalse();

        refusals.add(
                new org.fuin.porelo.organization.Organization.Refusal(
                        "abc", "projects/demo/repository.yml", "signed by someone, not its admin"));

        assertThat(ProjectAddressCommand.mayBeFiled(state, "demo")).isTrue();
    }

    @Test
    void aForgeWhoseHostKeyIsUnknownSaysSoAndNamesTheWayToTrustIt() {
        final Exception refused =
                new java.io.IOException(
                        "cannot read the organization's repository: Host key verification failed.");

        final var scp =
                OrgAccess.unreached(refused, "other", "git@forge.example:acme/organization.git");
        final var port =
                OrgAccess.unreached(refused, "other", "ssh://git@forge.example:2222/acme/o");

        assertThat(scp.reason()).isEqualTo("unknown-host-key");
        assertThat(scp.getMessage())
                .contains("ssh host key", "porelo credentials trust-host forge.example")
                .doesNotContain("other");
        assertThat(port.getMessage()).contains("porelo credentials trust-host forge.example:2222");
    }

    @Test
    void anyOtherFailureToReachTheForgeKeepsItsWordsAndItsReason() {
        final Exception down = new java.io.IOException("cannot read: Connection refused");

        final var refused =
                OrgAccess.unreached(down, "the words given", "git@forge.example:acme/o.git");

        assertThat(refused.reason()).isEqualTo("cannot-say");
        assertThat(refused.getMessage()).isEqualTo("the words given");
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

    private static final String ADDRESS = "git@github.com:acme/organization.git";

    private void ownCard() throws IOException {
        final Path card = dir.resolve("config/porelo/identity/card");
        Files.createDirectories(card.getParent());
        Files.writeString(card, "mia's card");
    }

    private Path settings() {
        return dir.resolve("config/porelo/organization.yml");
    }

    @Test
    void foundingAtHttpsIsAllowedAndSaysNoMachineCanFollowIt() throws Exception {
        ownCard();

        assertThat(run("org", "create", "https://github.com/acme/organization", "--id", "one"))
                .as("what it said: %s", err)
                .isZero();

        assertThat(calls).containsExactly("createOrganization[mia's card]");
        assertThat(out.toString())
                .as("what it means, and the way to ssh")
                .contains("none can follow")
                .contains("porelo setup --organization");
    }

    @Test
    void foundingAtAnSshAddressSaysNothingOfFollowing() throws Exception {
        ownCard();

        assertThat(run("org", "create", ADDRESS, "--id", "one"))
                .as("what it said: %s", err)
                .isZero();

        assertThat(out.toString()).doesNotContain("none can follow");
    }

    @Test
    void creatingFilesTheOwnCardAndPointsThisMachineAtIt() throws Exception {
        ownCard();

        assertThat(run("org", "create", ADDRESS, "--json", "--id", "one"))
                .as("what it said: %s", err)
                .isZero();

        assertThat(calls).containsExactly("createOrganization[mia's card]");
        assertThat(changes.getFirst().base()).as("nothing was accepted yet").isNull();
        assertThat(answer().get("address"))
                .as("as org answers it, with nothing of who connects")
                .isEqualTo("github.com:acme/organization.git");
        assertThat(answer().get("of")).isEqualTo("organization");
        assertThat(OrgAccess.repository(context())).contains(ADDRESS);
    }

    @Test
    void creatingWithNoTokenSaysToDeclareOneAndHow() throws Exception {
        ownCard();
        refusing = new Refused(Refused.CANNOT_SAY, "could not say enough", "");

        assertThat(run("org", "create", ADDRESS, "--json")).isEqualTo(69);

        assertThat(answer().get("words"))
                .as("the forge is asked who owns it with a token, and none is declared")
                .asString()
                .contains("porelo credentials declare https://github.com --kind token");
        assertThat(answer().get("revision"))
                .as("an empty repository has no revision, and a script reads null as none")
                .isNull();
    }

    @Test
    void creatingWithATokenKeepsWhatTheStoreSaid() throws Exception {
        ownCard();
        final Path token = dir.resolve("token");
        Files.writeString(token, "tok");
        final Path declared = context().paths().vault().credentialRegistry();
        Files.createDirectories(declared.getParent());
        Files.writeString(
                declared,
                "credentials:\n  - match: https://github.com\n    kind: token\n"
                        + "    source: file\n    file: "
                        + token
                        + "\n");
        refusing = new Refused(Refused.CANNOT_SAY, "could not say enough", "");

        assertThat(run("org", "create", ADDRESS, "--json")).isEqualTo(69);

        assertThat(answer().get("words"))
                .as("declaring a token again would not help someone who has one")
                .isEqualTo("could not say enough");
        assertThat(answer().get("revision"))
                .as("an empty repository has no revision, and a script reads null as none")
                .isNull();
    }

    @Test
    void creatingSaysToKeepASecondOwnerOrABackupKey() throws Exception {
        ownCard();

        assertThat(run("org", "create", ADDRESS)).isZero();

        assertThat(out.toString()).contains("second owner").contains("backup key");
    }

    @Test
    void askedAgainForTheSameAddressCreatingReachesTheStore() throws Exception {
        ownCard();
        organization();
        again = true;

        assertThat(run("org", "create", ADDRESS, "--json", "--id", "one"))
                .as("a run that got as far as the settings has to be answered: %s", err)
                .isZero();

        assertThat(answer().get("again")).isEqualTo(true);
    }

    @Test
    void aMachineWithAnotherOrganizationIsNotPointedElsewhere() throws Exception {
        ownCard();
        organization();
        final String before = Files.readString(settings());

        assertThat(run("org", "create", "git@github.com:other/organization.git", "--json"))
                .isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("exists");
        assertThat(calls).as("nothing is filed for a machine that cannot use it").isEmpty();
        assertThat(Files.readString(settings())).isEqualTo(before);
    }

    @Test
    void anotherOrganizationIsCreatedInThePlaceOfTheOneHeldWhenAskedTo() throws Exception {
        ownCard();
        organization();
        final String other = "git@github.com:other/organization.git";

        assertThat(run("org", "create", other, "--replace", "--json", "--id", "one")).isZero();

        assertThat(OrgAccess.repository(context())).contains(other);
    }

    @Test
    void aFailedCreationWritesNoSettingsAndNamesTheIdToAskAgainWith() throws Exception {
        ownCard();
        failing = unreachable();

        assertThat(run("org", "create", ADDRESS, "--json", "--id", "keep-me")).isEqualTo(69);

        assertThat(out.toString()).contains("keep-me");
        assertThat(settings()).doesNotExist();
    }

    @Test
    void creatingWithNoCardOfOurOwnIsNoCard() {
        assertThat(run("org", "create", ADDRESS, "--json")).isEqualTo(65);

        assertThat(answer().get("reason")).isEqualTo("no-card");
    }

    @Test
    void anAddressCarryingASecretIsRefusedBeforeItCanBeKept() throws Exception {
        ownCard();

        for (final String secret :
                List.of(
                        "https://mia:hunter2@github.com/acme/organization.git",
                        "https://hunter2@github.com/acme/organization.git",
                        "ssh://mia:hunter2@github.com/acme/organization.git")) {
            out.getBuffer().setLength(0);
            assertThat(run("org", "create", secret, "--json"))
                    .as("settings are handed to hosts, so the secret would travel")
                    .isEqualTo(64);
            assertThat(out.toString()).doesNotContain("hunter2");
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void aSshAddressWithItsGitUserAndPortIsNoSecret() {
        assertThat(OrgAccess.settable("ssh://git@github.com:22/acme/organization.git"))
                .isNotBlank();
    }

    @Test
    void aChangeNamingNothingIsUsageBeforeAnythingIsOpened() {
        assertThat(run("project", "repository", "change", "demo", "app", "--json"))
                .as("nothing to change, and no agent or forge is asked")
                .isEqualTo(64);
        assertThat(calls).as("nothing is written").isEmpty();
    }

    @Test
    void aReviewThatIsNoWordIsUsageBeforeAnythingIsOpened() {
        assertThat(
                        run(
                                "project",
                                "repository",
                                "change",
                                "unknown",
                                "app",
                                "--review",
                                "maybe",
                                "--json"))
                .as("wrong use, whatever the project is")
                .isEqualTo(64);
        assertThat(calls).as("nothing is written").isEmpty();
    }

    @Test
    void aChangeOfAProjectNotPlacedAnswersThePlaceFirst() {
        assertThat(
                        run(
                                "project",
                                "repository",
                                "change",
                                "unknown",
                                "app",
                                "--central",
                                ADDRESS,
                                "--json"))
                .isEqualTo(66);
        assertThat(calls).as("the organization is not written to").isEmpty();
    }

    @Test
    void aProjectsFileAnswersThePlaceFirstAndNothingOfTheOrganization() {
        assertThat(
                        run(
                                "project",
                                "repository",
                                "add",
                                "unknown",
                                "app",
                                "--upstream",
                                ADDRESS,
                                "--json"))
                .isEqualTo(66);
        assertThat(calls).as("the organization is not written to").isEmpty();

        out.getBuffer().setLength(0);
        assertThat(run("project", "repository", "remove", "demo", "app", "--json", "--id", "one"))
                .as("nowhere to fetch from, so it cannot say and asks for the id")
                .isEqualTo(69);
        assertThat(out.toString()).contains("one");
    }

    private ProjectFile.Look look =
            new ProjectFile.Look(
                    new ProjectFile.Read(
                            ProjectFile.REFUSED, null, null, "signed by someone gone", "their-key"),
                    "b".repeat(40),
                    "project:\n  name: \"demo\"\n# \u001b[2Jhidden\n",
                    null);

    private void readableCard() throws IOException {
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
    }

    @Test
    void aSigningWithoutTheRevisionReadIsWrongUseAndWritesNothing() {
        assertThat(run("project", "sign", "demo", "--yes", "--json")).isEqualTo(64);
        assertThat(run("project", "sign", "demo", "--base", "b".repeat(40), "--json"))
                .isEqualTo(64);
        assertThat(calls).isEmpty();
    }

    @Test
    void aLookAtAProjectsFileShowsItAndWhyAndNeedsNoAgent() throws Exception {
        readableCard();

        assertThat(run("project", "sign", "demo", "--json")).as("what it said: %s", err).isZero();

        final Map<?, ?> answer = answer();
        assertThat(answer.get("state")).isEqualTo("refused");
        assertThat(answer.get("why")).isEqualTo("signed by someone gone");
        assertThat(answer.get("revision")).isEqualTo("b".repeat(40));
        assertThat(answer.get("signer")).isEqualTo("their-key");
        assertThat(answer.get("of")).isEqualTo("project");
        assertThat((String) answer.get("file")).contains("name: \"demo\"");
        assertThat(calls).isEmpty();
    }

    @Test
    void aLookForAPersonShowsTheFileWithNothingThatMovesTheTerminal() throws Exception {
        readableCard();

        assertThat(run("project", "sign", "demo")).as("what it said: %s", err).isZero();

        assertThat(out.toString())
                .contains("signed by someone gone")
                .contains("name: \"demo\"")
                .contains("porelo project sign demo --yes --base " + "b".repeat(40))
                .doesNotContain("\u001b");
    }

    @Test
    void aLookNamesWhatASignatureWouldNotChangeAndDoesNotOfferOne() throws Exception {
        readableCard();
        look =
                new ProjectFile.Look(
                        new ProjectFile.Read(ProjectFile.REFUSED, null, null, "why", null),
                        "b".repeat(40),
                        null,
                        "it does not read");

        assertThat(run("project", "sign", "demo")).as("what it said: %s", err).isZero();
        assertThat(out.toString()).contains("it does not read").doesNotContain("--yes");

        out.getBuffer().setLength(0);
        assertThat(run("project", "sign", "demo", "--json")).isZero();
        assertThat(answer().get("blocked")).isEqualTo("it does not read");
    }

    @Test
    void aLookAtAFileThatReadsHereStillOffersASigningForAMachineThatRefusesIt() throws Exception {
        readableCard();
        look =
                new ProjectFile.Look(
                        new ProjectFile.Read(ProjectFile.READ, "b".repeat(40), null, null, "k"),
                        "b".repeat(40),
                        "text",
                        null);

        assertThat(run("project", "sign", "demo")).as("what it said: %s", err).isZero();
        assertThat(out.toString())
                .contains("another machine")
                .contains("porelo project sign demo --yes --base " + "b".repeat(40));
    }

    @Test
    void anIdWithoutSigningIsWrongUse() {
        assertThat(run("project", "sign", "demo", "--id", "one", "--json")).isEqualTo(64);
    }

    @Test
    void aLookWithNoCardOfOnesOwnIsNotAllowed() {
        assertThat(run("project", "sign", "demo", "--json")).isEqualTo(65);
    }

    @Test
    void aLookAtWhatCannotBeReachedCannotSay() throws Exception {
        readableCard();
        look =
                new ProjectFile.Look(
                        new ProjectFile.Read(ProjectFile.CANNOT_SAY, null, null, null),
                        null,
                        null,
                        null);

        assertThat(run("project", "sign", "demo", "--json")).isEqualTo(69);
    }

    @Test
    void aSigningGoesToTheRepositoryAndAnswersThatItsRevisionIsTheProjects() {
        assertThat(
                        run(
                                "project",
                                "sign",
                                "demo",
                                "--yes",
                                "--base",
                                "b".repeat(40),
                                "--id",
                                "one",
                                "--json"))
                .as("nowhere to fetch from, so it cannot say and asks for the id")
                .isEqualTo(69);
        assertThat(out.toString()).contains("one");
        assertThat(new ProjectSignCommand().of()).isEqualTo("project");
    }

    @Test
    void aChangeOfAProjectsFileAnswersThatItsRevisionIsTheProjects() {
        assertThat(new ProjectRepositoryCommand.Add().of()).isEqualTo("project");
        assertThat(new ProjectRepositoryCommand.Remove().of()).isEqualTo("project");
    }

    @Test
    void aPushIsLentTheAgentThatSigns() throws Exception {
        try (var lease =
                OrganizationAccess.lent(context(), "/run/agent.sock")
                        .lend("git@github.com:acme/organization.git")) {
            assertThat(lease.environment())
                    .as("over ssh a push has no other way to the person's key")
                    .containsEntry("SSH_AUTH_SOCK", "/run/agent.sock");
        }
    }
}
