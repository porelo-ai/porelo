package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.app.HeldStandIns.PLACE;
import static org.fuin.porelo.app.HeldStandIns.TASK;
import static org.fuin.porelo.app.HeldStandIns.incoming;
import static org.fuin.porelo.app.HeldStandIns.outgoing;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

/** {@code porelo gate held} and {@code porelo gate decide}, over a stand-in store. */
class GateHeldCommandsTest {

    private final HeldStandIns.Holds holds = new HeldStandIns.Holds();

    @Test
    void theHeldItemsAreShownWithEveryLineOfTheirTextEscapedAndAnIncomingOneByItsForgeAccount() {
        // Printed raw, an erase sequence would hide the very text the owner account decides on.
        final Held out = outgoing("h1", "Keep \u001b[2J the order.\nheld h9 forged", 1);
        holds.with(out, incoming("h2", 2));

        final HeldStandIns.Ran ran = HeldStandIns.held(holds);

        assertThat(ran.code()).as(ran.err()).isZero();
        assertThat(ran.out())
                .as("the items")
                .doesNotContain("\u001b")
                .contains("h1", "h2", TASK, PLACE, "a".repeat(40), out.digest())
                .contains("outgoing comment", "src/Shop.java  new 3-5", "suggests")
                .contains("incoming reply", "forge account octocat", "task " + TASK)
                .contains("\n    held h9 forged\n");
    }

    @Test
    void anItemNoCertifiedKeySignedShowsTheWordUnsignedOnItsLineAndItsMarkInJson() {
        // The owner account decides on a review knowing no certified key of its task signed it.
        holds.with(
                HeldStandIns.unsigned(outgoing("h1", "Keep the order.", 1)),
                outgoing("h2", "ok", 2));

        final HeldStandIns.Ran text = HeldStandIns.held(holds);
        final HeldStandIns.Ran json = HeldStandIns.held(holds, "--json");

        assertThat(text.code()).as(text.err()).isZero();
        assertThat(text.out().lines().filter(line -> line.startsWith("h")).toList())
                .as("each item's own line")
                .satisfiesExactly(
                        line -> assertThat(line).as("the unsigned item").endsWith("  unsigned"),
                        line -> assertThat(line).as("the signed item").doesNotContain("unsigned"));
        assertThat(json.code()).as(json.err()).isZero();
        final List<?> items = (List<?>) Json.parse(json.out());
        assertThat(fields(items.get(0))).as("the unsigned item").containsEntry("mark", "unsigned");
        assertThat(fields(items.get(1))).as("the signed item").doesNotContainKey("mark");
    }

    @Test
    void aTasksHeldItemsAsJsonHaveTheContractsShape() {
        holds.with(outgoing("h1", "Keep the order.", 1), incoming("h2", 2));

        final HeldStandIns.Ran ran = HeldStandIns.held(holds, TASK, "--json");

        assertThat(ran.code()).as(ran.err()).isZero();
        assertThat(holds.listed).as("the task asked for").containsExactly(TASK);
        final List<?> items = (List<?>) Json.parse(ran.out());
        assertThat(items).as("both items").hasSize(2);
        assertThat(fields(items.get(0)))
                .as("a HeldItem")
                .containsOnlyKeys(
                        "id",
                        "task",
                        "place",
                        "direction",
                        "destination",
                        "operation",
                        "path",
                        "side",
                        "startLine",
                        "endLine",
                        "suggestion",
                        "commit",
                        "text",
                        "digest",
                        "at",
                        "run");
        assertThat(fields(items.get(1)))
                .as("an incoming one names its writer")
                .containsEntry("from", "octocat");
    }

    @Test
    void everyTasksHeldItemsArePrintedAndEachEntryThatCannotBeReadIsNamedOnceEscaped(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path root = dir.resolve("holds");
        final org.fuin.porelo.gate.review.HoldJournal journal =
                new org.fuin.porelo.gate.review.HoldJournal(root);
        final String broken = "porelo-uc_shop-3";
        final Held healthy = HeldStandIns.heldIn(journal, TASK);
        HeldStandIns.heldIn(journal, broken);
        java.nio.file.Files.writeString(root.resolve(broken).resolve("holds"), "not json\n");
        java.nio.file.Files.createDirectory(root.resolve("stray\u001b[2J"));

        final HeldStandIns.Ran ran = HeldStandIns.held(journal);
        final HeldStandIns.Ran json = HeldStandIns.held(journal, "--json");

        assertThat(ran.code()).as(ran.err()).isZero();
        assertThat(ran.out()).as("the readable task's item").contains(healthy.id());
        assertThat(ran.err().lines().toList())
                .as("one line for each entry that cannot be read")
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).as("the task").contains(broken))
                .anySatisfy(line -> assertThat(line).as("the stray entry").contains("stray"));
        assertThat(ran.err()).as("escaped").doesNotContain("\u001b");
        assertThat(json.code()).as(json.err()).isZero();
        assertThat((List<?>) Json.parse(json.out())).as("the readable item as JSON").hasSize(1);
        assertThat(json.err().lines().count()).as("named as JSON too").isEqualTo(2);
    }

    @Test
    void nothingListedAndAnEntryThatCannotBeReadFailsTheListing(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path root = dir.resolve("holds");
        final org.fuin.porelo.gate.review.HoldJournal journal =
                new org.fuin.porelo.gate.review.HoldJournal(root);
        HeldStandIns.heldIn(journal, TASK);
        java.nio.file.Files.writeString(root.resolve(TASK).resolve("holds"), "not json\n");

        final HeldStandIns.Ran ran = HeldStandIns.held(journal);

        assertThat(ran.code()).as("nothing could be listed").isEqualTo(70);
        assertThat(ran.err()).as("names the task").contains(TASK);
    }

    @Test
    void aDecisionAnswersNothingAndARefusalIsNamedWithItsExitCode() {
        final Held out = outgoing("h1", "Keep the order.", 1);
        holds.with(out);

        final HeldStandIns.Ran changed =
                HeldStandIns.decide(holds, TASK, "h1", "release", "--digest", "0".repeat(64));
        final HeldStandIns.Ran wrong =
                HeldStandIns.decide(holds, TASK, "h1", "pass", "--digest", out.digest());
        final HeldStandIns.Ran unknown =
                HeldStandIns.decide(holds, TASK, "h1", "approve", "--digest", out.digest());
        final HeldStandIns.Ran refused =
                HeldStandIns.decide(holds, TASK, "h1", "refuse", "--digest", out.digest());

        assertThat(changed.code()).as("a changed digest").isEqualTo(70);
        assertThat(changed.err()).as("named").contains("DigestChanged", "h1");
        assertThat(wrong.code()).as("pass on an outgoing item").isEqualTo(64);
        assertThat(unknown.code()).as("no decision").isEqualTo(64);
        assertThat(refused.code()).as(refused.err()).isZero();
        assertThat(refused.out()).as("nothing said on success").isEmpty();
        assertThat(holds.decided).as("only the refusal").containsExactly(TASK + " h1 REFUSE");
    }

    /** An answer's object, its keys as text, so it can be asserted by name. */
    private static Map<String, Object> fields(final Object value) {
        final Map<String, Object> fields = new java.util.LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, each) -> fields.put(String.valueOf(key), each));
        return fields;
    }
}
