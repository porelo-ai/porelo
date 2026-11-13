package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.Waiting;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Whether an agent is waiting for a person, read from outside - from the screen tmux draws in an
 * attached task and from the machine-readable output of an unattended run, both of which the host
 * already has.
 *
 * <p><strong>Derived, never observed, and never a guess.</strong> What waiting looks like is the
 * agent's own declaration ({@link Waiting}); nothing here knows any agent's wording. An agent that
 * declares nothing is reported as {@code UNDECLARED}, which is a different answer from {@code
 * NOT_WAITING}: "this agent cannot tell us" is honest, "not waiting" from an agent that was never
 * able to say would be a lie with a timestamp on it.
 *
 * <p><strong>Pulled, never pushed.</strong> The host decides when to look - {@code tmux
 * capture-pane} through {@code podman exec}, a read of the state directory - and the container
 * never decides when the host learns something. Nothing here gives a task a way out.
 *
 * <p><strong>Cheap enough for a listing read twice a second.</strong> Declarations are read from
 * the installed agents at most once a {@link #DECLARATIONS}, a screen at most once a {@link
 * #SCREEN} per task, and a run's log again only when it changed. One instance lives as long as the
 * listing that uses it.
 */
public final class AgentWaiting {

    /**
     * How often the installed agents' declarations are read again: an agent can be updated under a
     * daemon.
     */
    static final Duration DECLARATIONS = Duration.ofMinutes(1);

    /**
     * How often one task's screen is read. A capture is a {@code podman exec}, about 130 ms
     * measured.
     */
    static final Duration SCREEN = Duration.ofSeconds(3);

    /**
     * How long one capture of a screen may take before it is given up as unread: a capture hung in
     * {@code podman exec} held a whole listing for the runner's ten minutes.
     */
    static final Duration CAPTURE = Duration.ofSeconds(3);

    /**
     * How long a declaration may be read against a task's screen without ever matching before it is
     * reported as unproven: an agent's wording changes with its version, and a rule that stopped
     * matching must not read as an agent that never waits.
     */
    static final Duration UNPROVEN_AFTER = Duration.ofHours(24);

    /** What the screen says about waiting for a person. */
    public enum Screen {

        /** A rule the agent declares matched its screen. */
        WAITING,

        /** The agent declares rules, and none matched. */
        NOT_WAITING,

        /** The agent declares nothing for its screen, so nothing can be said. */
        UNDECLARED,

        /**
         * Nothing here can see its screen: not attached, not running, or not its own screen right
         * now.
         */
        UNSEEN
    }

    /**
     * Whether an attached agent is at rest at its prompt or at work, as its own declaration of rest
     * reads its screen.
     */
    public enum Turn {

        /** Its screen showed rest on two looks at least {@link Rest#LOOK} apart. */
        AT_REST,

        /** Its screen shows it not at rest, or at rest for one look only. */
        WORKING,

        /**
         * Nothing can be said: not an attached running agent, an agent that does not declare its
         * rest and its questions, a screen that cannot be read, or a question to a person on it.
         */
        CANNOT_SAY
    }

    /** Whether a finished run's last message put something to the person. */
    public enum Asked {

        /** A rule the agent declares says it asked. */
        ASKED,

        /** A rule the agent declares says it did not. */
        DID_NOT_ASK,

        /**
         * Nothing declared can tell - today every agent, since none can yet say it stopped to ask.
         */
        CANNOT_SAY
    }

    /**
     * What was derived for one task.
     *
     * @param screen What its screen says.
     * @param waitingFor What it waits for, when the matching rule says; "" otherwise.
     * @param unproven Whether its declaration has been read for long without ever matching.
     * @param lastMessage What its last run said last, "" when the agent does not say where that is
     *     or it said nothing yet. Observed: the record is in the run's own output.
     * @param asked Whether that was a question.
     * @param askedFrom Where that answer came from: "declared" for a rule the agent declares, ""
     *     for none.
     * @param session The session its agent was running, recorded so the next start continues it; ""
     *     when none is - and then the next start begins a fresh one.
     * @param turn Whether its attached agent rests at its prompt or works.
     */
    public record Derived(
            Screen screen,
            String waitingFor,
            boolean unproven,
            String lastMessage,
            Asked asked,
            String askedFrom,
            String session,
            Turn turn) {

        /** What a task nothing could be read about carries. */
        public static final Derived NOTHING =
                new Derived(
                        Screen.UNSEEN, "", false, "", Asked.CANNOT_SAY, "", "", Turn.CANNOT_SAY);
    }

    /** Waits between two looks at a screen. */
    interface Pause {

        /**
         * Waits.
         *
         * @param time How long.
         * @return {@code false} when it was interrupted, so no second look is taken.
         */
        boolean waits(Duration time);
    }

    /** The pause a command asked once takes before it looks at a resting screen again. */
    static final Pause SLEEPS =
            time -> {
                try {
                    Thread.sleep(time);
                    return true;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };

    private record Declarations(Instant read, Map<String, Optional<AgentDefinition>> byAgent) {}

    /**
     * One read of a screen.
     *
     * <p>Rest is timed conservatively: a look saw the screen at some moment between the start and
     * the end of its capture, so the first look of a run of rest counts at its capture's end and a
     * later one at its start, and two looks counted {@link Rest#LOOK} apart were really that far
     * apart however slow a capture was.
     *
     * @param at When it was read, for how long it is kept.
     * @param screen What it says about waiting.
     * @param waitingFor What for, or "".
     * @param restingSince The end of the first look of the unbroken run of looks at the agent that
     *     showed rest, or {@code null} when the last look at the agent did not.
     * @param looked The start of the last look that saw the agent itself, not a pager over it, or
     *     {@code null} for none.
     * @param identity The container id Porelo recorded for the task when it was read, or {@code
     *     null} for none: a task removed and made again under its name inherits nothing.
     */
    private record Captured(
            Instant at,
            Screen screen,
            String waitingFor,
            @Nullable Instant restingSince,
            @Nullable Instant looked,
            @Nullable String identity) {

        static Captured unseen(final Instant at, final @Nullable String identity) {
            return new Captured(at, Screen.UNSEEN, "", null, null, identity);
        }
    }

    /**
     * What one capture brought back.
     *
     * @param text The screen's text, or {@code null} when none was read.
     * @param timedOut Whether the capture was given up after {@link #CAPTURE}.
     */
    private record Shown(@Nullable String text, boolean timedOut) {}

    private record Watched(Instant since, boolean matched) {}

    private record LogRead(long size, Instant modified, String lastMessage) {}

    private final PoreloContext context;

    private final Clock clock;

    private final java.util.function.Supplier<Map<String, Optional<AgentDefinition>>> installed;

    private volatile @Nullable Declarations declarations;

    private final Map<String, Captured> screens = new ConcurrentHashMap<>();

    private final Map<String, Watched> watched = new ConcurrentHashMap<>();

    private final Map<String, LogRead> logs = new ConcurrentHashMap<>();

    /**
     * One per task: its screen is read, judged against what was read before and kept by one read at
     * a time, so an older capture finishing late never puts back what a newer one reset.
     */
    private final Map<String, Object> reading = new ConcurrentHashMap<>();

    private final @Nullable Pause secondLook;

    /**
     * Constructor.
     *
     * @param context Where the agents, podman and the paths come from.
     * @param clock What "now" is.
     */
    AgentWaiting(PoreloContext context, Clock clock) {
        this(context, clock, () -> installed(context), null);
    }

    /**
     * Constructor for a command asked once, which has no earlier read of a screen: a screen at rest
     * is looked at a second time after {@link Rest#LOOK}, as the wake looks twice.
     *
     * @param context Where the agents, podman and the paths come from.
     * @param clock What "now" is.
     * @return The reader.
     */
    static AgentWaiting lookingTwice(PoreloContext context, Clock clock) {
        return new AgentWaiting(context, clock, () -> installed(context), SLEEPS);
    }

    /**
     * Constructor with where the declarations come from.
     *
     * @param context Where podman and the paths come from.
     * @param clock What "now" is.
     * @param installed Every installed agent's definition by name, read again at most once a {@link
     *     #DECLARATIONS}.
     */
    AgentWaiting(
            PoreloContext context,
            Clock clock,
            java.util.function.Supplier<Map<String, Optional<AgentDefinition>>> installed) {
        this(context, clock, installed, null);
    }

    /**
     * Constructor with where the declarations come from and how a second look waits.
     *
     * @param context Where podman and the paths come from.
     * @param clock What "now" is.
     * @param installed Every installed agent's definition by name, read again at most once a {@link
     *     #DECLARATIONS}.
     * @param secondLook How a screen first read at rest waits before it is read again, or {@code
     *     null} for no second look - a watch, whose next read is a look of its own.
     */
    AgentWaiting(
            PoreloContext context,
            Clock clock,
            java.util.function.Supplier<Map<String, Optional<AgentDefinition>>> installed,
            @Nullable Pause secondLook) {
        this.context = context;
        this.clock = clock;
        this.installed = installed;
        this.secondLook = secondLook;
    }

    /**
     * Derives what can be said about one task.
     *
     * @param container The task's container.
     * @param running Whether it is up.
     * @param agent The agent it runs, or {@code null}.
     * @param mode How somebody is involved - {@code AGENT} for an attached agent - or {@code null}.
     * @param state The task's state directory.
     * @return What was derived.
     */
    Derived about(
            String container,
            boolean running,
            @Nullable String agent,
            @Nullable String mode,
            Path state) {
        final AgentDefinition definition = agent == null ? null : definition(agent);
        final Waiting declared = definition == null ? null : definition.waiting();
        final org.fuin.porelo.agent.api.AtRest atRest =
                definition == null ? null : definition.atRest();
        final Captured screen = screenOf(container, running, mode, declared, atRest, state);
        final boolean unproven =
                screen.screen() != Screen.UNDECLARED && unproven(container, screen);
        final String last =
                declared == null || declared.lastMessage() == null
                        ? ""
                        : lastMessage(container, state.resolve("task.log"), declared.lastMessage());
        return new Derived(
                screen.screen(),
                screen.waitingFor(),
                unproven,
                last,
                Asked.CANNOT_SAY,
                "",
                new TaskSession(context).recorded(container).orElse(""),
                turn(screen, declared, atRest));
    }

    /**
     * Says whether an agent rests or works by what its screen showed, by the wake's rule: rest only
     * where its agent declares rest and questions, no question is open, and two reads at least
     * {@link Rest#LOOK} apart both showed rest.
     */
    private static Turn turn(
            Captured screen,
            @Nullable Waiting declared,
            org.fuin.porelo.agent.api.@Nullable AtRest atRest) {
        if (!Rest.readable(declared, atRest)
                || screen.screen() == Screen.WAITING
                || screen.screen() == Screen.UNSEEN
                || screen.screen() == Screen.UNDECLARED) {
            return Turn.CANNOT_SAY;
        }
        return restHeld(screen) ? Turn.AT_REST : Turn.WORKING;
    }

    /** Says whether two looks at the agent itself, {@link Rest#LOOK} apart, both showed rest. */
    private static boolean restHeld(final Captured screen) {
        final Instant since = screen.restingSince();
        final Instant looked = screen.looked();
        return since != null && looked != null && !since.plus(Rest.LOOK).isAfter(looked);
    }

    /** How many screens {@link #readScreens} reads at once. */
    static final int AT_ONCE = 8;

    /**
     * One task whose screen a listing will ask about.
     *
     * @param container The task's container.
     * @param running Whether it is up.
     * @param agent The agent it runs, or {@code null}.
     * @param mode How somebody is involved, or {@code null}.
     */
    record Wanted(
            String container, boolean running, @Nullable String agent, @Nullable String mode) {}

    /**
     * Reads the screens of several tasks at once, a few at a time, so that {@link #about} answers
     * them from what was read. Each read is a process in the container, about 100 ms; one after the
     * other, a listing of 30 tasks took about 4 s.
     *
     * @param tasks The tasks a listing is about to describe.
     */
    void readScreens(final List<Wanted> tasks) {
        final List<Wanted> due =
                tasks.stream()
                        .filter(
                                task ->
                                        task.running()
                                                && "AGENT".equals(task.mode())
                                                && task.agent() != null)
                        .toList();
        if (due.size() < 2) {
            return;
        }
        // Platform threads, ended with the call: each waits on a process, which a virtual thread is
        // not to do in this daemon.
        try (java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(AT_ONCE, due.size()),
                        Thread.ofPlatform().daemon().name("porelo-screen-", 0).factory())) {
            for (final Wanted task : due) {
                final AgentDefinition definition =
                        definition(java.util.Objects.requireNonNull(task.agent()));
                pool.submit(
                        () ->
                                screenOf(
                                        task.container(),
                                        true,
                                        task.mode(),
                                        definition == null ? null : definition.waiting(),
                                        definition == null ? null : definition.atRest(),
                                        context.paths().tasks().containerState(task.container())));
            }
        }
    }

    private Captured screenOf(
            String container,
            boolean running,
            @Nullable String mode,
            @Nullable Waiting declared,
            org.fuin.porelo.agent.api.@Nullable AtRest atRest,
            Path state) {
        if (declared == null || declared.screen().isEmpty()) {
            return new Captured(clock.instant(), Screen.UNDECLARED, "", null, null, null);
        }
        synchronized (reading.computeIfAbsent(container, name -> new Object())) {
            final Instant now = clock.instant();
            if (!running || !"AGENT".equals(mode)) {
                // Only an attached agent has a screen; an unattended run writes records, and a
                // shell is a person's.
                screens.remove(container);
                return Captured.unseen(now, null);
            }
            final String identity = org.fuin.porelo.wire.Sidecar.recordedId(state);
            final Captured kept = screens.get(container);
            final Captured before =
                    kept == null || !java.util.Objects.equals(kept.identity(), identity)
                            ? null
                            : kept;
            if (before != null && before.at().plus(SCREEN).isAfter(now)) {
                return before;
            }
            Captured read = look(container, declared, atRest, before, identity, state);
            if (secondLook != null
                    && read.restingSince() != null
                    && !restHeld(read)
                    && secondLook.waits(Rest.LOOK)) {
                // Rest seen, with no earlier look to hold it against: look again, as the wake
                // does, rather than say rest of what may be a gap between two tool calls.
                read = look(container, declared, atRest, read, identity, state);
            }
            screens.put(container, read);
            return read;
        }
    }

    private Captured look(
            String container,
            Waiting declared,
            org.fuin.porelo.agent.api.@Nullable AtRest atRest,
            @Nullable Captured before,
            @Nullable String identity,
            Path state) {
        final Instant start = clock.instant();
        final Shown shown = shown(container, state);
        final Instant end = clock.instant();
        final String text = shown.text();
        if (text == null) {
            // A capture given up is kept from when it was given up, so the listing that gave up
            // does not wait for it a second time.
            return Captured.unseen(shown.timedOut() ? end : start, identity);
        }
        final Waiting.Reading reading = declared.read(text);
        return switch (reading.seen()) {
            // A pager over the agent is still the agent's pane, and says nothing of the agent: keep
            // what was last read of it, and never let the pager stand in for a look at it.
            case NOT_ITS_SCREEN ->
                    before == null
                            ? Captured.unseen(start, identity)
                            : new Captured(
                                    start,
                                    before.screen(),
                                    before.waitingFor(),
                                    before.restingSince(),
                                    before.looked(),
                                    identity);
            case WAITING ->
                    new Captured(
                            start,
                            Screen.WAITING,
                            reading.waitingFor() == null ? "" : reading.waitingFor(),
                            null,
                            start,
                            identity);
            case NOT_WAITING ->
                    new Captured(
                            start,
                            Screen.NOT_WAITING,
                            "",
                            !Rest.shows(text, declared, atRest)
                                    ? null
                                    : before != null && before.restingSince() != null
                                            ? before.restingSince()
                                            : end,
                            start,
                            identity);
        };
    }

    /** Reads the screen: what the task wrote, or else a capture in the container, given a limit. */
    private Shown shown(final String container, final Path state) {
        // What the task wrote, when its writer answers: a file, not a process in the container.
        final String written = ScreenFile.read(state, clock);
        if (written != null) {
            return new Shown(written.isEmpty() ? null : written, false);
        }
        final java.util.concurrent.FutureTask<CommandResult> capture =
                new java.util.concurrent.FutureTask<>(
                        () ->
                                context.podman()
                                        .ask(
                                                container,
                                                Map.of(),
                                                List.of(
                                                        "tmux",
                                                        "capture-pane",
                                                        "-p",
                                                        "-J",
                                                        "-t",
                                                        org.fuin.porelo.runtime.Containerfile
                                                                .SESSION)));
        // A platform thread of its own, so a capture that hangs is given up, and interrupted,
        // which ends its process.
        Thread.ofPlatform().daemon().name("porelo-capture-" + container).start(capture);
        final CommandResult drawn;
        try {
            drawn = capture.get(CAPTURE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            capture.cancel(true);
            return new Shown(null, true);
        } catch (InterruptedException ex) {
            capture.cancel(true);
            Thread.currentThread().interrupt();
            return new Shown(null, false);
        } catch (java.util.concurrent.ExecutionException ex) {
            return new Shown(null, false);
        }
        // A non-zero exit: no session yet, or one that ended, so nothing is drawn to read.
        return new Shown(drawn.exitCode() == 0 ? drawn.standardOutput() : null, false);
    }

    private boolean unproven(String container, Captured screen) {
        if (screen.screen() == Screen.UNSEEN) {
            return false;
        }
        final Watched seen =
                watched.merge(
                        container,
                        new Watched(screen.at(), screen.screen() == Screen.WAITING),
                        (was, now) -> new Watched(was.since(), was.matched() || now.matched()));
        return !seen.matched() && !seen.since().plus(UNPROVEN_AFTER).isAfter(clock.instant());
    }

    private String lastMessage(String container, Path log, Waiting.LastMessage where) {
        try {
            if (!Files.isRegularFile(log)) {
                logs.remove(container);
                return "";
            }
            final long size = Files.size(log);
            final Instant modified = Files.getLastModifiedTime(log).toInstant();
            final LogRead before = logs.get(container);
            if (before != null && before.size() == size && before.modified().equals(modified)) {
                return before.lastMessage();
            }
            final List<Object> records = new ArrayList<>();
            for (final String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                try {
                    final Object record = Json.parse(line);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (RuntimeException notARecord) {
                    // A line that is not a record - an error printed around the stream - says
                    // nothing here.
                }
            }
            final String said = where.of(records);
            final String last = said == null ? "" : said;
            logs.put(container, new LogRead(size, modified, last));
            return last;
        } catch (IOException ex) {
            return "";
        }
    }

    private @Nullable AgentDefinition definition(String agent) {
        Declarations known = declarations;
        final Instant now = clock.instant();
        if (known == null || !known.read().plus(DECLARATIONS).isAfter(now)) {
            final Map<String, Optional<AgentDefinition>> byAgent = installed.get();
            known = new Declarations(now, byAgent);
            declarations = known;
        }
        return known.byAgent().getOrDefault(agent, Optional.empty()).orElse(null);
    }

    private static Map<String, Optional<AgentDefinition>> installed(PoreloContext context) {
        final Map<String, Optional<AgentDefinition>> byAgent = new java.util.HashMap<>();
        try (org.fuin.porelo.agent.api.InstalledAgents agents = context.agents()) {
            for (final org.fuin.porelo.agent.api.InstalledAgent each : agents.all()) {
                byAgent.put(each.name(), Optional.of(each.definition()));
            }
        } catch (RuntimeException ex) {
            // An agent that will not describe itself declares nothing that can be read.
        }
        return byAgent;
    }
}
