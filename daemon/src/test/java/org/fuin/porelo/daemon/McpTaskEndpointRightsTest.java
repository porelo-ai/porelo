package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.RunPlace;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.McpEndpoint;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a task's endpoint carries is decided per task, within the project file's {@code mcp}
 * section: the writing methods by the task's recorded rights, a start by what the task that starts
 * it holds, and the gate's reading methods by the push that is the task's place.
 */
class McpTaskEndpointRightsTest {

    private static final String LEAD = "porelo-uc-lead";

    private static final String HEAD =
            "project:\n  name: \"uc\"\n  security_class: \"guarded\"\nimage:\n  from:"
                    + " \"ubuntu:24.04\"\n";

    private static final String MINE = "a".repeat(40);

    private static final String THEIRS = "b".repeat(40);

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private McpTaskSockets sockets;

    @AfterEach
    void closeAll() {
        if (sockets != null) {
            sockets.close();
        }
    }

    private static String section(final String... methods) {
        return "mcp:\n"
                + "  methods: [\""
                + String.join("\", \"", methods)
                + "\"]\n"
                + "  agents:\n"
                + "    alpha:\n"
                + "  tasks: 2\n"
                + "  minutes: 60\n";
    }

    private PoreloContext context(final String file) throws IOException {
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
        final Path project = dir.resolve("project.yml");
        Files.writeString(project, file);
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), project + "\n");
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        sockets = new McpTaskSockets(context);
        identity(context, LEAD);
        return context;
    }

    private static void identity(final PoreloContext context, final String task)
            throws IOException {
        final Path state = Files.createDirectories(context.paths().tasks().containerState(task));
        org.fuin.porelo.wire.Sidecar.recordId(state, "a".repeat(64));
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        "uc",
                        "guarded",
                        "/rules",
                        "/dns",
                        "/porelo",
                        state.toString(),
                        null,
                        null,
                        null,
                        org.fuin.porelo.wire.LimitFactors.DEFAULT)
                .writeTo(state.resolve("sidecar.json"));
    }

    /** Records the lead's parts at its own gate push, as its start does. */
    private static void at(final PoreloContext context, final RunPlace place) throws IOException {
        place.writeTo(context.paths().tasks().containerState(LEAD));
    }

    /** Its own work with the rights named, and the project's services, as its own pushes. */
    private static TaskParts ownWorkWith(final Right... rights) {
        return new TaskParts(
                TaskParts.Workspace.OWN, List.of(), true, TaskParts.Account.AUTHOR, Set.of(rights));
    }

    private static Map<String, Object> start() {
        final Map<String, Object> start = new LinkedHashMap<>();
        start.put("project", "uc");
        start.put("repository", "uc");
        start.put("task", "child");
        start.put("agent", "alpha");
        start.put("minutes", 30);
        start.put("prompt", "go");
        return start;
    }

    private static final VarlinkServer.Method NEVER =
            (parameters, replies) -> {
                throw new AssertionError("a child was started");
            };

    private static Map<String, Object> call(
            final Path socket, final String method, final Map<String, Object> parameters)
            throws IOException {
        try (VarlinkClient client = new VarlinkClient(socket)) {
            return client.call(PoreloDaemon.INTERFACE + "." + method, parameters);
        }
    }

    private static void refused(
            final Path socket,
            final String method,
            final Map<String, Object> parameters,
            final String error,
            final Map<String, Object> said) {
        assertThatThrownBy(() -> call(socket, method, parameters))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(
                                            error.contains(".")
                                                    ? error
                                                    : PoreloDaemon.INTERFACE + "." + error);
                            assertThat(ex.getParameters()).isEqualTo(said);
                        });
    }

    private static Set<String> offered(final Path socket) throws IOException {
        try (VarlinkClient client = new VarlinkClient(socket)) {
            final String idl =
                    String.valueOf(
                            client.call(
                                            "org.varlink.service.GetInterfaceDescription",
                                            Map.of("interface", PoreloDaemon.INTERFACE))
                                    .get("description"));
            final Set<String> names = new TreeSet<>();
            final Matcher matcher =
                    Pattern.compile("(?m)^method\\s+([A-Za-z][A-Za-z0-9]*)").matcher(idl);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        }
    }

    @Test
    void aTaskWithoutTheRightToStartIsAnsweredAsIfItsEndpointHadNoStart() throws IOException {
        // Harm: the file's section alone decided, so a task started to report could start tasks.
        final PoreloContext context = context(HEAD + section("Start", "Report", "Stop"));
        at(context, new RunPlace(ownWorkWith(Right.PUSH, Right.REPORT), null, "lead"));
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        refused(
                socket,
                "Start",
                start(),
                "org.varlink.service.MethodNotFound",
                Map.of("method", PoreloDaemon.INTERFACE + ".Start"));
        refused(
                socket,
                "Stop",
                Map.of("task", "porelo-uc-child"),
                "org.varlink.service.MethodNotFound",
                Map.of("method", PoreloDaemon.INTERFACE + ".Stop"));
        assertThat(offered(socket)).as("described as it answers").containsExactly("Report");
    }

    @Test
    void aTaskWhoseRecordDoesNotReadHoldsNoWritingMethod() throws IOException {
        final PoreloContext context = context(HEAD + section("Start", "Report"));
        Files.writeString(
                context.paths().tasks().containerState(LEAD).resolve("run-place"),
                "{\"parts\": 7}");
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        for (final String method : List.of("Start", "Report")) {
            refused(
                    socket,
                    method,
                    Map.of(),
                    "org.varlink.service.MethodNotFound",
                    Map.of("method", PoreloDaemon.INTERFACE + "." + method));
        }
        assertThat(offered(socket)).isEmpty();
    }

    @Test
    void aChildAskingARightItsParentLacksIsRefusedAsExceedingIt() throws IOException {
        // Harm: a task without push started a child that pushes, and so pushed through it.
        final PoreloContext context = context(HEAD + section("Start"));
        at(context, new RunPlace(ownWorkWith(Right.START, Right.REPLY), null, "lead"));
        final McpEndpoint endpoint =
                new McpEndpoint(List.of("Start"), Map.of("alpha", List.of()), 2, 60);

        assertThatThrownBy(() -> sockets.start(LEAD, "uc", endpoint, start(), null, NEVER))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
                            assertThat(ex.getParameters())
                                    .containsEntry("rule", "exceedsParent")
                                    .containsEntry("detail", "rights push");
                        });
    }

    @Test
    void aTaskThatReadsAnothersWorkStartsNoChildAtItsOwnWork() throws IOException {
        // Harm: a task over unreviewed work started a child with the project's services and push.
        final PoreloContext context = context(HEAD + section("Start"));
        at(
                context,
                new RunPlace(
                        TaskParts.anothersWork(List.of(Right.START, Right.REPLY)), null, "other"));
        final McpEndpoint endpoint =
                new McpEndpoint(List.of("Start"), Map.of("alpha", List.of()), 2, 60);

        assertThatThrownBy(() -> sockets.start(LEAD, "uc", endpoint, start(), null, NEVER))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
                            assertThat(ex.getParameters())
                                    .containsEntry("rule", "exceedsParent")
                                    .containsEntry("detail", "workspace own");
                        });
    }

    private static final McpEndpoint STARTS =
            new McpEndpoint(List.of("Start"), Map.of("alpha", List.of()), 2, 60);

    /** The lead's parts at its own work: start, push, reply and report, the project's services. */
    private static TaskParts lead() {
        return ownWorkWith(Right.START, Right.PUSH, Right.REPLY, Right.REPORT);
    }

    /** Asks a start naming the given parts and expects it refused as exceeding its parent. */
    private void exceeds(final TaskParts parent, final Map<String, Object> named, final String part)
            throws IOException {
        final PoreloContext context = context(HEAD + section("Start"));
        at(context, new RunPlace(parent, null, "lead"));
        final Map<String, Object> asked = start();
        asked.putAll(named);

        assertThatThrownBy(() -> sockets.start(LEAD, "uc", STARTS, asked, null, NEVER))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
                            assertThat(ex.getParameters())
                                    .containsEntry("rule", "exceedsParent")
                                    .containsEntry("detail", part);
                        });
    }

    @Test
    void aChildNamingFewerRightsThanItsParentIsStartedWithThem() throws IOException {
        final PoreloContext context = context(HEAD + section("Start"));
        at(context, new RunPlace(lead(), null, "lead"));
        final Map<String, Object> asked = start();
        asked.put("rights", List.of("report"));
        final List<Map<String, Object>> started = new java.util.ArrayList<>();

        sockets.start(
                LEAD,
                "uc",
                STARTS,
                asked,
                null,
                (parameters, replies) -> started.add(Map.copyOf(parameters)));

        assertThat(started).hasSize(1);
        assertThat(started.getFirst())
                .as("its rights go on to the start as named")
                .containsEntry("rights", List.of("report"));
    }

    @Test
    void aChildNamingARightItsParentLacksIsRefusedNamingIt() throws IOException {
        // Harm: a task without openThreads opened threads through a child.
        exceeds(lead(), Map.of("rights", List.of("openThreads")), "rights openThreads");
    }

    @Test
    void aChildNamingTheProjectsServicesUnderAParentWithoutThemIsRefused() throws IOException {
        // Harm: a task cut off from the project's credentials and egress reached them through a
        // child.
        exceeds(withoutServices(), Map.of("projectServices", true), "projectServices");
    }

    @Test
    void aChildNamingNoServicesUnderAParentWithoutThemTakesTheDefaultAndIsRefused()
            throws IOException {
        // The default at its own work is the project's services on, which the parent lacks.
        exceeds(withoutServices(), Map.of(), "projectServices");
    }

    /** A task at its own work that may start, push and reply, without the project's services. */
    private static TaskParts withoutServices() {
        return new TaskParts(
                TaskParts.Workspace.OWN,
                List.of(),
                false,
                TaskParts.Account.AUTHOR,
                Set.of(Right.START, Right.PUSH, Right.REPLY));
    }

    @Test
    void aChildReadingLandedWorkItsParentDoesNotReadIsRefused() throws IOException {
        // Harm: a task read another task's landed work through a child it started.
        exceeds(lead(), Map.of("readLanded", List.of("other")), "readLanded other");
    }

    @Test
    void aChildNamingNothingUnderAParentWithoutPushIsRefusedForTheDefaultPush() throws IOException {
        // The default at its own work holds push, which the parent lacks.
        exceeds(ownWorkWith(Right.START, Right.REPLY), Map.of(), "rights push");
    }

    @Test
    void aChildNamingAnAccountIsRefusedAsItNamesNoPullRequest() throws IOException {
        // An account to write as is chosen at a pull request, and a child started here names none.
        final PoreloContext context = context(HEAD + section("Start"));
        at(context, new RunPlace(lead(), null, "lead"));
        final Map<String, Object> asked = start();
        asked.put("writeAs", "author");

        assertThatThrownBy(() -> sockets.start(LEAD, "uc", STARTS, asked, null, NEVER))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo("org.varlink.service.InvalidParameter");
                            assertThat(ex.getParameters()).containsEntry("parameter", "writeAs");
                        });
    }

    @Test
    void aReviewOfAnotherTasksWaitingPushIsNoSuchPush() throws IOException {
        // Harm: a task read another task's unreviewed work of the same project through Review.
        final PoreloContext context = context(HEAD + section("Review", "ReviewPage"));
        at(context, new RunPlace(ownWorkWith(Right.PUSH, Right.REPLY), null, "lead"));
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        refused(socket, "Review", Map.of("project", "uc", "name", "other"), "NoSuchPush", Map.of());
        final Map<String, Object> page = new LinkedHashMap<>();
        page.put("project", "uc");
        page.put("name", "other");
        page.put("commit", THEIRS);
        page.put("against", "");
        page.put("ignoreLineEndings", false);
        page.put("part", "diff");
        page.put("from", 0);
        refused(socket, "ReviewPage", page, "NoSuchPush", Map.of());
        assertThat(runner.lines()).as("the gate is never asked").noneMatch(l -> l.contains("git"));
    }

    @Test
    void aTaskWithoutAPlaceOrAtAnotherRepositoryReviewsNoPush() throws IOException {
        final PoreloContext context = context(HEAD + section("Review"));
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        refused(socket, "Review", Map.of("project", "uc", "name", "lead"), "NoSuchPush", Map.of());
        at(context, new RunPlace(ownWorkWith(Right.PUSH), "app", "lead"));
        refused(socket, "Review", Map.of("project", "uc", "name", "lead"), "NoSuchPush", Map.of());
        assertThat(runner.lines()).noneMatch(l -> l.contains("git"));
    }

    @Test
    void aReviewAgainstOneOfPorelosOwnRefsIsRefusedBeforeTheGateIsAsked() throws IOException {
        // Harm: a diff against another task's incoming, pinned or landed ref showed its work.
        final PoreloContext context = context(HEAD + section("Review"));
        at(context, new RunPlace(ownWorkWith(Right.PUSH), null, "lead"));
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        for (final String against :
                List.of(
                        "refs/porelo/incoming/other",
                        "porelo/incoming/other",
                        "refs/porelo/pinned/other",
                        "refs/porelo/landed/other/main")) {
            refused(
                    socket,
                    "Review",
                    Map.of("project", "uc", "name", "lead", "against", against),
                    "org.varlink.service.InvalidParameter",
                    Map.of("parameter", "against"));
        }
        assertThat(runner.lines()).noneMatch(l -> l.contains("git"));
    }

    /** Makes the project's own mirror, as a start does, so the gate is asked. */
    private static void mirror(final PoreloContext context) throws IOException {
        Files.createDirectories(context.paths().xdg().data().resolve("mirrors/uc.git/objects"));
    }

    @Test
    void aReviewOfItsOwnPushThatNoLongerWaitsNamesNoOtherPush() throws IOException {
        // Harm: the gate's answer listed every push that waits, so a task learned the others.
        final PoreloContext context = context(HEAD + section("Review"));
        at(context, new RunPlace(ownWorkWith(Right.PUSH), null, "lead"));
        mirror(context);
        runner.failing("refs/porelo/incoming/lead", 1, "");
        runner.answering(
                "for-each-ref",
                "refs/porelo/incoming/other\t" + THEIRS + "\t1700000000\tcommit\ttheirs\n");
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        refused(socket, "Review", Map.of("project", "uc", "name", "lead"), "NoSuchPush", Map.of());
    }

    /** A page of the lead's own push, of the commit named. */
    private static Map<String, Object> pageOf(final String commit) {
        final Map<String, Object> page = new LinkedHashMap<>();
        page.put("project", "uc");
        page.put("name", "lead");
        page.put("commit", commit);
        page.put("against", "");
        page.put("ignoreLineEndings", false);
        page.put("part", "diff");
        page.put("from", 0);
        return page;
    }

    @Test
    void aPageOfAnyCommitButTheOneCheckedWaitingIsNoSuchPush() throws IOException {
        // Harm: a push that moved between the check and the gate's read gave pages of a commit
        // nobody checked, past the head a pinned task is held to.
        final PoreloContext context = context(HEAD + section("ReviewPage"));
        at(context, new RunPlace(ownWorkWith(Right.PUSH), null, "lead"));
        mirror(context);
        runner.answering("log -1 --format=%H%n%s", MINE + "\nmine\n");
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();
        final int before = runner.lines().size();

        refused(socket, "ReviewPage", pageOf(THEIRS), "NoSuchPush", Map.of());
        assertThat(runner.lines().subList(before, runner.lines().size()))
                .as("no page is read past the check")
                .noneMatch(line -> line.contains(THEIRS));
    }

    @Test
    void anUpstreamNamedToPendingReviewOrReviewPageReachesNoOtherMirror() throws IOException {
        // Harm: a task named another upstream and read, or reached, past its own repository.
        final PoreloContext context = context(HEAD + section("Pending", "Review", "ReviewPage"));
        at(context, new RunPlace(ownWorkWith(Right.PUSH), null, "lead"));
        mirror(context);
        runner.answering("for-each-ref", WAITING);
        runner.answering("log -1 --format=%H%n%s", MINE + "\nmine\n");
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();
        final String elsewhere = dir.resolve("elsewhere.git").toString();
        final Map<String, Object> page = pageOf(MINE);
        page.put("upstream", elsewhere);

        for (final Map.Entry<String, Map<String, Object>> asked :
                List.of(
                        Map.entry(
                                "Pending",
                                Map.<String, Object>of("project", "uc", "upstream", elsewhere)),
                        Map.entry(
                                "Review",
                                Map.<String, Object>of(
                                        "project", "uc", "name", "lead", "upstream", elsewhere)),
                        Map.entry("ReviewPage", page))) {
            try {
                call(socket, asked.getKey(), asked.getValue());
            } catch (VarlinkException answered) {
                // What the gate answers is not the point here, only where it reads.
            }
        }
        assertThat(runner.lines())
                .as("only its own mirror is read, never the upstream named")
                .noneMatch(line -> line.contains(elsewhere))
                .filteredOn(line -> line.contains(" git ") || line.startsWith("git "))
                .allMatch(line -> line.contains("mirrors/uc.git"));
        assertThat(dir.resolve("elsewhere.git")).as("nothing made there").doesNotExist();
    }

    private static final String WAITING =
            "refs/porelo/incoming/lead\t"
                    + MINE
                    + "\t1700000000\tcommit\tmine\n"
                    + "refs/porelo/incoming/other\t"
                    + THEIRS
                    + "\t1700000000\tcommit\ttheirs\n";

    private static List<Object> pending(final Path socket) throws IOException {
        final Object rows = call(socket, "Pending", Map.of("project", "uc")).get("pending");
        assertThat(rows).as("a list of pushes").isInstanceOf(List.class);
        final List<Object> names = new java.util.ArrayList<>();
        for (final Object row : (List<?>) rows) {
            names.add(((Map<?, ?>) row).get("name"));
        }
        return names;
    }

    @Test
    void pendingShowsATaskItsOwnWaitingPushAlone() throws IOException {
        // Harm: a task saw every push of its project waiting, its siblings' work among them.
        final PoreloContext context = context(HEAD + section("Pending"));
        at(context, new RunPlace(ownWorkWith(Right.PUSH), null, "lead"));
        mirror(context);
        runner.answering("for-each-ref", WAITING);
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        assertThat(pending(socket)).containsExactly("lead");
    }

    @Test
    void pendingShowsAPinnedTaskOnlyTheCommitItIsPinnedTo() throws IOException {
        final PoreloContext context = context(HEAD + section("Pending"));
        mirror(context);
        runner.answering("for-each-ref", WAITING);
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();
        at(
                context,
                new RunPlace(TaskParts.anothersWork(List.of(Right.REPLY)), null, "other")
                        .pinnedTo(new RunPlace.Pin(THEIRS, null, null, "a".repeat(12))));

        assertThat(pending(socket)).as("the push it reads, at its pin").containsExactly("other");
        at(
                context,
                new RunPlace(TaskParts.anothersWork(List.of(Right.REPLY)), null, "other")
                        .pinnedTo(new RunPlace.Pin("c".repeat(40), null, null, "a".repeat(12))));
        assertThat(pending(socket)).as("moved on past its pin").isEmpty();
    }

    @Test
    void pendingShowsATaskWithoutAPlaceOrAtAPullRequestNoPush() throws IOException {
        final PoreloContext context = context(HEAD + section("Pending"));
        mirror(context);
        runner.answering("for-each-ref", WAITING);
        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        assertThat(pending(socket)).as("no place").isEmpty();
        at(
                context,
                RunPlace.atPullRequest(
                        ownWorkWith(Right.PUSH), "lead", "https://github.com/acme/uc/pull/7"));
        assertThat(pending(socket)).as("at a pull request").isEmpty();
    }
}
