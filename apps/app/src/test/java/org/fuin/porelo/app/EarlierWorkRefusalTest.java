package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.gate.GitGate;
import org.junit.jupiter.api.Test;

/**
 * Tests that a start refused for earlier work of its name prints that work's subject, which an
 * agent wrote, escaped.
 */
class EarlierWorkRefusalTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void theEarlierSubjectCanNeitherReorderNorRewriteTheRefusal() {
        // A subject holding U+202E would show the approval command after it in reverse; one
        // holding ESC could erase it.
        final String refusal =
                WorkspaceSetup.earlierWorkRefusal(
                        "second",
                        new GitGate.Waiting(COMMIT, "fix ‮evil\u001b[2K"),
                        true,
                        "uc",
                        "main");

        assertThat(refusal)
                .contains("(" + COMMIT + " fix \\u202eevil\\x1b[2K): review it")
                .contains("porelo gate approve --project uc --repository main --commit " + COMMIT)
                .doesNotContain("‮", "\u001b");
    }
}
