package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An author's own pull request is the one open from its branch, asked of the forge's adapter with
 * the forge's read credential lent for that call alone, and never kept.
 */
class OwnPullRequestsTest {

    private static final String UPSTREAM = "https://forge.example.org/uc/web-app.git";

    private static final String URL = "https://forge.example.org/uc/web-app/pull/7";

    private static final String ENTRY = "git.token.forge.example.org";

    private static final ForgeInstance FORGE =
            new ForgeInstance("forge.example.org", "forgejo", CommentReading.EXTENDED, null);

    private final List<String> lent = new ArrayList<>();

    private final List<String> asked = new ArrayList<>();

    private Optional<String> open = Optional.of(URL);

    @TempDir Path dir;

    private final OwnPullRequests own =
            new OwnPullRequests(
                    (adapter, credential, upstream, branch) -> {
                        asked.add(
                                adapter + " " + credential.token() + " " + upstream + " " + branch);
                        return open;
                    },
                    entry -> {
                        lent.add(entry);
                        return new ForgeCredential(entry, "lent-for-one-call");
                    });

    @Test
    void theOneOpenFromTheBranchIsThePullRequest() throws Exception {
        final PullRequestRef ref = own.of(FORGE, UPSTREAM, "porelo/fix");

        assertThat(ref.url()).as("its address").isEqualTo(URI.create(URL));
        assertThat(lent).as("the forge's read credential, lent").containsExactly(ENTRY);
        assertThat(asked)
                .as("the adapter asked once, with the lent token, the upstream and the branch")
                .containsExactly("forgejo lent-for-one-call " + UPSTREAM + " porelo/fix");
    }

    @Test
    void noneOpenFromTheBranchIsRefusedNamingIt() {
        open = Optional.empty();

        final OwnPullRequests.NoneOpen none =
                catchThrowableOfType(
                        () -> own.of(FORGE, UPSTREAM, "porelo/fix"),
                        OwnPullRequests.NoneOpen.class);

        assertThat(none).as("refused as none open").isNotNull();
        assertThat(none.branch()).as("the branch").isEqualTo("porelo/fix");
    }

    @Test
    void anAnswerAtAnotherForgeOrOfAnotherShapeIsUnreachable() {
        for (final String answered :
                List.of("https://elsewhere.example.org/uc/web-app/pull/7", "not an address")) {
            open = Optional.of(answered);

            final PlaceUnreadable refused =
                    catchThrowableOfType(
                            () -> own.of(FORGE, UPSTREAM, "porelo/fix"), PlaceUnreadable.class);

            assertThat(refused).as("refused: %s", answered).isNotNull();
            assertThat(refused.reason())
                    .as("an answer that is not this forge's pull request: %s", answered)
                    .isEqualTo(PlaceUnreadable.Reason.UNREACHABLE);
        }
    }

    @Test
    void aForgeEntryWithoutAnAdapterLendsNoCredentialAndAsksNothing() {
        final ForgeInstance bare =
                new ForgeInstance("forge.example.org", null, CommentReading.EXTENDED, "no adapter");

        final PlaceUnreadable refused =
                catchThrowableOfType(
                        () -> own.of(bare, UPSTREAM, "porelo/fix"), PlaceUnreadable.class);

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.reason())
                .as("no adapter reads it")
                .isEqualTo(PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING);
        assertThat(lent).as("no credential lent").isEmpty();
        assertThat(asked).as("nothing asked").isEmpty();
    }

    @Test
    void theMachinesCredentialsLendNothingFromAVaultThatIsNotThere() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), a -> 0);

        final PlaceUnreadable refused =
                catchThrowableOfType(
                        () -> OwnPullRequests.Credentials.of(context).lend(ENTRY),
                        PlaceUnreadable.class);

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.reason())
                .as("no token to lend")
                .isEqualTo(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN);
        assertThat(refused.getMessage()).as("names the entry").contains(ENTRY);
    }
}
