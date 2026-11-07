package org.fuin.porelo.organization.contract;

import java.util.Set;

/** What a broken stand-in accepts that it must refuse, and exactly which checks must then fail. */
enum Break {
    NONE_OF_A_MEMBERS_CHANGES(
            Set.of(
                    "aDeveloperCannotRaiseThemselves",
                    "anAllowedChangeIsAnswered",
                    "anOwnerUnlocksNoMember"),
            Set.of("aHostIsGivenNoKeyOfAFirstSeenPerson")),
    ACCEPTS_UNSIGNED(
            Set.of(
                    "anUnsignedChangeGivesNoAdminRight",
                    "anUnsignedChangeMakesNoOwner",
                    "anUnsignedChangeUnderALaterOneGivesNoRight"),
            Set.of()),
    ACCEPTS_ANY_SIGNER(
            Set.of(
                    "aDeveloperCannotRaiseThemselves",
                    "aLockedAdminChangesNothing",
                    "aReplacementByANonOwnerHoldsNothing",
                    "aStrangerMakesNoOwner",
                    "aStrangerUnderALaterChangeMakesNoOwner",
                    "aStrangersChangeGivesNoAdminRight",
                    "aStrangersChangeUnderALaterOneGivesNoRight",
                    "anAdminHasNoRightInAnotherProject",
                    "anOwnerAddsNoDeveloper",
                    "anOwnerLocksNoAdmin",
                    "anOwnerUnlocksNoMember"),
            Set.of()),
    ACCEPTS_REVOKED_KEY(
            Set.of(
                    "aLostKeySignsNothing",
                    "aLostKeyStaysLostAcrossARead",
                    "aLostKeyUnderALaterChangeSignsNothing",
                    "anOwnersLostKeyMakesNoOwner"),
            Set.of()),
    FORGETS_REVOCATION_ACROSS_A_READ(Set.of("aLostKeyStaysLostAcrossARead"), Set.of()),
    ACCEPTS_PLAYED_BACK_CARD(Set.of("anOlderCardPlayedBackHoldsNothing"), Set.of()),
    ACCEPTS_OLDER_STATE(
            Set.of("anOlderStateServedAgainIsRefused", "anOlderStateIsRefusedEachTime"), Set.of()),
    FORGETS_ON_REFUSAL(Set.of("anOlderStateIsRefusedEachTime"), Set.of()),
    ACCEPTS_REWRITTEN_HISTORY(Set.of("aRewrittenHistoryIsRefused"), Set.of()),
    ACCEPTS_DISCARDED_REVOCATION(Set.of("aDiscardedRevocationIsRefused"), Set.of()),
    SAYS_NOTHING_OF_A_REFUSAL(
            Set.of(
                    "aCardFiledByAStrangerHoldsNothing",
                    "aCardFiledByAnOwnerHoldsNothing",
                    "aCardRemovedByAStrangerStands",
                    "aCardTakenOverHoldsNothing",
                    "aCardTakenOverUnderALaterChangeHoldsNothing",
                    "aCardTheSourceDeniesGivesNoRight",
                    "aDeveloperCannotRaiseThemselves",
                    "aLockedAdminChangesNothing",
                    "aLostKeySignsNothing",
                    "aLostKeyStaysLostAcrossARead",
                    "aLostKeyUnderALaterChangeSignsNothing",
                    "aReplacementByANonOwnerHoldsNothing",
                    "aStrangerMakesNoOwner",
                    "aStrangerUnderALaterChangeMakesNoOwner",
                    "aStrangersChangeGivesNoAdminRight",
                    "aStrangersChangeUnderALaterOneGivesNoRight",
                    "anAdminHasNoRightInAnotherProject",
                    "anOlderCardPlayedBackHoldsNothing",
                    "anOwnerAddsNoDeveloper",
                    "anOwnerLocksNoAdmin",
                    "anOwnerUnlocksNoMember",
                    "anOwnersLostKeyMakesNoOwner",
                    "anUnsignedChangeGivesNoAdminRight",
                    "anUnsignedChangeMakesNoOwner",
                    "anUnsignedChangeUnderALaterOneGivesNoRight"),
            Set.of()),
    JUDGES_ONLY_THE_HEAD(
            Set.of(
                    "aCardTakenOverUnderALaterChangeHoldsNothing",
                    "aCardTheSourceDeniesGivesNoRight",
                    "aLostKeyUnderALaterChangeSignsNothing",
                    "aSquattedFirstCardGivesWay",
                    "aStrangerUnderALaterChangeMakesNoOwner",
                    "aStrangersChangeUnderALaterOneGivesNoRight",
                    "anUnsignedChangeUnderALaterOneGivesNoRight"),
            Set.of(
                    "aStrangersHostChangeUnderALaterOneIsRefused",
                    "anUnsignedHostChangeUnderALaterOneIsRefused")),
    NAMES_ANOTHER_REVISION(
            Set.of(
                    "aCardFiledByAStrangerHoldsNothing",
                    "aCardFiledByAnOwnerHoldsNothing",
                    "aCardRemovedByAStrangerStands",
                    "aCardTakenOverHoldsNothing",
                    "aCardTakenOverUnderALaterChangeHoldsNothing",
                    "aCardTheSourceDeniesGivesNoRight",
                    "aDeveloperCannotRaiseThemselves",
                    "aLockedAdminChangesNothing",
                    "aLostKeySignsNothing",
                    "aLostKeyStaysLostAcrossARead",
                    "aLostKeyUnderALaterChangeSignsNothing",
                    "aReplacementByANonOwnerHoldsNothing",
                    "aSquattedFirstCardGivesWay",
                    "aStrangerMakesNoOwner",
                    "aStrangerUnderALaterChangeMakesNoOwner",
                    "aStrangersChangeGivesNoAdminRight",
                    "aStrangersChangeUnderALaterOneGivesNoRight",
                    "anAdminHasNoRightInAnotherProject",
                    "anOlderCardPlayedBackHoldsNothing",
                    "anOwnerAddsNoDeveloper",
                    "anOwnerLocksNoAdmin",
                    "anOwnerUnlocksNoMember",
                    "anOwnersLostKeyMakesNoOwner",
                    "anUnsignedChangeGivesNoAdminRight",
                    "anUnsignedChangeMakesNoOwner",
                    "anUnsignedChangeUnderALaterOneGivesNoRight"),
            Set.of()),
    ADMIN_OF_ANY_PROJECT(Set.of("anAdminHasNoRightInAnotherProject"), Set.of()),
    ACCEPTS_LOCKED_ADMIN(Set.of("aLockedAdminChangesNothing"), Set.of()),
    OWNER_NAMES_NO_ADMIN(Set.of("anOwnerMayNameAnAdmin"), Set.of()),
    OWNER_ADDS_DEVELOPER(Set.of("anOwnerAddsNoDeveloper"), Set.of()),
    OWNER_TOUCHES_LOCKS(Set.of("anOwnerLocksNoAdmin", "anOwnerUnlocksNoMember"), Set.of()),
    ACCEPTS_CARD_TAKEN_OVER(
            Set.of("aCardTakenOverHoldsNothing", "aCardTakenOverUnderALaterChangeHoldsNothing"),
            Set.of()),
    ACCEPTS_REMOVAL_BY_ANYONE(Set.of("aCardRemovedByAStrangerStands"), Set.of()),
    ACCEPTS_REPLACEMENT_BY_ANYONE(Set.of("aReplacementByANonOwnerHoldsNothing"), Set.of()),
    ACCEPTS_CARD_THE_SOURCE_DENIES(Set.of("aCardTheSourceDeniesGivesNoRight"), Set.of()),
    ACCEPTS_SQUATTED_CARD(Set.of("aSquattedFirstCardGivesWay"), Set.of()),
    ACCEPTS_CARD_FILED_BY_ANOTHER(
            Set.of("aCardFiledByAStrangerHoldsNothing", "aCardFiledByAnOwnerHoldsNothing"),
            Set.of()),
    OFFLINE_ANSWERS_NOTHING(
            Set.of(),
            Set.of(
                    "aHostCarryingSomeRepositoriesIsToldWhichOnes",
                    "aHostIsGivenNoKeyOfAFirstSeenPerson",
                    "thePinFollowsAHandoverItSigned",
                    "thePinnedKeyAnswersTheHostsState")),
    OFFLINE_OWNER_PASSES_THE_PIN(Set.of(), Set.of("anOwnersChangeIsRefusedUnderThePin")),
    OFFLINE_TRUSTS_THE_WHOLE_SET(Set.of(), Set.of("aStrangersKeyBesideThePinSignsNothing")),
    OFFLINE_PIN_STAYS_PUT(Set.of(), Set.of("thePinFollowsAHandoverItSigned")),
    OFFLINE_GIVES_FIRST_SEEN_ADMIN(Set.of(), Set.of("aHostIsGivenNoKeyOfAFirstSeenPerson")),
    OFFLINE_GIVES_FIRST_SEEN_PERSON(Set.of(), Set.of("aHostIsGivenNoKeyOfAFirstSeenPerson")),
    OFFLINE_GIVES_FIRST_SEEN_SIGNER(Set.of(), Set.of("aHostIsGivenNoKeyOfAFirstSeenPerson")),
    OFFLINE_DROPS_A_FIRST_SEEN_ROLE(Set.of(), Set.of("aHostIsGivenNoKeyOfAFirstSeenPerson")),
    OFFLINE_CARRIES_WHOLE(Set.of(), Set.of("aHostCarryingSomeRepositoriesIsToldWhichOnes")),
    OFFLINE_REFUSES_THE_PIN(
            Set.of(),
            Set.of(
                    "aHostCarryingSomeRepositoriesIsToldWhichOnes",
                    "aHostIsGivenNoKeyOfAFirstSeenPerson",
                    "aRewrittenHistoryIsRefused",
                    "anOlderStateThanTheOneAppliedIsRefused",
                    "anOwnersChangeIsRefusedUnderThePin",
                    "thePinFollowsAHandoverItSigned",
                    "thePinnedKeyAnswersTheHostsState")),
    OFFLINE_IGNORES_THE_PIN(Set.of(), Set.of("anotherPersonsKeyPinnedIsRefused")),
    OFFLINE_ACCEPTS_UNSIGNED(
            Set.of(),
            Set.of("anUnsignedHostChangeIsRefused", "anUnsignedHostChangeUnderALaterOneIsRefused")),
    OFFLINE_ACCEPTS_ANY_SIGNER(
            Set.of(),
            Set.of(
                    "aStrangersHostChangeIsRefused",
                    "aStrangersHostChangeUnderALaterOneIsRefused",
                    "aStrangersKeyBesideThePinSignsNothing",
                    "anOwnersChangeIsRefusedUnderThePin")),
    OFFLINE_ACCEPTS_OLDER_STATE(Set.of(), Set.of("anOlderStateThanTheOneAppliedIsRefused")),
    OFFLINE_ACCEPTS_REWRITTEN_HISTORY(Set.of(), Set.of("aRewrittenHistoryIsRefused"));

    final Set<String> organizationChecks;

    final Set<String> offlineChecks;

    Break(final Set<String> organizationChecks, final Set<String> offlineChecks) {
        this.organizationChecks = organizationChecks;
        this.offlineChecks = offlineChecks;
    }
}
