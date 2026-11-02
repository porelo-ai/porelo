package org.fuin.porelo.organization.contract;

import java.util.Set;

/** What a broken stand-in does that it must not, and exactly which write checks must then fail. */
enum WriteBreak {
    ANY_SIGNER(
            Set.of(
                    "aDeveloperAddsNoMember",
                    "aDeveloperCannotRaiseThemselves",
                    "aStrangerAddsNoHost",
                    "aStrangerAddsNoOwner",
                    "aStrangerAddsNoProject",
                    "aStrangerFilesNoOwnersBackup",
                    "aStrangerMovesNoProjectsAddress",
                    "aStrangerRemovesNoProject",
                    "aStrangerReplacesNoCard",
                    "aStrangerWithdrawsNoCard",
                    "anAdminHasNoRightInAnotherProject",
                    "anOwnerAddsNoDeveloper",
                    "anOwnerIsNoAdminOfAHostOfSomeoneElse",
                    "anOwnerRemovesNoDeveloper")),
    IGNORES_ACTING_KEY(Set.of("aKeyNotOnThePersonsCardActsForNobody", "aReplacedKeyActsNoMore")),
    ACCEPTS_LOST_KEY(Set.of("aReplacedKeyActsNoMore", "aWithdrawnCardsKeyActsNoMore")),
    ACCEPTS_ANY_CARD(
            Set.of("aCardOfAnotherKeyThanTheActingOneIsRefused", "aCardOfAnotherLoginIsRefused")),
    IGNORES_BASE(Set.of("aBaseTheStoreMovedPastIsRefused")),
    MAKES_AN_ID_TWICE(
            Set.of(
                    "aBaseTheStoreMovedPastIsRefused",
                    "anIdAskedAgainForAnotherChangeIsRefused",
                    "anIdAskedAgainIsAnsweredAndNotMadeTwice")),
    TAKES_ANOTHER_PERSONS_ID(
            Set.of(
                    "anIdAskedAgainForAnotherChangeIsRefused",
                    "anotherPersonsIdIsNotThisPersonsChange")),
    ANSWERS_ANOTHER_CHANGE_AS_MADE(Set.of("anIdAskedAgainForAnotherChangeIsRefused")),
    REMOVES_THE_LAST(
            Set.of(
                    "theLastAdminStaysForItsAdmins",
                    "theLastOwnerIsNotRemoved",
                    "theOnlyOwnersCardIsNotWithdrawn")),
    FOUNDS_AGAIN(Set.of("aFoundedOrganizationIsNotFoundedAgain")),
    FOUNDS_FOR_ANYONE(Set.of("someoneTheSourceSaysOwnsNothingFoundsNothing")),
    OWNER_ADDS_DEVELOPER(Set.of("anOwnerAddsNoDeveloper", "anOwnerRemovesNoDeveloper")),
    LOCKED_ADMIN_ACTS(Set.of("aLockedAdminChangesNothing")),
    ADMIN_OF_ANY_PROJECT(Set.of("anAdminHasNoRightInAnotherProject")),
    OWNER_ON_ANY_HOST(Set.of("anOwnerIsNoAdminOfAHostOfSomeoneElse")),
    MOVES_ON_THOUGH_REFUSED(
            Set.of(
                    "aCardHandedOnByAnUnrelatedKeyHandsNothingOn",
                    "aCardNotSignedByTheOldKeyHandsNothingOn",
                    "aCardOfAnotherKeyThanTheActingOneIsRefused",
                    "aCardOfAnotherLoginIsRefused",
                    "aChangeThatWouldChangeNothingIsRefusedNotAnsweredAsMade",
                    "aDeveloperAddsNoMember",
                    "aDeveloperCannotRaiseThemselves",
                    "aFoundedOrganizationIsNotFoundedAgain",
                    "aKeyNotOnThePersonsCardActsForNobody",
                    "aKeyThatBacksNoOwnerTakesNoPlace",
                    "aLockedAdminChangesNothing",
                    "aStrangerAddsNoHost",
                    "aStrangerAddsNoOwner",
                    "aStrangerAddsNoProject",
                    "aStrangerFilesNoOwnersBackup",
                    "aStrangerMovesNoProjectsAddress",
                    "aStrangerRemovesNoProject",
                    "aStrangerReplacesNoCard",
                    "aStrangerWithdrawsNoCard",
                    "aWithdrawnCardsKeyActsNoMore",
                    "anAdminHasNoRightInAnotherProject",
                    "anOwnerAddsNoDeveloper",
                    "anOwnerIsNoAdminOfAHostOfSomeoneElse",
                    "anOwnerRemovesNoDeveloper",
                    "theLastAdminStaysForItsAdmins",
                    "theLastOwnerIsNotRemoved",
                    "theOnlyOwnersCardIsNotWithdrawn")),
    LOSES_THE_CHANGE(
            Set.of(
                    "aBackupKeyTakesItsOwnersPlace",
                    "aBaseTheStoreMovedPastIsRefused",
                    "aChangeThatWouldChangeNothingIsRefusedNotAnsweredAsMade",
                    "aDeveloperCannotRaiseThemselves",
                    "aKeyHandedOnKeepsWhatItDid",
                    "aLockedAdminChangesNothing",
                    "aReplacedKeyActsNoMore",
                    "aWithdrawnCardsKeyActsNoMore",
                    "anAddedMemberIsReadBackWithTheirRole",
                    "anAddedOwnerIsReadBackAndMayAct",
                    "theSourcesWordStandsForAnOldKeyItNoLongerLists",
                    "whatAReplacedKeyDidIsGoneForANewReaderUntilItIsAddedAgain")),
    KEEPS_WHAT_A_REPLACED_KEY_DID(
            Set.of(
                    "aChangeThatWouldChangeNothingIsRefusedNotAnsweredAsMade",
                    "whatAReplacedKeyDidIsGoneForANewReaderUntilItIsAddedAgain")),
    LOSES_WHAT_A_HANDED_ON_KEY_DID(Set.of("aKeyHandedOnKeepsWhatItDid")),
    ANSWERS_NOTHING_AS_MADE(Set.of("aChangeThatWouldChangeNothingIsRefusedNotAnsweredAsMade")),
    ANY_KEY_TAKES_A_PLACE(Set.of("aKeyThatBacksNoOwnerTakesNoPlace")),
    HANDS_ON_WITHOUT_THE_OLD_KEY(
            Set.of(
                    "aCardHandedOnByAnUnrelatedKeyHandsNothingOn",
                    "aCardNotSignedByTheOldKeyHandsNothingOn")),
    REFUSES_THE_SOURCES_WORD(Set.of("theSourcesWordStandsForAnOldKeyItNoLongerLists")),
    FOUNDS_WITH_A_SECOND_OWNER(
            Set.of(
                    "aBackupKeyTakesItsOwnersPlace",
                    "aFoundingOwnerIsTheOnlyOwnerAndAFreshReaderTrustsThem",
                    "theLastOwnerIsNotRemoved",
                    "theOnlyOwnersCardIsNotWithdrawn")),
    READER_REFUSES_WHAT_IT_WROTE(
            Set.of(
                    "aBaseTheStoreMovedPastIsRefused",
                    "aDeveloperAddsNoMember",
                    "aDeveloperCannotRaiseThemselves",
                    "aFirstCardIsTrustedByAFreshReader",
                    "aKeyHandedOnKeepsWhatItDid",
                    "aKeyNotOnThePersonsCardActsForNobody",
                    "aKeyThatBacksNoOwnerTakesNoPlace",
                    "aLockedAdminChangesNothing",
                    "aLockedMemberIsNoMember",
                    "aRemovedMemberIsGone",
                    "aStrangerAddsNoHost",
                    "aStrangerAddsNoOwner",
                    "aStrangerAddsNoProject",
                    "aStrangerFilesNoOwnersBackup",
                    "aStrangerMovesNoProjectsAddress",
                    "aStrangerRemovesNoProject",
                    "aStrangerReplacesNoCard",
                    "aStrangerWithdrawsNoCard",
                    "aWithdrawnCardsKeyActsNoMore",
                    "anAddedHostHasItsAdminAndItsProject",
                    "anAddedMemberIsReadBackWithTheirRole",
                    "anAddedOwnerIsReadBackAndMayAct",
                    "anAddedProjectHasItsFirstAdmin",
                    "anAdminHasNoRightInAnotherProject",
                    "anIdAskedAgainForAnotherChangeIsRefused",
                    "anOwnerAddsNoDeveloper",
                    "anOwnerIsNoAdminOfAHostOfSomeoneElse",
                    "anOwnerRemovesNoDeveloper",
                    "theLastAdminStaysForItsAdmins",
                    "whatAReplacedKeyDidIsGoneForANewReaderUntilItIsAddedAgain"));

    final Set<String> checks;

    WriteBreak(final Set<String> checks) {
        this.checks = checks;
    }
}
