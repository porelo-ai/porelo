package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards {@code identity keys}: the agent's keys, and which of them a card can name. */
class IdentityKeysCommandTest {
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

    private List<?> listed() {
        return (List<?>) registry.answer().get("keys");
    }

    private Map<?, ?> listed(int index) {
        return (Map<?, ?>) listed().get(index);
    }

    /** The person picks a key from this list; one the card cannot take must say why. */
    @Test
    void answers_each_key_in_the_agents_order_with_whether_a_card_takes_it() throws Exception {
        registry.founded();
        registry.forge.keys.put("bob", List.of(registry.key("bob")));
        registry.write("people/bob.card", registry.card("bob", "bob"));
        registry.commit("bob");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        registry.agent("alice", "bob");

        assertThat(IdentityRun.identity(registry, "keys", "--json")).isZero();
        assertThat(keys(registry.answer())).containsExactly("keys");
        assertThat(listed()).hasSize(2);
        assertThat(keys(listed(0)))
                .containsExactly("fingerprint", "key", "comment", "usable", "reason", "key_listed");
        assertThat(listed(0).get("fingerprint")).isEqualTo(registry.pin("alice"));
        assertThat(listed(0).get("key")).as("no comment").isEqualTo(registry.key("alice"));
        assertThat(listed(0).get("comment")).isInstanceOf(String.class);
        assertThat(listed(0).get("usable")).isEqualTo(true);
        assertThat(listed(0).get("reason")).isNull();
        assertThat(listed(0).get("key_listed")).as("no card names a login yet").isNull();
        assertThat(keys(listed(1)))
                .containsExactly(
                        "fingerprint", "key", "comment", "usable", "reason", "login", "key_listed");
        assertThat(listed(1).get("usable")).isEqualTo(false);
        assertThat(listed(1).get("reason")).isEqualTo("key-on-another-card");
        assertThat(listed(1).get("login")).isEqualTo("bob");
    }

    /** A key of another type is listed, never offered for a card. */
    @Test
    void a_key_of_another_type_is_not_usable() throws Exception {
        registry.agent();
        final Path rsa = dir.resolve("rsa");
        registry.runner.runOrFail(
                Command.of("ssh-keygen", "-q", "-t", "rsa", "-N", "", "-f", rsa.toString()));
        registry.runner.runOrFail(
                Command.of("ssh-add", "-q", rsa.toString())
                        .withEnvironment(
                                Map.of(
                                        "SSH_AUTH_SOCK",
                                        registry.environment().apply("SSH_AUTH_SOCK"))));

        assertThat(IdentityRun.identity(registry, "keys", "--json")).isZero();
        assertThat(listed(0).get("usable")).isEqualTo(false);
        assertThat(listed(0).get("reason")).isEqualTo("key-not-ed25519");
        assertThat((String) listed(0).get("key")).startsWith("ssh-rsa ");
        assertThat((String) listed(0).get("fingerprint")).startsWith("SHA256:");
    }

    /** A revoked key offered for a card would make one nobody accepts. */
    @Test
    void a_revoked_key_is_not_usable() throws Exception {
        IdentityRun.revoke(registry, "alice");
        registry.agent("alice", "bob");

        assertThat(IdentityRun.identity(registry, "keys", "--json")).isZero();
        assertThat(listed(0).get("reason")).isEqualTo("revoked");
        assertThat(listed(1).get("usable")).isEqualTo(true);
    }

    /** With a card kept here, its own login's key is usable and the forge is asked about it. */
    @Test
    void the_kept_cards_login_is_no_other_card_and_names_the_forge_listing() throws Exception {
        registry.founded();
        registry.forge.keys.put("alice", List.of(registry.key("alice")));
        registry.write("people/alice.card", registry.card("alice", "alice"));
        registry.commit("alice");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        registry.agent("alice", "bob");

        assertThat(IdentityRun.identity(registry, "keys", "--json")).isZero();
        assertThat(listed(0).get("usable")).isEqualTo(true);
        assertThat(listed(0).get("key_listed")).isEqualTo(true);
        assertThat(listed(1).get("key_listed")).isEqualTo(false);
    }

    /** No agent is told apart from an agent with no keys. */
    @Test
    void no_agent_exits_69_agent_absent() throws Exception {
        assertThat(IdentityRun.identity(registry, "keys", "--json")).isEqualTo(69);
        assertThat(registry.answer().get("reason")).isEqualTo("agent-absent");

        registry.agent();
        assertThat(IdentityRun.identity(registry, "keys", "--json")).isZero();
        assertThat(listed()).isEmpty();
    }

    /** A card read through a linked directory would name anybody's login. */
    @Test
    void a_linked_config_directory_is_refused() throws Exception {
        IdentityRun.keep(registry, registry.card("alice", "alice"));
        final Path config = registry.paths.xdg().config();
        final Path elsewhere = dir.resolve("elsewhere");
        Files.move(config, elsewhere);
        Files.createSymbolicLink(config, elsewhere);
        registry.agent("alice");

        assertThat(IdentityRun.identity(registry, "keys", "--json")).isEqualTo(66);
    }

    /** Registries read through a linked state directory may not be the ones read here. */
    @Test
    void a_linked_state_directory_is_refused() throws Exception {
        registry.founded();
        registry.write("people/bob.card", registry.card("bob", "bob"));
        registry.commit("bob");
        assertThat(registry.registry("read", registry.repo.toString())).isZero();
        final Path state = registry.paths.xdg().state();
        final Path elsewhere = dir.resolve("elsewhere-state");
        Files.move(state, elsewhere);
        Files.createSymbolicLink(state, elsewhere);
        registry.agent("alice", "bob");

        assertThat(IdentityRun.identity(registry, "keys", "--json")).isEqualTo(66);
    }
}
