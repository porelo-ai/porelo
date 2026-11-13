package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
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
