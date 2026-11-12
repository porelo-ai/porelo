package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What {@code porelo task list --watch} shows: the tasks the daemon's {@code Watch} last sent, the
 * clearance questions its {@code Prompts} left open, and the ages, which the view works out itself
 * from each task's {@code since}.
 *
 * <p>It holds what arrived and draws it; it never asks for anything. A list arrives only when
 * something other than an age changed, so a minute that changed nothing redraws the ages and
 * nothing else.
 */
final class TaskWatch implements AutoCloseable {

    /** What a task that waits for somebody carries in front of its row. */
    static final String MARKED = "! ";

    /** What every other row carries there, so the columns stay where the listing has them. */
    static final String UNMARKED = "  ";

    /**
     * One line of what the view shows.
     *
     * @param text The whole line.
     * @param settled The line without its age, which is what a change is; the text itself for a
     *     line without one.
     * @param ageColumn Where the age starts, or -1 for a line without one.
     * @param ageCell The age as its cell holds it, or "" for a line without one.
     */
    record Line(String text, String settled, int ageColumn, String ageCell) {

        /**
         * Returns a line without an age.
         *
         * @param text The line.
         * @return It.
         */
        static Line plain(final String text) {
            return new Line(text, text, -1, "");
        }
    }

    /**
     * One task as the view shows it.
     *
     * @param name Its container's name, which identifies it.
     * @param row Its row.
     * @param marked Whether it waits for somebody.
     */
    record Shown(String name, TaskListing.Row row, boolean marked) {

        /**
         * Returns the task's line.
         *
         * @return The marker and the listing's row.
         */
        Line line() {
            final String marker = marked ? MARKED : UNMARKED;
            return new Line(
                    marker + row.text(),
                    marker + row.settled(),
                    marker.length() + row.ageColumn(),
                    row.ageCell());
        }
    }

    /**
     * Everything the view shows at one moment.
     *
     * @param hint The line about waiting clearance questions, or "" when none waits.
     * @param tasks The tasks, those waiting for somebody first.
     * @param none What is said instead of a table when there is no task.
     * @param notes What the listing says under its table.
     */
    record Frame(String hint, List<Shown> tasks, String none, List<String> notes) {

        /**
         * Returns the frame's lines: the hint's line, always there so that a question arriving
         * moves no row, then the listing.
         *
         * @return The lines.
         */
        List<Line> lines() {
            final List<Line> lines = new ArrayList<>();
            lines.add(Line.plain(hint));
            if (tasks.isEmpty()) {
                lines.add(Line.plain(none));
            } else {
                lines.add(Line.plain(UNMARKED + TaskListing.header()));
                tasks.forEach(task -> lines.add(task.line()));
            }
            notes.forEach(note -> lines.add(Line.plain(note)));
            return lines;
        }
    }

    /** Where a view is drawn. */
    interface Display {

        /**
         * Shows a frame.
         *
         * @param frame What to show.
         * @param now When.
         */
        void show(Frame frame, Instant now);

        /** Gives back whatever was taken to show it; runs more than once without harm. */
        void close();
    }

    /** One open clearance question: which task asked, and until when. */
    private record Question(String task, @Nullable Instant deadline) {}

    private final Display display;

    private final @Nullable String project;

    private List<TaskInventory.Task> all = List.of();

    private List<String> unrecognized = List.of();

    private boolean listed;

    private final Map<String, Question> questions = new LinkedHashMap<>();

    private boolean missed;

    private @Nullable String unfollowed;

    /**
     * Constructor.
     *
     * @param display Where to draw.
     * @param project The one project whose tasks are shown, or {@code null} for every task.
     */
    TaskWatch(final Display display, final @Nullable String project) {
        this.display = display;
        this.project = project;
    }

    /**
     * Takes a list the daemon sent, which replaces the one held.
     *
     * @param tasks Every task on the machine.
     * @param unrecognized The containers named like tasks that are not listed.
     * @param now When it arrived.
     */
    synchronized void listed(
            final List<TaskInventory.Task> tasks,
            final List<String> unrecognized,
            final Instant now) {
        this.all = List.copyOf(tasks);
        this.unrecognized = List.copyOf(unrecognized);
        this.listed = true;
        show(now);
    }

    /**
     * Takes one event of the daemon's {@code Prompts}: an open question is kept until its verdict
     * arrives, "timeout" among them.
     *
     * @param event The event, as sent.
     * @param now When it arrived.
     */
    synchronized void prompted(final Map<String, Object> event, final Instant now) {
        final String key = String.valueOf(event.getOrDefault("key", ""));
        final Object verdict = event.get("verdict");
        if (event.get("missed") instanceof Number number && number.longValue() > 0) {
            // A dropped question is never asked again, so it may wait unseen until its deadline.
            missed = true;
        }
        if (verdict != null && !String.valueOf(verdict).isEmpty()) {
            questions.remove(key);
        } else if (!key.isEmpty()) {
            questions.put(
                    key,
                    new Question(
                            String.valueOf(event.getOrDefault("task", "")),
                            instant(String.valueOf(event.getOrDefault("deadline", "")))));
        }
        show(now);
    }

    /**
     * Says that the clearance questions cannot be followed.
     *
     * @param why What failed.
     * @param now When.
     */
    synchronized void unfollowed(final String why, final Instant now) {
        this.unfollowed = why;
        questions.clear();
        show(now);
    }

    /**
     * Moves the clock on: the ages are worked out again, and a question past its deadline is let
     * go.
     *
     * @param now The moment.
     */
    synchronized void tick(final Instant now) {
        questions
                .values()
                .removeIf(
                        question ->
                                question.deadline() != null && !now.isBefore(question.deadline()));
        show(now);
    }

    /**
     * Returns what the view shows now.
     *
     * @param now The moment ages are measured against.
     * @return The frame.
     */
    synchronized Frame frame(final Instant now) {
        final List<TaskInventory.Task> tasks = TaskListing.of(all, project);
        final List<Shown> shown =
                tasks.stream()
                        .map(
                                task ->
                                        new Shown(
                                                task.name(),
                                                TaskListing.row(task, now),
                                                waitsForSomebody(task)))
                        // Stable, so the listing's own order holds within each half.
                        .sorted(Comparator.comparing((Shown task) -> !task.marked()))
                        .toList();
        return new Frame(
                hint(tasks),
                shown,
                TaskListing.none(unrecognized),
                TaskListing.notes(tasks, unrecognized));
    }

    @Override
    public synchronized void close() {
        display.close();
    }

    private void show(final Instant now) {
        if (listed) {
            display.show(frame(now), now);
        }
    }

    /**
     * Returns the line about waiting clearance questions.
     *
     * <p>Counted for the tasks shown: under {@code --project}, a question of another project's task
     * is not this view's. The view names where they are answered and answers none itself.
     */
    private String hint(final List<TaskInventory.Task> tasks) {
        if (unfollowed != null) {
            return "clearance questions are not followed here: " + unfollowed;
        }
        final Map<String, String> names = new LinkedHashMap<>();
        tasks.forEach(task -> names.put(task.name(), task.display()));
        final Set<String> asking = new LinkedHashSet<>();
        int count = 0;
        for (final Question question : questions.values()) {
            final String shown = names.get(question.task());
            if (shown == null && project != null) {
                continue;
            }
            count++;
            asking.add(shown == null ? question.task() : shown);
        }
        if (count == 0) {
            return missed ? "clearance questions may be waiting unseen: some were missed" : "";
        }
        return (count == 1 ? "1 clearance question waits" : count + " clearance questions wait")
                + " for "
                + String.join(", ", asking)
                + " - answer "
                + (count == 1 ? "it" : "them")
                + " where clearance is asked, not here"
                + (missed ? "; others may have been missed" : "");
    }

    /**
     * Tells whether a task waits for somebody: a question it was seen to ask, a screen its agent
     * declares a wait on, or its work at the gate.
     */
    static boolean waitsForSomebody(final TaskInventory.Task task) {
        return task.activity() == TaskInventory.Activity.WAITING
                || task.derived().screen() == AgentWaiting.Screen.WAITING
                || task.waiting() > 0;
    }

    private static @Nullable Instant instant(final String text) {
        try {
            return text.isEmpty() ? null : Instant.parse(text);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    /** Where a terminal is drawn on: rows and columns counted from zero. */
    interface Terminal {

        /** Takes the screen over. */
        void enter();

        /**
         * Writes a whole line.
         *
         * @param row Its row.
         * @param text What it says.
         */
        void line(int row, String text);

        /**
         * Writes part of a line.
         *
         * @param row Its row.
         * @param column Where the part starts.
         * @param text The part.
         */
        void cell(int row, int column, String text);

        /**
         * Clears from a row to the end of the screen.
         *
         * @param row The first row cleared.
         */
        void clearFrom(int row);

        /** Sends what was written. */
        void flush();

        /** Gives the screen back as it was found. */
        void leave();
    }

    /**
     * Draws on a terminal, writing only what differs from what it shows: a changed row whole, an
     * age that moved alone.
     */
    static final class Screen implements Display {

        private final Terminal terminal;

        private @Nullable List<Line> shown;

        private boolean left;

        /**
         * Constructor.
         *
         * @param terminal Where to draw.
         */
        Screen(final Terminal terminal) {
            this.terminal = terminal;
        }

        @Override
        public synchronized void show(final Frame frame, final Instant now) {
            if (left) {
                return;
            }
            final List<Line> before = shown;
            final List<Line> lines = frame.lines();
            if (before == null) {
                terminal.enter();
            }
            for (int row = 0; row < lines.size(); row++) {
                final Line line = lines.get(row);
                final Line was = before == null || row >= before.size() ? null : before.get(row);
                if (was == null || !was.settled().equals(line.settled())) {
                    terminal.line(row, line.text());
                } else if (!was.text().equals(line.text())) {
                    if (was.ageColumn() == line.ageColumn()
                            && was.ageCell().length() == line.ageCell().length()) {
                        terminal.cell(row, line.ageColumn(), line.ageCell());
                    } else {
                        terminal.line(row, line.text());
                    }
                }
            }
            if (before != null && before.size() > lines.size()) {
                terminal.clearFrom(lines.size());
            }
            terminal.flush();
            shown = lines;
        }

        @Override
        public synchronized void close() {
            if (!left && shown != null) {
                terminal.leave();
                terminal.flush();
            }
            left = true;
        }
    }

    /**
     * Writes to something that is not a terminal: the listing once, then one line per change, with
     * when, which task and what changed, and never an escape sequence.
     */
    static final class Log implements Display {

        private final PrintWriter out;

        private @Nullable Frame shown;

        /**
         * Constructor.
         *
         * @param out Where to write.
         */
        Log(final PrintWriter out) {
            this.out = out;
        }

        @Override
        public synchronized void show(final Frame frame, final Instant now) {
            final Frame before = shown;
            shown = frame;
            if (before == null) {
                if (!frame.hint().isEmpty()) {
                    out.println(frame.hint());
                }
                frame.lines().stream().skip(1).map(Line::text).forEach(out::println);
                out.flush();
                return;
            }
            final String at = now.truncatedTo(ChronoUnit.SECONDS).toString();
            final Map<String, Shown> was = byName(before.tasks());
            final Map<String, Shown> is = byName(frame.tasks());
            for (final Shown task : frame.tasks()) {
                final Shown old = was.get(task.name());
                if (old == null) {
                    out.println(
                            at
                                    + " "
                                    + task.row().display()
                                    + " listed: "
                                    + task.line().text().strip());
                } else {
                    final String changed = changes(old, task);
                    if (!changed.isEmpty()) {
                        out.println(at + " " + task.row().display() + " " + changed);
                    }
                }
            }
            for (final Shown task : before.tasks()) {
                if (!is.containsKey(task.name())) {
                    out.println(at + " " + task.row().display() + " is no longer listed");
                }
            }
            if (!before.hint().equals(frame.hint())) {
                out.println(
                        at
                                + " "
                                + (frame.hint().isEmpty()
                                        ? "no clearance question waits"
                                        : frame.hint()));
            }
            if (!before.notes().equals(frame.notes())) {
                frame.notes().stream()
                        .filter(note -> !note.isEmpty())
                        .forEach(note -> out.println(at + " " + note));
            }
            out.flush();
        }

        @Override
        public void close() {
            out.flush();
        }

        private static Map<String, Shown> byName(final List<Shown> tasks) {
            final Map<String, Shown> named = new LinkedHashMap<>();
            tasks.forEach(task -> named.put(task.name(), task));
            return named;
        }

        /** Says what changed of a task other than its age, as "field old -> new" pairs. */
        private static String changes(final Shown old, final Shown task) {
            final List<String> said = new ArrayList<>();
            change(said, "name", old.row().display(), task.row().display());
            change(said, "project", old.row().project(), task.row().project());
            change(said, "class", old.row().securityClass(), task.row().securityClass());
            change(said, "state", old.row().state(), task.row().state());
            change(
                    said,
                    "helpers",
                    String.valueOf(old.row().helpers()),
                    String.valueOf(task.row().helpers()));
            if (old.marked() != task.marked()) {
                said.add(task.marked() ? "waits for somebody" : "no longer waits for somebody");
            }
            return String.join(", ", said);
        }

        private static void change(
                final List<String> said, final String what, final String was, final String is) {
            if (!Objects.equals(was, is)) {
                said.add(what + " " + was + " -> " + is);
            }
        }
    }

    /** Draws with the escape sequences of an xterm, which every terminal Porelo meets speaks. */
    static final class Ansi implements Terminal {

        /**
         * Gives the screen back: colours off and line wrapping on, then the reset an attach ends
         * with - the main screen, the cursor shown, and every mode a program may have left on.
         */
        static final String LEAVE = "\033[0m\033[?7h" + TerminalAttach.RESETS;

        private final PrintWriter out;

        /**
         * Constructor.
         *
         * @param out The terminal.
         */
        Ansi(final PrintWriter out) {
            this.out = out;
        }

        @Override
        public void enter() {
            // The alternate screen keeps the scrollback; no wrapping keeps a long line on its row,
            // so the rows below stay where the view drew them.
            out.print("\033[?1049h\033[?25l\033[?7l\033[H\033[2J");
        }

        @Override
        public void line(final int row, final String text) {
            out.print("\033[" + (row + 1) + ";1H" + text + "\033[K");
        }

        @Override
        public void cell(final int row, final int column, final String text) {
            out.print("\033[" + (row + 1) + ";" + (column + 1) + "H" + text);
        }

        @Override
        public void clearFrom(final int row) {
            out.print("\033[" + (row + 1) + ";1H\033[J");
        }

        @Override
        public void flush() {
            out.flush();
        }

        @Override
        public void leave() {
            out.print(LEAVE);
        }
    }
}
