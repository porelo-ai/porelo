package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Guards a person's complete departure and the settings forgotten only after it stands. */
class OrgLeaveCommandTest {

    @TempDir Path dir;
    private RegistryCheckout machine;
    private TestOrganization organization;
    private boolean losePushReply;
    private @org.jspecify.annotations.Nullable String rewindAtPush;
    private boolean refusePushOnce;
    private String holder = "michael";

    @BeforeEach
    void organization() throws Exception {
        machine = new RegistryCheckout(dir);
        organization = new TestOrganization(machine);
        organization.filed("michael");
        organization.filed("alice");
        organization
                .writes()
                .addOrganizationAdmin(
                        organization.change("michael", "michael", "admin-alice"), "alice");
        final Path identity = machine.paths.xdg().config().resolve("identity");
        Files.createDirectories(identity);
        Files.writeString(identity.resolve("card"), organization.card("michael"));
        Files.writeString(identity.resolve("key.pub"), machine.key("michael"));
    }

    @AfterEach
    void close() {
        machine.close();
    }

    private GitTreeStore store() {
        return GitTreeStore.at(
                machine.runner,
                organization.repo.toString(),
                "acme",
                "forge.example.org",
                FetchAccess.NONE,
                machine.paths.xdg().state().resolve("organization"));
    }

    private void anotherOwner() throws Exception {
        organization
                .writes()
                .addOrganizationOwner(
                        organization.change("michael", "michael", "owner-alice"), "alice");
        organization
                .writes()
                .addOrganizationAdmin(
                        organization.change("michael", "michael", "admin-michael"), "michael");
    }

    private CommandLine line() {
        final CommandLine line = PoreloCli.commandLine(machine.context());
        line.setOut(new PrintWriter(machine.out));
        line.setErr(new PrintWriter(machine.err));
        seams(line);
        return line;
    }

    private void seams(final CommandLine line) {
        if (line.getCommand() instanceof OrgReading reading) {
            reading.opener = (context, address) -> new OrgAccess.Opened(store(), machine.forge);
        }
        if (line.getCommand() instanceof OrgWriting writing) {
            writing.session =
                    context ->
                            new OrgWriting.Session(
                                    writes(),
                                    organization.change("michael", holder, "unused").acting(),
                                    machine.keys.get(holder),
                                    new ProjectFile(
                                            machine.runner,
                                            FetchAccess.NONE,
                                            org.fuin.porelo.organization.gittree.CommitSigning
                                                    .agent("unused"),
                                            OrgAccess.projectFiles(context)));
        }
        line.getSubcommands().values().forEach(this::seams);
    }

    private org.fuin.porelo.organization.OrganizationWrites writes() {
        final Map<String, Path> keys = new java.util.HashMap<>();
        machine.keyFiles.forEach((login, path) -> keys.put(machine.key(login), path));
        final var delegate =
                new org.fuin.porelo.organization.gittree.GitTreeWrites(
                        command -> {
                            if (refusePushOnce && command.arguments().contains("push")) {
                                refusePushOnce = false;
                                return new org.fuin.porelo.core.process.CommandResult(
                                        command, 1, "", "the remote hung up");
                            }
                            if (rewindAtPush != null && command.arguments().contains("push")) {
                                final String revision = rewindAtPush;
                                rewindAtPush = null;
                                organization.servesAgain(revision);
                            }
                            return machine.runner.run(command);
                        },
                        organization.repo.toString(),
                        "acme",
                        FetchAccess.NONE,
                        machine.forge,
                        new org.fuin.porelo.organization.gittree.CommitSigning(Map.of(), keys));
        return (org.fuin.porelo.organization.OrganizationWrites)
                java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {org.fuin.porelo.organization.OrganizationWrites.class},
                        (proxy, method, arguments) -> {
                            final Object result;
                            try {
                                result = method.invoke(delegate, arguments);
                            } catch (java.lang.reflect.InvocationTargetException ex) {
                                throw ex.getCause();
                            }
                            if (losePushReply && method.getName().equals("together")) {
                                losePushReply = false;
                                throw new java.io.IOException("the push reply was lost");
                            }
                            return result;
                        });
    }

    @Test
    void anOwnerAndAdminLeavesBothListsAndRevokesTheirOwnKey() throws Exception {
        anotherOwner();
        final String base = organization.tip();
        final String card = organization.card("michael");

        assertThat(line().execute("org", "leave", "--json"))
                .as("the owner and admin can leave: %s", machine.err)
                .isZero();

        final OrganizationQuestions state = store().read(machine.forge);
        assertThat(state.getOrganizationOwners())
                .as("the leaving owner is removed")
                .extracting(org.fuin.porelo.organization.Organization.Owner::login)
                .containsExactly("alice");
        assertThat(state.getOrganizationAdmins().orElseThrow())
                .as("the leaving admin is removed")
                .containsExactly("alice");
        assertThat(state.getCard("michael")).as("the card is withdrawn").isEmpty();
        assertThat(state.getRefusals()).as("every signed departure commit is accepted").isEmpty();
        assertThat(organization.git("diff", "--name-only", base, "HEAD"))
                .as("only the leaving person's writable files change")
                .isEqualTo("organization-admins.yml\nowners.yml\npeople/michael.card");
        assertThat(machine.answer())
                .as("the departure's JSON contract")
                .isEqualTo(
                        Map.of(
                                "organization",
                                "acme",
                                "login",
                                "michael",
                                "left",
                                List.of("owner", "organization-admin"),
                                "stillListed",
                                List.of(),
                                "forgotten",
                                true));
        Files.writeString(organization.repo.resolve("people/michael.card"), card);
        organization.git("add", "people/michael.card");
        organization.git(
                "-c",
                "user.signingkey=" + machine.keyFiles.get("michael"),
                "commit",
                "-q",
                "-S",
                "-m",
                "Try the withdrawn key");
        assertThat(store().read(machine.forge).getRefusals())
                .as("the walk refuses any later commit signed by the revoked key")
                .anyMatch(refusal -> refusal.why().contains("revoked"));
    }

    @Test
    void theLastOwnerIsRefusedWithoutChangesAndNamesOrganizationAdmins() {
        final String base = organization.tip();

        assertThat(line().execute("org", "leave", "--json"))
                .as("leaving cannot strand the organization")
                .isEqualTo(65);

        assertThat(organization.tip()).as("no departure commit landed").isEqualTo(base);
        assertThat(organization.git("status", "--porcelain"))
                .as("the organization's files are untouched")
                .isEmpty();
        assertThat(machine.answer().get("reason"))
                .as("the same named refusal as other org writes")
                .isEqualTo("last-of-its-kind");
        assertThat(machine.answer().get("words").toString())
                .as("an admin is named as a possible successor")
                .contains("alice", "owner");
        assertThat(machine.paths.xdg().config().resolve("identity/card"))
                .as("a refused departure keeps the identity")
                .exists();
    }

    @Test
    void aProjectAdminAndDeveloperAndHostAdminStayListedWithoutRights() throws Exception {
        anotherOwner();
        organization
                .writes()
                .addProject(
                        organization.change("alice", "alice", "project-demo"), "demo", "michael");
        organization
                .writes()
                .addProject(
                        organization.change("alice", "alice", "project-other"), "other", "alice");
        organization
                .writes()
                .addProjectMember(
                        organization.change("alice", "alice", "developer"),
                        "other",
                        "michael",
                        "developer");
        organization
                .writes()
                .addHost(organization.change("alice", "alice", "host"), "build", "michael");
        final String members =
                Files.readString(organization.repo.resolve("projects/demo/members.yml"));
        final String host = Files.readString(organization.repo.resolve("hosts/build/host.yml"));

        assertThat(line().execute("org", "leave", "--json"))
                .as("the person gives up their card: %s", machine.err)
                .isZero();

        assertThat(machine.answer().get("stillListed"))
                .as("each remaining role tells an organization admin which file to tidy")
                .isEqualTo(
                        List.of(
                                Map.of("file", "hosts/build/host.yml", "role", "host-admin"),
                                Map.of(
                                        "file",
                                        "projects/demo/members.yml",
                                        "role",
                                        "project-admin"),
                                Map.of("file", "projects/other/members.yml", "role", "developer")));
        assertThat(Files.readString(organization.repo.resolve("projects/demo/members.yml")))
                .as("leave does not rewrite the project's list")
                .isEqualTo(members);
        assertThat(Files.readString(organization.repo.resolve("hosts/build/host.yml")))
                .as("leave does not rewrite the host's list")
                .isEqualTo(host);
        assertThat(store().read(machine.forge).projectAdmins("demo").orElseThrow())
                .as("a written entry grants nothing after revocation")
                .doesNotContain("michael");
    }

    @Test
    void aSuccessfulLeaveForgetsTheOrganizationAndItsKeptState() throws Exception {
        anotherOwner();
        final Path checkout = Files.createDirectory(dir.resolve("own-checkout"));
        Files.writeString(checkout.resolve("keep"), "the person's files");
        new OrgCheckouts(checkout, Map.of("demo", checkout)).write(machine.context());
        final Path state = machine.paths.xdg().state();
        for (final String file :
                List.of(
                        "organization/project-files/accepted",
                        "organization/settings-taken/demo/main",
                        "organization/refresh.json",
                        "organization-host/pin",
                        "organization-leave/leave-interrupted.json",
                        "identity/ops/old-operation.json",
                        "organization-projects/demo.git/HEAD")) {
            Files.createDirectories(state.resolve(file).getParent());
            Files.writeString(state.resolve(file), "kept");
        }

        assertThat(line().execute("org", "leave", "--json"))
                .as("forgetting follows the accepted departure: %s", machine.err)
                .isZero();

        assertThat(OrgAccess.repository(machine.context()))
                .as("the context has no organization")
                .isEmpty();
        assertThat(OrgAccess.ownLogin(machine.context()))
                .as("the kept own card is forgotten")
                .isEmpty();
        assertThat(machine.paths.xdg().config().resolve("identity/key.pub"))
                .as("the organization's kept identity key is forgotten")
                .doesNotExist();
        assertThat(OrgCheckouts.of(machine.context()))
                .as("checkout settings are forgotten")
                .isEqualTo(new OrgCheckouts(null, Map.of()));
        for (final String directory :
                List.of(
                        "organization",
                        "organization-host",
                        "organization-projects",
                        "organization-leave",
                        "identity")) {
            assertThat(state.resolve(directory)).as("%s is forgotten", directory).doesNotExist();
        }
        assertThat(checkout.resolve("keep"))
                .as("the person's own checkouts stay")
                .hasContent("the person's files");
    }

    @Test
    void anAdminWithoutOwnershipLeavesTheirOrganizationAdminEntryWritten() throws Exception {
        Files.writeString(
                machine.paths.xdg().config().resolve("identity/card"), organization.card("alice"));
        final CommandLine line = line();
        final OrgWriting writing =
                (OrgWriting)
                        line.getSubcommands()
                                .get("org")
                                .getSubcommands()
                                .getOrDefault(
                                        "leave", new CommandLine(new OrgPersonCommand.Revoke()))
                                .getCommand();
        writing.session =
                context ->
                        new OrgWriting.Session(
                                organization.writes(),
                                organization.change("alice", "alice", "unused").acting(),
                                machine.keys.get("alice"),
                                new ProjectFile(
                                        machine.runner,
                                        FetchAccess.NONE,
                                        org.fuin.porelo.organization.gittree.CommitSigning.agent(
                                                "unused"),
                                        OrgAccess.projectFiles(context)));

        assertThat(line.execute("org", "leave", "--json"))
                .as("only an owner may rewrite organization-admins.yml: %s", machine.err)
                .isZero();

        assertThat(machine.answer().get("stillListed"))
                .as("the unwritable admin entry is reported")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "file",
                                        "organization-admins.yml",
                                        "role",
                                        "organization-admin")));
        assertThat(store().read(machine.forge).getOrganizationAdmins().orElseThrow())
                .as("the list stays written")
                .containsExactly("alice");
        assertThat(store().read(machine.forge).getCard("alice"))
                .as("the card no longer stands")
                .isEmpty();
    }

    @Test
    void theLastOwnerNamesTrustedPeopleWhenThereAreNoOtherAdmins() throws Exception {
        organization
                .writes()
                .removeOrganizationAdmin(
                        organization.change("michael", "michael", "remove-admin"), "alice");

        assertThat(line().execute("org", "leave", "--json"))
                .as("the sole owner must choose a successor")
                .isEqualTo(65);

        assertThat(machine.answer().get("words").toString())
                .as("a trusted card holder can be promoted")
                .contains("alice");
    }

    @Test
    void aMovedBasePublishesNoneOfTheDepartureAndForgetsNothing() throws Exception {
        anotherOwner();
        final String stale = organization.tip();
        organization.filed("bob");
        final String now = organization.tip();

        assertThat(line().execute("org", "leave", "--base", stale, "--json"))
                .as("a concurrent change refuses the whole leave")
                .isEqualTo(65);

        assertThat(organization.tip())
                .as("none of the consecutive departure commits landed")
                .isEqualTo(now);
        assertThat(machine.answer().get("reason"))
                .as("the ordinary write refusal")
                .isEqualTo("moved-on");
        assertThat(OrgAccess.repository(machine.context()))
                .as("a refused push keeps the organization")
                .contains(TestOrganization.ADDRESS);
    }

    @Test
    void aRetryThatFindsTheOrganizationMovedOnBeginsTheDepartureAgain() throws Exception {
        anotherOwner();
        refusePushOnce = true;
        assertThat(line().execute("org", "leave", "--id", "departure", "--json"))
                .as("a push that did not land cannot say: %s", machine.err)
                .isEqualTo(69);
        organization.filed("bob");
        assertThat(line().execute("org", "leave", "--id", "departure", "--json"))
                .as("the earlier departure is not on main, and main moved on")
                .isEqualTo(65);
        machine.out.getBuffer().setLength(0);

        assertThat(line().execute("org", "leave", "--json"))
                .as("a departure begun again on the new main stands: %s", machine.err)
                .isZero();
        assertThat(OrgAccess.repository(machine.context()))
                .as("the organization is forgotten")
                .isEmpty();
    }

    @Test
    void anOwnerWhoseCardIsRevokedIsNoOtherOwner() throws Exception {
        anotherOwner();
        organization
                .writes()
                .revokeCard(organization.change("michael", "michael", "revoke-alice"), "alice");
        final String base = organization.tip();

        assertThat(line().execute("org", "leave", "--json"))
                .as("an owner entry whose key is revoked cannot act: %s", machine.err)
                .isEqualTo(65);
        assertThat(machine.answer().get("reason")).isEqualTo("last-of-its-kind");
        assertThat(organization.tip()).as("nothing landed").isEqualTo(base);
    }

    @Test
    void anOwnerEntryOfAnOlderKeyStaysListedForAnAdmin() throws Exception {
        anotherOwner();
        organization.listed("michael", "michael", "alice-new");
        final String card = machine.card("michael", "alice-new");
        organization
                .writes()
                .replaceCard(
                        organization.change("alice", "alice", "replace-michael"), "michael", card);
        final Path identity = machine.paths.xdg().config().resolve("identity");
        Files.writeString(identity.resolve("card"), card);
        Files.writeString(identity.resolve("key.pub"), machine.key("alice-new"));
        holder = "alice-new";

        assertThat(line().execute("org", "leave", "--json"))
                .as("the new key cannot take out its older key's entry: %s", machine.err)
                .isZero();
        assertThat(machine.answer().get("left")).as("no role taken out").isEqualTo(List.of());
        assertThat(machine.answer().get("stillListed").toString())
                .as("named for an organization admin")
                .contains("owners.yml", "organization-admins.yml");
        assertThat(organization.owners()).as("the older key's entry stays").contains("michael");
    }

    @Test
    void aDepartureDirectoryThatIsALinkHasNothingRemovedThroughIt() throws Exception {
        final Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        final Path kept = Files.writeString(elsewhere.resolve("kept.txt"), "kept");
        final Path state = machine.paths.xdg().state();
        Files.createDirectories(state);
        Files.createSymbolicLink(state.resolve("organization-leave"), elsewhere);

        try {
            OrgDeparture.remove(machine.context());
        } catch (java.io.IOException refused) {
            /* Refusing the link is as good as passing it by. */
        }

        assertThat(kept).as("nothing behind the link is removed").exists();
    }

    @Test
    void aLostPushReplyCanBeRetriedWithoutAnotherDeparture() throws Exception {
        anotherOwner();
        losePushReply = true;
        assertThat(line().execute("org", "leave", "--id", "departure", "--json"))
                .as("the lost answer is a failure")
                .isEqualTo(70);
        final String landed = organization.tip();
        machine.out.getBuffer().setLength(0);

        assertThat(line().execute("org", "leave", "--id", "departure", "--json"))
                .as("the original id completes forgetting: %s", machine.err)
                .isZero();

        assertThat(organization.tip()).as("the original push is answered again").isEqualTo(landed);
        assertThat(machine.answer().get("left"))
                .as("the original roles survive a lost reply")
                .isEqualTo(List.of("owner", "organization-admin"));
        assertThat(OrgAccess.repository(machine.context()))
                .as("the retry finishes forgetting")
                .isEmpty();
    }

    @Test
    void interruptedForgettingCanFinishAfterItsCardHasGone() throws Exception {
        anotherOwner();
        final Path settings = machine.paths.xdg().config().resolve("checkouts.yml");
        Files.createDirectory(settings);
        Files.writeString(settings.resolve("obstruction"), "cannot delete yet");
        assertThat(line().execute("org", "leave", "--json"))
                .as("an incomplete cleanup cannot claim success")
                .isEqualTo(70);
        final String landed = organization.tip();
        Files.delete(settings.resolve("obstruction"));
        Files.delete(settings);
        Files.deleteIfExists(machine.paths.xdg().config().resolve("identity/card"));
        machine.out.getBuffer().setLength(0);

        assertThat(line().execute("org", "leave", "--json"))
                .as("a confirmed departure needs no card to finish cleanup: %s", machine.err)
                .isZero();

        assertThat(organization.tip()).as("retrying cleanup makes no commits").isEqualTo(landed);
        assertThat(OrgAccess.repository(machine.context())).as("cleanup is complete").isEmpty();
    }

    @Test
    void aRemoteRewoundDuringThePushRefusesTheWholeDeparture() throws Exception {
        anotherOwner();
        final String previous = organization.git("rev-parse", "HEAD^");
        rewindAtPush = previous;

        assertThat(line().execute("org", "leave", "--json"))
                .as("the push must match exactly the base that was read")
                .isEqualTo(65);

        assertThat(organization.tip())
                .as("no departure commit lands on a concurrently changed base")
                .isEqualTo(previous);
        assertThat(OrgAccess.repository(machine.context()))
                .as("a refused departure forgets nothing")
                .contains(TestOrganization.ADDRESS);
    }

    @Test
    void aMissingKeyDuringRetryKeepsTheUncertainDeparture() throws Exception {
        anotherOwner();
        losePushReply = true;
        assertThat(line().execute("org", "leave", "--json"))
                .as("the push succeeded but its receipt was lost")
                .isEqualTo(70);
        final String landed = organization.tip();
        machine.out.getBuffer().setLength(0);
        final CommandLine unavailable = line();
        final OrgWriting leave =
                (OrgWriting)
                        unavailable
                                .getSubcommands()
                                .get("org")
                                .getSubcommands()
                                .get("leave")
                                .getCommand();
        leave.session =
                context -> {
                    throw new OrganizationQuestions.Refused(
                            OrganizationQuestions.Refused.NO_KEY,
                            "the agent is temporarily unavailable",
                            null);
                };
        assertThat(unavailable.execute("org", "leave", "--json"))
                .as("a temporary missing key is still its usual refusal")
                .isEqualTo(69);
        machine.out.getBuffer().setLength(0);

        assertThat(line().execute("org", "leave", "--json"))
                .as("restoring the key resumes the uncertain original leave: %s", machine.err)
                .isZero();

        assertThat(organization.tip()).as("the accepted leave is not repeated").isEqualTo(landed);
        assertThat(machine.answer().get("left"))
                .as("the original roles survive preflight refusals")
                .isEqualTo(List.of("owner", "organization-admin"));
        assertThat(OrgAccess.repository(machine.context())).as("forgetting finishes").isEmpty();
    }

    @Test
    void setupCannotReplaceAnOrganizationWhoseLeaveIsUnfinished() throws Exception {
        anotherOwner();
        final Path settings = machine.paths.xdg().config().resolve("checkouts.yml");
        Files.createDirectory(settings);
        Files.writeString(settings.resolve("obstruction"), "cannot delete yet");
        assertThat(line().execute("org", "leave", "--json"))
                .as("the departure stands but cleanup is unfinished")
                .isEqualTo(70);
        final var original = OrgAccess.repository(machine.context());
        final CommandLine setup = line();
        ((OrgSetup) setup.getSubcommands().get("setup").getMixins().get("organization")).opener =
                (context, address) -> new OrgAccess.Opened(store(), machine.forge);
        machine.out.getBuffer().setLength(0);

        assertThat(
                        setup.execute(
                                "setup",
                                "--organization",
                                "https://forge.example.org/other/organization.git",
                                "--replace",
                                "--json"))
                .as("setup must not strand the old departure")
                .isEqualTo(65);

        assertThat(OrgAccess.repository(machine.context()))
                .as("setup changed no organization settings")
                .isEqualTo(original);
        assertThat(machine.answer().get("words").toString())
                .as("the refusal tells how to finish")
                .contains("org leave");
        Files.delete(settings.resolve("obstruction"));
        Files.delete(settings);
        machine.out.getBuffer().setLength(0);
        assertThat(line().execute("org", "leave", "--json"))
                .as("the original leave can still finish: %s", machine.err)
                .isZero();
    }
}
