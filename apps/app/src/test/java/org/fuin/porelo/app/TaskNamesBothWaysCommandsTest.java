package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * Every {@code task} and {@code talk} command that takes a task finds it by its container's name
 * and by its own, shown by running the commands picocli's tree holds.
 *
 * <p>{@code porelo task files b37online} said nothing was handed to the task, while the container's
 * name listed six records: most task commands took both spellings, and files, give, take-back,
 * label, clearance and talk did not. A guard that read the commands' source found half of them once
 * the code style moved an annotation's attribute onto the next line, and a mention of the
 * resolution in a file never showed that the command resolved its task through it. So the commands
 * are found in the tree a person's words are parsed by, and each is run.
 */
class TaskNamesBothWaysCommandsTest {

    /** The one task's project. */
    private static final String PROJECT = "default";

    /** The task's own name. */
    private static final String OWN = "birch";

    /** Its container's name. */
    private static final String CONTAINER = "porelo-" + PROJECT + "_" + OWN;

    /** A name no task has: what a command that takes one spelling makes of the other. */
    private static final String NOBODY = "rowan";

    private static final String ID = "c".repeat(64);

    /** Stands for the file on this machine a command is given, made in each run's machine. */
    private static final String HANDED = "the handed file";

    /**
     * What a command's further required parameters are given, by their label: a command that takes
     * a task and something more is run with something it can take.
     */
    private static final Map<String, String> FURTHER =
            Map.of(
                    "<peer>", "peer-one",
                    "<id>", "message-one",
                    "<mode>", "deny",
                    "FILE", HANDED,
                    "NAME", "handed.txt",
                    "CAPTION", "a caption");

    @TempDir Path dir;

    @Test
    void everyTaskAndTalkCommandFindsItsTaskByEitherName() throws Exception {
        final List<String> commands = takingATask();
        // A floor, not the exact count: a label changed so the walk no longer finds a command would
        // otherwise drop it from the check without a word.
        assertThat(commands)
                .as("task and talk commands that take a task")
                .hasSizeGreaterThanOrEqualTo(22);

        final List<String> failures = new ArrayList<>();
        for (final String command : commands) {
            final Outcome byContainer = run(command, CONTAINER);
            final Outcome byOwn = run(command, OWN);
            final Outcome byNobody = run(command, NOBODY);
            if (!byOwn.equals(byContainer)) {
                failures.add(
                        command
                                + " did not reach by the task's own name what the container's"
                                + " name reached: "
                                + byOwn.unlike(byContainer));
            } else if (byNobody.equals(byContainer)) {
                failures.add(
                        command
                                + " answers a name no task has as it answers the task's, so this"
                                + " test cannot tell which task it reached: "
                                + byContainer);
            }
        }
        assertThat(failures).as("commands that do not find their task by either name").isEmpty();
    }

    @Test
    void aCommandNobodyTypesIsLeftOutByBeingHidden() {
        final CommandLine task =
                PoreloCli.commandLine(NoAccount.context()).getSubcommands().get("task");
        assertThat(
                        task.getSubcommands()
                                .get("watch-builds")
                                .getCommandSpec()
                                .usageMessage()
                                .hidden())
                .as("run by a task's launch, never typed")
                .isTrue();
        assertThat(takingATask()).doesNotContain("task watch-builds");
    }

    /** The commands of the real tree whose first positional parameter is a task. */
    private static List<String> takingATask() {
        final CommandLine root = PoreloCli.commandLine(NoAccount.context());
        final Map<String, CommandSpec> found = new LinkedHashMap<>();
        for (final String group : List.of("task", "talk")) {
            walk(root.getSubcommands().get(group), found);
        }
        return found.entrySet().stream()
                .filter(entry -> !entry.getValue().usageMessage().hidden())
                .filter(entry -> takesATask(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void walk(CommandLine line, Map<String, CommandSpec> found) {
        for (final CommandLine sub : line.getSubcommands().values()) {
            final CommandSpec spec = sub.getCommandSpec();
            // An alias is the same command again.
            found.putIfAbsent(spec.qualifiedName(" ").replaceFirst("^porelo ", ""), spec);
            walk(sub, found);
        }
    }

    private static boolean takesATask(CommandSpec spec) {
        return spec.positionalParameters().stream()
                .filter(positional -> positional.index().min() == 0)
                .findFirst()
                .map(positional -> positional.paramLabel().replaceAll("[<>]", ""))
                .filter("task"::equalsIgnoreCase)
                .isPresent();
    }

    /** What one run did: its exit, what it said, what it ran and what it left on disk. */
    private record Outcome(
            int code, String out, String err, List<String> ran, Map<String, String> files) {

        /** Where this one differs from another, for the failure's message. */
        String unlike(Outcome other) {
            final List<String> differences = new ArrayList<>();
            if (code != other.code) {
                differences.add("exit " + code + ", not " + other.code);
            }
            if (!out.equals(other.out)) {
                differences.add("said '" + out + "', not '" + other.out + "'");
            }
            if (!err.equals(other.err)) {
                differences.add("complained '" + err + "', not '" + other.err + "'");
            }
            for (int index = 0; index < Math.max(ran.size(), other.ran.size()); index++) {
                final String mine = index < ran.size() ? ran.get(index) : "nothing";
                final String theirs = index < other.ran.size() ? other.ran.get(index) : "nothing";
                if (!mine.equals(theirs)) {
                    differences.add("ran '" + mine + "', not '" + theirs + "'");
                    break;
                }
            }
            final java.util.Set<String> paths = new java.util.TreeSet<>(files.keySet());
            paths.addAll(other.files.keySet());
            for (final String path : paths) {
                if (!java.util.Objects.equals(files.get(path), other.files.get(path))) {
                    differences.add(
                            files.containsKey(path)
                                    ? other.files.containsKey(path)
                                            ? "wrote other bytes to " + path
                                            : "wrote " + path
                                    : "did not write " + path);
                }
            }
            return String.join("; ", differences);
        }
    }

    private Outcome run(String command, String given) throws Exception {
        final Path machine = dir.resolve("machine");
        if (Files.exists(machine)) {
            try (Stream<Path> paths = Files.walk(machine)) {
                for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(machine);
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> machine.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> machine.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> machine.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> machine.resolve("run").toString();
                                    default -> null;
                                },
                        machine);
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .answering(
                                "ps --all --no-trunc",
                                CONTAINER
                                        + "\tExited (0) 2 minutes ago\t\t\t"
                                        + PROJECT
                                        + "\tguarded\t"
                                        + OWN
                                        + "\t\t"
                                        + ID
                                        + "\t"
                                        + OWN
                                        + "\n");
        final PoreloContext context =
                new PoreloContext(
                        runner, new PoreloPaths(xdg, machine.resolve("bin")), arguments -> 0);
        TaskIdentityFixture.record(context.paths(), CONTAINER, PROJECT, OWN, ID);
        // What its start recorded about it, which a caption or a clearance is written into.
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState(CONTAINER));
        Files.copy(
                context.paths().tasks().taskRecord(CONTAINER).resolve("sidecar.json"),
                state.resolve("sidecar.json"));
        new org.fuin.porelo.wire.TaskProfile(
                        org.fuin.porelo.wire.TaskProfile.VERSION,
                        "example",
                        org.fuin.porelo.wire.TaskMode.UNATTENDED,
                        "fix the parser",
                        "porelo/work",
                        "2026-11-12T10:00:00Z",
                        "prompt")
                .writeTo(state);
        new Mailbox(context.paths().messaging().mailbox(CONTAINER)).create();
        // One file handed in before: what 'task files' lists is the task's.
        final Path handIns =
                Files.createDirectories(context.paths().xdg().state().resolve("hand-ins"));
        Files.writeString(
                handIns.resolve(CONTAINER + ".jsonl"),
                "{\"event\":\"given\",\"name\":\"notes.txt\",\"bytes\":6,\"sha256\":\""
                        + "d".repeat(64)
                        + "\",\"at\":\"2026-11-12T10:00:00Z\",\"by\":\"someone\",\"run\":\"r1\"}\n");
        final Path handed = Files.writeString(machine.resolve("handed.txt"), "handed in\n");

        final CommandLine line = PoreloCli.commandLine(context);
        final List<String> arguments = new ArrayList<>(List.of(command.split(" ")));
        arguments.add(given);
        final CommandSpec spec = line.getCommandSpec();
        CommandSpec target = spec;
        for (final String word : command.split(" ")) {
            target = target.subcommands().get(word).getCommandSpec();
        }
        for (final PositionalParamSpec further : target.positionalParameters()) {
            if (further.index().min() > 0 && further.arity().min() > 0) {
                final String value = FURTHER.get(further.paramLabel());
                assertThat(value)
                        .as("what %s's %s is given", command, further.paramLabel())
                        .isNotNull();
                arguments.add(HANDED.equals(value) ? handed.toString() : value);
            }
        }
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        line.setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true));
        final InputStream in = System.in;
        System.setIn(new ByteArrayInputStream("a word\n".getBytes(StandardCharsets.UTF_8)));
        final int code;
        try {
            code = line.execute(arguments.toArray(String[]::new));
        } finally {
            System.setIn(in);
        }
        final Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(machine)) {
            for (final Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(
                        steady(machine.relativize(path).toString()),
                        steady(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)));
            }
        }
        return new Outcome(
                code,
                steady(out.toString()),
                steady(err.toString()),
                runner.lines().stream().map(TaskNamesBothWaysCommandsTest::steady).toList(),
                files);
    }

    /**
     * Returns a text without what differs from one run to the next: an id drawn at random and the
     * time. Neither holds a task's name, so both runs reach the same task or neither does.
     */
    private static String steady(String text) {
        return text.replaceAll(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z", "<time>")
                .replaceAll("\\d{8}T\\d{6,9}", "<time>");
    }
}
