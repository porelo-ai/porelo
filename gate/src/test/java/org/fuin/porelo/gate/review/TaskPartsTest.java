package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskPartsTest {

    @Test
    void aStartNamingNoPartGetsOwnWorkServicesPushReplyAndNoJudgment() {
        final TaskParts parts = TaskParts.ownWork();

        assertThat(parts.worksOnItsOwn()).as("own work").isTrue();
        assertThat(parts.gated()).as("a gate").isTrue();
        assertThat(parts.projectServices()).as("the project's services").isTrue();
        assertThat(parts.writesAsOpener()).as("the account that opened its pull request").isTrue();
        assertThat(parts.holds(Right.PUSH)).as("push").isTrue();
        assertThat(parts.holds(Right.REPLY)).as("reply").isTrue();
        for (final Right other :
                List.of(
                        Right.OPEN_THREADS,
                        Right.RESOLVE_THREADS,
                        Right.START,
                        Right.STOP,
                        Right.DONE,
                        Right.REPORT,
                        Right.REJECT)) {
            assertThat(parts.holds(other)).as("no " + other.word() + " from a default").isFalse();
        }
        assertThat(parts.broken()).as("the default breaks no rule").isEmpty();
    }

    @Test
    void aTaskAtAnothersWorkHasNoServicesNoPushAndTheRightsNamedAlone() {
        final TaskParts parts = TaskParts.anothersWork(List.of(Right.REPLY, Right.OPEN_THREADS));

        assertThat(parts.readsAnothersWork()).as("another task's work").isTrue();
        assertThat(parts.worksOnItsOwn()).as("not its own").isFalse();
        assertThat(parts.gated()).as("a gate that serves the frozen work").isTrue();
        assertThat(parts.projectServices()).as("no project services").isFalse();
        assertThat(parts.writesAsOpener()).as("the forge instance's account").isFalse();
        assertThat(parts.holds(Right.PUSH)).as("no push").isFalse();
        assertThat(parts.holds(Right.OPEN_THREADS)).as("openThreads, as named").isTrue();
        assertThat(parts.broken()).as("it breaks no rule").isEmpty();
        assertThat(TaskParts.anothersWork(List.of()).holds(Right.REPLY))
                .as("no right comes from a default there")
                .isFalse();
    }

    @Test
    void aTaskWithoutAGateHoldsNoRightAndKeepsTheServices() {
        assertThat(TaskParts.withoutGate(false, false))
                .as("no workspace")
                .satisfies(
                        parts -> {
                            assertThat(parts.gated()).as("no gate").isFalse();
                            assertThat(parts.holds(Right.PUSH)).as("no push").isFalse();
                            assertThat(parts.holds(Right.REPLY)).as("no reply").isFalse();
                            assertThat(parts.projectServices()).as("services").isTrue();
                            assertThat(parts.broken()).as("no rule broken").isEmpty();
                        });
        assertThat(TaskParts.withoutGate(true, false))
                .as("every repository read only is another kind than one repository read only")
                .isNotEqualTo(TaskParts.withoutGate(false, true))
                .isNotEqualTo(TaskParts.withoutGate(false, false));
    }

    @Test
    void eachOfPorelosOwnRulesRefusesNamingItselfAndThePartThatBreaksIt() {
        assertThat(broken("foreign", false, "review", "push"))
                .as("another task's work with push")
                .isEqualTo(new TaskParts.Broken("foreignWorkLeadsOut", "push"));
        assertThat(broken("foreign", true, "review", "reply"))
                .as("another task's work with the project's services")
                .isEqualTo(new TaskParts.Broken("foreignWorkLeadsOut", "projectServices"));
        assertThat(broken("none", true, "author", "reject", "push"))
                .as("reject together with push")
                .isEqualTo(new TaskParts.Broken("rejectWithPush", "push"));
        assertThat(broken("own", true, "author", "reject", "reply"))
                .as("reject over own work")
                .isEqualTo(new TaskParts.Broken("rejectOverOwnWork", "own"));
        assertThat(broken("readOnly", true, "author", "push"))
                .as("push without own work")
                .isEqualTo(new TaskParts.Broken("pushWithoutOwnWork", "readOnly"));
        assertThat(broken("foreign", false, "review", "reject", "push"))
                .as("reject together with push at another task's work names the judgment")
                .isEqualTo(new TaskParts.Broken("rejectWithPush", "push"));
        assertThat(parts("foreign", false, "review", "reject", "reply").broken())
                .as("reject over another task's work, with no push, breaks none")
                .isEmpty();
    }

    @Test
    void rejectWithoutAnotherTasksWorkIsRefusedWhateverElseTheWorkspaceIs() {
        // Only a waiting push of another task is a reject's place: a task reading landed work, all
        // repositories or nothing has no push it could discard.
        for (final String workspace : List.of("readOnly", "allRepositories", "none")) {
            assertThat(broken(workspace, true, "author", "reject", "reply"))
                    .as("reject with workspace %s", workspace)
                    .isEqualTo(new TaskParts.Broken("rejectOverOwnWork", workspace));
        }
    }

    @Test
    void thePartsReadBackAsTheyWereRecorded() {
        final TaskParts recorded =
                TaskParts.anothersWork(List.of(Right.OPEN_THREADS, Right.REPORT))
                        .readingLanded(List.of("shop-1", "shop-2"));

        assertThat(TaskParts.of(recorded.document())).as("read back").isEqualTo(recorded);
        assertThat(recorded.document())
                .as("the record writes each part out, by the contract's words")
                .containsEntry("workspace", "foreign")
                .containsEntry("projectServices", Boolean.FALSE)
                .containsEntry("writeAs", "review")
                .containsEntry("readLanded", List.of("shop-1", "shop-2"))
                .containsEntry("rights", List.of("openThreads", "report"));
        assertThat(TaskParts.of(TaskParts.ownWork().document()))
                .as("the default set read back")
                .isEqualTo(TaskParts.ownWork());
    }

    @Test
    void aRecordThatIsNoPartsDocumentIsRefusedAndNeverReadAsADefault() {
        final Map<String, Object> whole = new LinkedHashMap<>(TaskParts.ownWork().document());

        for (final String member : Set.copyOf(whole.keySet())) {
            final Map<String, Object> lacking = new LinkedHashMap<>(whole);
            lacking.remove(member);
            assertThatThrownBy(() -> TaskParts.of(lacking))
                    .as("a record without " + member)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TaskParts.of(with(whole, "rights", List.of("push", "approve"))))
                .as("a right no start records")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approve");
        assertThatThrownBy(() -> TaskParts.of(with(whole, "workspace", "REVIEWER")))
                .as("a role word is no kind of workspace")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskParts.of(with(whole, "writeAs", "owner")))
                .as("an account no start records")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskParts.of("AUTHOR"))
                .as("the record of an older Porelo, a bare role")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRightIsNamedByItsContractWordAndNoOther() {
        assertThat(java.util.Arrays.stream(Right.values()).map(Right::word))
                .as("the contract's Right, in its order")
                .containsExactly(
                        "push",
                        "reply",
                        "openThreads",
                        "resolveThreads",
                        "start",
                        "stop",
                        "done",
                        "report",
                        "reject");
        assertThat(Right.named("openThreads")).as("by its word").contains(Right.OPEN_THREADS);
        assertThat(Right.named("OPEN_THREADS")).as("never by its constant's name").isEmpty();
        assertThat(Right.named("approve")).as("no right lets work on").isEmpty();
    }

    private static TaskParts.Broken broken(
            final String workspace,
            final boolean services,
            final String account,
            final String... rights) {
        return parts(workspace, services, account, rights).broken().orElseThrow();
    }

    /** Parts as a record would hold them, so a test reaches the ones no factory gives. */
    private static TaskParts parts(
            final String workspace,
            final boolean services,
            final String account,
            final String... rights) {
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("workspace", workspace);
        document.put("readLanded", List.of());
        document.put("projectServices", Boolean.valueOf(services));
        document.put("writeAs", account);
        document.put("rights", List.of(rights));
        return TaskParts.of(document);
    }

    private static Map<String, Object> with(
            final Map<String, Object> document, final String member, final Object value) {
        final Map<String, Object> changed = new LinkedHashMap<>(document);
        changed.put(member, value);
        return changed;
    }
}
