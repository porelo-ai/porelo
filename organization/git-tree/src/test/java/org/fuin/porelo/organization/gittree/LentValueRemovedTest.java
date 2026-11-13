package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.gittree.GitTreeStore.Unreachable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Git quotes what the forge answered in more shapes than any line filter knows, and a forge may
 * answer with the token it was just lent: every value lent to a git run is removed from what it
 * wrote, by value, before anything else is done with it.
 */
class LentValueRemovedTest {

    private static final String LEAD = "cannot read the organization's repository";

    private static final String TOKEN = "lent-token";

    private static final Set<String> LENT = FetchAccess.Lease.formsOf("x-access-token", TOKEN);

    @TempDir Path dir;

    @Test
    void aProtocolErrorQuotingTheTokenNamesItNot() {
        final Unreachable unreachable =
                Unreachable.fromGit(LEAD, "fatal: protocol error: unexpected 'lent-token'\n", LENT);

        assertThat(unreachable.getMessage())
                .doesNotContain(TOKEN)
                .contains("fatal: protocol error: unexpected");
    }

    @Test
    void anErrContinuationLineHoldingTheTokenNamesItNot() {
        final Unreachable unreachable =
                Unreachable.fromGit(
                        LEAD, "fatal: remote error: ERR first line\nlent-token\n", LENT);

        assertThat(unreachable.getMessage()).doesNotContain(TOKEN);
    }

    @Test
    void theTokenUrlEncodedOrAsBasicAuthorizationIsRemovedToo() {
        final String token = "lent/token+1";
        final String basic =
                Base64.getEncoder()
                        .encodeToString(
                                ("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        final Unreachable unreachable =
                Unreachable.fromGit(
                        LEAD,
                        "fatal: unexpected 'lent%2Ftoken%2B1'\nfatal: header 'Basic "
                                + basic
                                + "'\n",
                        FetchAccess.Lease.formsOf("x-access-token", token));

        assertThat(unreachable.getMessage())
                .doesNotContain("lent%2Ftoken%2B1")
                .doesNotContain(basic)
                .doesNotContain(basic.replace("=", ""));
    }

    @Test
    void lowercasePercentEscapesAreRemovedToo() {
        final Unreachable unreachable =
                Unreachable.fromGit(
                        LEAD,
                        "fatal: unexpected 'lent%2ftoken%2b1'\n",
                        FetchAccess.Lease.formsOf("x-access-token", "lent/token+1"));

        assertThat(unreachable.getMessage()).doesNotContain("lent%2ftoken%2b1");
    }

    @Test
    void aValueSplitAcrossTheLinesAfterErrNamesNoneOfIt() {
        final Unreachable unreachable =
                Unreachable.fromGit(
                        LEAD,
                        "fatal: remote error: first line\nlent-\ntoken\nwhat the forge said next\n",
                        LENT);

        assertThat(unreachable.getMessage())
                .doesNotContain("lent-")
                .doesNotContain("token")
                .doesNotContain("what the forge said next");
    }

    @Test
    void aPaddedBasicAuthorizationIsRemovedWithAndWithoutItsPadding() {
        // "x-access-token:lent-token" is 25 bytes, so its Base64 ends in "==".
        final String padded =
                Base64.getEncoder()
                        .encodeToString(
                                "x-access-token:lent-token".getBytes(StandardCharsets.UTF_8));
        assertThat(padded).as("the fixture gives padding").endsWith("==");
        final String bare = padded.replace("=", "");

        assertThat(
                        Unreachable.fromGit(LEAD, "fatal: header 'Basic " + padded + "'\n", LENT)
                                .getMessage())
                .doesNotContain(bare);
        assertThat(
                        Unreachable.fromGit(LEAD, "fatal: header 'Basic " + bare + "'\n", LENT)
                                .getMessage())
                .doesNotContain(bare);
    }

    @Test
    void aFetchThatTimedOutKeepsNoLentValueInWhatItThrows() {
        final ProcessCommandRunner real = new ProcessCommandRunner();
        final CommandRunner hanging =
                command -> {
                    if (command.arguments().contains("fetch")) {
                        throw new CommandException(
                                command,
                                new java.io.IOException("Timed out after 1 s"),
                                "fatal: protocol error: unexpected 'lent-token'\n");
                    }
                    return real.run(command);
                };
        final FetchAccess access =
                address ->
                        new FetchAccess.Lease() {
                            @Override
                            public Set<String> lent() {
                                return LENT;
                            }
                        };

        assertThatThrownBy(
                        () ->
                                TreeRepository.fetch(
                                        dir.resolve("organization.git").toString(),
                                        hanging,
                                        access))
                .isInstanceOfSatisfying(
                        CommandException.class,
                        thrown -> assertThat(thrown.standardError()).doesNotContain(TOKEN));
    }

    @Test
    void aFetchThatFailedQuotingWhatItWasLentNamesItNot() {
        final ProcessCommandRunner real = new ProcessCommandRunner();
        final CommandRunner quoting =
                command ->
                        command.arguments().contains("fetch")
                                ? new CommandResult(
                                        command,
                                        128,
                                        "",
                                        "fatal: protocol error: unexpected 'lent-token'\n")
                                : real.run(command);
        final FetchAccess access =
                address ->
                        new FetchAccess.Lease() {
                            @Override
                            public Set<String> lent() {
                                return LENT;
                            }
                        };

        assertThatThrownBy(
                        () ->
                                TreeRepository.fetch(
                                        dir.resolve("organization.git").toString(),
                                        quoting,
                                        access))
                .isInstanceOf(Unreachable.class)
                .hasMessageStartingWith(LEAD)
                .hasMessageNotContaining(TOKEN);
    }
}
