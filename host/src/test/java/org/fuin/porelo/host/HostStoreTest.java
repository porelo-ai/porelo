package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Protects store credentials and prevents a failed copy from retiring the last good snapshot. */
class HostStoreTest {
    private static final Map<String, String> KEYS =
            Map.of(
                    "repository", "s3:https://store.example.org/project",
                    "password", "fixture-password-value",
                    "s3-credentials",
                            "[default]\n"
                                    + "aws_access_key_id=fixture-id\n"
                                    + "aws_secret_access_key=fixture-key\n");
    @TempDir Path directory;
    private HostReadFixture tree;
    private final List<Call> calls = new ArrayList<>();
    private Function<List<String>, HostPrograms.Result> reply = argv -> null;
    private String ioFailure = "";
    private final List<Path> scratch = new ArrayList<>();

    private record Call(List<String> argv, Map<String, String> environment) {}

    @BeforeEach
    void prepareHost() throws Exception {
        tree = new HostReadFixture(directory.resolve("root"));
        tree.person("alice", "member=alpha developer\n", "");
        tree.person("bob", "member=alpha developer\n", "");
    }

    private static String line(String name, String value) {
        return "store "
                + name
                + " "
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + "\n";
    }

    private static String input() {
        return List.of("repository", "password", "s3-credentials").stream()
                .map(name -> line(name, KEYS.get(name)))
                .collect(java.util.stream.Collectors.joining());
    }

    private void keys() throws Exception {
        for (final var key : KEYS.entrySet())
            tree.put("/etc/porelo-host/store/" + key.getKey(), key.getValue());
    }

    private HostReadFixture.Answer run(String input, String... args) {
        return run(0, Map.of(), input, args);
    }

    private HostReadFixture.Answer run(
            long uid, Map<String, String> environment, String input, String... args) {
        final HostFiles files = tree.files();
        final ProjectRuntime runtime =
                new ProjectRuntime(
                        files,
                        this::command,
                        Path::of,
                        (socket, token) -> {
                            throw new AssertionError("the store never reaches the forge");
                        });
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int code =
                PoreloHost.run(
                        args,
                        environment,
                        () -> uid,
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        files,
                        env -> {
                            throw new AssertionError("root's store needs no sudo person");
                        },
                        new HostSweep(files),
                        runtime,
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        Clock.systemUTC());
        return new HostReadFixture.Answer(code, out.toString(), err.toString());
    }

    private HostPrograms.Result command(
            List<String> argv, byte[] input, Map<String, String> environment, Duration timeout)
            throws IOException {
        calls.add(new Call(List.copyOf(argv), Map.copyOf(environment)));
        assertThat(input).as("credentials reach restic by files alone").isEmpty();
        if (argv.contains(ioFailure)) throw new IOException(KEYS.get("password"));
        final HostPrograms.Result custom = reply.apply(argv);
        if (custom != null) return custom;
        if (argv.contains("passwd")) {
            final String login = argv.getLast();
            final int uid =
                    switch (login) {
                        case "porelo-matrix" -> 900;
                        case "alice" -> 1000;
                        default -> 1001;
                    };
            return new HostPrograms.Result(
                    0, login + ":x:" + uid + ":" + uid + "::/nonexistent/" + login + ":/bin/sh\n");
        }
        if (argv.contains("chown")) {
            final Path data = Path.of(argv.getLast());
            scratch.add(data);
            assertThat(mode(data.getParent()))
                    .as("root retains exclusive access until ownership is settled")
                    .isEqualTo(0700);
        }
        if (argv.contains("snapshots")) return new HostPrograms.Result(0, "[]");
        return new HostPrograms.Result(0, "");
    }

    private static int mode(Path path) throws IOException {
        return ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS))
                        .intValue()
                & 07777;
    }

    private List<List<String>> commands(String word) {
        return calls.stream().map(Call::argv).filter(argv -> argv.contains(word)).toList();
    }

    private void noSecrets(HostReadFixture.Answer answer) {
        final List<String> forbidden = new ArrayList<>(KEYS.values());
        forbidden.addAll(List.of("fixture-id", "fixture-key"));
        forbidden.addAll(
                KEYS.values().stream()
                        .map(
                                value ->
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        value.getBytes(StandardCharsets.UTF_8)))
                        .toList());
        for (final String value : forbidden) {
            assertThat(answer.out() + answer.err())
                    .as("answers never include a key")
                    .doesNotContain(value);
            assertThat(calls.toString())
                    .as("arguments and environment never include a key")
                    .doesNotContain(value);
        }
    }

    @Test
    void putPublishesExactlyThreePrivateFilesWithoutExposingTheirValues() throws Exception {
        final var answer = run(input(), "store", "put");
        assertThat(answer.code()).as("root can install the complete credential set").isZero();
        final Path store = tree.path("/etc/porelo-host/store");
        assertThat(mode(store)).as("only root traverses the credentials directory").isEqualTo(0700);
        try (final var entries = Files.list(store)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .as("no staged key is left behind")
                    .containsExactlyInAnyOrderElementsOf(KEYS.keySet());
        }
        for (final var key : KEYS.entrySet()) {
            final Path file = store.resolve(key.getKey());
            assertThat(Files.readString(file))
                    .as("the input is decoded without changing bytes")
                    .isEqualTo(key.getValue());
            assertThat(mode(file))
                    .as("credentials are never public, even during publication")
                    .isEqualTo(0600);
            assertThat(answer.out())
                    .as("only the installed names are reported")
                    .contains(key.getKey());
        }
        assertThat(calls).as("put does not pass keys to a child").isEmpty();
        noSecrets(answer);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "store fourth YQ==\n",
                "store ../password YQ==\n",
                "store password !!!\n",
                "store password YQ== extra\n",
                "password YQ==\n"
            })
    void aRefusedLinePublishesNoneOfTheKeys(String invalid) {
        final var answer = run(input() + invalid, "store", "put");
        assertThat(answer.code())
                .as("the whole stream is validated before publication")
                .isEqualTo(65);
        assertThat(tree.path("/etc/porelo-host/store"))
                .as("no partial credential set is installed")
                .doesNotExist();
        assertThat(answer.out()).as("a rejected stream reports no published file").isEmpty();
        noSecrets(answer);
    }

    @Test
    void incompleteOrDuplicateInputLeavesThePreviousSetIntact() throws Exception {
        keys();
        for (final String input :
                List.of(
                        line("repository", "replacement"),
                        input() + line("password", "replacement"))) {
            assertThat(run(input, "store", "put").code())
                    .as("all three distinct names are required")
                    .isEqualTo(65);
            for (final var key : KEYS.entrySet()) {
                assertThat(Files.readString(tree.path("/etc/porelo-host/store/" + key.getKey())))
                        .as("rejected replacements preserve every prior key")
                        .isEqualTo(key.getValue());
            }
        }
    }

    @Test
    void putChecksEveryDestinationBeforeReplacingAnyKey() throws Exception {
        keys();
        final Path outside = Files.writeString(directory.resolve("outside"), "untouched");
        final Path password = tree.path("/etc/porelo-host/store/password");
        Files.delete(password);
        Files.createSymbolicLink(password, outside);
        final var answer =
                run(
                        input().replace(
                                        line("repository", KEYS.get("repository")),
                                        line("repository", "replacement")),
                        "store",
                        "put");
        assertThat(answer.code())
                .as("a linked destination refuses the complete publication")
                .isEqualTo(73);
        assertThat(Files.readString(tree.path("/etc/porelo-host/store/repository")))
                .as("earlier names are left alone")
                .isEqualTo(KEYS.get("repository"));
        assertThat(Files.readString(outside))
                .as("a destination link is never followed")
                .isEqualTo("untouched");
    }

    @ParameterizedTest
    @ValueSource(strings = {"put", "keep", "restore"})
    void everyStoreVerbRefusesAnUnprivilegedCallerBeforeReadingOrRunning(String verb) {
        final var answer = run(1000, Map.of(), input(), "store", verb);
        assertThat(answer.code()).as("only real root operates the store").isEqualTo(77);
        assertThat(calls).as("refusal precedes every child process").isEmpty();
        assertThat(tree.path("/etc/porelo-host/store"))
                .as("refusal writes no credentials")
                .doesNotExist();
        assertThat(run(0, Map.of(), "", "store", verb, "--help").code())
                .as("the same verb is recognized behind the root boundary")
                .isZero();
    }

    @Test
    void keepRunsEachAccountsCopyBeforeBackupAndRetention() throws Exception {
        keys();
        final var answer = run("", "store", "keep");
        assertThat(answer.code()).as("all hourly entries can be kept").isZero();
        final var helpers = commands("runuser");
        assertThat(helpers).as("the room and every recorded person have an entry").hasSize(3);
        assertThat(helpers.get(0))
                .as("the room runs as its own account")
                .containsSequence("runuser", "-u", "porelo-matrix", "--")
                .contains("porelo-message-matrix", "backup");
        assertThat(helpers.get(1))
                .as("usage runs as the person")
                .containsSequence("runuser", "-u", "alice", "--")
                .contains("porelo", "keep");
        assertThat(helpers.get(2))
                .as("the next person is kept independently")
                .containsSequence("runuser", "-u", "bob", "--");
        final var backups = restic("backup");
        assertThat(backups).as("the snapshots are scoped by entry tags").hasSize(3);
        assertThat(backups.stream().map(argv -> argv.get(argv.indexOf("--tag") + 1)).toList())
                .as("the fixed list has room and usage only, never a vault or cache")
                .containsExactly("room", "usage-alice", "usage-bob");
        for (int i = 0; i < helpers.size(); i++) {
            final List<String> backup = backups.get(i);
            final List<String> forget = restic("forget").get(i);
            assertThat(calls.stream().map(Call::argv).toList().indexOf(backup))
                    .as("the account finishes copying before root backs up")
                    .isGreaterThan(calls.stream().map(Call::argv).toList().indexOf(helpers.get(i)));
            assertThat(calls.stream().map(Call::argv).toList().indexOf(forget))
                    .as("retention follows a successful backup")
                    .isGreaterThan(calls.stream().map(Call::argv).toList().indexOf(backup));
            assertThat(backup)
                    .as("relative backup paths give restore a stable root")
                    .containsSequence("-C", helpers.get(i).getLast())
                    .contains(".");
            assertThat(forget)
                    .as("48 snapshots belong to the tag across staging paths and hosts")
                    .containsSequence("--keep-last", "48")
                    .containsSequence("--group-by", "tags");
        }
        assertThat(scratch)
                .as("each entry gets a fresh directory")
                .doesNotHaveDuplicates()
                .hasSize(3);
        for (final Path data : scratch)
            assertThat(data.getParent()).as("successful staging is removed").doesNotExist();
        noSecrets(answer);
    }

    private List<List<String>> restic(String verb) {
        return calls.stream()
                .map(Call::argv)
                .filter(argv -> argv.contains("restic") && argv.contains(verb))
                .toList();
    }

    @Test
    void aSecondCopyFailureStopsWithoutBackingUpOrForgettingIt() throws Exception {
        keys();
        reply =
                argv ->
                        argv.contains("runuser") && argv.contains("alice")
                                ? new HostPrograms.Result(
                                        9, KEYS.get("password"), KEYS.get("s3-credentials"))
                                : null;
        final var answer = run("", "store", "keep");
        assertThat(answer.code()).as("a failed entry fails the operation").isEqualTo(70);
        assertThat(answer.err())
                .as("the refusal identifies the failed entry and step")
                .contains("usage-alice", "backup command");
        assertThat(restic("backup")).as("a failed copy produces no snapshot").hasSize(1);
        assertThat(restic("forget"))
                .as("only the previously successful entry is retired")
                .hasSize(1);
        assertThat(commands("runuser")).as("later entries do not run").hasSize(2);
        for (final Path data : scratch)
            assertThat(data.getParent()).as("failed staging is removed too").doesNotExist();
        noSecrets(answer);
    }

    @Test
    void aPartialResticBackupNeverForgetsOrStartsTheNextEntry() throws Exception {
        keys();
        reply =
                argv ->
                        argv.contains("restic") && argv.contains("backup")
                                ? new HostPrograms.Result(3, KEYS.get("password"))
                                : null;
        final var answer = run("", "store", "keep");
        assertThat(answer.code()).as("partially unreadable data is a failed backup").isEqualTo(70);
        assertThat(answer.err())
                .as("root's failed step is named safely")
                .contains("room", "restic backup");
        assertThat(restic("forget")).as("the last good snapshot survives").isEmpty();
        assertThat(commands("runuser")).as("the next entry never starts").hasSize(1);
        noSecrets(answer);
    }

    @Test
    void aRetentionFailureStopsBeforeTheNextEntry() throws Exception {
        keys();
        reply =
                argv ->
                        argv.contains("restic") && argv.contains("forget")
                                ? new HostPrograms.Result(1, "")
                                : null;
        final var answer = run("", "store", "keep");
        assertThat(answer.code())
                .as("failed retention is not reported as a completed keep")
                .isEqualTo(70);
        assertThat(answer.err())
                .as("the entry and retention step remain actionable")
                .contains("room", "restic forget");
        assertThat(commands("runuser")).as("subsequent entries are left alone").hasSize(1);
    }

    @Test
    void onlyResticGetsCredentialFilePathsInItsEnvironment() throws Exception {
        keys();
        final var answer = run("", "store", "keep");
        assertThat(answer.code()).as("the file-based credential route works").isZero();
        for (final Call call : calls) {
            if (call.argv().contains("restic")) {
                assertThat(call.environment())
                        .as("restic receives paths, not credential contents")
                        .containsEntry(
                                "RESTIC_REPOSITORY_FILE",
                                tree.path("/etc/porelo-host/store/repository").toString())
                        .containsEntry(
                                "RESTIC_PASSWORD_FILE",
                                tree.path("/etc/porelo-host/store/password").toString())
                        .containsEntry(
                                "AWS_SHARED_CREDENTIALS_FILE",
                                tree.path("/etc/porelo-host/store/s3-credentials").toString())
                        .doesNotContainKeys(
                                "RESTIC_REPOSITORY",
                                "RESTIC_PASSWORD",
                                "AWS_ACCESS_KEY_ID",
                                "AWS_SECRET_ACCESS_KEY");
            } else {
                assertThat(call.environment())
                        .as("the account never receives even the store's paths")
                        .doesNotContainKeys(
                                "RESTIC_REPOSITORY_FILE",
                                "RESTIC_PASSWORD_FILE",
                                "AWS_SHARED_CREDENTIALS_FILE");
            }
        }
        noSecrets(answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep", "restore"})
    void readableKeysOrALinkedKeyRefuseBeforeRestic(String verb) throws Exception {
        keys();
        Files.setAttribute(tree.path("/etc/porelo-host/store/password"), "unix:mode", 0644);
        assertThat(run("", "store", verb).code())
                .as("old or manually widened credentials are refused")
                .isEqualTo(70);
        assertThat(calls).as("no process consumes unsafe keys").isEmpty();
        Files.setAttribute(tree.path("/etc/porelo-host/store/password"), "unix:mode", 0600);
        Files.delete(tree.path("/etc/porelo-host/store/s3-credentials"));
        Files.createSymbolicLink(
                tree.path("/etc/porelo-host/store/s3-credentials"),
                tree.path("/etc/porelo-host/store/password"));
        assertThat(run("", "store", verb).code())
                .as("linked credentials are refused")
                .isEqualTo(70);
        assertThat(calls).as("a link never reaches the credential reader").isEmpty();
    }

    @Test
    void keepInitializesOnlyAnAbsentRepositoryBeforeItsFirstEntry() throws Exception {
        keys();
        reply =
                argv ->
                        argv.contains("restic") && argv.contains("config")
                                ? new HostPrograms.Result(10, "")
                                : null;
        assertThat(run("", "store", "keep").code()).as("first keep creates a repository").isZero();
        assertThat(restic("init")).as("one initialization serves all entries").hasSize(1);
        assertThat(calls.stream().map(Call::argv).toList().indexOf(restic("init").getFirst()))
                .as("initialization precedes every account copy")
                .isLessThan(
                        calls.stream()
                                .map(Call::argv)
                                .toList()
                                .indexOf(commands("runuser").getFirst()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 11, 12, 130})
    void anUnavailableRepositoryIsNeverInitializedOrCalledFirstStart(int status) throws Exception {
        keys();
        reply =
                argv ->
                        argv.contains("restic") && argv.contains("config")
                                ? new HostPrograms.Result(status, "", KEYS.get("password"))
                                : null;
        for (final String verb : List.of("keep", "restore")) {
            calls.clear();
            final var answer = run("", "store", verb);
            assertThat(answer.code()).as("an unavailable repository fails closed").isEqualTo(70);
            assertThat(restic("init")).as("a refusal never creates another store").isEmpty();
            assertThat(commands("runuser"))
                    .as("no entry runs without a trustworthy repository answer")
                    .isEmpty();
            assertThat(answer.out()).as("failure is not absence").doesNotContain("first start");
            noSecrets(answer);
        }
    }

    @Test
    void restoreReportsFirstStartForEveryEntryWithoutASnapshot() throws Exception {
        keys();
        final var answer = run("", "store", "restore");
        assertThat(answer.code())
                .as("a new project's empty store is a successful first start")
                .isZero();
        for (final String tag : List.of("room", "usage-alice", "usage-bob"))
            assertThat(answer.out())
                    .as("each absent entry is explained")
                    .contains(tag + ": first start");
        assertThat(restic("restore")).as("there is no snapshot to restore").isEmpty();
        assertThat(commands("runuser")).as("no helper receives an empty restore").isEmpty();
    }

    @Test
    void restoreDoesNotInitializeAMissingRepository() throws Exception {
        keys();
        reply = argv -> argv.contains("config") ? new HostPrograms.Result(10, "") : null;
        final var answer = run("", "store", "restore");
        assertThat(answer.code()).as("an unmade store is a first start").isZero();
        assertThat(answer.out())
                .as("absence is said per entry")
                .contains(
                        "room: first start", "usage-alice: first start", "usage-bob: first start");
        assertThat(restic("init")).as("only keep creates a repository").isEmpty();
        assertThat(commands("runuser")).as("nothing is restored from no store").isEmpty();
    }

    @Test
    void restoreHandsEachAccountsDataBackAfterResticAndRecognizesThePresentRoom() throws Exception {
        keys();
        reply =
                argv -> {
                    if (argv.contains("snapshots"))
                        return new HostPrograms.Result(0, "[{\"id\":\"" + "a".repeat(64) + "\"}]");
                    if (argv.contains("runuser") && argv.contains("porelo-message-matrix"))
                        return new HostPrograms.Result(0, "{\"restore\":\"present\"}\n");
                    return null;
                };
        final var answer = run("", "store", "restore");
        assertThat(answer.code()).as("all existing snapshots can be restored").isZero();
        assertThat(answer.out())
                .as("an already present room causes no change")
                .contains("room: unchanged");
        final var restores = restic("restore");
        final var helpers = commands("runuser");
        assertThat(restores).as("each entry restores its own latest snapshot").hasSize(3);
        for (int i = 0; i < restores.size(); i++) {
            final var restore = restores.get(i);
            final var helper = helpers.get(i);
            assertThat(restore)
                    .as("root restores to the directory later given to its owner")
                    .contains("latest")
                    .containsSequence("--target", helper.getLast());
            assertThat(calls.stream().map(Call::argv).toList().indexOf(restore))
                    .as("the account sees only complete restored data")
                    .isLessThan(calls.stream().map(Call::argv).toList().indexOf(helper));
        }
        assertThat(helpers.get(1))
                .as("usage uses the person's restore operation")
                .containsSequence("porelo", "keep", "--restore");
        for (final Path data : scratch)
            assertThat(data.getParent()).as("restored staging is cleaned").doesNotExist();
    }

    @Test
    void aRunningTaskRefusesUsageRestoreWithItsOwnExitCode() throws Exception {
        keys();
        reply =
                argv -> {
                    if (argv.contains("snapshots"))
                        return new HostPrograms.Result(0, "[{\"id\":\"" + "a".repeat(64) + "\"}]");
                    if (argv.contains("runuser") && argv.contains("alice"))
                        return new HostPrograms.Result(69, KEYS.get("password"));
                    return null;
                };
        final var answer = run("", "store", "restore");
        assertThat(answer.code()).as("a running task's refusal remains 69").isEqualTo(69);
        assertThat(answer.err())
                .as("the person and refused step are named")
                .contains("usage-alice", "restore command", "69");
        assertThat(commands("runuser")).as("the next person's restore never runs").hasSize(2);
        noSecrets(answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "null", "[{}]"})
    void malformedSnapshotAnswersAreNotAnEmptyStore(String output) throws Exception {
        keys();
        reply = argv -> argv.contains("snapshots") ? new HostPrograms.Result(0, output) : null;
        final var answer = run("", "store", "restore");
        assertThat(answer.code())
                .as("an unparseable inventory cannot authorize first start")
                .isEqualTo(70);
        assertThat(answer.out())
                .as("unknown snapshots are not absent snapshots")
                .doesNotContain("first start");
        assertThat(commands("runuser")).as("malformed inventory reaches no helper").isEmpty();
    }

    @Test
    void aSnapshotListingFailureDoesNotBecomeFirstStart() throws Exception {
        keys();
        reply = argv -> argv.contains("snapshots") ? new HostPrograms.Result(1, "[]") : null;
        final var answer = run("", "store", "restore");
        assertThat(answer.code())
                .as("the snapshot query must succeed as well as parse")
                .isEqualTo(70);
        assertThat(answer.out())
                .as("an error never says first start")
                .doesNotContain("first start");
        assertThat(commands("runuser")).as("no account restores after a failed listing").isEmpty();
    }

    @Test
    void allKeepsTheSameHourlyEntriesWithoutRememberingThePreviousRun() throws Exception {
        keys();
        assertThat(run("", "store", "keep").code()).as("first hourly copy").isZero();
        final var first =
                restic("backup").stream().map(argv -> argv.get(argv.indexOf("--tag") + 1)).toList();
        calls.clear();
        assertThat(run("", "store", "keep", "--all").code())
                .as("down includes all hourly entries too")
                .isZero();
        assertThat(
                        restic("backup").stream()
                                .map(argv -> argv.get(argv.indexOf("--tag") + 1))
                                .toList())
                .as("the timer sets the pace; no local due state suppresses a copy")
                .containsExactlyElementsOf(first);
    }

    @Test
    void downEntriesAreAddedOnlyWhenAllIsRequested() {
        final var hourly =
                new StoreEntries.Entry(
                        "hourly",
                        "alice",
                        List.of("porelo", "keep", "<dir>"),
                        List.of("porelo", "keep", "--restore", "<dir>"),
                        48,
                        StoreEntries.Schedule.HOURLY);
        final var down =
                new StoreEntries.Entry(
                        "down",
                        "alice",
                        hourly.backup(),
                        hourly.restore(),
                        48,
                        StoreEntries.Schedule.DOWN);
        assertThat(StoreEntries.due(List.of(hourly, down), false))
                .as("ordinary keeps exclude down-only data")
                .containsExactly(hourly);
        assertThat(StoreEntries.due(List.of(hourly, down), true))
                .as("down includes both schedules in list order")
                .containsExactly(hourly, down);
    }

    @Test
    void lockedPeopleStillHaveTheirUsageKept() throws Exception {
        keys();
        final Path alice = tree.path("/etc/porelo-host/people/alice.conf");
        Files.writeString(alice, Files.readString(alice).replace("locked=false", "locked=true"));
        assertThat(run("", "store", "keep").code())
                .as("locking admission does not discard history")
                .isZero();
        assertThat(
                        restic("backup").stream()
                                .map(argv -> argv.get(argv.indexOf("--tag") + 1))
                                .toList())
                .as("every person record is an entry, regardless of current admission")
                .containsExactly("room", "usage-alice", "usage-bob");
    }

    @Test
    void aFailedInitializationRunsNoEntryOrRetention() throws Exception {
        keys();
        reply =
                argv ->
                        argv.contains("config")
                                ? new HostPrograms.Result(10, "")
                                : argv.contains("init") ? new HostPrograms.Result(1, "") : null;
        final var answer = run("", "store", "keep");
        assertThat(answer.code()).as("a repository must be created before copying").isEqualTo(70);
        assertThat(answer.err())
                .as("initialization is identified as the failed step")
                .contains("restic init");
        assertThat(commands("runuser"))
                .as("no account starts after failed initialization")
                .isEmpty();
        assertThat(restic("forget")).as("nothing is retired after failed initialization").isEmpty();
    }

    @Test
    void aFailedResticRestoreRunsNoAccountCommand() throws Exception {
        keys();
        reply =
                argv ->
                        argv.contains("snapshots")
                                ? new HostPrograms.Result(
                                        0, "[{\"id\":\"" + "a".repeat(64) + "\"}]")
                                : argv.contains("restore")
                                        ? new HostPrograms.Result(1, KEYS.get("password"))
                                        : null;
        final var answer = run("", "store", "restore");
        assertThat(answer.code()).as("incomplete restored data is not consumed").isEqualTo(70);
        assertThat(answer.err())
                .as("the entry and root restore step are named")
                .contains("room", "restic restore");
        assertThat(commands("runuser")).as("no helper sees an incomplete tree").isEmpty();
        assertThat(commands("chown"))
                .as("failed restored data is never handed to an account")
                .isEmpty();
        noSecrets(answer);
    }

    @Test
    void aRunnerFailureNamesTheEntryWithoutEchoingTheException() throws Exception {
        keys();
        ioFailure = "runuser";
        final var answer = run("", "store", "keep");
        assertThat(answer.code())
                .as("failure to run the account command fails the entry")
                .isEqualTo(70);
        assertThat(answer.err())
                .as("only the entry and operation are disclosed")
                .contains("room", "backup command");
        assertThat(restic("forget")).as("no retention follows a runner error").isEmpty();
        noSecrets(answer);
    }

    @Test
    void stagingCleanupNeverFollowsAnAccountsLinkOrRemovesASibling() throws Exception {
        keys();
        final Path outside = Files.writeString(directory.resolve("outside"), "untouched");
        final Path sibling =
                tree.put("/var/cache/porelo-host-store/unrelated/data", "other operation");
        Files.setAttribute(sibling.getParent().getParent(), "unix:mode", 0711);
        reply =
                argv -> {
                    if (argv.contains("runuser")) {
                        final Path data = Path.of(argv.getLast());
                        assertThat(data.getFileName().toString())
                                .as("the account's directory has a fixed leaf under root's parent")
                                .isEqualTo("data");
                        try {
                            assertThat(mode(data.getParent()))
                                    .as("the account can search but cannot replace its directory")
                                    .isEqualTo(0711);
                            Files.createSymbolicLink(data.resolve("link"), outside);
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }
                    return null;
                };
        assertThat(run("", "store", "keep").code())
                .as("account-created links do not break private cleanup")
                .isZero();
        assertThat(Files.readString(outside))
                .as("cleanup never crosses a link")
                .isEqualTo("untouched");
        assertThat(Files.readString(sibling))
                .as("another operation's staging is left alone")
                .isEqualTo("other operation");
        for (final Path data : scratch)
            assertThat(data.getParent())
                    .as("only this operation's staging is removed")
                    .doesNotExist();
    }

    @Test
    void rootRestoresAndAssignsOwnershipBeforeMakingStagingSearchable() throws Exception {
        keys();
        reply =
                argv -> {
                    if (argv.contains("snapshots"))
                        return new HostPrograms.Result(0, "[{\"id\":\"" + "a".repeat(64) + "\"}]");
                    if (argv.contains("restic") && argv.contains("restore")) {
                        final Path data = Path.of(argv.get(argv.indexOf("--target") + 1));
                        try {
                            assertThat(mode(data.getParent()))
                                    .as("no account can change root's active restore tree")
                                    .isEqualTo(0700);
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }
                    if (argv.contains("chown"))
                        assertThat(argv)
                                .as("ownership assignment never follows restored links")
                                .contains("--recursive", "--no-dereference", "--");
                    return null;
                };
        assertThat(run("", "store", "restore").code())
                .as("ownership is handed over only after root finishes")
                .isZero();
        assertThat(commands("chown"))
                .as("every restored entry gets its current local owner")
                .hasSize(3);
    }

    @Test
    void publicHostAndCacheAncestorsArePreservedWhilePrivateDirectoriesAreProtected()
            throws Exception {
        keys();
        final Path host = tree.path("/etc/porelo-host");
        Files.setAttribute(host, "unix:mode", 0755);
        final Path cache = tree.path("/var/cache");
        Files.createDirectories(
                cache,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setAttribute(cache.getParent(), "unix:mode", 0755);
        Files.setAttribute(cache, "unix:mode", 0755);
        final var answer = run("", "store", "keep");
        assertThat(answer.code())
                .as(
                        "an installed host's ordinary root-owned cache ancestor is usable: %s",
                        answer.err())
                .isZero();
        assertThat(mode(cache))
                .as("the shared cache ancestor keeps its public permissions")
                .isEqualTo(0755);
        assertThat(mode(host))
                .as("only the store's own credential directory needs to be private")
                .isEqualTo(0755);
        assertThat(mode(tree.path("/var/cache/porelo-host-store")))
                .as("only the dedicated staging base needs its fixed searchable mode")
                .isEqualTo(0711);
    }

    @ParameterizedTest
    @ValueSource(strings = {"writable", "unowned"})
    void anUnsafeCacheAncestorIsRefusedEvenWhenTheCacheItselfIsSafe(String defect)
            throws Exception {
        keys();
        final Path cache = tree.path("/var/cache");
        Files.createDirectories(
                cache,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path ancestor = cache.getParent();
        Files.setAttribute(ancestor, "unix:mode", 0755);
        Files.setAttribute(cache, "unix:mode", 0755);
        if (defect.equals("writable")) {
            Files.setAttribute(ancestor, "unix:mode", 0775);
        } else {
            tree.overrides.put(ancestor, Map.of("uid", 1000));
        }
        final var answer = run("", "store", "keep");
        assertThat(answer.code())
                .as("every ancestor must belong to root without shared write access")
                .isEqualTo(70);
        assertThat(answer.err())
                .as("the unsafe path prevents staging the first entry")
                .contains("room", "staging directory");
        assertThat(commands("runuser")).as("no account runs beneath an unsafe ancestor").isEmpty();
        assertThat(restic("backup")).as("unsafe staging cannot reach a backup").isEmpty();
        assertThat(restic("forget")).as("unsafe staging cannot retire snapshots").isEmpty();
    }

    @Test
    void putCannotReplaceKeysWhileAKeepUsesThem() throws Exception {
        keys();
        final var keeping = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var publishing = new java.util.concurrent.CountDownLatch(1);
        reply =
                argv -> {
                    if (argv.contains("restic") && argv.contains("config")) {
                        keeping.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("the test must release its keep");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(ex);
                        }
                    }
                    return null;
                };
        tree.afterAttributes =
                path -> {
                    if (Thread.currentThread().getName().equals("store-publisher")
                            && path.equals(tree.path("/etc/porelo-host/store.lock")))
                        publishing.countDown();
                };
        final var keep = new java.util.concurrent.FutureTask<>(() -> run("", "store", "keep"));
        final var put =
                new java.util.concurrent.FutureTask<>(
                        () ->
                                run(
                                        input().replace(
                                                        line("password", KEYS.get("password")),
                                                        line("password", "replacement")),
                                        "store",
                                        "put"));
        final Thread keeper = Thread.ofPlatform().name("store-keeper").start(keep);
        final Thread publisher = Thread.ofPlatform().name("store-publisher").unstarted(put);
        try {
            assertThat(keeping.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the keep holds the lock while restic consumes keys")
                    .isTrue();
            publisher.start();
            assertThat(publishing.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the writer has reached the same store lock")
                    .isTrue();
            assertThatThrownBy(() -> put.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("a complete new key set waits until the active keep ends")
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(Files.readString(tree.path("/etc/porelo-host/store/password")))
                    .as("the active keep retains its credential generation")
                    .isEqualTo(KEYS.get("password"));
            release.countDown();
            assertThat(keep.get(10, java.util.concurrent.TimeUnit.SECONDS).code())
                    .as("the old generation finishes successfully")
                    .isZero();
            assertThat(put.get(10, java.util.concurrent.TimeUnit.SECONDS).code())
                    .as("publication follows the completed keep")
                    .isZero();
            assertThat(Files.readString(tree.path("/etc/porelo-host/store/password")))
                    .as("the writer publishes after the lock is released")
                    .isEqualTo("replacement");
        } finally {
            release.countDown();
            keeper.interrupt();
            publisher.interrupt();
            keeper.join(10000);
            if (publisher.isAlive()) publisher.join(10000);
        }
    }
}
