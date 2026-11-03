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

    /** alice's card accepted by the organization. */
    private TestOrganization filed() throws Exception {
        final TestOrganization organization = new TestOrganization(registry);
        organization.filed("alice");
        return organization;
    }

    /** The interface reads the answer by its fields and their order; a missing one breaks it. */
    @Test
    void answers_a_filed_card_in_the_contract_order() throws Exception {
        final TestOrganization organization = filed();
        IdentityRun.keep(registry, organization.card("alice"));
        registry.agent("alice");

        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
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
                        "days_left",
                        "renew_due",
                        "predecessor",
                        "card",
                        "agent",
                        "agent_holds_key",
                        "revoked",
                        "card_refused",
                        "organization",
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
        final Map<?, ?> filed = first(answer.get("organization"));
        assertThat(keys(filed))
                .containsExactly("address", "filed", "revision", "stale", "may_file", "who_may");
        assertThat(filed.get("address")).isEqualTo(TestOrganization.ADDRESS);
        assertThat(filed.get("filed")).isEqualTo("done");
        assertThat(filed.get("revision")).isEqualTo(organization.tip());
        assertThat(filed.get("stale")).isEqualTo(false);
        assertThat(filed.get("may_file")).isEqualTo(true);
        assertThat((List<?>) filed.get("who_may")).isEmpty();
        final Map<?, ?> forge = (Map<?, ?>) answer.get("forge");
        assertThat(keys(forge)).containsExactly("login", "key_listed", "keys_url", "account_url");
        assertThat(forge.get("key_listed")).isEqualTo(true);
        assertThat(forge.get("keys_url")).isEqualTo("https://forge.example.org/alice.keys");
        assertThat(forge.get("account_url"))
                .isEqualTo("https://forge.example.org/user/settings/keys");
        assertThat((List<?>) answer.get("pending")).isEmpty();
        assertThat((List<?>) answer.get("sole_without_backup")).as("alice owns nothing").isEmpty();
    }

    /** The one owner or admin without a backup key is told where, so the interface offers one. */
    @Test
    void names_the_organization_and_each_host_the_person_alone_holds_without_a_backup_key()
            throws Exception {
        final TestOrganization organization = filed();
        IdentityRun.keep(registry, organization.filed("michael"));
        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat(registry.answer().get("sole_without_backup"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "role",
                                        "organization-owner",
                                        "scope",
                                        Map.of(
                                                "kind",
                                                "organization",
                                                "name",
                                                TestOrganization.ADDRESS))));

        IdentityRun.keep(registry, organization.filed("hank"));
        organization
                .writes()
                .addHost(organization.change("michael", "michael", "h1"), "wharf6", "hank");
        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat(registry.answer().get("sole_without_backup"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "role",
                                        "host-admin",
                                        "scope",
                                        Map.of("kind", "host", "name", "wharf6"))));
    }

    /** Only a machine with no card is refused; the interface then offers to make one. */
    @Test
    void no_card_is_refused_no_identity_on_stdout() {
        assertThat(IdentityRun.identity(registry, "show", "--json")).isEqualTo(65);
        assertThat(keys(registry.answer())).containsExactly("reason", "words", "revision");
        assertThat(registry.answer().get("reason")).isEqualTo("no-identity");
    }

    /** A card waiting for its key is still shown, with what is missing, never refused. */
    @Test
    void a_card_without_its_key_or_organization_is_answered_with_what_is_missing()
            throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "alice"));

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("agent")).as("no agent at all").isEqualTo("absent");
        assertThat(registry.answer().get("agent_holds_key")).isEqualTo(false);
        assertThat((List<?>) registry.answer().get("organization")).isEmpty();
        assertThat(registry.answer().get("forge")).as("no organization names a forge").isNull();
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

    /** The person sees whether the organization took their card, and what it last said. */
    @Test
    void answers_absent_for_a_card_the_organization_does_not_hold_and_stale_out_of_reach()
            throws Exception {
        final TestOrganization organization = new TestOrganization(registry);
        IdentityRun.keep(registry, registry.card("alice", "alice"));

        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat(first(registry.answer().get("organization")).get("filed")).isEqualTo("absent");
        assertThat(registry.answer().get("forge"))
                .extracting(forge -> ((Map<?, ?>) forge).get("key_listed"))
                .as("the forge lists none of alice's keys, so it could not be asked")
                .isNull();

        organization.outOfReach();
        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat(first(registry.answer().get("organization")).get("stale")).isEqualTo(true);
    }

    /** A lost key's replacement is filed by an owner; the person must be told whom to ask. */
    @Test
    void a_lost_key_replacement_may_not_be_filed_by_the_person_and_names_the_owners()
            throws Exception {
        final TestOrganization organization = filed();
        organization.filed("michael");
        IdentityRun.keep(registry, registry.card("alice", "alice-new"));

        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        final Map<?, ?> filed = first(registry.answer().get("organization"));
        assertThat(filed.get("filed")).isEqualTo("pending");
        assertThat(filed.get("may_file")).isEqualTo(false);
        assertThat((List<?>) filed.get("who_may"))
                .extracting(owner -> ((Map<?, ?>) owner).get("login"))
                .isEqualTo(List.of("michael"));

        // Handed on from the old key, the person files it with their own key.
        IdentityRun.keep(registry, registry.card("alice", "alice-new", "alice"));
        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat(first(registry.answer().get("organization")).get("may_file")).isEqualTo(true);
        assertThat(registry.answer().get("predecessor")).isEqualTo(registry.pin("alice"));
    }

    /** A lost key's card an owner has accepted waits for nobody, so it is no longer pending. */
    @Test
    void a_lost_keys_card_an_owner_accepted_is_no_longer_pending() throws Exception {
        final TestOrganization organization = filed();
        organization.filed("michael");
        registry.agent("alice-new");
        IdentityRun.keep(registry, organization.card("alice"));
        organization.listed("alice", "alice-new");
        assertThat(
                        IdentityRun.identity(
                                organization,
                                "revoke",
                                "--lost",
                                "--key",
                                registry.pin("alice-new"),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat((List<?>) registry.answer().get("pending")).hasSize(1);
        final String card = (String) registry.answer().get("card");

        organization
                .writes()
                .replaceCard(organization.change("michael", "michael", "r1"), "alice", card);

        assertThat(IdentityRun.identity(organization, "show", "--json")).isZero();
        assertThat((List<?>) registry.answer().get("pending")).isEmpty();
    }

    /** An interrupted operation is named so the person can finish it; a broken record is not. */
    @Test
    void pending_lists_the_operations_not_done_and_skips_what_does_not_parse() throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        final Path ops = registry.paths.xdg().state().resolve("identity").resolve("ops");
        Files.createDirectories(ops);
        final String card = registry.card("alice", "alice-new", "alice");
        Files.writeString(
                ops.resolve("rot-20261031-1.json"),
                org.fuin.porelo.wire.Json.write(
                        Map.of(
                                "id",
                                "rot-20261031-1",
                                "action",
                                "rotate",
                                "fingerprint",
                                registry.pin("alice-new"),
                                "predecessor",
                                registry.pin("alice"),
                                "card",
                                card,
                                "done",
                                false,
                                "kept",
                                false)));
        Files.writeString(
                ops.resolve("lost-20261030-1.json"),
                org.fuin.porelo.wire.Json.write(
                        Map.of(
                                "id",
                                "lost-20261030-1",
                                "action",
                                "revoke-lost",
                                "fingerprint",
                                registry.pin("alice-new"),
                                "predecessor",
                                registry.pin("alice"),
                                "card",
                                card,
                                "done",
                                true,
                                "kept",
                                true)));
        Files.writeString(ops.resolve("half.json"), "{\"id\":\"half\",\"act");

        assertThat(IdentityRun.identity(registry, "show", "--json")).isZero();
        assertThat(registry.answer().get("pending"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "id",
                                        "rot-20261031-1",
                                        "action",
                                        "rotate",
                                        "fingerprint",
                                        registry.pin("alice-new"))));
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
