package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.vault.PersonCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards {@code identity show}: who the person here is, and where their card stands. */
class IdentityShowCommandTest {
    @TempDir Path dir;
    private RegistryCheckout registry;

    @BeforeEach
    void checkout() throws Exception {
        registry = new RegistryCheckout(dir);
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        PoreloContext.forgetAsked();
    }

    private static List<Object> keys(Object map) {
        return new ArrayList<>(((Map<?, ?>) map).keySet());
    }

    private static Map<?, ?> first(Object list) {
        return (Map<?, ?>) ((List<?>) list).getFirst();
    }

    /** alice's card filed and trusted, and read here. */
    private void filed() throws Exception {
        registry.founded();
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
    }

    /** The interface reads the answer by its fields and their order; a missing one breaks it. */
    @Test
    void answers_a_filed_card_in_the_contract_order() throws Exception {
        filed();
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        registry.agent("alice");

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        final Map<?, ?> answer = registry.answer();
        assertThat(keys(answer))
                .containsExactly(
                        "login",
                        "name",
                        "email",
                        "fingerprint",
                        "key",
                        "created",
                        "expires",
                        "predecessor",
                        "card",
                        "agent",
                        "agent_holds_key",
                        "revoked",
                        "card_refused",
                        "registry",
                        "forge",
                        "pending",
                        "sole_without_backup");
        assertThat(answer.get("login")).isEqualTo("alice");
        assertThat(answer.get("email")).as("none on the card").isNull();
        assertThat(answer.get("fingerprint")).isEqualTo(registry.pin("alice"));
        assertThat(answer.get("key")).isEqualTo(registry.key("alice"));
        assertThat(answer.get("created")).isEqualTo("2026-01-01");
        assertThat(answer.get("predecessor")).isNull();
        assertThat(answer.get("card"))
                .isEqualTo(Files.readString(new IdentityStore(registry.context()).cardFile()));
        assertThat(answer.get("agent")).isEqualTo("holds");
        assertThat(answer.get("agent_holds_key")).isEqualTo(true);
        assertThat(answer.get("revoked")).isEqualTo(false);
        assertThat(answer.get("card_refused")).isNull();
        final Map<?, ?> filed = first(answer.get("registry"));
        assertThat(keys(filed))
                .containsExactly("address", "filed", "revision", "may_file", "who_may");
        assertThat(filed.get("address")).isEqualTo(RegistryCheckout.ORIGIN);
        assertThat(filed.get("filed")).isEqualTo("done");
        assertThat(filed.get("revision")).isEqualTo(registry.head());
        assertThat(filed.get("may_file")).isEqualTo(true);
        assertThat((List<?>) filed.get("who_may")).isEmpty();
        final Map<?, ?> forge = (Map<?, ?>) answer.get("forge");
        assertThat(keys(forge)).containsExactly("login", "key_listed", "keys_url", "account_url");
        assertThat(forge.get("key_listed")).isEqualTo(true);
        assertThat(forge.get("keys_url")).isEqualTo("https://forge.example.org/alice.keys");
        assertThat(forge.get("account_url"))
                .isEqualTo("https://forge.example.org/user/settings/keys");
        assertThat(registry.forge.opened)
                .as("the key list is public: no token goes to the forge")
                .last()
                .isEqualTo("https://forge.example.org null");
        assertThat((List<?>) answer.get("pending")).isEmpty();
        assertThat((List<?>) answer.get("sole_without_backup")).as("alice owns nothing").isEmpty();
    }

    /** The one owner or admin without a backup key is told where, so the interface offers one. */
    @Test
    void names_each_registry_and_host_the_person_alone_holds_without_a_backup_key()
            throws Exception {
        filed();
        IdentityRun.keep(registry, registry.card("michael", "michael"));
        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("sole_without_backup"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "role",
                                        "registry-owner",
                                        "scope",
                                        Map.of(
                                                "kind",
                                                "registry",
                                                "name",
                                                RegistryCheckout.ORIGIN))));

        IdentityRun.keep(registry, registry.card("hank", "hank"));
        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("sole_without_backup"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "role",
                                        "host-admin",
                                        "scope",
                                        Map.of("kind", "host", "name", "walk6m"))));
    }

    /** Only a machine with no card is refused; the interface then offers to make one. */
    @Test
    void no_card_is_refused_no_identity_on_stdout() {
        assertThat(IdentityRun.identity(registry, "show", "--json")).isEqualTo(65);
        assertThat(keys(registry.answer())).containsExactly("reason", "words");
        assertThat(registry.answer().get("reason")).isEqualTo("no-identity");
    }

    /** A card waiting for its key is still shown, with what is missing, never refused. */
    @Test
    void a_card_without_its_key_or_registry_is_answered_with_what_is_missing() throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "alice"));

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("agent")).as("no agent at all").isEqualTo("absent");
        assertThat(registry.answer().get("agent_holds_key")).isEqualTo(false);
        assertThat((List<?>) registry.answer().get("registry")).isEmpty();
        assertThat(registry.answer().get("forge")).as("no registry names a forge").isNull();
        assertThat(registry.out.toString()).contains("\"forge\":null");

        registry.agent("bob");
        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("agent"))
                .as("an agent, locked or without the entry")
                .isEqualTo("not-held");
    }

    /** A revoked key must not look like one that still works. */
    @Test
    void a_key_in_the_revocation_list_is_answered_revoked() throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        IdentityRun.revoke(registry, "bob");
        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("revoked")).as("another key revoked").isEqualTo(false);

        IdentityRun.revoke(registry, "alice");
        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("revoked")).isEqualTo(true);
    }

    /** A card whose signature fails is shown with why, as card verify says it. */
    @Test
    void a_card_that_does_not_verify_is_answered_with_why() throws Exception {
        final String card = registry.card("alice", "alice").replace("\"Alice\"", "\"Alicf\"");
        Files.createDirectories(new IdentityStore(registry.context()).cardFile().getParent());
        Files.writeString(new IdentityStore(registry.context()).cardFile(), card);

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat((String) registry.answer().get("card_refused"))
                .contains("is not signed by its own key");
    }

    /** A card that cannot be read is no card shown, and not "no identity" either. */
    @Test
    void an_unreadable_card_exits_66() throws Exception {
        Files.createDirectories(new IdentityStore(registry.context()).cardFile());
        assertThat(IdentityRun.identity(registry, "show", "--json")).isEqualTo(66);
        assertThat(registry.out.toString()).isEmpty();
    }

    /** The person sees whether the registry took their card, and why not. */
    @Test
    void each_registry_answers_pending_or_refused_as_its_read_left_the_card() throws Exception {
        registry.founded();
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        IdentityRun.keep(registry, registry.card("alice", "alice"));

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(first(registry.answer().get("registry")).get("filed"))
                .as("the forge could not be asked: first seen")
                .isEqualTo("pending");
        assertThat(registry.answer().get("forge"))
                .extracting(forge -> ((Map<?, ?>) forge).get("key_listed"))
                .as("could not be asked")
                .isNull();

        registry.forge.keys.put("alice", List.of(registry.key("bob")));
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        final Map<?, ?> refused = first(registry.answer().get("registry"));
        assertThat(keys(refused))
                .containsExactly("address", "filed", "why", "revision", "may_file", "who_may");
        assertThat(refused.get("filed")).isEqualTo("refused");
        assertThat((String) refused.get("why")).isNotEmpty();
        assertThat(registry.answer().get("forge"))
                .extracting(forge -> ((Map<?, ?>) forge).get("key_listed"))
                .as("the forge lists all of alice's keys, and not this one")
                .isEqualTo(false);
    }

    /** A lost key's replacement is filed by an owner; the person must be told whom to ask. */
    @Test
    void a_lost_key_replacement_may_not_be_filed_by_the_person_and_names_the_owners()
            throws Exception {
        filed();
        registry.write(
                "people/michael.card",
                PersonCard.write(
                        "michael",
                        "Michael",
                        "michael@example.org",
                        registry.keys.get("michael"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        null));
        registry.commit("michael");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        IdentityRun.keep(registry, registry.card("alice", "alice-new"));

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        final Map<?, ?> filed = first(registry.answer().get("registry"));
        assertThat(filed.get("filed")).isEqualTo("pending");
        assertThat(filed.get("may_file")).isEqualTo(false);
        assertThat(filed.get("who_may"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "login",
                                        "michael",
                                        "name",
                                        "Michael",
                                        "email",
                                        "michael@example.org")));

        // Handed on from the old key, the person files it with their own key.
        IdentityRun.keep(registry, registry.card("alice", "alice-new", "alice"));
        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(first(registry.answer().get("registry")).get("may_file")).isEqualTo(true);
        assertThat(registry.answer().get("predecessor")).isEqualTo(registry.pin("alice"));
    }

    /** An interrupted operation is named so the person can finish it; a broken record is not. */
    @Test
    void pending_lists_the_operations_not_done_and_skips_what_does_not_parse() throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        final Path ops = registry.paths.xdg().state().resolve("identity").resolve("ops");
        Files.createDirectories(ops);
        Files.writeString(
                ops.resolve("rot-20261031-1.json"),
                "{\"id\":\"rot-20261031-1\",\"action\":\"rotate\",\"done\":false}");
        Files.writeString(
                ops.resolve("lost-20261030-1.json"),
                "{\"id\":\"lost-20261030-1\",\"action\":\"revoke-lost\",\"done\":true}");
        Files.writeString(ops.resolve("half.json"), "{\"id\":\"half\",\"act");

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("pending"))
                .isEqualTo(List.of(Map.of("id", "rot-20261031-1", "action", "rotate")));
    }

    /** A name on a card must not reorder what a terminal shows; the card keeps its lines. */
    @Test
    void text_is_made_printable_and_the_card_keeps_its_lines() throws Exception {
        IdentityRun.keep(
                registry,
                PersonCard.write(
                        "alice",
                        "Al‮ice",
                        "",
                        registry.keys.get("alice"),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2030, 1, 1),
                        null));

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("name")).isEqualTo("Al?ice");
        final String card = (String) registry.answer().get("card");
        assertThat(card).doesNotContain("‮").contains("name: \"Al?ice\"\n");
        assertThat(card.lines()).hasSizeGreaterThan(6);

        assertThat(IdentityRun.identity(registry, "show")).isZero();
        assertThat(registry.out.toString())
                .contains("login alice\n")
                .contains("fingerprint " + registry.pin("alice") + "\n")
                .doesNotContain("‮");
    }
}
