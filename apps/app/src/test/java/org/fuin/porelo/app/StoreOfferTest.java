package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.List;
import org.fuin.porelo.core.credential.Credential;
import org.junit.jupiter.api.Test;

/**
 * Tests that a missing secret is offered to be stored where a refusal named 'porelo vault put', run as that command
 * itself - an ssh key from a path asked, a token or a key typed without echo by the command.
 */
class StoreOfferTest {

    private final StringWriter said = new StringWriter();

    private Offer answering(final String... answers) {
        final ArrayDeque<String> left = new ArrayDeque<>(List.of(answers));
        return new Offer(question -> left.poll(), false, false, new PrintWriter(said, true));
    }

    @Test
    void anSshKeyIsStoredFromAPathTheTerminalGives() {
        assertThat(CredentialsCheckCommand.store("git.corp.example", Credential.Kind.SSH_KEY,
                answering("~/.ssh/id_ed25519"))).containsExactly("vault", "put", "git.corp.example", "--from-file",
                        "~/.ssh/id_ed25519");
        assertThat(CredentialsCheckCommand.store("git.corp.example", Credential.Kind.SSH_KEY, answering("")))
                .as("no path given").isNull();
    }

    @Test
    void aTokenOrAKeyIsTypedIntoTheCommandItself() {
        assertThat(CredentialsCheckCommand.store("forge", Credential.Kind.TOKEN, answering("")))
                .containsExactly("vault", "put", "forge", "--type", "token");
        assertThat(CredentialsCheckCommand.store("forge", Credential.Kind.TOKEN, answering("n"))).isNull();
    }

    @Test
    void nobodyToAskIsTheRefusalAsBefore() {
        assertThat(CredentialsCheckCommand.store("forge", Credential.Kind.TOKEN, Offer.NOBODY)).isNull();
    }

    @Test
    void aTextAnswerIsAskedOnceAndRefusedWithTheCommandWhenNoneComes() {
        assertThat(answering("  /tmp/key  ").text("no key", "Path of the private key:", "porelo vault put x"))
                .isEqualTo("/tmp/key");
        assertThat(answering("").text("no key", "Path:", "porelo vault put x")).isNull();
        assertThat(said.toString()).contains("run 'porelo vault put x'");
    }
}
