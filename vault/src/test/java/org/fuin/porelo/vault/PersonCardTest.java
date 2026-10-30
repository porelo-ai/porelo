package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A person's card and the chain of its changes of key. Harm guarded: a card taken on its word lets
 * whoever can write a file speak as somebody else; a change of key signed by one key alone hands an
 * identity to a stranger or to a key nobody holds.
 */
class PersonCardTest {

    private static final LocalDate MADE = LocalDate.parse("2026-10-30");

    private static final LocalDate ENDS = LocalDate.parse("2027-10-30");

    private final SigningKey first = SigningKey.generate("first");

    private final SigningKey second = SigningKey.generate("second");

    private final SigningKey third = SigningKey.generate("third");

    private PersonCard card(final SigningKey own, final @Nullable SigningKey predecessor) {
        return PersonCard.read(
                PersonCard.write("alice", "Alice", "", own, MADE, ENDS, predecessor), "test");
    }

    @Test
    void reads_what_it_writes_with_every_field() {

        final PersonCard card =
                PersonCard.read(
                        PersonCard.write(
                                "alice",
                                "Alice \"Al\" Example",
                                "alice@example.org",
                                first,
                                MADE,
                                ENDS,
                                null),
                        "test");

        assertThat(card.login()).isEqualTo("alice");
        assertThat(card.name()).isEqualTo("Alice \"Al\" Example");
        assertThat(card.email()).isEqualTo("alice@example.org");
        assertThat(card.key()).isEqualTo(PersonCard.keyLine(first));
        assertThat(card.created()).isEqualTo(MADE);
        assertThat(card.expires()).isEqualTo(ENDS);
        assertThat(card.predecessor()).isEmpty();
        assertThat(card.signedAsItMustBe()).isTrue();
    }

    @Test
    void a_body_changed_after_signing_is_not_signed() {
        final String file = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);

        // Same key, another name: the signature covers the body's exact bytes.
        final PersonCard forged = PersonCard.read(file.replace("\"Alice\"", "\"Mallory\""), "test");

        assertThat(forged.signedAsItMustBe()).isFalse();
    }

    @Test
    void a_card_signed_by_another_key_is_not_signed() {
        final String other = PersonCard.write("alice", "Alice", "", second, MADE, ENDS, null);
        final String mine = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);
        final String body = mine.substring(0, mine.indexOf("-----BEGIN"));

        final PersonCard forged =
                PersonCard.read(body + other.substring(other.indexOf("-----BEGIN")), "test");

        assertThat(forged.signedAsItMustBe()).isFalse();
    }

    @Test
    void a_change_of_key_needs_both_signatures() {
        final String both = PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first);
        final int secondSignature = both.indexOf("-----BEGIN", both.indexOf("-----END"));

        assertThat(PersonCard.read(both, "test").signedAsItMustBe()).isTrue();
        assertThat(PersonCard.read(both.substring(0, secondSignature), "test").signedAsItMustBe())
                .as("the new key alone")
                .isFalse();
    }

    @Test
    void a_change_of_key_signed_by_the_old_key_alone_is_not_signed() {
        final String both = PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first);
        final int firstSignature = both.indexOf("-----BEGIN");
        final int secondSignature = both.indexOf("-----BEGIN", both.indexOf("-----END"));

        final PersonCard oldAlone =
                PersonCard.read(
                        both.substring(0, firstSignature) + both.substring(secondSignature),
                        "test");

        assertThat(oldAlone.signedAsItMustBe()).isFalse();
    }

    @Test
    void refuses_a_merge_key_an_anchor_and_a_tag() {
        final String card = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);
        final String key = PersonCard.keyLine(second);

        for (final String changed :
                List.of(
                        card.replace(
                                "name: \"Alice\"\n",
                                "name: \"Alice\"\n<<: {key: \"" + key + "\"}\n"),
                        card.replace("name: \"Alice\"", "name: &n \"Alice\"\nemail: *n"),
                        card.replace("name: \"Alice\"", "name: !!str \"Alice\""),
                        card.replace("name: \"Alice\"", "name: [\"Alice\"]"))) {
            assertThatThrownBy(() -> PersonCard.read(changed, "test"))
                    .as(changed)
                    .isInstanceOf(CardException.class);
        }
    }

    @Test
    void refuses_a_key_that_is_not_one_line_and_a_name_with_a_control_character() {
        final String card = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);
        final String key = PersonCard.keyLine(first);

        for (final String changed :
                List.of(
                        card.replace("key: \"" + key, "key: \"\\n" + key),
                        card.replace("key: \"" + key, "key: \"" + key + "\\u2000"),
                        card.replace("name: \"Alice\"", "name: \"Alice\\e[2J\""),
                        card.replace("name: \"Alice\"", "name: \"Alice\\r\\nREVOKED\""))) {
            assertThatThrownBy(() -> PersonCard.read(changed, "test"))
                    .as(changed)
                    .isInstanceOf(CardException.class);
        }
    }

    @Test
    void refuses_a_day_that_is_no_day_or_carries_a_time() {
        final String card = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);

        for (final String changed :
                List.of(
                        card.replace("expires: \"2027-10-30\"", "expires: 2027-02-31"),
                        card.replace("expires: \"2027-10-30\"", "expires: \"2027-02-31\""),
                        card.replace(
                                "expires: \"2027-10-30\"", "expires: 2027-10-30T23:00:00-05:00"))) {
            assertThatThrownBy(() -> PersonCard.read(changed, "test"))
                    .as(changed)
                    .isInstanceOf(CardException.class);
        }
    }

    @Test
    void an_unquoted_day_is_read_as_written() {
        final String card = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);

        final PersonCard read =
                PersonCard.read(
                        card.replace("expires: \"2027-10-30\"", "expires: 2027-10-30"), "test");

        assertThat(read.expires()).isEqualTo(ENDS);
    }

    @Test
    void the_earlier_cards_lead_back_in_any_order() {
        final PersonCard middle = card(second, first);
        // The same key's later card, which made no change of key: it cannot lead back.
        final PersonCard renewed = card(second, null);
        final PersonCard last = card(third, second);
        final String pinned = PersonCard.fingerprint(PersonCard.keyLine(first));

        assertThat(PersonCardChain.check(last, List.of(renewed, middle), pinned)).hasSize(3);
        assertThat(PersonCardChain.check(last, List.of(middle, renewed), pinned)).hasSize(3);
    }

    @Test
    void a_change_of_key_signed_twice_by_one_key_is_not_signed() {
        final String both = PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first);
        final int firstSignature = both.indexOf("-----BEGIN");
        final int secondSignature = both.indexOf("-----BEGIN", both.indexOf("-----END"));
        final String newOnly = both.substring(firstSignature, secondSignature);
        final String oldOnly = both.substring(secondSignature);
        final String body = both.substring(0, firstSignature);

        assertThat(PersonCard.read(body + newOnly + newOnly, "test").signedAsItMustBe())
                .as("the new key twice")
                .isFalse();
        assertThat(PersonCard.read(body + oldOnly + oldOnly, "test").signedAsItMustBe())
                .as("the old key twice")
                .isFalse();
    }

    @Test
    void refuses_a_field_given_twice_and_an_anchor_alone() {
        final String card = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);

        for (final String changed :
                List.of(
                        card.replace("name: \"Alice\"", "name: \"Alice\"\nname: \"Mallory\""),
                        card.replace("name: \"Alice\"", "name: &n \"Alice\""))) {
            assertThatThrownBy(() -> PersonCard.read(changed, "test"))
                    .as(changed)
                    .isInstanceOf(CardException.class);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(
            value = 10,
            threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void many_copies_of_each_earlier_card_are_walked_once() {
        final List<SigningKey> keys = new java.util.ArrayList<>();
        for (int at = 0; at <= 26; at++) {
            keys.add(SigningKey.generate("key" + at));
        }
        final List<PersonCard> earlier = new java.util.ArrayList<>();
        // Every step but the first given twice: walked naively, that is 2^25 tries.
        for (int at = 1; at < 26; at++) {
            earlier.add(card(keys.get(at + 1), keys.get(at)));
            earlier.add(card(keys.get(at + 1), keys.get(at)));
        }

        assertThatThrownBy(
                        () ->
                                PersonCardChain.check(
                                        earlier.getLast(),
                                        earlier,
                                        PersonCard.fingerprint(PersonCard.keyLine(keys.get(0)))))
                .isInstanceOf(CardException.class);
    }

    @Test
    void cards_that_lead_in_a_circle_are_refused() {
        final PersonCard there = card(third, second);
        final PersonCard back = card(second, third);

        assertThatThrownBy(
                        () ->
                                PersonCardChain.check(
                                        there,
                                        List.of(back, there),
                                        PersonCard.fingerprint(PersonCard.keyLine(first))))
                .isInstanceOf(CardException.class);
    }

    @Test
    void a_revoked_key_is_walked_around_whatever_the_order() {
        final SigningKey fourth = SigningKey.generate("fourth");
        final PersonCard toSecond = card(second, first);
        final PersonCard viaSecond = card(third, second);
        // After the second key was revoked, the third was certified again from the first.
        final PersonCard fromFirst = card(third, first);
        final PersonCard last = card(fourth, third);
        final String pinned = PersonCard.fingerprint(PersonCard.keyLine(first));
        final String revoked = toSecond.fingerprint();

        for (final List<PersonCard> earlier :
                List.of(
                        List.of(toSecond, viaSecond, fromFirst),
                        List.of(toSecond, fromFirst, viaSecond))) {
            assertThat(PersonCardChain.check(last, earlier, pinned, revoked::equals))
                    .containsExactly(pinned, fromFirst.fingerprint(), last.fingerprint());
        }
        assertThatThrownBy(
                        () ->
                                PersonCardChain.check(
                                        last,
                                        List.of(toSecond, viaSecond),
                                        pinned,
                                        revoked::equals))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void many_dead_ends_before_the_way_back_do_not_hide_it() {
        final SigningKey end = SigningKey.generate("end");
        final PersonCard last = card(end, third);
        final List<PersonCard> earlier = new java.util.ArrayList<>();
        // More dead ends than any chain has steps, each tried before the card that leads back.
        for (int at = 0; at < 70; at++) {
            earlier.add(card(third, SigningKey.generate("dead" + at)));
        }
        earlier.add(card(third, second));
        earlier.add(card(second, first));
        final String pinned = PersonCard.fingerprint(PersonCard.keyLine(first));

        assertThat(PersonCardChain.check(last, earlier, pinned)).hasSize(4).startsWith(pinned);
    }

    @Test
    void a_card_given_twice_keeps_the_reason_it_was_refused() {
        final SigningKey fourth = SigningKey.generate("fourth");
        final SigningKey fifth = SigningKey.generate("fifth");
        final PersonCard toThird = card(fourth, third);
        final PersonCard last = card(fifth, fourth);
        final String pinned = PersonCard.fingerprint(PersonCard.keyLine(first));

        // The second key's own card is missing; the copy must not turn that into a circle.
        assertThatThrownBy(
                        () ->
                                PersonCardChain.check(
                                        last,
                                        List.of(toThird, toThird, card(third, second)),
                                        pinned))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("is needed to lead back");
    }

    @Test
    void a_key_refused_too_deep_is_tried_again_nearer_the_card() {
        final SigningKey from = SigningKey.generate("from");
        final SigningKey via = SigningKey.generate("via");
        final SigningKey to = SigningKey.generate("to");
        final PersonCard last = card(SigningKey.generate("last"), from);
        final List<PersonCard> earlier = new java.util.ArrayList<>();
        // A long way round reaches the key 'via' past the most changes a chain may have ...
        SigningKey at = from;
        for (int step = 0; step < 62; step++) {
            final SigningKey next = SigningKey.generate("dead" + step);
            earlier.add(card(at, next));
            at = next;
        }
        earlier.add(card(at, via));
        earlier.add(card(via, to));
        earlier.add(card(to, first));
        // ... and a short one, given last, reaches it too.
        earlier.add(card(from, via));
        final String pinned = PersonCard.fingerprint(PersonCard.keyLine(first));

        assertThat(PersonCardChain.check(last, earlier, pinned)).hasSize(5).startsWith(pinned);
    }

    @Test
    void a_chain_may_make_sixty_four_changes_of_key_and_no_more() {
        final List<SigningKey> keys = new java.util.ArrayList<>();
        for (int at = 0; at <= 65; at++) {
            keys.add(SigningKey.generate("key" + at));
        }
        final List<PersonCard> earlier = new java.util.ArrayList<>();
        for (int at = 1; at < 65; at++) {
            earlier.add(card(keys.get(at), keys.get(at - 1)));
        }
        final String pinned = PersonCard.fingerprint(PersonCard.keyLine(keys.get(0)));

        assertThat(PersonCardChain.check(card(keys.get(64), keys.get(63)), earlier, pinned))
                .as("64 changes")
                .hasSize(65);
        assertThatThrownBy(
                        () ->
                                PersonCardChain.check(
                                        card(keys.get(65), keys.get(64)), earlier, pinned))
                .as("65 changes")
                .isInstanceOf(CardException.class)
                .hasMessageContaining("more than 64");
    }

    @Test
    void a_chain_of_changes_leads_back_to_the_pinned_key() {
        final PersonCard middle = card(second, first);
        final PersonCard last = card(third, second);

        assertThat(
                        PersonCardChain.check(
                                last,
                                List.of(middle),
                                PersonCard.fingerprint(PersonCard.keyLine(first))))
                .containsExactly(
                        PersonCard.fingerprint(PersonCard.keyLine(first)),
                        middle.fingerprint(),
                        last.fingerprint());
    }

    @Test
    void a_card_whose_chain_misses_the_pinned_key_is_refused() {
        final PersonCard stranger = card(third, second);

        assertThatThrownBy(
                        () ->
                                PersonCardChain.check(
                                        stranger,
                                        List.of(),
                                        PersonCard.fingerprint(PersonCard.keyLine(first))))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("needed to lead back");
    }

    @Test
    void a_stranger_naming_the_pinned_key_as_predecessor_is_refused() {
        final String honest = PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first);
        // Only the stranger's own signature: the pinned key never agreed to the change.
        final PersonCard claimed =
                PersonCard.read(
                        honest.substring(
                                0, honest.indexOf("-----BEGIN", honest.indexOf("-----END"))),
                        "test");

        assertThatThrownBy(
                        () ->
                                PersonCardChain.check(
                                        claimed,
                                        List.of(),
                                        PersonCard.fingerprint(PersonCard.keyLine(first))))
                .isInstanceOf(CardException.class)
                .hasMessageContaining("not signed");
    }

    @Test
    void refuses_a_field_no_card_has_and_a_key_of_another_kind() {
        final String file = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);

        assertThatThrownBy(() -> PersonCard.read("machine: \"l1\"\n" + file, "test"))
                .hasMessageContaining("'machine' is not a field");
        assertThatThrownBy(
                        () ->
                                PersonCard.read(
                                        file.replaceFirst("key: \"ssh-ed25519 ", "key: \"ssh-rsa "),
                                        "test"))
                .hasMessageContaining("'key' is not an 'ssh-ed25519");
        assertThatThrownBy(() -> PersonCard.read(file.replace("\"alice\"", "\"Alice\""), "test"))
                .hasMessageContaining("is not a login");
    }

    @Test
    void refuses_a_login_porelo_keeps_for_itself() {
        final String file = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);

        assertThatThrownBy(
                        () ->
                                PersonCard.read(
                                        file.replace("\"alice\"", "\"porelo-matrix\""), "test"))
                .hasMessageContaining("keeps for itself");
        assertThatThrownBy(() -> PersonCard.read(file.replace("\"alice\"", "\"porelo\""), "test"))
                .hasMessageContaining("keeps for itself");
    }

    @Test
    void a_card_without_signature_is_refused() {
        final String file = PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null);

        assertThatThrownBy(
                        () ->
                                PersonCard.read(
                                        file.substring(0, file.indexOf("-----BEGIN")), "test"))
                .hasMessageContaining("carries no signature");
    }
}
