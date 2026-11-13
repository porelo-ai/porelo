package org.fuin.porelo.app;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fuin.porelo.runtime.ContainerSummary;
import org.jspecify.annotations.Nullable;

/**
 * Takes the tasks whose container ended without a stop out of their rooms, as a stop does.
 *
 * <p><strong>Nothing reports a container's end with the vault in hand.</strong> The poststop hook
 * runs without the person's vault, and leaving needs the task's own secrets from it; a machine that
 * was off says nothing at all. So {@code porelod} compares what is enrolled with what is here: at
 * its start, which covers the machine that was off, once a minute while it runs, and when the vault
 * opens, since a locked vault reads no secret and so leaves nothing.
 *
 * <p>Only on a central homeserver, as a stop: elsewhere a retire would end the account for good.
 */
public final class TaskRoomSweep implements AutoCloseable {

    /** How often it sweeps while the daemon runs. */
    public static final Duration INTERVAL = Duration.ofMinutes(1);

    private final PoreloContext context;

    private final Duration every;

    private final BooleanSupplier vaultOpen;

    private final Consumer<String> log;

    private final Supplier<Instant> clock;

    /** The key of a sweep that failed as a whole. */
    private static final String SWEEP = "";

    /**
     * What was said already, as {@code task scheme}: said once while it lasts, tried again at every
     * sweep.
     */
    private final Set<String> told = ConcurrentHashMap.newKeySet();

    private volatile boolean running = true;

    /** When the next timed sweep is due; {@code null} before the first. */
    private @Nullable Instant next;

    /** Whether the vault was open at the last look. */
    private boolean wasOpen;

    /**
     * Constructor.
     *
     * @param context The account.
     * @param every How often to sweep.
     * @param log Where each task that left, and each that could not, is said, one line each.
     */
    public TaskRoomSweep(
            final PoreloContext context, final Duration every, final Consumer<String> log) {
        this(
                context,
                every,
                () -> context.vault().exists() && context.opener().isPresent(),
                log,
                Instant::now);
    }

    TaskRoomSweep(
            final PoreloContext context,
            final Duration every,
            final BooleanSupplier vaultOpen,
            final Consumer<String> log,
            final Supplier<Instant> clock) {
        this.context = context;
        this.every = every;
        this.vaultOpen = vaultOpen;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Tells whether to sweep now: the first look, the vault having opened since the last, or the
     * interval having passed.
     *
     * @return {@code true} when a sweep is due.
     */
    synchronized boolean due() {
        final Instant now = clock.get();
        final boolean open = vaultOpen.getAsBoolean();
        final boolean opened = open && !wasOpen;
        wasOpen = open;
        if (next == null || opened || !now.isBefore(next)) {
            next = now.plus(every);
            return true;
        }
        return false;
    }

    /**
     * Sweeps once.
     *
     * <p>Never throws: a project or a transport that cannot be read keeps nothing else from
     * leaving.
     *
     * @return Each task that left, as {@code task scheme}.
     */
    public synchronized List<String> sweepOnce() {
        if (!vaultOpen.getAsBoolean()) {
            // Nothing is read now; the vault's opening sweeps.
            return List.of();
        }
        final List<String> left;
        final Set<String> failing = new java.util.HashSet<>();
        try {
            final Set<String> runs = running();
            left =
                    new TaskConversations(context)
                            .sweep(
                                    context,
                                    task -> runs.contains(task) || here(task),
                                    (key, sentence) -> {
                                        failing.add(key);
                                        if (told.add(key)) {
                                            log.accept(sentence + "; tried again at each sweep");
                                        }
                                    });
        } catch (RuntimeException ex) {
            // Said once for as long as it lasts, not every minute.
            failing.add(SWEEP);
            if (told.add(SWEEP)) {
                log.accept(
                        "the tasks that ended are not taken out of their rooms: "
                                + ex.getMessage()
                                + "; tried again at each sweep");
            }
            return List.of();
        } finally {
            // What no longer fails is said again when it fails again.
            told.retainAll(failing);
        }
        for (final String each : left) {
            final int space = each.indexOf(' ');
            log.accept(
                    each.substring(0, space)
                            + " left the "
                            + each.substring(space + 1)
                            + " conversation: it ended without a stop");
        }
        return left;
    }

    /**
     * The containers running now, once per sweep: every task-shaped one, its record matching or
     * not. A running task whose record was lost or names another id is here all the same; taken for
     * ended, it was retired at every sweep.
     */
    Set<String> running() {
        final Set<String> names = new java.util.HashSet<>();
        for (final ContainerSummary summary : context.podman().taskCandidates()) {
            if (summary.running()) {
                names.add(summary.name());
            }
        }
        return names;
    }

    /**
     * Asks again, right before a task would leave, whether it is here: a start of it unfinished, or
     * its container running since the sweep's own look. What cannot be told keeps it.
     */
    private boolean here(final String container) {
        try {
            if (new TaskReservations(context).unfinishedNames().contains(container)) {
                return true;
            }
            return running().contains(container);
        } catch (IOException | RuntimeException ex) {
            return true;
        }
    }

    /** Starts sweeping, on a thread of its own: at once, then as {@link #due} says. */
    public void start() {
        if (every.isZero() || every.isNegative()) {
            return;
        }
        BackgroundPass.start(
                "porelo-room-sweep",
                () -> {
                    while (running) {
                        if (due()) {
                            sweepOnce();
                        }
                        try {
                            Thread.sleep(MessageWatch.VAULT_LOOK.toMillis());
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });
    }

    @Override
    public void close() {
        running = false;
    }
}
