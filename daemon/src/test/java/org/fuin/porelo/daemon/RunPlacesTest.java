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
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start's role and place: an author at its task's own gate push is taken, anything else is
 * refused before anything is made, whether the task runs or not.
 */
class RunPlacesTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    private static final String PULL_REQUEST = "https://forge.example.org/uc/web-app/pull/7";

    private static final String ENTRY = "git.token.forge.example.org";

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
    void aStartNamingNeitherLeavesTheLaunchItsAuthorAtItsOwnPush() {
        assertThat(taken(Map.of(), true)).as("the launch fills the default").isNull();
    }

    @Test
    void anAuthorAtItsOwnGatePushIsTaken() {
        final RunPlace own = new RunPlace("AUTHOR", null, "fix");

        assertThat(taken(Map.of("role", "AUTHOR", "place", push(null, "fix")), true))
                .as("named without its repository, which is the task's own")
                .isEqualTo(own);
        assertThat(taken(Map.of("place", push("demo", "fix")), true))
                .as("named by the project's own repository, and no role: an author")
                .isEqualTo(own);
    }

    @Test
    void anAuthorAtItsOwnPushInAnotherRepositoryIsTakenNamedByIt() {
        assertThat(
                        RunPlaces.taken(
                                context,
                                Map.of("place", push("web", "fix")),
                                projectFile,
                                "fix",
                                "web",
                                true))
                .as("the repository is kept: it is not the project's own")
                .isEqualTo(new RunPlace("AUTHOR", "web", "fix"));
    }

    @Test
    void aReviewerAtItsOwnTasksWorkIsRoleNotAllowed() {
        for (final Map<String, Object> parameters :
                List.<Map<String, Object>>of(
                        Map.of("role", "REVIEWER"),
                        Map.of("role", "REVIEWER", "place", push(null, "fix")))) {
            final VarlinkException refused = refused(parameters);

            assertThat(refused.getErrorName())
                    .as("%s", parameters)
                    .isEqualTo(PoreloDaemon.INTERFACE + ".RoleNotAllowed");
            assertThat(refused.getParameters())
                    .as("the role and the place it was refused at")
                    .isEqualTo(
                            Map.of(
                                    "role",
                                    "REVIEWER",
                                    "place",
                                    Map.of("gatePush", Map.of("name", "fix"))));
        }
    }

    @Test
    void anotherTasksPushIsNotTheTasksPushWithTheHeadThatWaitsThere() throws IOException {
        Files.createDirectories(dir.resolve("data/mirrors/demo.git"));
        runner.answering("--format=%H%n%s", HEAD + "\nother work\n");

        final VarlinkException refused =
                refused(Map.of("role", "AUTHOR", "place", push(null, "other")));

        assertThat(refused.getErrorName())
                .as("another task's push")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");
        assertThat(refused.getParameters())
                .as("the head that waits there")
                .isEqualTo(Map.of("head", HEAD));
    }

    @Test
    void aPushOfAnotherRepositoryOrNoneWaitingHasAnEmptyHead() {
        for (final Map<String, Object> place :
                List.of(push("web", "fix"), push("nowhere", "fix"), push(null, "other"))) {
            final VarlinkException refused = refused(Map.of("place", place));

            assertThat(refused.getErrorName())
                    .as("%s", place)
                    .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");
            assertThat(refused.getParameters())
                    .as("nothing waits there: %s", place)
                    .isEqualTo(Map.of("head", ""));
        }
        assertThat(runner.invocations())
                .as("no mirror of a repository the project does not have is looked into")
                .isEmpty();
    }

    @Test
    void ownPullRequestIsUnreadableForWantOfItsForgeAdapter() {
        final VarlinkException refused = refused(Map.of("place", Map.of("ownPullRequest", true)));

        assertThat(refused.getErrorName())
                .as("the task's own pull request")
                .isEqualTo(PoreloDaemon.INTERFACE + ".PlaceUnreadable");
        assertThat(refused.getParameters())
                .as("the reason, and a detail for a person")
                .containsEntry("reason", "ForgeAdapterMissing")
                .containsKey("detail");
        assertThat(String.valueOf(refused.getParameters().get("detail")))
                .as("says the task's own pull request is not read yet")
                .contains("pull request")
                .contains("not read yet");
    }

    @Test
    void aReviewerAtAPullRequestOfAKnownForgeIsTakenAndReachesTheStart() {
        final RunPlace taken =
                takenAt(Map.of("role", "REVIEWER", "place", Map.of("pullRequest", PULL_REQUEST)));

        assertThat(taken)
                .as("the place recorded")
                .isEqualTo(RunPlace.atPullRequest(RunPlace.REVIEWER, "fix", PULL_REQUEST));
        assertThat(taken.place())
                .as("the place as the contract gives it")
                .isEqualTo(Map.of("pullRequest", PULL_REQUEST));
        assertThat(started).as("what reached the run's start").containsExactly(taken);
        assertThat(forges.asked).as("the vault was asked for the entry only").contains(ENTRY);
    }

    @Test
    void anAuthorIsTheDefaultRoleAtAPullRequest() {
        assertThat(takenAt(Map.of("place", Map.of("pullRequest", PULL_REQUEST))))
                .as("a start naming only the place")
                .isEqualTo(RunPlace.atPullRequest(RunPlace.AUTHOR, "fix", PULL_REQUEST));
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
                        Map.of("role", "OWNER"))) {
            final VarlinkException refused = refused(parameters);

            assertThat(refused.getErrorName())
                    .as("%s", parameters)
                    .isEqualTo("org.varlink.service.InvalidParameter");
        }
    }

    @Test
    void aRunningTaskAskedAsAReviewerOrAtAnotherPushIsStillRefused() {
        // It may stop before the launch looks, and must not then start as an author.
        running("fix");
        final Map<String, Object> asked = new HashMap<>();
        asked.put("project", "demo");
        asked.put("task", "fix");
        asked.put("repository", "demo");

        asked.put("role", "REVIEWER");
        assertThat(canStartRefused(asked).getErrorName())
                .as("a reviewer at its own work, although it runs")
                .isEqualTo(PoreloDaemon.INTERFACE + ".RoleNotAllowed");

        asked.remove("role");
        asked.put("place", push(null, "other"));
        assertThat(canStartRefused(asked).getErrorName())
                .as("another task's push, although it runs")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");

        asked.put("role", "AUTHOR");
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
        assertThat(canStartRefused(asked).getErrorName())
                .as("any other name is another task's push")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");
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
                                    PoreloDaemon.request(asked, context).repository(),
                                    true))
                    .as("the task on %s, its push named by %s", names[0], names[1])
                    .isEqualTo(new RunPlace("AUTHOR", "web", "fix"));
        }
    }

    @Test
    void aTaskWithoutAGateHasNoPushOfItsOwnAndRecordsNoRole() {
        assertThat(taken(Map.of("role", "AUTHOR"), false))
                .as("a role alone is taken and nothing is recorded")
                .isNull();
        assertThat(refused(Map.of("place", push(null, "fix")), false).getErrorName())
                .as("any push it names is another's")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");
    }

    @Test
    void canStartAnswersTheSameRefusals() {
        final Map<String, Object> asked = new HashMap<>();
        asked.put("project", "demo");
        asked.put("task", "fix");
        asked.put("repository", "demo");

        asked.put("role", "REVIEWER");
        assertThat(canStartRefused(asked).getErrorName())
                .as("a reviewer at its own work")
                .isEqualTo(PoreloDaemon.INTERFACE + ".RoleNotAllowed");

        asked.remove("role");
        asked.put("place", push(null, "other"));
        assertThat(canStartRefused(asked).getErrorName())
                .as("another task's push")
                .isEqualTo(PoreloDaemon.INTERFACE + ".NotTheTasksPush");

        asked.put("place", Map.of("ownPullRequest", true));
        assertThat(canStartRefused(asked).getErrorName())
                .as("the task's own pull request")
                .isEqualTo(PoreloDaemon.INTERFACE + ".PlaceUnreadable");

        asked.put("place", Map.of("pullRequest", PULL_REQUEST));
        assertThat(canStartRefused(asked).getErrorName())
                .as("a pull request at a forge this machine was never told of")
                .isEqualTo(PoreloDaemon.INTERFACE + ".UnknownForge");

        asked.put("place", Map.of());
        assertThat(canStartRefused(asked).getErrorName())
                .as("an empty place")
                .isEqualTo("org.varlink.service.InvalidParameter");

        asked.put("role", "AUTHOR");
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

    private org.fuin.porelo.app.@org.jspecify.annotations.Nullable RunPlace taken(
            final Map<String, Object> parameters, final boolean gated) {
        return RunPlaces.taken(context, parameters, projectFile, "fix", "demo", gated);
    }

    private VarlinkException refused(final Map<String, Object> parameters) {
        return refused(parameters, true);
    }

    private static Map<String, Object> pullRequest() {
        return Map.of("place", Map.of("pullRequest", PULL_REQUEST));
    }

    private RunPlace takenAt(final Map<String, Object> parameters) {
        final RunPlace taken =
                RunPlaces.taken(
                        context,
                        parameters,
                        projectFile,
                        "fix",
                        "demo",
                        true,
                        forges,
                        (task, place, ref) -> started.add(place));
        assertThat(taken).as("taken: %s", parameters).isNotNull();
        return taken;
    }

    private VarlinkException refusedAt(final Map<String, Object> parameters) {
        final VarlinkException refused =
                catchThrowableOfType(() -> takenAt(parameters), VarlinkException.class);
        assertThat(refused).as("refused: %s", parameters).isNotNull();
        return refused;
    }

    /** The forges this machine knows, its adapters and whether the vault holds an entry. */
    private static final class StandInForges implements RunPlaces.Forges {

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
            return holds;
        }
    }

    private VarlinkException refused(final Map<String, Object> parameters, final boolean gated) {
        final VarlinkException refused =
                catchThrowableOfType(() -> taken(parameters, gated), VarlinkException.class);
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
