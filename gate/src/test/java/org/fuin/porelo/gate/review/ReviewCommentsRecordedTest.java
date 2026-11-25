package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the journal's answer to which push a container last recorded under a name. */
class ReviewCommentsRecordedTest {

    private static final String CONTAINER = "0123456789abcdef0123";

    private static final String OTHER_CONTAINER = "fedcba9876543210fedc";

    private static final String FIRST_HEAD = "1".repeat(40);

    private static final String SECOND_HEAD = "3".repeat(40);

    private static final String BASE = "2".repeat(40);

    private static final Origin OWNER = Origin.of(new OwnerAccount());

    @TempDir Path dir;

    @Test
    void theLastHeadAndBaseRecordedUnderANameAreAnswered() throws Exception {
        final ReviewComments store = new ReviewComments(dir.resolve("review"));
        store.keep(push(CONTAINER, FIRST_HEAD, null), comment(), OWNER, null);
        store.keep(push(CONTAINER, SECOND_HEAD, BASE), comment(), OWNER, null);

        assertThat(store.recorded("shop", "app", "main", CONTAINER))
                .as("the push as its latest entry recorded it")
                .contains(push(CONTAINER, SECOND_HEAD, BASE));
    }

    @Test
    void aContainerThatRecordedNothingUnderTheNameGetsNothingOfAnother() throws Exception {
        final ReviewComments store = new ReviewComments(dir.resolve("review"));
        store.keep(push(CONTAINER, FIRST_HEAD, BASE), comment(), OWNER, null);

        assertThat(store.recorded("shop", "app", "main", OTHER_CONTAINER))
                .as("another container's journal is not read")
                .isEmpty();
        assertThat(store.recorded("shop", "app", "other", CONTAINER))
                .as("another name in the same container")
                .isEmpty();
        assertThat(store.recorded("shop", "lib", "main", CONTAINER))
                .as("another repository in the same container")
                .isEmpty();
        assertThat(store.recorded("web", "app", "main", CONTAINER))
                .as("another project in the same container")
                .isEmpty();
    }

    @Test
    void aStoreThatWasNeverWrittenAnswersNothing() {
        assertThat(
                        new ReviewComments(dir.resolve("none"))
                                .recorded("shop", "app", "main", CONTAINER))
                .as("no directory yet")
                .isEmpty();
    }

    private static Push push(final String container, final String head, final String base) {
        return new Push("shop", "app", "main", head, base, container);
    }

    private static ReviewComment comment() {
        return new ReviewComment(
                new LineAnchor("src/a.txt", Side.NEW, 2, 3), new Body("why"), null);
    }

    @org.junit.jupiter.api.Test
    void aTaskThatIsNoRecordedContainerIdIsRefusedBeforeAnyPathIsRead(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path root) {
        final ReviewComments store = new ReviewComments(root);
        for (final String task : java.util.List.of("x/../y", "..", "/etc", "ABCDEF012345", "abc")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> store.recorded("shop", "shop", "cart", task))
                    .as("a task id %s names no container", task)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
