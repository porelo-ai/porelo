package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.organization.gittree.GitTreeStore.Unreachable;
import org.fuin.porelo.organization.gittree.GitTreeStore.Unreachable.Cause;
import org.junit.jupiter.api.Test;

/**
 * A person shown only "could not be read" cannot tell a refused key from no network; and what the
 * forge says through git may quote the credential it was just lent, so it never reaches a display.
 */
class UnreachableCauseTest {

    private static final String LEAD = "cannot read the organization's repository";

    @Test
    void aRefusedKeyIsNamedWithGitsOwnWords() {
        final Unreachable unreachable =
                Unreachable.fromGit(
                        LEAD,
                        "git@forge.example.org: Permission denied (publickey).\n"
                                + "fatal: Could not read from remote repository.\n");
        assertThat(unreachable.cause()).as("the refused key is named").isEqualTo(Cause.KEY_REFUSED);
        assertThat(unreachable.said())
                .as("git's own words are kept")
                .contains("Permission denied (publickey)");
        assertThat(unreachable.getMessage())
                .as("the message still carries the words a check reads")
                .startsWith(LEAD + ": ")
                .contains("Permission denied (publickey)");
    }

    @Test
    void gitsLinesReadAsSeparateClauses() {
        assertThat(
                        Unreachable.fromGit(
                                        LEAD,
                                        "error: unable to read askpass response from '/bin/false'\n"
                                                + "fatal: could not read Username\n")
                                .getMessage())
                .as("each of git's lines is its own clause, not run into the next")
                .endsWith("'/bin/false'; fatal: could not read Username");
    }

    @Test
    void anUntrustedHostKeyIsNamed() {
        assertThat(
                        Unreachable.fromGit(
                                        LEAD,
                                        "No ED25519 host key is known for forge.example.org.\n"
                                                + "Host key verification failed.\n")
                                .cause())
                .as("an untrusted host key is its own cause")
                .isEqualTo(Cause.HOST_KEY);
    }

    @Test
    void noNetworkIsNamed() {
        assertThat(
                        Unreachable.fromGit(
                                        LEAD,
                                        "ssh: Could not resolve hostname forge.example.org:"
                                                + " Temporary failure in name resolution\n")
                                .cause())
                .as("an unresolved name is no network")
                .isEqualTo(Cause.NO_NETWORK);
        assertThat(
                        Unreachable.fromGit(
                                        LEAD,
                                        "ssh: connect to host forge.example.org port 22:"
                                                + " Connection timed out\n")
                                .cause())
                .as("a connection that never came about is no network")
                .isEqualTo(Cause.NO_NETWORK);
    }

    @Test
    void whatTheForgeSaidNeverReachesTheWords() {
        final Unreachable unreachable =
                Unreachable.fromGit(
                        LEAD,
                        "remote: token ghx_secret_value is not valid\n"
                                + "fatal: remote error: ghx_secret_value\n"
                                + "fatal: Authentication failed for"
                                + " 'https://forge.example.org/acme/organization.git/'\n");
        assertThat(unreachable.said())
                .as("a line the forge wrote is left out")
                .doesNotContain("ghx_secret_value")
                .contains("Authentication failed");
        assertThat(unreachable.getMessage())
                .as("nor is it in the message")
                .doesNotContain("ghx_secret_value");
        assertThat(unreachable.cause())
                .as("a failed authentication is a refused key")
                .isEqualTo(Cause.KEY_REFUSED);
    }

    @Test
    void nothingGitSaidLeavesTheLeadAlone() {
        final Unreachable unreachable = Unreachable.fromGit(LEAD, "remote: only the forge\n");
        assertThat(unreachable.said()).as("no words of git's").isNull();
        assertThat(unreachable.getMessage()).as("the lead alone").isEqualTo(LEAD);
        assertThat(unreachable.cause()).as("no cause to tell").isEqualTo(Cause.UNREACHABLE);
    }

    @Test
    void longWordsAreCut() {
        final String said = Unreachable.fromGit(LEAD, "fatal: " + "x".repeat(2000)).said();
        assertThat(said).as("a display is not flooded").isNotNull();
        assertThat(said.length()).as("cut").isLessThanOrEqualTo(400);
    }
}
