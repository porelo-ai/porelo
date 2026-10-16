package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AuthorizationsNeeded}: a question for a person, raised once per credential until answered.
 */
class AuthorizationsNeededTest {

    @TempDir
    Path dir;

    private PoreloContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> "XDG_STATE_HOME".equals(name) ? dir.resolve("state").toString() : null,
                dir);
        return new PoreloContext(new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void raisedOnceUntilAGrantLandsAndRaisedAgainWhenItEnds() throws Exception {
        final PoreloContext context = context();

        AuthorizationsNeeded.raise(context, "search", "porelo-p-a", "p", AuthorizationsNeeded.NEVER);
        final String first = AuthorizationsNeeded.open(context).getFirst().get("at");
        Thread.sleep(5);
        AuthorizationsNeeded.raise(context, "search", "porelo-p-b", "p", AuthorizationsNeeded.NEVER);

        // A second refusal for the same credential keeps the first question: one person, one answer.
        assertThat(AuthorizationsNeeded.open(context)).singleElement().satisfies(question -> {
            assertThat(question).containsEntry("task", "porelo-p-a").containsEntry("state", "never");
            assertThat(question.get("at")).isEqualTo(first);
        });

        AuthorizationsNeeded.clear(context, "search");
        assertThat(AuthorizationsNeeded.open(context)).isEmpty();

        AuthorizationsNeeded.raise(context, "search", "porelo-p-a", "p", AuthorizationsNeeded.ENDED);
        assertThat(AuthorizationsNeeded.open(context)).singleElement()
                .satisfies(question -> assertThat(question).containsEntry("state", "ended"));
    }
}
