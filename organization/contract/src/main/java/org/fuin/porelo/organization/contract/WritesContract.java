package org.fuin.porelo.organization.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.organization.OrganizationWrites.Change;
import org.fuin.porelo.organization.OrganizationWrites.Written;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * What any organization's store must guarantee when it is changed through its API: only the one
 * allowed makes a change, a refused change leaves nothing, a change asked for again is not made
 * twice, and what a change says it made every fresh reader accepts. An adapter's test extends this
 * and gives its fixture.
 */
public abstract class WritesContract {

    private static final String OWNER = "mia";

    private static final String ADMIN = "alice";

    private static final String DEVELOPER = "bob";

    private static final String STRANGER = "mallory";

    private static final String PROJECT = "demo";

    private static final String OTHER_PROJECT = "other";

    private static final String HOST = "build";

    /**
     * Returns a new fixture, for one check alone.
     *
     * @return The fixture.
     * @throws Exception When it cannot be made.
     */
    protected abstract WritesFixture fixture() throws Exception;

    /**
     * Runs every check against the adapter.
     *
     * @return One test per check.
     */
    @TestFactory
    Stream<DynamicTest> everyCheckHoldsForThisAdapter() {
        return checks().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> c.body().run(fixture())));
    }

    /**
     * Returns the checks.
     *
     * @return Every check, by name.
     */
    public static List<Check<WritesFixture>> checks() {
        return List.of(
                new Check<>(
                        "aFoundingOwnerIsTheOnlyOwnerAndAFreshReaderTrustsThem",
                        WritesContract::aFoundingOwnerIsTheOnlyOwnerAndAFreshReaderTrustsThem),
                new Check<>(
                        "someoneTheSourceSaysOwnsNothingFoundsNothing",
                        WritesContract::someoneTheSourceSaysOwnsNothingFoundsNothing),
                new Check<>(
                        "aFoundedOrganizationIsNotFoundedAgain",
                        WritesContract::aFoundedOrganizationIsNotFoundedAgain),
                new Check<>(
                        "aFirstCardIsTrustedByAFreshReader",
                        WritesContract::aFirstCardIsTrustedByAFreshReader),
                new Check<>(
                        "anAddedOwnerIsReadBackAndMayAct",
                        WritesContract::anAddedOwnerIsReadBackAndMayAct),
                new Check<>(
                        "anAddedProjectHasItsFirstAdmin",
                        WritesContract::anAddedProjectHasItsFirstAdmin),
                new Check<>(
                        "anAddedMemberIsReadBackWithTheirRole",
                        WritesContract::anAddedMemberIsReadBackWithTheirRole),
                new Check<>("aLockedMemberIsNoMember", WritesContract::aLockedMemberIsNoMember),
                new Check<>("aRemovedMemberIsGone", WritesContract::aRemovedMemberIsGone),
                new Check<>(
                        "anAddedHostHasItsAdminAndItsProject",
                        WritesContract::anAddedHostHasItsAdminAndItsProject),
                new Check<>("aStrangerAddsNoOwner", WritesContract::aStrangerAddsNoOwner),
                new Check<>("aStrangerRemovesNoOwner", WritesContract::aStrangerRemovesNoOwner),
                new Check<>("aStrangerNamesNoAdmin", WritesContract::aStrangerNamesNoAdmin),
                new Check<>("aDeveloperLocksNobody", WritesContract::aDeveloperLocksNobody),
                new Check<>("anOwnerLocksNobody", WritesContract::anOwnerLocksNobody),
                new Check<>(
                        "aStrangerPutsNoProjectOnAHost",
                        WritesContract::aStrangerPutsNoProjectOnAHost),
                new Check<>(
                        "aStrangersKeyTakesNoFreeLogin",
                        WritesContract::aStrangersKeyTakesNoFreeLogin),
                new Check<>("aStrangerAddsNoProject", WritesContract::aStrangerAddsNoProject),
                new Check<>("aStrangerRemovesNoProject", WritesContract::aStrangerRemovesNoProject),
                new Check<>("aStrangerWithdrawsNoCard", WritesContract::aStrangerWithdrawsNoCard),
                new Check<>("aStrangerReplacesNoCard", WritesContract::aStrangerReplacesNoCard),
                new Check<>("aStrangerAddsNoHost", WritesContract::aStrangerAddsNoHost),
                new Check<>(
                        "aStrangerMovesNoProjectsAddress",
                        WritesContract::aStrangerMovesNoProjectsAddress),
                new Check<>("aDeveloperAddsNoMember", WritesContract::aDeveloperAddsNoMember),
                new Check<>(
                        "aDeveloperCannotRaiseThemselves",
                        WritesContract::aDeveloperCannotRaiseThemselves),
                new Check<>(
                        "anAdminHasNoRightInAnotherProject",
                        WritesContract::anAdminHasNoRightInAnotherProject),
                new Check<>("anOwnerAddsNoDeveloper", WritesContract::anOwnerAddsNoDeveloper),
                new Check<>(
                        "aLockedAdminChangesNothing", WritesContract::aLockedAdminChangesNothing),
                new Check<>(
                        "anOwnerIsNoAdminOfAHostOfSomeoneElse",
                        WritesContract::anOwnerIsNoAdminOfAHostOfSomeoneElse),
                new Check<>(
                        "aKeyNotOnThePersonsCardActsForNobody",
                        WritesContract::aKeyNotOnThePersonsCardActsForNobody),
                new Check<>(
                        "aWithdrawnCardsKeyActsNoMore",
                        WritesContract::aWithdrawnCardsKeyActsNoMore),
                new Check<>("aReplacedKeyActsNoMore", WritesContract::aReplacedKeyActsNoMore),
                new Check<>(
                        "whatAReplacedKeyDidIsGoneForANewReaderUntilItIsAddedAgain",
                        WritesContract::whatAReplacedKeyDidIsGoneForANewReaderUntilItIsAddedAgain),
                new Check<>(
                        "aKeyHandedOnKeepsWhatItDid", WritesContract::aKeyHandedOnKeepsWhatItDid),
                new Check<>(
                        "aChangeThatWouldChangeNothingIsRefusedNotAnsweredAsMade",
                        WritesContract::aChangeThatWouldChangeNothingIsRefusedNotAnsweredAsMade),
                new Check<>("anOwnerRemovesNoDeveloper", WritesContract::anOwnerRemovesNoDeveloper),
                new Check<>(
                        "aStrangerFilesNoOwnersBackup",
                        WritesContract::aStrangerFilesNoOwnersBackup),
                new Check<>(
                        "aKeyThatBacksNoOwnerTakesNoPlace",
                        WritesContract::aKeyThatBacksNoOwnerTakesNoPlace),
                new Check<>(
                        "aBackupKeyTakesItsOwnersPlace",
                        WritesContract::aBackupKeyTakesItsOwnersPlace),
                new Check<>(
                        "theSourcesWordStandsForAnOldKeyItNoLongerLists",
                        WritesContract::theSourcesWordStandsForAnOldKeyItNoLongerLists),
                new Check<>(
                        "aCardNotSignedByTheOldKeyHandsNothingOn",
                        WritesContract::aCardNotSignedByTheOldKeyHandsNothingOn),
                new Check<>(
                        "aCardHandedOnByAnUnrelatedKeyHandsNothingOn",
                        WritesContract::aCardHandedOnByAnUnrelatedKeyHandsNothingOn),
                new Check<>(
                        "aCardOfAnotherLoginIsRefused",
                        WritesContract::aCardOfAnotherLoginIsRefused),
                new Check<>(
                        "aCardOfAnotherKeyThanTheActingOneIsRefused",
                        WritesContract::aCardOfAnotherKeyThanTheActingOneIsRefused),
                new Check<>(
                        "aBaseTheStoreMovedPastIsRefused",
                        WritesContract::aBaseTheStoreMovedPastIsRefused),
                new Check<>(
                        "anIdAskedAgainIsAnsweredAndNotMadeTwice",
                        WritesContract::anIdAskedAgainIsAnsweredAndNotMadeTwice),
                new Check<>(
                        "anotherPersonsIdIsNotThisPersonsChange",
                        WritesContract::anotherPersonsIdIsNotThisPersonsChange),
                new Check<>(
                        "anIdAskedAgainForAnotherChangeIsRefused",
                        WritesContract::anIdAskedAgainForAnotherChangeIsRefused),
                new Check<>("theLastOwnerIsNotRemoved", WritesContract::theLastOwnerIsNotRemoved),
                new Check<>(
                        "theOnlyOwnersCardIsNotWithdrawn",
                        WritesContract::theOnlyOwnersCardIsNotWithdrawn),
                new Check<>(
                        "theLastAdminStaysForItsAdmins",
                        WritesContract::theLastAdminStaysForItsAdmins));
    }

    private static Change change(final WritesFixture f, final String login, final String id)
            throws Exception {
        return new Change(new Acting(login, f.key(login)), f.read().revision(), id);
    }

    /** Refused for this reason, and a fresh reader finds the store as it was. */
    private static void refused(
            final WritesFixture f, final String reason, final ThrowingCallable call)
            throws Exception {
        final String before = f.read().revision();

        assertThatThrownBy(call)
                .as("a change that must be refused as %s", reason)
                .isInstanceOfSatisfying(
                        Refused.class, r -> assertThat(r.reason()).isEqualTo(reason));

        final OrganizationQuestions after = f.read();
        assertThat(after.revision()).as("a refused change leaves nothing").isEqualTo(before);
        assertThat(after.getRefusals()).as("and nothing a reader refuses").isEmpty();
    }

    private static void found(final WritesFixture f) throws Exception {
        f.sourceSaysOwner(OWNER, true);
        f.writes()
                .createOrganization(
                        new Change(new Acting(OWNER, f.key(OWNER)), null, "found"),
                        f.card(OWNER, OWNER));
    }

    private static void cardOf(final WritesFixture f, final String login) throws Exception {
        f.writes().addCard(change(f, login, "card-" + login), f.card(login, login));
    }

    /** An owner, a project with its admin and developer, and a stranger with a card. */
    private static void organization(final WritesFixture f) throws Exception {
        found(f);
        cardOf(f, ADMIN);
        cardOf(f, DEVELOPER);
        cardOf(f, STRANGER);
        f.writes().addProject(change(f, OWNER, "project"), PROJECT, ADMIN);
        f.writes().addProjectMember(change(f, ADMIN, "member"), PROJECT, DEVELOPER, "developer");
    }

    private static void aFoundingOwnerIsTheOnlyOwnerAndAFreshReaderTrustsThem(final WritesFixture f)
            throws Exception {
        found(f);

        final OrganizationQuestions read = f.read();
        assertThat(read.getRefusals()).as("nothing the founding made is refused").isEmpty();
        assertThat(read.getOrganizationOwners())
                .as("the one who founded it, and nobody else")
                .extracting(Organization.Owner::login)
                .containsExactly(OWNER);
        assertThat(state(read, OWNER)).isEqualTo(Organization.TRUSTED);
    }

    private static void someoneTheSourceSaysOwnsNothingFoundsNothing(final WritesFixture f) {
        f.sourceSaysOwner(STRANGER, false);

        assertThatThrownBy(
                        () ->
                                f.writes()
                                        .createOrganization(
                                                new Change(
                                                        new Acting(STRANGER, f.key(STRANGER)),
                                                        null,
                                                        "found"),
                                                f.card(STRANGER, STRANGER)))
                .as("taking an empty repository for an organization they do not own")
                .isInstanceOfSatisfying(
                        Refused.class, r -> assertThat(r.reason()).isEqualTo(Refused.NOT_ALLOWED));
        assertThatCode(() -> found(f))
                .as("what they tried left nothing in the rightful owner's way")
                .doesNotThrowAnyException();
    }

    private static void aFoundedOrganizationIsNotFoundedAgain(final WritesFixture f)
            throws Exception {
        found(f);
        f.sourceSaysOwner(STRANGER, true);

        refused(
                f,
                Refused.EXISTS,
                () ->
                        f.writes()
                                .createOrganization(
                                        new Change(
                                                new Acting(STRANGER, f.key(STRANGER)),
                                                null,
                                                "again"),
                                        f.card(STRANGER, STRANGER)));
        assertThat(f.read().isOrganizationOwner(STRANGER)).isFalse();
    }

    private static String state(final OrganizationQuestions read, final String login) {
        return read.getPersons().stream()
                .filter(p -> p.login().equals(login))
                .map(OrganizationQuestions.Person::state)
                .findFirst()
                .orElse("absent");
    }

    private static void aFirstCardIsTrustedByAFreshReader(final WritesFixture f) throws Exception {
        found(f);

        final Written written = f.writes().addCard(change(f, ADMIN, "card"), f.card(ADMIN, ADMIN));

        final OrganizationQuestions read = f.read();
        assertThat(written.again()).isFalse();
        assertThat(read.revision()).as("what it says it made").isEqualTo(written.revision());
        assertThat(state(read, ADMIN)).isEqualTo(Organization.TRUSTED);
        assertThat(read.getRefusals()).isEmpty();
    }

    private static void anAddedOwnerIsReadBackAndMayAct(final WritesFixture f) throws Exception {
        found(f);
        cardOf(f, ADMIN);

        f.writes().addOrganizationOwner(change(f, OWNER, "owner"), ADMIN);
        assertThat(f.read().isOrganizationOwner(ADMIN)).isTrue();
        f.writes().addHost(change(f, ADMIN, "host"), HOST, ADMIN);

        final OrganizationQuestions read = f.read();
        assertThat(read.isHostAdmin(HOST, ADMIN)).as("what they did as an owner stands").isTrue();
        assertThat(read.getRefusals()).isEmpty();
    }

    private static void anAddedProjectHasItsFirstAdmin(final WritesFixture f) throws Exception {
        found(f);
        cardOf(f, ADMIN);

        f.writes().addProject(change(f, OWNER, "project"), PROJECT, ADMIN);

        final OrganizationQuestions read = f.read();
        assertThat(read.isProjectMember(PROJECT, ADMIN)).contains("admin");
        assertThat(read.projectAdmins(PROJECT)).contains(List.of(ADMIN));
        assertThat(read.getRefusals()).isEmpty();
    }

    private static void anAddedMemberIsReadBackWithTheirRole(final WritesFixture f)
            throws Exception {
        organization(f);

        final OrganizationQuestions read = f.read();
        assertThat(read.isProjectMember(PROJECT, DEVELOPER)).contains("developer");
        assertThat(read.isProjectMember(PROJECT, STRANGER)).isEmpty();
        assertThat(read.getRefusals()).isEmpty();
    }

    private static void aLockedMemberIsNoMember(final WritesFixture f) throws Exception {
        organization(f);

        f.writes().lockProjectMember(change(f, ADMIN, "lock"), PROJECT, DEVELOPER);

        assertThat(f.read().isProjectMember(PROJECT, DEVELOPER))
                .as("a lock that readers ignore locks nothing")
                .isEmpty();
        assertThat(f.read().getRefusals()).isEmpty();
    }

    private static void aRemovedMemberIsGone(final WritesFixture f) throws Exception {
        organization(f);

        f.writes().removeProjectMember(change(f, ADMIN, "off"), PROJECT, DEVELOPER);

        assertThat(f.read().isProjectMember(PROJECT, DEVELOPER)).isEmpty();
        assertThat(f.read().getRefusals()).isEmpty();
    }

    private static void anAddedHostHasItsAdminAndItsProject(final WritesFixture f)
            throws Exception {
        organization(f);

        f.writes().addHost(change(f, OWNER, "host"), HOST, ADMIN);
        f.writes().addProjectToHost(change(f, ADMIN, "on"), HOST, PROJECT);

        final OrganizationQuestions read = f.read();
        assertThat(read.isHostAdmin(HOST, ADMIN)).isTrue();
        assertThat(read.hostOf(PROJECT)).contains(HOST);
        assertThat(read.getRefusals()).isEmpty();
    }

    private static void aStrangerAddsNoOwner(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().addOrganizationOwner(attempt, STRANGER));
    }

    private static void aStrangerAddsNoProject(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().addProject(attempt, OTHER_PROJECT, STRANGER));
    }

    private static void aStrangerRemovesNoProject(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().removeProject(attempt, PROJECT));
        assertThat(f.read().isProjectMember(PROJECT, ADMIN)).contains("admin");
    }

    private static void aStrangerWithdrawsNoCard(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().revokeCard(attempt, ADMIN));
        assertThat(state(f.read(), ADMIN)).isEqualTo(Organization.TRUSTED);
    }

    private static void aStrangerReplacesNoCard(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().replaceCard(attempt, ADMIN, f.card(ADMIN, STRANGER)));
    }

    private static void aStrangerAddsNoHost(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().addHost(attempt, HOST, STRANGER));
    }

    private static void aStrangerMovesNoProjectsAddress(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () ->
                        f.writes()
                                .changeProjectRepository(
                                        attempt, PROJECT, "https://forge.example/evil/demo.git"));
    }

    private static void aDeveloperAddsNoMember(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, DEVELOPER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().addProjectMember(attempt, PROJECT, STRANGER, "developer"));
    }

    private static void aDeveloperCannotRaiseThemselves(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, DEVELOPER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().changeProjectMemberRole(attempt, PROJECT, DEVELOPER, "admin"));
        assertThat(f.read().isProjectMember(PROJECT, DEVELOPER)).contains("developer");
    }

    private static void anAdminHasNoRightInAnotherProject(final WritesFixture f) throws Exception {
        organization(f);
        f.writes().addProject(change(f, OWNER, "other"), OTHER_PROJECT, DEVELOPER);

        final Change attempt = change(f, ADMIN, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().addProjectMember(attempt, OTHER_PROJECT, STRANGER, "developer"));
    }

    private static void anOwnerAddsNoDeveloper(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, OWNER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().addProjectMember(attempt, PROJECT, STRANGER, "developer"));
    }

    private static void aLockedAdminChangesNothing(final WritesFixture f) throws Exception {
        organization(f);
        f.writes().addProjectMember(change(f, ADMIN, "second"), PROJECT, STRANGER, "admin");
        assertThat(f.read().isProjectMember(PROJECT, STRANGER)).contains("admin");
        f.writes().lockProjectMember(change(f, STRANGER, "lock"), PROJECT, ADMIN);

        final Change attempt = change(f, ADMIN, "x");
        refused(
                f,
                Refused.LOCKED,
                () -> f.writes().removeProjectMember(attempt, PROJECT, DEVELOPER));
    }

    private static void anOwnerIsNoAdminOfAHostOfSomeoneElse(final WritesFixture f)
            throws Exception {
        organization(f);
        f.writes().addHost(change(f, OWNER, "host"), HOST, ADMIN);

        final Change attempt = change(f, OWNER, "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().addHostAdmin(attempt, HOST, DEVELOPER));
    }

    private static void aKeyNotOnThePersonsCardActsForNobody(final WritesFixture f)
            throws Exception {
        organization(f);

        final Change attempt =
                new Change(new Acting(OWNER, f.key(STRANGER)), f.read().revision(), "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().addOrganizationOwner(attempt, STRANGER));
    }

    private static void aWithdrawnCardsKeyActsNoMore(final WritesFixture f) throws Exception {
        organization(f);
        f.writes().revokeCard(change(f, DEVELOPER, "go"), DEVELOPER);

        assertThat(state(f.read(), DEVELOPER)).isNotEqualTo(Organization.TRUSTED);
        final Change attempt = change(f, DEVELOPER, "back");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().addCard(attempt, f.card(DEVELOPER, DEVELOPER)));
    }

    private static void aReplacedKeyActsNoMore(final WritesFixture f) throws Exception {
        organization(f);
        final Change lost = change(f, ADMIN, "x");
        final String next = ADMIN + "-new";
        f.key(next);
        f.sourceLists(ADMIN, next);
        f.writes().replaceCard(change(f, OWNER, "swap"), ADMIN, f.card(ADMIN, next));

        final String before = f.read().revision();
        final Change attempt = new Change(lost.acting(), before, "y");
        assertThatThrownBy(
                        () -> f.writes().addProjectMember(attempt, PROJECT, STRANGER, "developer"))
                .as("the old key acts for nobody, though its holder is still an admin")
                .isInstanceOfSatisfying(
                        Refused.class, r -> assertThat(r.reason()).isEqualTo(Refused.NOT_ALLOWED));
        assertThat(f.read().revision()).as("a refused change leaves nothing").isEqualTo(before);
        assertThat(f.read().getPersons())
                .as("the replacement stands for the login")
                .filteredOn(p -> p.login().equals(ADMIN))
                .extracting(OrganizationQuestions.Person::key)
                .containsExactly(f.key(next));
    }

    /** An owner's replacement links nothing to the old key, which the source no longer lists. */
    private static void whatAReplacedKeyDidIsGoneForANewReaderUntilItIsAddedAgain(
            final WritesFixture f) throws Exception {
        organization(f);
        final String next = ADMIN + "-new";
        f.key(next);
        f.sourceLists(ADMIN, next);
        f.writes().replaceCard(change(f, OWNER, "swap"), ADMIN, f.card(ADMIN, next));

        final OrganizationQuestions lost = f.read();
        assertThat(lost.getPersons())
                .as("the replacement stands for the login")
                .filteredOn(p -> p.login().equals(ADMIN))
                .extracting(OrganizationQuestions.Person::key)
                .containsExactly(f.key(next));
        assertThat(lost.isProjectMember(PROJECT, DEVELOPER))
                .as("a member the old key added counts for nothing on a new machine")
                .isEmpty();
        assertThat(lost.getRefusals()).as("and a reader says so").isNotEmpty();
        assertThat(lost.isProjectMember(PROJECT, ADMIN))
                .as("the admin an owner added stays")
                .contains("admin");

        f.writes().changeProjectAdmin(change(f, OWNER, "admin"), PROJECT, ADMIN);
        f.writes()
                .addProjectMember(
                        new Change(new Acting(ADMIN, f.key(next)), f.read().revision(), "again"),
                        PROJECT,
                        DEVELOPER,
                        "developer");

        final OrganizationQuestions back = f.read();
        assertThat(back.getRefusals()).as("the way back leaves nothing refused").isEmpty();
        assertThat(back.isProjectMember(PROJECT, DEVELOPER))
                .as("an owner adds the admin again, and the new key adds the rest")
                .contains("developer");
    }

    /** The refused member still stands in the store's text, so this change would change nothing. */
    private static void aChangeThatWouldChangeNothingIsRefusedNotAnsweredAsMade(
            final WritesFixture f) throws Exception {
        organization(f);
        final String next = ADMIN + "-new";
        f.key(next);
        f.sourceLists(ADMIN, next);
        f.writes().replaceCard(change(f, OWNER, "swap"), ADMIN, f.card(ADMIN, next));
        final String before = f.read().revision();

        assertThatThrownBy(
                        () ->
                                f.writes()
                                        .addProjectMember(
                                                new Change(
                                                        new Acting(ADMIN, f.key(next)),
                                                        before,
                                                        "again"),
                                                PROJECT,
                                                DEVELOPER,
                                                "developer"))
                .as("answered as made, it would say a member stands whom no reader has")
                .isInstanceOfSatisfying(
                        Refused.class,
                        r -> assertThat(r.reason()).isEqualTo(Refused.REFUSED_STATE));
        assertThat(f.read().revision()).as("and nothing was pushed").isEqualTo(before);
    }

    private static void anOwnerRemovesNoDeveloper(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, OWNER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().removeProjectMember(attempt, PROJECT, DEVELOPER));
    }

    private static void aStrangerFilesNoOwnersBackup(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().addBackupKey(attempt, null, f.key(STRANGER)));
    }

    private static void aKeyThatBacksNoOwnerTakesNoPlace(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt =
                new Change(new Acting(OWNER, f.key(STRANGER)), f.read().revision(), "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().replaceKeyWithBackup(attempt, null, f.card(OWNER, STRANGER)));
        assertThat(f.read().isOrganizationOwner(OWNER)).isTrue();
    }

    private static void aBackupKeyTakesItsOwnersPlace(final WritesFixture f) throws Exception {
        organization(f);
        final String backup = OWNER + "-backup";
        final String next = OWNER + "-new";
        f.writes().addBackupKey(change(f, OWNER, "backup"), null, f.key(backup));
        f.key(next);
        f.sourceLists(OWNER, OWNER, next);

        f.writes()
                .replaceKeyWithBackup(
                        new Change(new Acting(OWNER, f.key(backup)), f.read().revision(), "swap"),
                        null,
                        f.card(OWNER, next));

        assertThat(f.read().getOrganizationOwners())
                .as("the new key holds the place, and the backup no longer does")
                .filteredOn(owner -> !owner.backup())
                .extracting(Organization.Owner::key)
                .containsExactly(f.key(next));
    }

    /** The source lists only the old key, so nothing but its signature could vouch for the new. */
    private static void handOnRefused(final WritesFixture f, final String card, final String next)
            throws Exception {
        final String before = f.read().revision();

        assertThatThrownBy(
                        () ->
                                f.writes()
                                        .changeCard(
                                                new Change(
                                                        new Acting(ADMIN, f.key(next)),
                                                        before,
                                                        "on"),
                                                card))
                .as("taking a person's login with no word from their key")
                .isInstanceOfSatisfying(
                        Refused.class, r -> assertThat(r.reason()).isEqualTo(Refused.NOT_ALLOWED));
        assertThat(f.read().revision()).as("and nothing was pushed").isEqualTo(before);
    }

    /**
     * No card stands for a new reader then, so the identity source decides, as for a first card.
     */
    private static void theSourcesWordStandsForAnOldKeyItNoLongerLists(final WritesFixture f)
            throws Exception {
        organization(f);
        final String next = ADMIN + "-new";
        f.key(next);
        f.sourceLists(ADMIN, next);

        assertThatCode(
                        () ->
                                f.writes()
                                        .changeCard(
                                                new Change(
                                                        new Acting(ADMIN, f.key(next)),
                                                        f.read().revision(),
                                                        "on"),
                                                f.card(ADMIN, next)))
                .as("the identity source's word is all a new reader has")
                .doesNotThrowAnyException();

        assertThat(f.read().getPersons())
                .as("the new key's own card stands for a new reader")
                .filteredOn(p -> p.login().equals(ADMIN))
                .extracting(OrganizationQuestions.Person::key)
                .containsExactly(f.key(next));
    }

    private static void aCardNotSignedByTheOldKeyHandsNothingOn(final WritesFixture f)
            throws Exception {
        organization(f);
        final String next = ADMIN + "-new";
        f.key(next);

        handOnRefused(f, f.card(ADMIN, next), next);
    }

    private static void aCardHandedOnByAnUnrelatedKeyHandsNothingOn(final WritesFixture f)
            throws Exception {
        organization(f);
        final String next = ADMIN + "-new";
        f.key(next);

        handOnRefused(f, f.handedOn(ADMIN, next, STRANGER), next);
    }

    /** A card the old key signed links the two, so what the old key did keeps its signer. */
    private static void aKeyHandedOnKeepsWhatItDid(final WritesFixture f) throws Exception {
        organization(f);
        final String next = ADMIN + "-new";
        f.key(next);
        f.sourceLists(ADMIN, next);

        f.writes()
                .changeCard(
                        new Change(new Acting(ADMIN, f.key(next)), f.read().revision(), "on"),
                        f.handedOn(ADMIN, next, ADMIN));

        final OrganizationQuestions read = f.read();
        assertThat(read.getRefusals()).as("a new machine agrees with an old one").isEmpty();
        assertThat(read.isProjectMember(PROJECT, DEVELOPER))
                .as("the old key added them")
                .contains("developer");
    }

    private static void aStrangerRemovesNoOwner(final WritesFixture f) throws Exception {
        organization(f);
        f.writes().addOrganizationOwner(change(f, OWNER, "second"), ADMIN);

        final Change attempt = change(f, STRANGER, "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().removeOrganizationOwner(attempt, OWNER));
        assertThat(f.read().isOrganizationOwner(OWNER)).isTrue();
    }

    private static void aStrangerNamesNoAdmin(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().changeProjectAdmin(attempt, PROJECT, STRANGER));
        assertThat(f.read().isProjectMember(PROJECT, STRANGER)).isEmpty();
    }

    private static void aDeveloperLocksNobody(final WritesFixture f) throws Exception {
        organization(f);
        f.writes().addProjectMember(change(f, ADMIN, "other"), PROJECT, STRANGER, "developer");

        final Change attempt = change(f, DEVELOPER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().lockProjectMember(attempt, PROJECT, STRANGER));
        assertThat(f.read().isProjectMember(PROJECT, STRANGER)).contains("developer");
    }

    private static void anOwnerLocksNobody(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, OWNER, "x");
        refused(
                f,
                Refused.NOT_ALLOWED,
                () -> f.writes().lockProjectMember(attempt, PROJECT, DEVELOPER));
        assertThat(f.read().isProjectMember(PROJECT, DEVELOPER)).contains("developer");
    }

    private static void aStrangerPutsNoProjectOnAHost(final WritesFixture f) throws Exception {
        organization(f);
        f.writes().addHost(change(f, OWNER, "host"), HOST, ADMIN);

        final Change attempt = change(f, STRANGER, "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().addProjectToHost(attempt, HOST, PROJECT));
        assertThat(f.read().hostOf(PROJECT)).isEmpty();
    }

    /** The card and the acting person agree here, so only the source's word can say no. */
    private static void aStrangersKeyTakesNoFreeLogin(final WritesFixture f) throws Exception {
        found(f);
        f.key(ADMIN);

        final Change attempt =
                new Change(new Acting(ADMIN, f.key(STRANGER)), f.read().revision(), "x");
        refused(f, Refused.NOT_ALLOWED, () -> f.writes().addCard(attempt, f.card(ADMIN, STRANGER)));
        assertThat(state(f.read(), ADMIN)).as("the login stays free").isEqualTo("absent");
    }

    private static void aCardOfAnotherLoginIsRefused(final WritesFixture f) throws Exception {
        found(f);

        final Change attempt = change(f, STRANGER, "x");
        refused(f, Refused.USAGE, () -> f.writes().addCard(attempt, f.card(ADMIN, STRANGER)));
    }

    private static void aCardOfAnotherKeyThanTheActingOneIsRefused(final WritesFixture f)
            throws Exception {
        found(f);

        final Change attempt = change(f, ADMIN, "x");
        refused(f, Refused.USAGE, () -> f.writes().addCard(attempt, f.card(ADMIN, STRANGER)));
    }

    private static void aBaseTheStoreMovedPastIsRefused(final WritesFixture f) throws Exception {
        organization(f);
        final Change late = change(f, ADMIN, "late");
        f.writes().addHost(change(f, OWNER, "host"), HOST, DEVELOPER);

        refused(
                f,
                Refused.MOVED_ON,
                () -> f.writes().removeProjectMember(late, PROJECT, DEVELOPER));
        assertThat(f.read().isProjectMember(PROJECT, DEVELOPER)).contains("developer");
    }

    private static void anIdAskedAgainIsAnsweredAndNotMadeTwice(final WritesFixture f)
            throws Exception {
        organization(f);
        final Change first = change(f, OWNER, "again");
        final Written made = f.writes().addOrganizationOwner(first, ADMIN);
        f.writes().addHost(change(f, OWNER, "host"), HOST, ADMIN);
        final String before = f.read().revision();

        final Written again = f.writes().addOrganizationOwner(first, ADMIN);

        assertThat(again.again()).as("the id is looked for before the base").isTrue();
        assertThat(again.revision()).isEqualTo(made.revision());
        assertThat(f.read().revision()).as("and nothing is made twice").isEqualTo(before);
    }

    private static void anotherPersonsIdIsNotThisPersonsChange(final WritesFixture f)
            throws Exception {
        found(f);
        f.writes().addCard(change(f, ADMIN, "same-id"), f.card(ADMIN, ADMIN));

        final Written written =
                f.writes().addCard(change(f, DEVELOPER, "same-id"), f.card(DEVELOPER, DEVELOPER));

        assertThat(written.again()).as("another person's commit is not their change").isFalse();
        assertThat(state(f.read(), DEVELOPER)).isEqualTo(Organization.TRUSTED);
    }

    private static void anIdAskedAgainForAnotherChangeIsRefused(final WritesFixture f)
            throws Exception {
        organization(f);
        f.writes().addOrganizationOwner(change(f, OWNER, "one"), ADMIN);

        final Change other = change(f, OWNER, "one");
        refused(f, Refused.USAGE, () -> f.writes().addOrganizationOwner(other, DEVELOPER));
        assertThat(f.read().isOrganizationOwner(DEVELOPER))
                .as("answered as made, it would say an owner stands who does not")
                .isFalse();
    }

    private static void theLastOwnerIsNotRemoved(final WritesFixture f) throws Exception {
        found(f);

        final Change attempt = change(f, OWNER, "x");
        refused(
                f,
                Refused.LAST_OF_ITS_KIND,
                () -> f.writes().removeOrganizationOwner(attempt, OWNER));
    }

    private static void theOnlyOwnersCardIsNotWithdrawn(final WritesFixture f) throws Exception {
        found(f);

        final Change attempt = change(f, OWNER, "x");
        refused(f, Refused.LAST_OF_ITS_KIND, () -> f.writes().revokeCard(attempt, OWNER));
    }

    private static void theLastAdminStaysForItsAdmins(final WritesFixture f) throws Exception {
        organization(f);

        final Change attempt = change(f, ADMIN, "x");
        refused(
                f,
                Refused.LAST_OF_ITS_KIND,
                () -> f.writes().removeProjectMember(attempt, PROJECT, ADMIN));
    }
}
