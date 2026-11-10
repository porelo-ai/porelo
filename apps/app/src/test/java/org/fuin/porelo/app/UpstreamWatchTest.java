package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.UpstreamDistance;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for how often {@link UpstreamWatch} looks, and whether it looks at all.
 *
 * <p>A daemon that reaches the network on its own is something an operator is entitled to refuse -
 * on a metered connection, or on a machine meant to be quiet - so the switch is part of the feature
 * rather than an afterthought.
 */
class UpstreamWatchTest {

    private static java.util.function.UnaryOperator<String> saying(String value) {
        return name -> Map.of(UpstreamWatch.INTERVAL_VARIABLE, value).get(name);
    }

    @Test
    void looksEveryQuarterHourWhenNothingSaysOtherwise() {

        assertThat(UpstreamWatch.interval(name -> null)).isEqualTo(UpstreamWatch.DEFAULT_INTERVAL);
    }

    @Test
    void takesTheIntervalAnOperatorAsksFor() {

        assertThat(UpstreamWatch.interval(saying("60"))).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void zeroTurnsTheMeasuringOffEntirely() {

        // The only way to stop a daemon touching the network by itself, so it has to be exact:
        // anything that merely made it rare would be a different promise.
        assertThat(UpstreamWatch.interval(saying("0"))).isZero();
    }

    @Test
    void aTurnedOffWatchStartsNoThread() {

        final UpstreamWatch watch = new UpstreamWatch(null, Duration.ZERO);
        watch.start();

        assertThat(Thread.getAllStackTraces().keySet().stream().map(Thread::getName))
                .noneMatch("porelo-upstream"::equals);
    }

    @Test
    void nonsenseFallsBackToTheDefaultRatherThanTurningItOff() {

        // A misspelt variable that silently disabled this would be found weeks later as "the number
        // never updates", which is the worst way to learn about a typo.
        assertThat(UpstreamWatch.interval(saying("every fifteen minutes")))
                .isEqualTo(UpstreamWatch.DEFAULT_INTERVAL);
        assertThat(UpstreamWatch.interval(saying(""))).isEqualTo(UpstreamWatch.DEFAULT_INTERVAL);
    }

    /** What happens on this machine while the host's central-state file is read. */
    private Runnable whileAsked = () -> {};

    private Path centralFile;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    /** What the host answered of central the time before, which a refused answer must keep. */
    private static final UpstreamDistance.Central EARLIER =
            new UpstreamDistance.Central("ahead", 2, Instant.parse("2026-11-08T09:00:00Z"), null);

    /** When the host's follow read central, as its answer says. */
    private static final String READ_AT = "2026-11-09T10:00:00Z";

    private static final String PROJECT =
            """
            project:
              name: "quad"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            repositories:
              backend:
                upstream: "git@github.com:acme/backend.git"
                central: "git@github.com:acme/backend.git"
              frontend:
                upstream: "git@github.com:acme/frontend.git"
                central: "git@github.com:acme/frontend.git"
              docs:
                upstream: "git@github.com:acme/docs.git"
                central: "git@github.com:acme/docs.git"
              notes:
                upstream: "git@github.com:acme/notes.git"
            """;

    /** A machine where the project is taken, and a watch reading the host's files under dir. */
    private UpstreamWatch watch(Path dir, boolean onAHost) throws Exception {
        final PoreloContext context = context(dir);
        PlacedFixture.record(context, "quad");
        final Path clone = context.paths().projects().organizationClone("quad");
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), PROJECT);
        final Path files = dir.resolve("host/projects");
        if (onAHost) {
            Files.createDirectories(files);
            Files.writeString(
                    files.resolve("quad.json"),
                    """
                    {"project": "quad", "revision": "56420b3", "address": "git@github.com:acme/quad.git",
                     "fetch_from": null, "signing_keys": [], "homeserver": null, "room": null,
                     "repositories": ["backend", "frontend", "docs", "notes"]}
                    """);
        }
        final Path central = dir.resolve("host/central");
        Files.createDirectories(central);
        centralFile = central.resolve("quad.json");
        final UpstreamWatch watch = new UpstreamWatch(context, Duration.ZERO);
        watch.hostFiles = files;
        watch.centralFiles = central;
        // Root owns nothing in a test; RootOwnedFile checks attributes through this reader.
        watch.centralAttributes =
                path -> {
                    if (path.equals(centralFile)) {
                        whileAsked.run();
                    }
                    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new java.nio.file.NoSuchFileException(path.toString());
                    }
                    return java.util.Map.of(
                            "uid",
                            0,
                            "mode",
                            Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                    ? 0040755
                                    : 0100640);
                };
        records(dir)
                .putMeasured(
                        "quad.backend",
                        new UpstreamDistance.Distance(
                                5,
                                Instant.parse("2026-11-09T09:00:00Z"),
                                UpstreamDistance.Reason.MEASURED,
                                null),
                        dir);
        records(dir).putCentral("quad.backend", EARLIER, dir);
        return watch;
    }

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    private static UpstreamRecords records(Path dir) {
        return new UpstreamRecords(dir.resolve("data/porelo/upstream"));
    }

    /** The host's answer, with the backend's entry as given and the others as a host says them. */
    private static String withBackend(String backend) {
        return "{\"project\":\"quad\",\"read_at\":\""
                + READ_AT
                + "\",\"repositories\":["
                + backend
                + (backend.isEmpty() ? "" : ",")
                + "{\"repository\":\"Frontend\",\"state\":\"up-to-date\"},"
                + "{\"repository\":\"docs\",\"state\":\"cannot-say\",\"why\":\"unreachable\"}]}\n";
    }

    private void answering(String output) throws Exception {
        Files.writeString(centralFile, output);
    }

    @Test
    void theTimerRecordsWhatTheHostSaysOfCentral(@TempDir Path dir) throws Exception {
        // Recorded wrong, a client tells a person their work is at central while it is not.
        final UpstreamWatch watch = watch(dir, true);
        answering(withBackend("{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":3}"));

        watch.measureOnce();

        final UpstreamDistance.Distance backend = records(dir).get("quad.backend");
        assertThat(backend.central().state()).as("the host's state").isEqualTo("ahead");
        assertThat(backend.ahead()).as("the host's commits").isEqualTo(3);
        assertThat(backend.central().aheadMeasured())
                .as("when the host's follow read it, not when it was asked")
                .isEqualTo(Instant.parse(READ_AT));
        assertThat(backend.behind()).as("behind stays the gate's own measure").isEqualTo(5);
        assertThat(records(dir).get("quad.frontend").ahead())
                .as("up to date is 0 ahead, its name matched whatever its case")
                .isZero();
        final UpstreamDistance.Central docs = records(dir).get("quad.docs").central();
        assertThat(docs.state()).as("the host cannot say").isEqualTo("cannot-say");
        assertThat(docs.why()).as("and says why").isEqualTo("unreachable");
        assertThat(docs.ahead()).as("no number where it cannot say").isNull();
        assertThat(records(dir).get("quad.notes").central().state())
                .as("no central address in the project file, so the host keeps no copy")
                .isEqualTo("no-central");
        assertThat(runner.lines())
                .as("the published central-state file is read directly without spawning a program")
                .noneMatch(line -> line.contains("porelo-host") || line.contains("central"));
    }

    @Test
    void aProjectRemovedWhileTheHostIsAskedGetsNoRecordBack(@TempDir Path dir) throws Exception {
        // Its removal deletes its file, then its records; an answer written after that is a
        // record of nothing, which doctor reports as left over.
        final UpstreamWatch watch = watch(dir, true);
        answering(withBackend("{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":3}"));
        final Path clone = context(dir).paths().projects().organizationClone("quad");
        final Path upstream = dir.resolve("data/porelo/upstream");
        whileAsked =
                () -> {
                    try {
                        Files.delete(clone.resolve("project.yml"));
                        Files.delete(clone);
                        try (var records = Files.list(upstream)) {
                            for (final Path each : records.toList()) {
                                if (each.getFileName().toString().startsWith("quad")) {
                                    Files.delete(each);
                                }
                            }
                        }
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                };

        watch.measureOnce();

        try (var left = Files.list(upstream)) {
            assertThat(left.map(each -> each.getFileName().toString()))
                    .as("no record comes back for the removed project")
                    .noneMatch(name -> name.startsWith("quad"));
        }
    }

    /** Rewrites this host's file of the project to carry the given repositories. */
    private static void carrying(Path dir, String repositories) throws Exception {
        final Path file = dir.resolve("host/projects/quad.json");
        Files.writeString(
                file,
                Files.readString(file)
                        .replace(
                                "[\"backend\", \"frontend\", \"docs\", \"notes\"]",
                                "[" + repositories + "]"));
    }

    @Test
    void aProjectCarriedWholeSaysNothingOfCentralForItsOwnRepository(@TempDir Path dir)
            throws Exception {
        // Its own repository is no row with a central address; no-central would say this host
        // keeps no copy of what it carries with the project.
        final UpstreamWatch watch = watch(dir, true);
        carrying(dir, "");
        answering(withBackend("{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":3}"));

        watch.measureOnce();

        assertThat(records(dir).get("quad").central())
                .as("nothing recorded of central for the project's own repository")
                .isNull();
        assertThat(records(dir).get("quad.backend").central().state())
                .as("its rows are still asked about")
                .isEqualTo("ahead");
    }

    @Test
    void anAnswerNamingACopyThisHostNoLongerCarriesIsNotRefused(@TempDir Path dir)
            throws Exception {
        // The host's forge keeps a copy a while after its file stops naming it; refused whole, the
        // carried rows' answers are lost every pass.
        final UpstreamWatch watch = watch(dir, true);
        carrying(dir, "\"backend\"");
        answering(withBackend("{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":3}"));

        watch.measureOnce();

        assertThat(records(dir).get("quad.backend").central().state())
                .as("the carried row's answer is taken")
                .isEqualTo("ahead");
        assertThat(records(dir).get("quad.frontend").central())
                .as("nothing recorded for a row not carried")
                .isNull();
    }

    @Test
    void aRepositoryTheHostDoesNotListCannotSayRatherThanZero(@TempDir Path dir) throws Exception {
        final UpstreamWatch watch = watch(dir, true);
        answering(
                withBackend("{\"repository\":\"backend\",\"state\":\"up-to-date\"}")
                        .replace(
                                ",{\"repository\":\"docs\",\"state\":\"cannot-say\",\"why\":\"unreachable\"}",
                                ""));

        watch.measureOnce();

        final UpstreamDistance.Central docs = records(dir).get("quad.docs").central();
        assertThat(docs.state()).as("not listed, so not known").isEqualTo("cannot-say");
        assertThat(docs.why()).as("the host did not reach it").isEqualTo("unreachable");
        assertThat(docs.ahead()).as("never 0 in its place").isNull();
    }

    @Test
    void aHostThatListsNothingCannotSayOfAny(@TempDir Path dir) throws Exception {
        final UpstreamWatch watch = watch(dir, true);
        answering("{\"project\":\"quad\",\"read_at\":\"" + READ_AT + "\",\"repositories\":[]}");

        watch.measureOnce();

        for (final String each : java.util.List.of("backend", "frontend", "docs")) {
            final UpstreamDistance.Central central = records(dir).get("quad." + each).central();
            assertThat(central.state())
                    .as("%s: the host could not say", each)
                    .isEqualTo("cannot-say");
            assertThat(central.ahead()).as("%s: no number", each).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "{\"project\":\"quad\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[]}\n{}",
                "{\"project\":\"other\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[]}",
                "{\"project\":\"quad\",\"read_at\":\"2026-11-09T11:00:00+01:00\",\"repositories\":[]}",
                "{\"project\":\"quad\",\"read_at\":\"yesterday\",\"repositories\":[]}",
                "{\"project\":\"quad\",\"repositories\":[]}",
                "{\"project\":\"quad\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[],\"token\":\"x\"}",
                "{\"project\":\"quad\",\"read_at\":\"2026-11-09T10:00:00Z\"}",
            })
    void anAnswerNoHostMayGiveIsRefusedWhole(String output, @TempDir Path dir) throws Exception {
        // Recorded, another program's malformed answer would become a number a person acts on.
        final UpstreamWatch watch = watch(dir, true);
        answering(output);

        watch.measureOnce();

        refusedKeepingTheEarlier(dir);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"repository\":\"backend\",\"state\":\"behind\"}",
                "{\"repository\":\"backend\",\"state\":\"no-central\"}",
                "{\"repository\":\"backend\",\"state\":\"ahead\"}",
                "{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":-1}",
                "{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":1000000}",
                "{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":2.5}",
                "{\"repository\":\"backend\",\"state\":\"ahead\",\"commits\":\"3\"}",
                "{\"repository\":\"backend\",\"state\":\"up-to-date\",\"commits\":0}",
                "{\"repository\":\"backend\",\"state\":\"up-to-date\",\"why\":\"local\"}",
                "{\"repository\":\"backend\",\"state\":\"cannot-say\",\"why\":\"two\\nlines\"}",
                "{\"repository\":\"backend\",\"state\":\"cannot-say\",\"why\":\"\\u202e\"}",
                "{\"repository\":\"backend\",\"state\":\"cannot-say\",\"why\":\"\"}",
                "{\"repository\":\"backend\",\"state\":\"cannot-say\",\"why\":\"WHY_TOO_LONG\"}",
                "{\"repository\":\"backend\",\"state\":\"up-to-date\",\"token\":\"x\"}",
                "{\"repository\":\"elsewhere\",\"state\":\"up-to-date\"}",
                "{\"repository\":\"notes\",\"state\":\"up-to-date\"}",
                "{\"repository\":\"backend\",\"state\":\"up-to-date\"},"
                        + "{\"repository\":\"BACKEND\",\"state\":\"up-to-date\"}",
                "\"backend\"",
            })
    void anEntryNoHostMayGiveRefusesTheWholeAnswer(String backend, @TempDir Path dir)
            throws Exception {
        final UpstreamWatch watch = watch(dir, true);
        answering(withBackend(backend.replace("WHY_TOO_LONG", "x".repeat(201))));

        watch.measureOnce();

        refusedKeepingTheEarlier(dir);
    }

    private static void refusedKeepingTheEarlier(Path dir) {
        final UpstreamDistance.Central central = records(dir).get("quad.backend").central();
        assertThat(central.state()).as("refused, so it cannot say").isEqualTo("cannot-say");
        assertThat(central.why()).as("and says it was refused").isEqualTo("host-answer-refused");
        assertThat(central.ahead()).as("the earlier number, never 0 or a guess").isEqualTo(2);
        assertThat(central.aheadMeasured())
                .as("with the age it had")
                .isEqualTo(EARLIER.aheadMeasured());
        assertThat(records(dir).get("quad.frontend").ahead())
                .as("nothing of a refused answer is taken")
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "not-roots", "group-writable", "parent-writable", "symlink"})
    void aHostThatCannotBeAskedKeepsTheEarlierNumberAndCannotSay(String how, @TempDir Path dir)
            throws Exception {
        // A refused host file cannot erase evidence of pending work.
        final UpstreamWatch watch = watch(dir, true);
        if (!"missing".equals(how)) {
            answering(withBackend("{\"repository\":\"backend\",\"state\":\"up-to-date\"}"));
        }
        final var regular = watch.centralAttributes;
        switch (how) {
            case "missing" -> {}
            case "not-roots" ->
                    watch.centralAttributes =
                            path ->
                                    path.equals(centralFile)
                                            ? java.util.Map.of("uid", 1000, "mode", 0100640)
                                            : regular.read(path);
            case "group-writable" ->
                    watch.centralAttributes =
                            path ->
                                    path.equals(centralFile)
                                            ? java.util.Map.of("uid", 0, "mode", 0100660)
                                            : regular.read(path);
            case "parent-writable" ->
                    watch.centralAttributes =
                            path ->
                                    path.equals(centralFile.getParent())
                                            ? java.util.Map.of("uid", 0, "mode", 0040775)
                                            : regular.read(path);
            case "symlink" ->
                    watch.centralAttributes =
                            path ->
                                    path.equals(centralFile)
                                            ? java.util.Map.of("uid", 0, "mode", 0120755)
                                            : regular.read(path);
            default -> throw new IllegalArgumentException(how);
        }

        watch.measureOnce();

        final UpstreamDistance.Central central = records(dir).get("quad.backend").central();
        assertThat(central.why()).as("it could not be asked").isEqualTo("host-command-failed");
        assertThat(central.state()).as("so it cannot say").isEqualTo("cannot-say");
        assertThat(central.ahead()).as("the earlier number stays").isEqualTo(2);
        assertThat(records(dir).get("quad.notes").central().state())
                .as("a row with no central has no copy to ask about, whatever the host does")
                .isEqualTo("no-central");
    }

    @Test
    void offAHostTheHostIsNeverAsked(@TempDir Path dir) throws Exception {
        // A machine that is no host has no such file, and one placed there by anybody else must
        // not be read.
        final UpstreamWatch watch = watch(dir, false);
        answering(withBackend("{\"repository\":\"backend\",\"state\":\"up-to-date\"}"));
        final var read = new java.util.concurrent.atomic.AtomicBoolean();
        whileAsked = () -> read.set(true);

        watch.measureOnce();

        assertThat(read.get()).as("nothing asked").isFalse();
        assertThat(records(dir).get("quad.backend").central())
                .as("what was there stays")
                .isEqualTo(EARLIER);
    }

    @Test
    void aListingNeverAsksTheHostOfCentral(@TempDir Path dir) throws Exception {
        watch(dir, true);
        answering(withBackend("{\"repository\":\"backend\",\"state\":\"up-to-date\"}"));
        final ProjectInventory inventory = new ProjectInventory(context(dir));
        inventory.hostFiles = dir.resolve("host/projects");

        inventory.projects();

        assertThat(runner.lines())
                .noneMatch(line -> line.contains("porelo-host") || line.contains("--central"));
    }
}
