package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.fuin.porelo.core.process.Exec;
import org.fuin.porelo.wire.AppArmorProfile;
import org.jspecify.annotations.Nullable;

/**
 * Puts {@code porelo} and {@code porelod} into the AppArmor profile {@code porelo} at start, where
 * AppArmor runs, the profile is loaded and they are not in it already.
 *
 * <p>A binary under {@code /usr} wears the label by the profile's attachment. One anywhere else - a
 * developer's build, the account install a leg uses - would bind its sockets unlabelled, and a task
 * in {@code porelo-task} would be refused them. So it asks the kernel for the profile on its next
 * {@code exec} and re-executes itself with the same arguments; the profile names no developer's
 * path. Any process of the account could do the same: the bound {@code chcon} gives on SELinux.
 *
 * <p>Nothing here may stop the binary. A refused entry or a failed {@code exec} leaves it running
 * as it was, and {@code porelo doctor} says it is outside the profile.
 */
public final class AppArmorEntry {

    private AppArmorEntry() {}

    /**
     * Enters the profile and re-executes this process, or returns where nothing is to be done.
     *
     * <p>Called first thing in {@code main}, on the thread that runs it: the request is the
     * thread's own, and the {@code exec} must come from the same thread.
     */
    public static void enter() {
        enter(AppArmorProfile.real(), Path.of("/proc"), Exec::replaceCurrentProcess);
    }

    /**
     * Enters the profile and re-executes this process, as {@link #enter()} does.
     *
     * @param profile What the machine and this process are.
     * @param proc Where procfs is.
     * @param exec Replaces this process; returns only when that failed.
     */
    static void enter(AppArmorProfile profile, Path proc, Consumer<List<String>> exec) {
        if (!profile.shouldEnter()) {
            return;
        }
        // Read before the profile is asked for: where there is nothing to re-execute, the thread
        // is not to be left with a request that its next child would carry out instead.
        final List<String> arguments = arguments(proc);
        if (arguments == null || !profile.enterOnExec(AppArmorProfile.HOST)) {
            return;
        }
        try {
            exec.accept(arguments);
        } catch (RuntimeException ex) {
            // Still this process, outside the profile; doctor says so. A child it starts on this
            // thread goes into the profile, which confines nothing.
        }
    }

    /**
     * Returns what to re-execute: this binary by its real path, then this process's own arguments.
     *
     * <p>By the path the link names rather than {@code /proc/self/exe} itself, so the process keeps
     * its name ({@code porelod}, not {@code exe}) for whatever finds it by that name.
     *
     * @param proc Where procfs is.
     * @return The arguments; {@code null} where the binary is gone or the arguments unreadable.
     */
    static @Nullable List<String> arguments(Path proc) {
        try {
            // Fails for a binary an update replaced while this ran: whatever has the name now is
            // another version.
            final Path binary = Files.readSymbolicLink(proc.resolve("self/exe")).toRealPath();
            final String line =
                    new String(
                            Files.readAllBytes(proc.resolve("self/cmdline")),
                            StandardCharsets.UTF_8);
            final List<String> given = new ArrayList<>(List.of(line.split("\0", -1)));
            // Every argument ends in a NUL, so the split leaves one empty word after the last.
            if (given.size() < 2 || !given.getLast().isEmpty()) {
                return null;
            }
            given.removeLast();
            final List<String> arguments = new ArrayList<>();
            arguments.add(binary.toString());
            arguments.addAll(given.subList(1, given.size()));
            return List.copyOf(arguments);
        } catch (IOException | UnsupportedOperationException ex) {
            return null;
        }
    }
}
