package org.fuin.porelo.app;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test that leaves a clearance watcher of its own running, and stops that watcher.
 *
 * <p>A watcher follows its events file for as long as it lives and is started detached, so one a
 * test leaves outlives the test JVM, reparented to the user manager: nothing names it once the
 * test's temporary directory is gone, and it never exits. Every watcher a test starts is a
 * descendant of this JVM while it runs, which is what this asks.
 */
public final class WatcherLeakGuard implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(WatcherLeakGuard.class);

    /** How long a watcher a test stopped may take to exit, a JVM's shutdown included. */
    static final Duration PATIENCE = Duration.ofSeconds(10);

    private final Supplier<Stream<ProcessHandle>> processes;

    private final Duration patience;

    /** Creates the automatically discovered guard over this JVM's own descendants. */
    public WatcherLeakGuard() {
        this(() -> ProcessHandle.current().descendants(), PATIENCE);
    }

    WatcherLeakGuard(final Supplier<Stream<ProcessHandle>> processes, final Duration patience) {
        this.processes = processes;
        this.patience = patience;
    }

    /** Records the watchers already running, which this test did not start. */
    @Override
    public void beforeEach(final ExtensionContext context) {
        context.getStore(NAMESPACE).put(this, watchers().map(ProcessHandle::pid).toList());
    }

    /** Stops and reports every watcher the test left running. */
    @Override
    public void afterEach(final ExtensionContext context) {
        final Object before = context.getStore(NAMESPACE).remove(this);
        final Set<Long> earlier =
                before instanceof List<?> pids
                        ? pids.stream().map(Long.class::cast).collect(Collectors.toSet())
                        : Set.of();
        final List<String> left = leftOver(earlier);
        if (!left.isEmpty()) {
            throw new AssertionError(
                    "a test left "
                            + left.size()
                            + " clearance watcher(s) of its own running, stopped now: "
                            + String.join("; ", left));
        }
    }

    /**
     * Waits for the watchers started since {@code earlier} to exit, stops those still running when
     * the patience runs out, and names them.
     *
     * @param earlier Watchers that ran before the test.
     * @return Each one left running, as pid and command line.
     */
    List<String> leftOver(final Set<Long> earlier) {
        final long until = System.nanoTime() + patience.toNanos();
        List<ProcessHandle> left = started(earlier);
        while (!left.isEmpty() && System.nanoTime() < until) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            left = started(earlier);
        }
        final List<String> named =
                left.stream()
                        .map(
                                watcher ->
                                        watcher.pid()
                                                + " "
                                                + watcher.info().commandLine().orElse("?"))
                        .toList();
        left.forEach(WatcherLeakGuard::stop);
        return named;
    }

    private List<ProcessHandle> started(final Set<Long> earlier) {
        return watchers().filter(watcher -> !earlier.contains(watcher.pid())).toList();
    }

    private Stream<ProcessHandle> watchers() {
        return processes.get().filter(ProcessHandle::isAlive).filter(WatcherLeakGuard::isWatcher);
    }

    /**
     * Whether a process is a clearance watcher: {@code shield watch} in its command line.
     *
     * <p>Read from the command line, not the arguments: measured, the JDK reported no arguments for
     * a watcher whose command line it did report.
     *
     * @param process The process.
     * @return {@code true} for a watcher, whichever binary runs it.
     */
    static boolean isWatcher(final ProcessHandle process) {
        return process.info().commandLine().orElse("").contains(" shield watch ");
    }

    private static void stop(final ProcessHandle watcher) {
        watcher.descendants().forEach(ProcessHandle::destroyForcibly);
        watcher.destroyForcibly();
    }
}
