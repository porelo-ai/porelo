package org.fuin.porelo.organization.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationQuestions.Trust;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * What any organization's store must guarantee when it is read: a change takes effect only signed
 * by the one allowed to make it, wherever it lies in the history, and a history that cannot be
 * rewritten unnoticed, so no revocation and no older card is played back. An adapter's test extends
 * this and gives its fixture.
 */
public abstract class OrganizationContract {

    private static final String OWNER = "mia";

    private static final String SECOND_OWNER = "olga";

    private static final String ADMIN = "alice";

    private static final String DEVELOPER = "bob";

    private static final String SAME_KEY = "carol";

    private static final String SQUATTED = "dana";

    private static final String DENIED = "erin";

    private static final String STRANGER = "mallory";

    private static final String LATER = "peter";

    private static final String PROJECT = "demo";

    private static final String OTHER_PROJECT = "other";

    /**
     * Returns a new fixture, for one check alone.
     *
     * @return The fixture.
     * @throws Exception When it cannot be made.
     */
    protected abstract OrganizationFixture fixture() throws Exception;

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
    public static List<Check<OrganizationFixture>> checks() {
        return List.of(
                new Check<>("anAllowedChangeIsAnswered", OrganizationContract::allowedChange),
                new Check<>("anOwnerMayNameAnAdmin", OrganizationContract::ownerNamesAdmin),
                new Check<>("anUnsignedChangeGivesNoAdminRight", s -> unsigned(s, false)),
                new Check<>("aStrangersChangeGivesNoAdminRight", s -> stranger(s, false)),
                new Check<>("aDeveloperCannotRaiseThemselves", OrganizationContract::selfRaise),
                new Check<>(
                        "anAdminHasNoRightInAnotherProject", OrganizationContract::otherProject),
                new Check<>("aLockedAdminChangesNothing", OrganizationContract::lockedAdmin),
                new Check<>("anOwnerAddsNoDeveloper", OrganizationContract::ownerNoDeveloper),
                new Check<>("anOwnerLocksNoAdmin", OrganizationContract::ownerNoLock),
                new Check<>("anOwnerUnlocksNoMember", OrganizationContract::ownerNoUnlock),
                new Check<>("anUnsignedChangeMakesNoOwner", OrganizationContract::unsignedOwner),
                new Check<>("aStrangerMakesNoOwner", s -> strangerOwner(s, false)),
                new Check<>("aLostKeySignsNothing", s -> lostKey(s, false)),
                new Check<>("aLostKeyStaysLostAcrossARead", OrganizationContract::lostAcrossRead),
                new Check<>("anOwnersLostKeyMakesNoOwner", OrganizationContract::ownersLostKey),
                new Check<>("anOlderCardPlayedBackHoldsNothing", OrganizationContract::playedBack),
                new Check<>("aCardTakenOverHoldsNothing", s -> cardTaken(s, false)),
                new Check<>("aCardFiledByAStrangerHoldsNothing", s -> filedForAnother(s, STRANGER)),
                new Check<>("aCardFiledByAnOwnerHoldsNothing", s -> filedForAnother(s, OWNER)),
                new Check<>("aCardRemovedByAStrangerStands", OrganizationContract::cardRemoved),
                new Check<>("aReplacementByANonOwnerHoldsNothing", OrganizationContract::replaced),
                new Check<>("aCardTheSourceDeniesGivesNoRight", OrganizationContract::denied),
                new Check<>("aSquattedFirstCardGivesWay", OrganizationContract::squatted),
                new Check<>("anUnsignedChangeUnderALaterOneGivesNoRight", s -> unsigned(s, true)),
                new Check<>("aStrangersChangeUnderALaterOneGivesNoRight", s -> stranger(s, true)),
                new Check<>("aStrangerUnderALaterChangeMakesNoOwner", s -> strangerOwner(s, true)),
                new Check<>("aLostKeyUnderALaterChangeSignsNothing", s -> lostKey(s, true)),
                new Check<>("aCardTakenOverUnderALaterChangeHoldsNothing", s -> cardTaken(s, true)),
                new Check<>("anOlderStateServedAgainIsRefused", OrganizationContract::older),
                new Check<>("anOlderStateIsRefusedEachTime", OrganizationContract::olderTwice),
                new Check<>("aRewrittenHistoryIsRefused", OrganizationContract::rewritten),
                new Check<>("aDiscardedRevocationIsRefused", OrganizationContract::discarded));
    }

    /** The stranger holds a card throughout: with none, no role could count, whoever allowed it. */
    private static void project(final OrganizationFixture store) throws Exception {
        store.found(OWNER);
        store.fileCard(ADMIN);
        store.fileCard(DEVELOPER);
        store.fileCard(STRANGER);
        store.makeProject(OWNER, PROJECT, ADMIN);
    }

    private static void projectWithDeveloper(final OrganizationFixture store) throws Exception {
        project(store);
        store.addMember(ADMIN, PROJECT, DEVELOPER, "developer");
    }

    /** A later change anybody may make, so that the attack is no longer the tip. */
    private static void bury(final OrganizationFixture store, final boolean buried)
            throws Exception {
        if (buried) {
            store.fileCard(LATER);
        }
    }

    private static List<String> refusedRevisions(final OrganizationQuestions answers) {
        return answers.getRefusals().stream().map(Organization.Refusal::revision).toList();
    }

    /** What was refused is the attack and nothing before it, so that it cannot pass unnoticed. */
    private static void saidOnly(final OrganizationQuestions answers, final String attack) {
        assertThat(refusedRevisions(answers))
                .as("the refusal names the attack's revision, and nothing else was refused")
                .containsOnly(attack);
    }

    private static void noAdmin(final OrganizationQuestions answers, final String login) {
        assertThat(answers.isProjectMember(PROJECT, login))
                .as("nobody allowed %s a role in the project", login)
                .isEmpty();
        assertThat(answers.projectAdmins(PROJECT))
                .as("the project's admins are as they were")
                .hasValueSatisfying(
                        admins -> assertThat(admins).contains(ADMIN).doesNotContain(login));
    }

    private static void refusedState(final OrganizationFixture store) {
        assertThatThrownBy(store::read)
                .as("a state that does not continue what was trusted hides a change")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(Refused.REFUSED_STATE));
    }

    private static void allowedChange(final OrganizationFixture store) throws Exception {
        projectWithDeveloper(store);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.revision()).as("the answers rest on a state").isNotNull();
        assertThat(answers.isOrganizationOwner(OWNER)).as("the founder is the owner").isTrue();
        assertThat(answers.isProjectMember(PROJECT, ADMIN)).as("the first admin").contains("admin");
        assertThat(answers.isProjectMember(PROJECT, DEVELOPER))
                .as("the admin was allowed to add this member")
                .contains("developer");
        assertThat(answers.getRefusals()).as("nothing here was refused").isEmpty();
    }

    private static void ownerNamesAdmin(final OrganizationFixture store) throws Exception {
        project(store);
        store.ownerNamesAdmin(OWNER, PROJECT, DEVELOPER);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(PROJECT, DEVELOPER))
                .as("or a project whose admins lost their keys could not be recovered")
                .contains("admin");
        assertThat(answers.getRefusals()).as("nothing here was refused").isEmpty();
    }

    private static void unsigned(final OrganizationFixture store, final boolean buried)
            throws Exception {
        project(store);
        final String attack = store.adminNobodyAllowed(null, PROJECT, STRANGER);
        bury(store, buried);

        final OrganizationQuestions answers = store.read();

        noAdmin(answers, STRANGER);
        saidOnly(answers, attack);
    }

    private static void stranger(final OrganizationFixture store, final boolean buried)
            throws Exception {
        project(store);
        final String attack = store.adminNobodyAllowed(STRANGER, PROJECT, STRANGER);
        bury(store, buried);

        final OrganizationQuestions answers = store.read();

        noAdmin(answers, STRANGER);
        saidOnly(answers, attack);
    }

    private static void selfRaise(final OrganizationFixture store) throws Exception {
        projectWithDeveloper(store);
        final String attack = store.adminNobodyAllowed(DEVELOPER, PROJECT, DEVELOPER);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(PROJECT, DEVELOPER))
                .as("a member stays what an admin made them")
                .contains("developer");
        saidOnly(answers, attack);
    }

    private static void otherProject(final OrganizationFixture store) throws Exception {
        project(store);
        store.makeProject(OWNER, OTHER_PROJECT, DEVELOPER);
        final String attack = store.adminNobodyAllowed(ADMIN, OTHER_PROJECT, STRANGER);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(OTHER_PROJECT, STRANGER))
                .as("an admin of one project has no right in another")
                .isEmpty();
        saidOnly(answers, attack);
    }

    private static void lockedAdmin(final OrganizationFixture store) throws Exception {
        project(store);
        store.addMember(ADMIN, PROJECT, DEVELOPER, "admin");
        store.lockMember(ADMIN, PROJECT, DEVELOPER);
        final String attack = store.adminNobodyAllowed(DEVELOPER, PROJECT, STRANGER);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(PROJECT, DEVELOPER))
                .as("a locked member is listed but holds no role")
                .isEmpty();
        noAdmin(answers, STRANGER);
        saidOnly(answers, attack);
    }

    private static void ownerNoDeveloper(final OrganizationFixture store) throws Exception {
        project(store);
        final String attack = store.developerNobodyAllowed(OWNER, PROJECT, STRANGER);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(PROJECT, STRANGER))
                .as("an owner may name admins, and may add nothing else")
                .isEmpty();
        saidOnly(answers, attack);
    }

    private static void ownerNoLock(final OrganizationFixture store) throws Exception {
        project(store);
        final String attack = store.lockNobodyAllowed(OWNER, PROJECT, ADMIN);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(PROJECT, ADMIN))
                .as("a lock is the project's admins' own")
                .contains("admin");
        saidOnly(answers, attack);
    }

    private static void ownerNoUnlock(final OrganizationFixture store) throws Exception {
        projectWithDeveloper(store);
        store.lockMember(ADMIN, PROJECT, DEVELOPER);
        final String attack = store.unlockNobodyAllowed(OWNER, PROJECT, DEVELOPER);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(PROJECT, DEVELOPER))
                .as("the lock stays, since only a project's admin lifts it")
                .isEmpty();
        saidOnly(answers, attack);
    }

    private static void unsignedOwner(final OrganizationFixture store) throws Exception {
        store.found(OWNER);
        store.fileCard(STRANGER);
        final String attack = store.ownerNobodyAllowed(null, STRANGER);

        final OrganizationQuestions answers = store.read();

        noOwner(answers);
        saidOnly(answers, attack);
    }

    private static void strangerOwner(final OrganizationFixture store, final boolean buried)
            throws Exception {
        store.found(OWNER);
        store.fileCard(STRANGER);
        final String attack = store.ownerNobodyAllowed(STRANGER, STRANGER);
        bury(store, buried);

        final OrganizationQuestions answers = store.read();

        noOwner(answers);
        saidOnly(answers, attack);
    }

    private static void noOwner(final OrganizationQuestions answers) {
        assertThat(answers.isOrganizationOwner(STRANGER)).as("nobody allowed that").isFalse();
        assertThat(answers.isOrganizationOwner(OWNER)).as("the owner stays").isTrue();
    }

    /**
     * The lost key also holds another admin's card, so that the revocation must stop it: replacing
     * the card alone leaves it a right. The revocation refuses that second card too, so this is
     * refused for more than the attack.
     */
    private static void lostKeyHeldTwice(final OrganizationFixture store) throws Exception {
        project(store);
        store.fileCardWithKeyOf(SAME_KEY, ADMIN);
        store.addMember(ADMIN, PROJECT, SAME_KEY, "admin");
        store.replaceLostKey(OWNER, ADMIN);
    }

    private static void saidAmongOthers(final OrganizationQuestions answers, final String attack) {
        assertThat(refusedRevisions(answers))
                .as("the refusal names the attack's revision")
                .contains(attack);
    }

    private static void lostKey(final OrganizationFixture store, final boolean buried)
            throws Exception {
        lostKeyHeldTwice(store);
        final String attack = store.adminWithLostKey(ADMIN, PROJECT, STRANGER);
        bury(store, buried);

        final OrganizationQuestions answers = store.read();

        noAdmin(answers, STRANGER);
        saidAmongOthers(answers, attack);
    }

    private static void lostAcrossRead(final OrganizationFixture store) throws Exception {
        lostKeyHeldTwice(store);
        store.read();
        final String attack = store.adminWithLostKey(ADMIN, PROJECT, STRANGER);

        final OrganizationQuestions answers = store.read();

        noAdmin(answers, STRANGER);
        saidAmongOthers(answers, attack);
    }

    /**
     * An owner is named by key in the list of owners, which a replaced card does not change, so
     * only the revocation stops the lost key here.
     */
    private static void ownersLostKey(final OrganizationFixture store) throws Exception {
        store.found(OWNER);
        store.fileCard(OWNER);
        store.fileCard(SECOND_OWNER);
        store.fileCard(STRANGER);
        store.addOwner(OWNER, SECOND_OWNER);
        store.replaceLostKey(SECOND_OWNER, OWNER);
        final String attack = store.ownerWithLostKey(OWNER, STRANGER);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.trusts(OWNER, store.key(OWNER)).answer())
                .as("the replacement was accepted, so that the key really was revoked")
                .isEqualTo(Trust.YES);
        assertThat(answers.isOrganizationOwner(STRANGER))
                .as("a revoked key is no owner's, though the list of owners still names it")
                .isFalse();
        saidAmongOthers(answers, attack);
    }

    private static void playedBack(final OrganizationFixture store) throws Exception {
        project(store);
        final String lost = store.key(ADMIN);
        store.replaceLostKey(OWNER, ADMIN);
        final String current = store.key(ADMIN);
        final String attack = store.playBackOlderCard(ADMIN);

        final OrganizationQuestions answers = store.read();

        assertThat(current).as("the replacement is another key").isNotEqualTo(lost);
        assertThat(answers.trusts(ADMIN, lost).answer())
                .as("the card played back gives the lost key nothing")
                .isNotEqualTo(Trust.YES);
        assertThat(answers.keyHolder(lost)).as("nobody holds the lost key").isEmpty();
        assertThat(answers.trusts(ADMIN, current).answer())
                .as("the replacement still holds")
                .isEqualTo(Trust.YES);
        saidOnly(answers, attack);
    }

    private static void cardTaken(final OrganizationFixture store, final boolean buried)
            throws Exception {
        project(store);
        final String own = store.key(ADMIN);
        final String attack = store.cardNobodyAllowed(STRANGER, ADMIN);
        bury(store, buried);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.trusts(ADMIN, store.key(STRANGER)).answer())
                .as("the stranger's key is not this person's")
                .isNotEqualTo(Trust.YES);
        assertThat(answers.trusts(ADMIN, own).answer())
                .as("the person keeps their own card")
                .isEqualTo(Trust.YES);
        saidOnly(answers, attack);
    }

    /** A card once signed could otherwise be played back by anybody, as its person's first. */
    private static void filedForAnother(final OrganizationFixture store, final String signer)
            throws Exception {
        project(store);
        final String attack = store.cardFiledByAnother(signer, DENIED);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.trusts(DENIED, store.key(DENIED)).answer())
                .as("a person's first card counts from their own key alone")
                .isNotEqualTo(Trust.YES);
        saidOnly(answers, attack);
    }

    private static void cardRemoved(final OrganizationFixture store) throws Exception {
        project(store);
        final String own = store.key(ADMIN);
        final String attack = store.removeCardNobodyAllowed(STRANGER, ADMIN);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.trusts(ADMIN, own).answer())
                .as("the card stands, so the person still acts")
                .isEqualTo(Trust.YES);
        assertThat(answers.isProjectMember(PROJECT, ADMIN))
                .as("and keeps their role")
                .contains("admin");
        saidOnly(answers, attack);
    }

    private static void replaced(final OrganizationFixture store) throws Exception {
        project(store);
        final String own = store.key(ADMIN);
        final String attack = store.replacementNobodyAllowed(STRANGER, ADMIN);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.trusts(ADMIN, own).answer())
                .as("only an owner replaces a lost key")
                .isEqualTo(Trust.YES);
        saidOnly(answers, attack);
    }

    private static void denied(final OrganizationFixture store) throws Exception {
        project(store);
        final String attack = store.fileCardTheSourceDenies(DENIED);
        store.addMember(ADMIN, PROJECT, DENIED, "admin");

        final OrganizationQuestions answers = store.read();

        assertThat(answers.isProjectMember(PROJECT, DENIED))
                .as("a card the identity source denies gives no role")
                .isEmpty();
        assertThat(answers.trusts(DENIED, store.key(DENIED)).answer())
                .as("and is not trusted")
                .isNotEqualTo(Trust.YES);
        saidOnly(answers, attack);
    }

    private static void squatted(final OrganizationFixture store) throws Exception {
        project(store);
        final String attack = store.squatFirstCard(STRANGER, SQUATTED);
        store.fileCard(SQUATTED);

        final OrganizationQuestions answers = store.read();

        assertThat(answers.trusts(SQUATTED, store.key(STRANGER)).answer())
                .as("the squatter's key holds nothing in that name")
                .isNotEqualTo(Trust.YES);
        assertThat(answers.trusts(SQUATTED, store.key(SQUATTED)).answer())
                .as("the login's own card still counts")
                .isEqualTo(Trust.YES);
        assertThat(refusedRevisions(answers))
                .as("the squat is refused or given way to, and nothing else was")
                .isSubsetOf(attack);
    }

    private static void older(final OrganizationFixture store) throws Exception {
        projectWithDeveloper(store);
        store.read();
        store.serveOlderState();

        refusedState(store);
    }

    private static void olderTwice(final OrganizationFixture store) throws Exception {
        projectWithDeveloper(store);
        store.read();
        store.serveOlderState();

        refusedState(store);
        refusedState(store);
    }

    private static void rewritten(final OrganizationFixture store) throws Exception {
        projectWithDeveloper(store);
        store.read();
        store.rewriteHistory();

        refusedState(store);
    }

    private static void discarded(final OrganizationFixture store) throws Exception {
        project(store);
        store.replaceLostKey(OWNER, ADMIN);
        store.read();
        store.discardRevocation();

        refusedState(store);
    }
}
