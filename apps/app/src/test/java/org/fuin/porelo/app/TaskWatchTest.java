package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TaskWatch}, the view {@code task list --watch} draws. Harm: a view that redraws
 * every row, or redraws because a clock moved, flickers and hides which task changed; a waiting
 * clearance question nobody sees keeps a task blocked; a terminal left on the alternate screen with
 * no cursor after Ctrl-C; escape sequences in a session log.
 */
class TaskWatchTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:30Z");

    /** What a terminal was asked to draw, one entry per call. */
    private static final class Recording implements TaskWatch.Terminal {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void enter() {
            calls.add("enter");
        }

        @Override
        public void line(final int row, final String text) {
            calls.add("line " + row + " " + text);
        }

        @Override
        public void cell(final int row, final int column, final String text) {
            calls.add("cell " + row + ":" + column + " " + text);
        }

        @Override
        public void clearFrom(final int row) {
            calls.add("clear " + row);
        }

        @Override
        public void flush() {
            // Nothing to send.
        }

        @Override
        public void leave() {
            calls.add("leave");
        }

        List<String> drawn() {
            final List<String> drawn = List.copyOf(calls);
            calls.clear();
            return drawn;
        }
    }

    private static TaskInventory.Task task(
            final String within,
            final String project,
            final String state,
            final TaskInventory.Activity activity) {
        return new TaskInventory.Task(
                "porelo-" + project + "-" + within,
                project,
                "guarded",
                state,
                state.startsWith("Up"),
                2,
                null,
                null,
                null,
                null,
                "2026-11-12T09:58:00Z",
                activity,
                null,
                null,
                null,
                0,
                "RUNNING",
                "");
    }

    private static List<TaskInventory.Task> machine(final String buildState) {
        return List.of(
                task("shell", "uc", "Up 4 minutes", TaskInventory.Activity.IDLE),
                task("build", "uc", buildState, TaskInventory.Activity.WORKING),
                task("other", "ab", "Up 9 minutes", TaskInventory.Activity.IDLE));
    }

    private static Map<String, Object> question(final String task, final String key) {
        final Map<String, Object> event = new HashMap<>();
        event.put("task", task);
        event.put("key", key);
        event.put("destination", "example.org");
        event.put("deadline", "");
        return event;
    }

    private static Map<String, Object> answered(
            final String task, final String key, final String verdict) {
        final Map<String, Object> event = question(task, key);
        event.put("verdict", verdict);
        return event;
    }

    @Test
    void oneTaskChangingRedrawsItsRowAndNoOther() {
        final Recording terminal = new Recording();
        final TaskWatch view = new TaskWatch(new TaskWatch.Screen(terminal), null);
        view.listed(machine("Up 1 minute"), List.of(), NOW);
        assertThat(terminal.drawn())
                .as("the first list draws the screen: hint line, header, three rows")
                .startsWith("enter")
                .hasSize(1 + 5);

        view.listed(machine("Exited (1) 3 seconds ago"), List.of(), NOW.plusSeconds(5));

        assertThat(terminal.drawn())
                .as("only the row of the task that changed")
                .singleElement()
                .asString()
                .startsWith("line 3 ")
                .contains("build ")
                .contains("Exited (1)");
    }

    @Test
    void aMinuteWithNoChangeWritesOnlyAges() {
        final Recording terminal = new Recording();
        final TaskWatch view = new TaskWatch(new TaskWatch.Screen(terminal), null);
        view.listed(machine("Up 1 minute"), List.of(), NOW);
        terminal.drawn();

        view.tick(NOW.plus(Duration.ofMinutes(1)));

        final int ageColumn = TaskWatch.UNMARKED.length() + TaskListing.header().indexOf("AGE");
        assertThat(terminal.drawn())
                .as("each row's age cell, at the age column, and nothing else")
                .containsExactly(
                        "cell 2:" + ageColumn + " 3m   ",
                        "cell 3:" + ageColumn + " 3m   ",
                        "cell 4:" + ageColumn + " 3m   ");
    }

    @Test
    void aTaskWaitingForSomebodyComesFirstAndIsMarked() {
        final Recording terminal = new Recording();
        final TaskWatch view = new TaskWatch(new TaskWatch.Screen(terminal), null);
        final List<TaskInventory.Task> tasks = new ArrayList<>(machine("Up 1 minute"));
        tasks.set(2, task("other", "ab", "Up 9 minutes", TaskInventory.Activity.WAITING));

        view.listed(tasks, List.of(), NOW);

        final List<String> drawn = terminal.drawn();
        assertThat(drawn)
                .as("the waiting task is the first row, marked")
                .filteredOn(call -> call.startsWith("line 2 "))
                .singleElement()
                .asString()
                .startsWith("line 2 " + TaskWatch.MARKED + "other ");
        assertThat(drawn)
                .as("the others follow in the listing's order, unmarked")
                .filteredOn(call -> call.startsWith("line 3 "))
                .singleElement()
                .asString()
                .startsWith("line 3 " + TaskWatch.UNMARKED + "shell ");
    }

    @Test
    void aWaitingClearanceQuestionIsOneLineThatGoesWithItsVerdict() {
        final Recording terminal = new Recording();
        final TaskWatch view = new TaskWatch(new TaskWatch.Screen(terminal), null);
        view.listed(machine("Up 1 minute"), List.of(), NOW);
        terminal.drawn();

        view.prompted(question("porelo-uc-build", "k1"), NOW);
        final List<String> asked = terminal.drawn();
        view.prompted(answered("porelo-uc-build", "k1", "allow"), NOW);
        final List<String> settled = terminal.drawn();

        assertThat(asked)
                .as("the hint line alone, naming the task")
                .singleElement()
                .asString()
                .startsWith("line 0 1 clearance question waits for build")
                .contains("not here");
        assertThat(settled).as("gone with its verdict").containsExactly("line 0 ");
    }

    @Test
    void aQuestionGoesWhenItsDeadlinePasses() {
        final Recording terminal = new Recording();
        final TaskWatch view = new TaskWatch(new TaskWatch.Screen(terminal), null);
        view.listed(machine("Up 1 minute"), List.of(), NOW);
        final Map<String, Object> asked = question("porelo-uc-build", "k1");
        asked.put("deadline", NOW.plusSeconds(30).toString());
        view.prompted(asked, NOW);
        terminal.drawn();

        view.tick(NOW.plusSeconds(60));

        assertThat(terminal.drawn()).as("the hint line cleared").contains("line 0 ");
    }

    @Test
    void underAProjectOnlyItsOwnTasksQuestionsAreCounted() {
        final Recording terminal = new Recording();
        final TaskWatch view = new TaskWatch(new TaskWatch.Screen(terminal), "uc");
        view.listed(machine("Up 1 minute"), List.of(), NOW);
        final List<String> first = terminal.drawn();

        view.prompted(question("porelo-ab-other", "k2"), NOW);
        final List<String> other = terminal.drawn();
        view.prompted(question("porelo-uc-shell", "k3"), NOW);
        final List<String> own = terminal.drawn();

        assertThat(first)
                .as("another project's task is not shown")
                .noneMatch(call -> call.contains("other "));
        assertThat(other).as("another project's question is not this view's").isEmpty();
        assertThat(own)
                .singleElement()
                .asString()
                .startsWith("line 0 1 clearance question waits for shell");
    }

    @Test
    void stoppingGivesTheTerminalBackAsAnAttachDoes() {
        final StringWriter screen = new StringWriter();
        final TaskWatch view =
                new TaskWatch(
                        new TaskWatch.Screen(new TaskWatch.Ansi(new PrintWriter(screen))), null);
        view.listed(machine("Up 1 minute"), List.of(), NOW);
        final String drawn = screen.toString();

        view.close();

        assertThat(drawn)
                .as("drawn on the alternate screen, the cursor hidden")
                .startsWith("\033[?1049h\033[?25l");
        assertThat(screen.toString().substring(drawn.length()))
                .as(
                        "colours off, wrapping on, then the main screen and the cursor back as an attach leaves them")
                .startsWith("\033[0m\033[?7h")
                .endsWith(TerminalAttachTest.RESETS);
    }

    @Test
    void outputThatIsNoTerminalIsALineForEachChangeWithoutEscapes() {
        final StringWriter log = new StringWriter();
        final TaskWatch view = new TaskWatch(new TaskWatch.Log(new PrintWriter(log)), null);
        view.listed(machine("Up 1 minute"), List.of(), NOW);
        view.tick(NOW.plus(Duration.ofMinutes(1)));
        final int listed = log.toString().length();

        view.listed(
                machine("Exited (1) 3 seconds ago"),
                List.of(),
                Instant.parse("2026-11-12T10:02:00Z"));
        view.prompted(question("porelo-uc-build", "k1"), Instant.parse("2026-11-12T10:02:01Z"));

        assertThat(log.toString()).as("readable history in a file").doesNotContain("\033");
        assertThat(log.toString().substring(listed).lines().toList())
                .as(
                        "a minute that changed nothing wrote nothing; then one line per change, with when and which task")
                .containsExactly(
                        "2026-11-12T10:02:00Z build state Up -> Exited (1)",
                        "2026-11-12T10:02:01Z 1 clearance question waits for build - answer it where clearance is asked, not here");
    }
}
