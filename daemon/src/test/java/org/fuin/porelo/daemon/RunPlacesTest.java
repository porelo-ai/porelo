package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.app.OwnPullRequests;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.app.RunPlaceCheck;
import org.fuin.porelo.app.StartParts;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start's parts and place: a task at its own work at its own gate push or pull request, or at
 * another task's pull request with the rights it names, is taken; anything else is refused before
 * anything is made, whether the task runs or not.
 */
class RunPlacesTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    private static final String PULL_REQUEST = "https://forge.example.org/uc/web-app/pull/7";

    private static final String ENTRY = "git.token.forge.example.org";

    /** The entry an author off a host writes under at that forge. */
    private static final String AUTHOR_ENTRY = "git.author.forge.example.org";

    /** The parts a start at another task's pull request records, naming reply alone. */
    private static final TaskParts READS_REPLY =
            new TaskParts.Asked(Set.of(Right.REPLY), null, null, null, false, false, false)
                    .atAnothersWork();

    private static final Map<String, Object> OWN = Map.of("ownPullRequest", true);

    private final StandInAdapter adapter = new StandInAdapter();

    private final StandInForges forges = new StandInForges();

    private final List<RunPlace> started = new java.util.ArrayList<>();

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context;

    private Path projectFile;

    @BeforeEach
    void machine() throws IOException {
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                new XdgPaths(
                                        dir.resolve("config"),
                                        dir.resolve("data"),
                                        dir.resolve("state"),
                                        dir.resolve("run"),
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        // Where this machine keeps a project of its own, so a call naming "demo" finds it.
        projectFile = context.paths().projects().defaultProjectFile("demo");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(
                projectFile,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  web:
                    upstream: "https://forge.example.org/uc/web-app.git"
                """);
    }

    @Test
    void aStartNamingNeitherLeavesTheLaunchItsOwnPushWithTheDefaultParts() {
        final StartParts taken = taken(Map.of());

        assertThat(taken.place()).as("the launch fills the place").isNull();
        assertThat(taken.parts()).as("own work, push and reply").isEqualTo(TaskParts.ownWork());
        assertThat(taken.asked().namesAny()).as("nothing named").isFalse();
    }

    @Test
    void aTaskAtItsOwnGatePushIsTaken() {
        final RunPlace own = new RunPlace(TaskParts.ownWork(), null, "fix");

        assertThat(taken(Map.of("place", push(null, "fix"))).place())
                .as("named without its repository, which is the task's own")
                .isEqualTo(own);
        assertThat(taken(Map.of("place", push("demo", "fix"))).place())
                .as("named by the project's own repository: its own work")
                .isEqualTo(own);
    }

    @Test
    void aStartNamingRoleOrReadsIsRefusedAsAnUnknownParameter() {
        for (final String gone : List.of("role", "reads")) {
            final VarlinkException refused =
                    refused(Map.of(gone, "role".equals(gone) ? "AUTHOR" : List.of("other")));

            assertThat(refused.getErrorName())
                    .as("%s is no member of Start", gone)
                    .isEqualTo("org.varlink.service.InvalidParameter");
            assertThat(refused.getParameters()).as("named").isEqualTo(Map.of("parameter", gone));
            assertThat(
                            canStartRefused(Map.of("project", "demo", "task", "fix", gone, "x"))
                                    .getParameters())
                    .as("%s refused by CanStart too", gone)
                    .isEqualTo(Map.of("parameter", gone));
        }
    }

    @Test
    void namedRightsReplaceTheDefaultSetAndAnEmptyListHoldsNone() {
        final StartParts some = taken(Map.of("rights", List.of("reply", "report")));
        final StartParts none = taken(Map.of("rights", List.of()));

        assertThat(some.parts().document().get("rights"))
                .as("the rights named alone")
                .isEqualTo(List.of("reply", "report"));
        assertThat(none.parts().document().get("rights"))
                .as("an empty list is no right, never the default set")
                .isEqualTo(List.of());
        assertThat(none.parts().holds(Right.PUSH)).as("no push").isFalse();
    }

    @Test
    void aRightNoStartNamesOrAMemberOfAnotherShapeIsAnInvalidParameter() {
        for (final Map<String, Object> parameters :
                List.<Map<String, Object>>of(
                        Map.of("rights", List.of("approve")),
                        Map.of("rights", "push"),
                        Map.of("writeAs", "owner"),
                        Map.of("projectServices", "yes"),
                        Map.of("readLanded", List.of("../other")))) {
            assertThat(refused(parameters).getParameters())
                    .as("%s", parameters)
                    .isEqualTo(Map.of("parameter", parameters.keySet().iterator().next()));
        }
    }

    @Test
    void writeAsAtAGatePushOrWithoutAPlaceIsRefusedNeverIgnored() {
        for (final Map<String, Object> parameters :
                List.<Map<String, Object>>of(
                        Map.of("writeAs", "author"),
                        Map.of("writeAs", "author", "place", push(null, "fix")))) {
            final VarlinkException refused = refused(parameters);

            assertThat(refused.getErrorName())
                    .as("%s", parameters)
                    .isEqualTo("org.varlink.service.InvalidParameter");
            assertThat(refused.getParameters()).isEqualTo(Map.of("parameter", "writeAs"));
        }
    }

    @Test
    void aJudgmentOverItsOwnWorkIsStartRuleBroken() {
        for (final Map<String, Object> parameters :
                List.<Map<String, Object>>of(
                        Map.of("rights", List.of("reply", "reject")),
                        Map.of("rights", List.of("reject"), "place", push(null, "fix")))) {
            final VarlinkException refused = refused(parameters);

            assertThat(refused.getErrorName())
                    .as("%s", parameters)
                    .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
            assertThat(refused.getParameters())
                    .as("the rule and the part that breaks it")
                    .isEqualTo(Map.of("rule", "rejectOverOwnWork", "detail", "own"));
        }
    }

    @Test
    void pushWithoutAGateIsStartRuleBroken() {
        final VarlinkException refused =
                refused(Map.of("readOnly", true, "rights", List.of("push")));

        assertThat(refused.getErrorName())
                .as("push named for a task that only reads")
                .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
        assertThat(refused.getParameters())
                .isEqualTo(Map.of("rule", "pushWithoutOwnWork", "detail", "readOnly"));
    }

    @Test
    void aTaskAtItsOwnPushInAnotherRepositoryIsTakenNamedByIt() {
        assertThat(
                        RunPlaces.taken(
                                        context,
                                        Map.of("place", push("web", "fix")),
                                        projectFile,
                                        "fix",
                                        "web")
                                .place())
                .as("the repository is kept: it is not the project's own")
                .isEqualTo(new RunPlace(TaskParts.ownWork(), "web", "fix"));
    }

    @Test
    void anotherTasksPushIsNotTheTasksPushWithTheHeadThatWaitsThereForATaskWithoutAGate()
            throws IOException {
        Files.createDirectories(dir.resolve("data/mirrors/demo.git"));
        runner.answering("--format=%H%n%s", HEAD + "\nother work\n");

        final VarlinkException refused =
                refused(Map.of("noGate", true, "place", push(null, "other")));

        assertThat(refused.getErrorName())
                .as("another task's push, for a task with no push of its own")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");
        assertThat(refused.getParameters())
                .as("the head that waits there")
                .isEqualTo(Map.of("head", HEAD));
    }

    @Test
    void anotherTasksPushIsAnotherTasksWorkForATaskWithAGateNeverItsOwn() throws IOException {
        Files.createDirectories(dir.resolve("data/mirrors/demo.git"));
        runner.answering("--format=%H%n%s", HEAD + "\nother work\n");
        pushedBy("other");

        assertThat(refused(Map.of("place", push(null, "other"))).getParameters())
                .as("no default rights there")
                .isEqualTo(Map.of("rule", "foreignWorkNamesNoRights", "detail", "rights"));
        final StartParts taken =
                taken(Map.of("rights", List.of("reply"), "place", push(null, "other")));
        assertThat(taken.parts().readsAnothersWork())
                .as("the place decides: another task's work")
                .isTrue();
        assertThat(taken.parts().document())
                .as("no services there")
                .containsEntry("projectServices", Boolean.FALSE);
        assertThat(java.util.Objects.requireNonNull(taken.place()).name())
                .as("the push it reads")
                .isEqualTo("other");
    }

    @Test
    void rejectIsTakenOnlyAtAnotherTasksWaitingPushAndNeverBesidePushOrOverOwnWork()
            throws IOException {
        Files.createDirectories(dir.resolve("data/mirrors/demo.git"));
        runner.answering("--format=%H%n%s", HEAD + "\nother work\n");
        pushedBy("other");

        final StartParts taken =
                taken(Map.of("rights", List.of("reject"), "place", push(null, "other")));
        assertThat(taken.parts().holds(org.fuin.porelo.gate.review.Right.REJECT))
                .as("reject at another task's waiting push is taken")
                .isTrue();
        assertThat(taken.parts().readsAnothersWork()).as("another task's work").isTrue();
        assertThat(
                        refused(
                                        Map.of(
                                                "rights",
                                                List.of("reject", "push"),
                                                "place",
                                                push(null, "other")))
                                .getParameters())
                .as("reject beside push, at another task's push")
                .isEqualTo(Map.of("rule", "rejectWithPush", "detail", "push"));
        assertThat(
                        refused(Map.of("rights", List.of("reject"), "place", push(null, "fix")))
                                .getParameters())
                .as("reject at its own push")
                .isEqualTo(Map.of("rule", "rejectOverOwnWork", "detail", "own"));
    }

    @Test
    void anotherTasksPushNoRecordedTaskIsKnownForIsRefusedBeforeAnythingIsMade()
            throws IOException {
        // The run's place binds the push to the task that pushed it, and refuses one without; the
        // start asks the same, before the task exists.
        Files.createDirectories(dir.resolve("data/mirrors/demo.git"));
        runner.answering("--format=%H%n%s", HEAD + "\nother work\n");

        assertThat(
                        refused(Map.of("rights", List.of("reply"), "place", push(null, "other")))
                                .getParameters())
                .as("a push no task is recorded for")
                .containsEntry("reason", "NoSuchPush");
    }

    @Test
    void aPushOfAnotherRepositoryOrNoneWaitingHasAnEmptyHead() {
        for (final Map<String, Object> place :
                List.of(push("web", "fix"), push("nowhere", "fix"), push(null, "other"))) {
            final VarlinkException refused = refused(Map.of("noGate", true, "place", place));

            assertThat(refused.getErrorName())
                    .as("%s", place)
                    .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");
            assertThat(refused.getParameters())
                    .as("nothing waits there: %s", place)
                    .isEqualTo(Map.of("head", ""));
        }
        assertThat(runner.invocations())
                .as(
                        "only the runtime is listed, no mirror of a repository the project lacks is looked into")
                .allMatch(command -> command.describe().startsWith("podman ps "));
    }

    @Test
    void anotherTasksPushOfNoRepositoryOfTheProjectOrWithNothingWaitingIsUnreadable() {
        assertThat(
                        refused(Map.of("rights", List.of("reply"), "place", push("nowhere", "fix")))
                                .getParameters())
                .as("a repository the project does not name")
                .containsEntry("reason", "NotInProject");
        assertThat(
                        refused(Map.of("rights", List.of("reply"), "place", push(null, "other")))
                                .getParameters())
                .as("a name nothing waits under")
                .containsEntry("reason", "NoSuchPush");
    }

    @Test
    void aTaskAtItsOwnPullRequestIsTakenAtTheOneOpenFromItsBranchAndReachesTheStart() {
        final RunPlace taken = ownAt(Map.of("place", OWN));

        assertThat(taken)
                .as("own work at the pull request open from its branch")
                .isEqualTo(RunPlace.atPullRequest(TaskParts.ownWork(), "fix", PULL_REQUEST));
        assertThat(started).as("the start is told of it").containsExactly(taken);
        assertThat(adapter.asked)
                .as("the forge's adapter, asked with its lent credential about the task's branch")
                .containsExactly(
                        "forgejo "
                                + ENTRY
                                + " https://forge.example.org/uc/web-app.git porelo/fix");
    }

    @Test
    void noPullRequestOpenFromItsBranchIsNoPullRequestNamingTheBranch() {
        adapter.open = Optional.empty();

        final VarlinkException refused = ownRefused(Map.of("place", OWN));

        assertThat(refused.getErrorName())
                .as("none open")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NoPullRequest");
        assertThat(refused.getParameters())
                .as("the branch it was looked for from")
                .isEqualTo(Map.of("branch", "porelo/fix"));
        assertThat(started).as("nothing is started").isEmpty();
    }

    /** A project with an upstream of its own and two repositories with theirs. */
    private void upstreams() throws IOException {
        Files.writeString(
                projectFile,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                  upstream: "https://forge.example.org/uc/demo.git"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  web:
                    upstream: "https://forge.example.org/uc/web-app.git"
                  api:
                    upstream: "https://forge.example.org/uc/api-app.git"
                """);
    }

    /** Leaves the durable record of the task that pushed under {@code task}, gone since. */
    private void pushedBy(final String task) throws IOException {
        final String container =
                context.tasks()
                        .containerName(
                                org.fuin.porelo.core.project.ProjectReader.read(projectFile),
                                task,
                                null);
        final Path runtime = context.paths().tasks().containerState(container);
        final Path durable = context.paths().tasks().taskRecord(container);
        org.fuin.porelo.wire.Sidecar.recordId(durable, "fedcba9876543210");
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        "demo",
                        "guarded",
                        runtime.resolve("ruleset.nft").toString(),
                        runtime.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        runtime.toString(),
                        task,
                        null,
                        null,
                        org.fuin.porelo.wire.LimitFactors.DEFAULT)
                .writeTo(durable.resolve("sidecar.json"));
    }

    /** Leaves the record of the task {@code fix} and its runtime row at {@code repository}. */
    private void recordedAt(final String repository, final boolean listed) throws IOException {
        final String container =
                context.tasks()
                        .containerName(
                                org.fuin.porelo.core.project.ProjectReader.read(projectFile),
                                "fix",
                                null);
        final Path runtime = context.paths().tasks().containerState(container);
        final Path durable = context.paths().tasks().taskRecord(container);
        org.fuin.porelo.wire.Sidecar.recordId(durable, "0123456789abcdef");
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        "demo",
                        "guarded",
                        runtime.resolve("ruleset.nft").toString(),
                        runtime.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        runtime.toString())
                .writeTo(durable.resolve("sidecar.json"));
        runner.answering(
                "ps",
                listed
                        ? container
                                + "\tExited (0) 4 minutes ago\t1788500000\t0\tdemo\tguarded\t"
                                + repository
                                + "\t\t0123456789abcdef\tfix\n"
                        : "");
    }

    /** Takes the own pull request of the task {@code fix}, naming {@code repository} or none. */
    private StartParts ownTaken(final @org.jspecify.annotations.Nullable String repository) {
        return RunPlaces.taken(
                context,
                Map.of("place", OWN),
                projectFile,
                "fix",
                repository,
                forges,
                new OwnPullRequests(adapter, entry -> new ForgeCredential(entry, "lent")),
                (task, place, ref) -> started.add(place));
    }

    @Test
    void anExistingTaskStartedWithNoRepositoryNamedIsCheckedAtTheRepositoryItRecorded()
            throws IOException {
        upstreams();
        recordedAt("web", true);
        adapter.open = Optional.empty();

        final VarlinkException refused =
                catchThrowableOfType(() -> ownTaken(null), VarlinkException.class);

        assertThat(refused).as("an unnamed repository is the recorded one").isNotNull();
        assertThat(refused.getErrorName())
                .as("none open")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NoPullRequest");
        assertThat(refused.getParameters())
                .as("the branch it was looked for from")
                .isEqualTo(Map.of("branch", "porelo/fix"));
        assertThat(adapter.asked)
                .as("the recorded repository's upstream, not the project's")
                .containsExactly(
                        "forgejo "
                                + ENTRY
                                + " https://forge.example.org/uc/web-app.git porelo/fix");
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void aRepositoryNamedOtherThanTheRecordedOneDoesNotChangeWhereTheTaskIsChecked()
            throws IOException {
        upstreams();
        recordedAt("web", true);

        ownTaken("api");

        assertThat(adapter.asked)
                .as("the recorded repository's upstream, whatever the call names")
                .containsExactly(
                        "forgejo "
                                + ENTRY
                                + " https://forge.example.org/uc/web-app.git porelo/fix");
    }

    @Test
    void aTaskTheRuntimeDoesNotListIsCheckedAtWhatTheCallNames() throws IOException {
        upstreams();
        recordedAt("web", false);

        ownTaken("api");

        assertThat(adapter.asked)
                .as("no row, no recorded repository: the call's own")
                .containsExactly(
                        "forgejo "
                                + ENTRY
                                + " https://forge.example.org/uc/api-app.git porelo/fix");
    }

    @Test
    void aRuntimeListingThatFailsRefusesTheStartAsPlaceUnreadable() throws IOException {
        upstreams();
        runner.failing("ps", 125, "podman is down");

        final VarlinkException refused =
                catchThrowableOfType(() -> ownTaken("web"), VarlinkException.class);

        assertThat(refused).as("unchecked is refused").isNotNull();
        assertThat(refused.getErrorName())
                .as("by the place's own refusal")
                .isEqualTo(PoreloDaemon.INTERFACE + ".PlaceUnreadable");
        assertThat(refused.getParameters())
                .as("with its reason and a short cause")
                .containsEntry("reason", "Unreachable")
                .containsKey("detail");
        assertThat(adapter.asked).as("the forge is not asked").isEmpty();
    }

    @Test
    void aStartAtAnotherTasksPullRequestNamingItsRightsDoesNotLookTheTaskUp() throws IOException {
        runner.failing("ps", 125, "podman is down");

        takenAt(pullRequest());

        assertThat(runner.invocations())
                .as(
                        "another task's pull request, with the rights named, uses nothing of the record")
                .noneMatch(command -> command.describe().contains(" ps "));
    }

    @Test
    void anotherTasksPullRequestNamingNoRightsIsRefusedBeforeTheForgeIsAsked() {
        final VarlinkException refused =
                refusedAt(Map.of("place", Map.of("pullRequest", PULL_REQUEST)));

        assertThat(refused.getErrorName())
                .as("another task's work has no default rights")
                .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
        assertThat(refused.getParameters())
                .isEqualTo(Map.of("rule", "foreignWorkNamesNoRights", "detail", "rights"));
        assertThat(forges.asked).as("no entry of the vault is asked for").isEmpty();
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void servicesOrPushAskedAtAnotherTasksPullRequestLeadOut() {
        for (final Map<String, Object> parameters :
                List.<Map<String, Object>>of(
                        Map.of(
                                "rights",
                                List.of("reply"),
                                "projectServices",
                                true,
                                "place",
                                Map.of("pullRequest", PULL_REQUEST)),
                        Map.of(
                                "rights",
                                List.of("push"),
                                "place",
                                Map.of("pullRequest", PULL_REQUEST)))) {
            final VarlinkException refused = refusedAt(parameters);

            assertThat(refused.getErrorName())
                    .as("%s", parameters)
                    .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
            assertThat(refused.getParameters()).containsEntry("rule", "foreignWorkLeadsOut");
        }
        assertThat(forges.asked).as("no entry of the vault is asked for").isEmpty();
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void aPullRequestAtNoRepositoryOfTheProjectIsNotInProjectBeforeAnyTokenIsLent() {
        final VarlinkException refused =
                refusedAt(
                        Map.of(
                                "rights",
                                List.of("reply"),
                                "place",
                                Map.of(
                                        "pullRequest",
                                        "https://forge.example.org/elsewhere/other/pull/3")));

        assertThat(refused.getErrorName())
                .as("a pull request of no repository the project names")
                .isEqualTo(PoreloDaemon.INTERFACE + ".PlaceUnreadable");
        assertThat(refused.getParameters()).containsEntry("reason", "NotInProject");
        assertThat(forges.asked).as("no entry of the vault is asked for").isEmpty();
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void eitherAccountIsTakenAtEachKindOfPullRequestAndAbsentIsAuthorAtBoth() {
        for (final String named : List.of("author", "review")) {
            final Map<String, Object> atAnothers =
                    Map.of(
                            "rights",
                            List.of("reply"),
                            "writeAs",
                            named,
                            "place",
                            Map.of("pullRequest", PULL_REQUEST));
            assertThat(takenAt(atAnothers).parts().document())
                    .as("%s at another task's pull request", named)
                    .containsEntry("writeAs", named);
            assertThat(
                            ownAt(
                                            Map.of(
                                                    "writeAs",
                                                    named,
                                                    "place",
                                                    Map.of("pullRequest", PULL_REQUEST)))
                                    .parts()
                                    .document())
                    .as("%s at its own pull request", named)
                    .containsEntry("writeAs", named);
        }
        assertThat(takenAt(pullRequest()).parts().document())
                .as("absent at another task's pull request")
                .containsEntry("writeAs", "author");
        assertThat(ownAt(Map.of("place", Map.of("pullRequest", PULL_REQUEST))).parts().document())
                .as("absent at its own pull request")
                .containsEntry("writeAs", "author");
    }

    @Test
    void aJudgmentAtItsOwnPullRequestIsStartRuleBrokenAndTheForgeIsNotAsked() {
        final VarlinkException refused =
                ownRefused(Map.of("rights", List.of("reply", "reject"), "place", OWN));

        assertThat(refused.getErrorName())
                .as("a judgment over its own work")
                .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
        assertThat(refused.getParameters())
                .as("the rule and the part")
                .isEqualTo(Map.of("rule", "rejectOverOwnWork", "detail", "own"));
        assertThat(adapter.asked).as("the forge is not asked").isEmpty();
    }

    @Test
    void anOwnPullRequestAtAForgeUnknownOrWithoutItsTokenIsRefusedBeforeTheForgeIsAsked() {
        forges.holds = Optional.of(false);
        assertThat(ownRefused(Map.of("place", OWN)).getParameters())
                .as("no token for the upstream's forge")
                .containsEntry("reason", "MissingForgeToken");

        forges.known.clear();
        final VarlinkException unknown = ownRefused(Map.of("place", OWN));
        assertThat(unknown.getErrorName())
                .as("an upstream at a forge this machine does not know")
                .isEqualTo(PoreloDaemon.INTERFACE + ".UnknownForge");
        assertThat(unknown.getParameters())
                .as("its host")
                .isEqualTo(Map.of("host", "forge.example.org"));
        assertThat(adapter.asked).as("the forge is not asked").isEmpty();
    }

    @Test
    void aTaskAtAnotherTasksPullRequestOfAKnownForgeIsTakenAndReachesTheStart() {
        final RunPlace taken = takenAt(pullRequest());

        assertThat(taken)
                .as("the place recorded, with the rights named and nothing that leads out")
                .isEqualTo(RunPlace.atPullRequest(READS_REPLY, "fix", PULL_REQUEST));
        assertThat(taken.place())
                .as("the place as the contract gives it")
                .isEqualTo(Map.of("pullRequest", PULL_REQUEST));
        assertThat(started).as("what reached the run's start").containsExactly(taken);
        assertThat(forges.asked).as("the vault was asked for the entry only").contains(ENTRY);
    }

    @Test
    void anAuthorOffAHostWithoutItsAuthorEntryIsRefusedAtThePullRequestNamingTheEntry() {
        forges.lacking.add(AUTHOR_ENTRY);

        for (final Map<String, Object> place :
                List.<Map<String, Object>>of(
                        Map.of("place", Map.of("pullRequest", PULL_REQUEST)),
                        Map.of(
                                "writeAs",
                                "author",
                                "place",
                                Map.of("pullRequest", PULL_REQUEST)))) {
            final VarlinkException refused =
                    catchThrowableOfType(() -> ownAt(place), VarlinkException.class);
            assertThat(refused).as("refused: %s", place).isNotNull();
            assertThat(refused.getErrorName())
                    .as("an author with no entry of its own: %s", place)
                    .isEqualTo(PoreloDaemon.INTERFACE + ".PlaceUnreadable");
            assertThat(refused.getParameters())
                    .as("the reason")
                    .containsEntry("reason", "MissingForgeToken");
            assertThat(String.valueOf(refused.getParameters().get("detail")))
                    .as("the entry to store, and how")
                    .contains(AUTHOR_ENTRY, "porelo vault put " + AUTHOR_ENTRY);
        }
        assertThat(ownRefused(Map.of("place", OWN)).getParameters())
                .as("an author at its own pull request, with no entry of its own")
                .containsEntry("reason", "MissingForgeToken");
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void aTaskAtAnotherTasksWorkWritingAsTheReviewAccountNeedsNoAuthorEntry() {
        forges.lacking.add(AUTHOR_ENTRY);

        final RunPlace taken =
                takenAt(
                        Map.of(
                                "rights",
                                List.of("reply"),
                                "writeAs",
                                "review",
                                "place",
                                Map.of("pullRequest", PULL_REQUEST)));

        assertThat(taken.parts().writesAsOpener())
                .as("it writes under the forge's entry")
                .isFalse();
        assertThat(forges.asked)
                .as("the author's entry is not asked for")
                .doesNotContain(AUTHOR_ENTRY);
    }

    @Test
    void aTaskAtAnotherTasksWorkWritingAsAuthorIsHeldToTheAuthorEntryAsAnyAuthor() {
        forges.lacking.add(AUTHOR_ENTRY);

        final VarlinkException refused = refusedAt(pullRequest());

        assertThat(refused.getParameters())
                .as("absent is author: the account is checked by the account, not the workspace")
                .containsEntry("reason", "MissingForgeToken");
        assertThat(String.valueOf(refused.getParameters().get("detail")))
                .as("the entry to store")
                .contains(AUTHOR_ENTRY);
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void anAuthorOffAHostIsTakenWithItsAuthorEntry() {
        ownAt(Map.of("place", Map.of("pullRequest", PULL_REQUEST)));

        assertThat(forges.asked)
                .as("the forge's entry and the author's own, never one for the other")
                .contains(ENTRY, AUTHOR_ENTRY);
    }

    @Test
    void thePullRequestOpenFromTheTasksBranchIsItsOwnWorkByItsAddress() {
        assertThat(ownAt(Map.of("place", Map.of("pullRequest", PULL_REQUEST))))
                .as("a start naming only the place, which is its own pull request")
                .isEqualTo(RunPlace.atPullRequest(TaskParts.ownWork(), "fix", PULL_REQUEST));
    }

    @Test
    void anotherPullRequestOpenFromTheTasksBranchMakesThisOneAnotherTasksWork() {
        adapter.open = Optional.of("https://forge.example.org/uc/web-app/pull/8");

        assertThat(ownAt(pullRequest()).parts().readsAnothersWork())
                .as("the forge names another pull request as the task's own")
                .isTrue();
    }

    @Test
    void anExistingTaskOfItsOwnWorkWithNoPullRequestOpenCannotBeToldAndIsRefused()
            throws IOException {
        upstreams();
        recordedAt("web", true);
        adapter.open = Optional.empty();

        final VarlinkException refused = ownRefused(pullRequest());

        assertThat(refused.getErrorName())
                .as("a failed lookup is never taken for another task's work")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NoPullRequest");
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void aPullRequestWhoseProjectFileDoesNotReadIsRefusedNeverTakenAsOwnWork() throws IOException {
        Files.writeString(projectFile, "project: [not a project\n");

        final VarlinkException refused =
                ownRefused(Map.of("place", Map.of("pullRequest", PULL_REQUEST)));

        assertThat(refused.getErrorName())
                .as("own work and its defaults are never taken from a file that does not read")
                .isEqualTo(PoreloDaemon.INTERFACE + ".PlaceUnreadable");
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void aPullRequestWhereTheTasksBranchCannotBeSettledIsRefusedNeverTakenAsOwnWork() {
        final VarlinkException refused =
                ownRefused(
                        Map.of(
                                "place",
                                Map.of("pullRequest", PULL_REQUEST),
                                "branch",
                                "not..a..branch"));

        assertThat(refused.getErrorName())
                .as("own work and its defaults are never taken without the branch to tell it by")
                .isEqualTo(PoreloDaemon.INTERFACE + ".PlaceUnreadable");
        assertThat(adapter.asked).as("the forge is not asked about no branch").isEmpty();
        assertThat(started).as("nothing is started").isEmpty();
    }

    @Test
    void aPullRequestAtAHostThisMachineDoesNotKnowIsUnknownForge() {
        forges.known.clear();
        final VarlinkException refused = refusedAt(pullRequest());
        assertThat(refused.getErrorName())
                .as("an unknown forge host")
                .isEqualTo(PoreloDaemon.INTERFACE + ".UnknownForge");
        assertThat(refused.getParameters())
                .as("its host")
                .containsEntry("host", "forge.example.org");
        assertThat(started).as("what reached the run's start").isEmpty();
    }

    @Test
    void anUnreadableForgeFileIsPlaceUnreadable() {
        forges.unreadable = true;
        assertThat(refusedAt(pullRequest()).getParameters())
                .as("an unreadable forge file")
                .containsEntry("reason", "ForgeFileUnreadable");
        assertThat(started).as("what reached the run's start").isEmpty();
    }

    @Test
    void aForgeWithoutAnInstalledAdapterIsPlaceUnreadable() {
        forges.installed = false;
        assertThat(refusedAt(pullRequest()).getParameters())
                .as("an adapter that is not installed")
                .containsEntry("reason", "ForgeAdapterMissing");
        forges.installed = true;
        forges.known.put(
                "forge.example.org",
                new ForgeInstance(
                        "forge.example.org", null, CommentReading.EXTENDED, "no adapter named"));
        assertThat(refusedAt(pullRequest()).getParameters())
                .as("an entry that names no adapter")
                .containsEntry("reason", "ForgeAdapterMissing");
        assertThat(started).as("what reached the run's start").isEmpty();
    }

    @Test
    void aForgeWhoseTokenTheVaultLacksOrCannotTellIsPlaceUnreadable() {
        forges.holds = Optional.of(false);
        assertThat(refusedAt(pullRequest()).getParameters())
                .as("no token entry")
                .containsEntry("reason", "MissingForgeToken");
        assertThat(String.valueOf(refusedAt(pullRequest()).getParameters().get("detail")))
                .as("names the entry")
                .contains(ENTRY);
        forges.holds = Optional.empty();
        assertThat(refusedAt(pullRequest()).getParameters())
                .as("a vault that cannot tell")
                .containsEntry("reason", "MissingForgeToken");
        assertThat(started).as("what reached the run's start").isEmpty();
    }

    @Test
    void aPullRequestAddressOfAnotherShapeIsAnInvalidParameter() {
        for (final String address :
                List.of(
                        "porelo/fix",
                        "http://forge.example.org/a/b/pull/1",
                        "https://user@forge.example.org/a/b/pull/1",
                        "https://forge.example.org/a/b/pull/1?x=1")) {
            assertThat(refusedAt(Map.of("place", Map.of("pullRequest", address))).getErrorName())
                    .as("%s", address)
                    .isEqualTo("org.varlink.service.InvalidParameter");
        }
        assertThat(started).as("what reached the run's start").isEmpty();
    }

    @Test
    void aPlaceWithNoneOrSeveralFieldsOrAnUnknownRoleIsAnInvalidParameter() {
        final Map<String, Object> both = new HashMap<>();
        both.put("gatePush", Map.of("name", "fix"));
        both.put("ownPullRequest", true);
        final Map<String, Object> nulls = new HashMap<>();
        nulls.put("gatePush", null);
        for (final Map<String, Object> parameters :
                List.<Map<String, Object>>of(
                        Map.of("place", Map.of()),
                        Map.of("place", nulls),
                        Map.of("place", both),
                        Map.of("place", "fix"),
                        Map.of("place", Map.of("gatePush", Map.of("repository", "demo"))),
                        Map.of("role", "OWNER"),
                        Map.of("rights", List.of("owner")))) {
            final VarlinkException refused = refused(parameters);

            assertThat(refused.getErrorName())
                    .as("%s", parameters)
                    .isEqualTo("org.varlink.service.InvalidParameter");
        }
    }

    @Test
    void aRunningTaskAskedForAJudgmentOverItsWorkOrAtAnotherPushIsStillRefused() {
        // It may stop before the launch looks, and must not then start as asked.
        running("fix");
        final Map<String, Object> asked = new HashMap<>();
        asked.put("project", "demo");
        asked.put("task", "fix");
        asked.put("repository", "demo");

        asked.put("rights", List.of("reject"));
        assertThat(canStartRefused(asked).getErrorName())
                .as("a judgment over its own work, although it runs")
                .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");

        asked.remove("rights");
        asked.put("place", push(null, "other"));
        assertThat(canStartRefused(asked).getParameters())
                .as("another task's push naming no rights, although it runs")
                .containsEntry("rule", "foreignWorkNamesNoRights");

        asked.put("place", push(null, "fix"));
        TaskMethods.canStartAt(context, asked, projectFile);
    }

    @Test
    void canStartWithoutATaskNameTakesThePushStartWouldNameIt() {
        final Map<String, Object> asked = new HashMap<>();
        asked.put("project", "demo");
        asked.put("repository", "web");
        final String named = PoreloDaemon.request(asked, context).task();

        asked.put("place", push("web", named));
        TaskMethods.canStartAt(context, asked, projectFile);

        asked.put("place", push("web", "shell"));
        assertThat(canStartRefused(asked).getParameters())
                .as("any other name is another task's push, which names its rights")
                .containsEntry("rule", "foreignWorkNamesNoRights");
    }

    @Test
    void theTasksOwnPushIsTakenByItsRepositorysKeyOrFullName() {
        for (final String[] names : new String[][] {{"web", "web-app"}, {"web-app", "web"}}) {
            final Map<String, Object> asked = new HashMap<>();
            asked.put("project", "demo");
            asked.put("task", "fix");
            asked.put("repository", names[0]);
            asked.put("place", push(names[1], "fix"));

            TaskMethods.canStartAt(context, asked, projectFile);
            assertThat(
                            RunPlaces.taken(
                                            context,
                                            asked,
                                            projectFile,
                                            "fix",
                                            PoreloDaemon.request(asked, context).repository())
                                    .place())
                    .as("the task on %s, its push named by %s", names[0], names[1])
                    .isEqualTo(new RunPlace(TaskParts.ownWork(), "web", "fix"));
        }
    }

    @Test
    void aTaskWithoutAGateHasNoPushOfItsOwnAndRecordsItsPartsAlone() {
        final StartParts taken =
                taken(
                        Map.of(
                                "noGate",
                                true,
                                "rights",
                                List.of("report"),
                                "projectServices",
                                false));
        assertThat(taken.place()).as("no place").isNull();
        assertThat(taken.parts().document())
                .as("the parts named, the rest by the default of no workspace")
                .containsEntry("workspace", "none")
                .containsEntry("rights", List.of("report"))
                .containsEntry("projectServices", Boolean.FALSE);
        assertThat(refused(Map.of("noGate", true, "place", push(null, "fix"))).getErrorName())
                .as("any push it names is another's")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");
    }

    @Test
    void canStartAnswersTheSameRefusals() {
        final Map<String, Object> asked = new HashMap<>();
        asked.put("project", "demo");
        asked.put("task", "fix");
        asked.put("repository", "demo");

        asked.put("rights", List.of("reject"));
        assertThat(canStartRefused(asked).getErrorName())
                .as("a judgment over its own work, with no place named")
                .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");

        asked.remove("rights");
        asked.put("writeAs", "author");
        assertThat(canStartRefused(asked).getParameters())
                .as("an account to write as with no place named")
                .isEqualTo(Map.of("parameter", "writeAs"));

        asked.remove("writeAs");
        asked.put("place", push(null, "other"));
        assertThat(canStartRefused(asked).getParameters())
                .as("another task's push naming no rights")
                .containsEntry("rule", "foreignWorkNamesNoRights");

        asked.put("place", OWN);
        assertThat(canStartRefused(asked).getErrorName())
                .as("the own pull request of a task whose repository names no upstream")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NoPullRequest");

        asked.put("place", Map.of("pullRequest", PULL_REQUEST));
        asked.put("rights", List.of("reply"));
        assertThat(canStartRefused(asked).getErrorName())
                .as("a pull request at a forge this machine was never told of")
                .isEqualTo(PoreloDaemon.INTERFACE + ".UnknownForge");

        asked.remove("rights");
        asked.put("place", Map.of());
        assertThat(canStartRefused(asked).getErrorName())
                .as("an empty place")
                .isEqualTo("org.varlink.service.InvalidParameter");

        asked.put("place", push(null, "fix"));
        TaskMethods.canStartAt(context, asked, projectFile);
    }

    /** Says to the runtime that the task of this name runs. */
    private void running(final String task) {
        final String container =
                context.tasks()
                        .containerName(
                                org.fuin.porelo.core.project.ProjectReader.read(projectFile),
                                task,
                                null);
        runner.answering(
                "ps", container + "\tUp 4 minutes\t1788500000\t0\tdemo\tguarded\tdemo\t\t\n");
    }

    private VarlinkException canStartRefused(final Map<String, Object> parameters) {
        final VarlinkException refused =
                catchThrowableOfType(
                        () -> TaskMethods.canStartAt(context, parameters, projectFile),
                        VarlinkException.class);
        assertThat(refused).as("refused: %s", parameters).isNotNull();
        return refused;
    }

    private StartParts taken(final Map<String, Object> parameters) {
        return RunPlaces.taken(context, parameters, projectFile, "fix", "demo");
    }

    /** Another task's pull request, with the one right a start there names. */
    private static Map<String, Object> pullRequest() {
        return Map.of("rights", List.of("reply"), "place", Map.of("pullRequest", PULL_REQUEST));
    }

    /** Taken for the task of the repository without an upstream: no pull request is its own. */
    private RunPlace takenAt(final Map<String, Object> parameters) {
        final RunPlace taken =
                RunPlaces.taken(
                                context,
                                parameters,
                                projectFile,
                                "fix",
                                "demo",
                                forges,
                                (task, place, ref) -> started.add(place))
                        .place();
        assertThat(taken).as("taken: %s", parameters).isNotNull();
        return taken;
    }

    /** Taken for the task of the repository with an upstream, its own pull request found. */
    private RunPlace ownAt(final Map<String, Object> parameters) {
        final RunPlace taken =
                RunPlaces.taken(
                                context,
                                parameters,
                                projectFile,
                                "fix",
                                "web",
                                forges,
                                new OwnPullRequests(
                                        adapter, entry -> new ForgeCredential(entry, "lent")),
                                (task, place, ref) -> started.add(place))
                        .place();
        assertThat(taken).as("taken: %s", parameters).isNotNull();
        return taken;
    }

    private VarlinkException ownRefused(final Map<String, Object> parameters) {
        final VarlinkException refused =
                catchThrowableOfType(() -> ownAt(parameters), VarlinkException.class);
        assertThat(refused).as("refused: %s", parameters).isNotNull();
        return refused;
    }

    /** A forge adapter that answers one open pull request, and records what it was asked. */
    private static final class StandInAdapter implements OwnPullRequests.Adapter {

        Optional<String> open = Optional.of(PULL_REQUEST);

        final List<String> asked = new java.util.ArrayList<>();

        @Override
        public Optional<String> pullRequestOf(
                final String name,
                final ForgeCredential credential,
                final String upstream,
                final String branch) {
            asked.add(name + " " + credential.entry() + " " + upstream + " " + branch);
            return open;
        }
    }

    private VarlinkException refusedAt(final Map<String, Object> parameters) {
        final VarlinkException refused =
                catchThrowableOfType(() -> takenAt(parameters), VarlinkException.class);
        assertThat(refused).as("refused: %s", parameters).isNotNull();
        return refused;
    }

    /** The forges this machine knows, its adapters and whether the vault holds an entry. */
    private static final class StandInForges implements RunPlaceCheck.Forges {

        final Map<String, ForgeInstance> known =
                new HashMap<>(
                        Map.of(
                                "forge.example.org",
                                new ForgeInstance(
                                        "forge.example.org",
                                        "forgejo",
                                        CommentReading.EXTENDED,
                                        null)));

        boolean unreadable;

        boolean installed = true;

        Optional<Boolean> holds = Optional.of(true);

        /** Entries the vault does not hold while it otherwise answers {@link #holds}. */
        final java.util.Set<String> lacking = new java.util.HashSet<>();

        final List<String> asked = new java.util.ArrayList<>();

        @Override
        public Optional<ForgeInstance> of(final java.net.URI url) throws ForgeFileUnreadable {
            if (unreadable) {
                throw new ForgeFileUnreadable("cut off");
            }
            return Optional.ofNullable(known.get(url.getHost()));
        }

        @Override
        public boolean installed(final String adapter) {
            return installed;
        }

        @Override
        public Optional<Boolean> holds(final String entry) {
            asked.add(entry);
            return lacking.contains(entry) ? Optional.of(false) : holds;
        }
    }

    private VarlinkException refused(final Map<String, Object> parameters) {
        final VarlinkException refused =
                catchThrowableOfType(() -> taken(parameters), VarlinkException.class);
        assertThat(refused).as("refused: %s", parameters).isNotNull();
        return refused;
    }

    private static Map<String, Object> push(
            final @org.jspecify.annotations.Nullable String repository, final String name) {
        final Map<String, Object> push = new HashMap<>();
        if (repository != null) {
            push.put("repository", repository);
        }
        push.put("name", name);
        return Map.of("gatePush", push);
    }
}
