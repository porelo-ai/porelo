package org.fuin.porelo.wire;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Tells where the second lock holds on a machine that confines programs by AppArmor: a task's
 * profile that may connect to no unix socket whose peer is not one of Porelo's binaries.
 *
 * <p><strong>The lock is on the connecting side.</strong> AppArmor cannot forbid an unconfined
 * process a bind, so the rule SELinux puts on the socket's label cannot be carried over. Porelo's
 * binaries wear the label {@value #HOST} instead, and a task is put into {@value #TASK}, which the
 * kernel lets connect only to a peer wearing {@value #HOST} or {@value #TASK}. A stand-in bound by
 * any other process of the account is refused at the task's {@code connect}. Both profiles are in
 * {@code apparmor/} beside {@code selinux/}.
 *
 * <p><strong>Everything here is read from files an unprivileged process can read</strong>, so it is
 * asked over a {@code /proc} and {@code /sys} of a test's own as well. {@code
 * /sys/kernel/security/apparmor/profiles} is root's alone; the per-profile directories under {@code
 * policy/profiles} are what is read instead (to be measured on the ubuntu leg).
 *
 * <p>The task is put into its profile only where {@link #state()} says {@link State#INSTALLED}:
 * everywhere else it runs as before, and {@code porelo doctor} says which of the other answers
 * holds.
 */
public final class AppArmorProfile {

    /**
     * The profile Porelo's binaries wear: flagged {@code unconfined}, it confines nothing and is
     * the label the task's rule names.
     */
    public static final String HOST = "porelo";

    /** The profile a task's agent runs in, enforcing, with no way out. */
    public static final String TASK = "porelo-task";

    /** What loads both profiles where the package put them. */
    public static final String INSTALL =
            "sudo apparmor_parser -r -W /etc/apparmor.d/porelo /etc/apparmor.d/porelo-task";

    /** The answers, in the order they are decided. */
    public enum State {

        /** AppArmor does not run here; SELinux or nothing holds the lock. */
        NOT_NEEDED,

        /**
         * AppArmor runs and {@value AppArmorProfile#TASK} is not loaded: a task runs as before,
         * with no lock.
         */
        MISSING,

        /**
         * The profile is loaded, but this kernel does not mediate unix sockets, so it would not
         * hold the peer rule: a task runs as before, with no lock.
         */
        ABSENT_ON_KERNEL,

        /**
         * Everything is loaded, and this process is not in {@value AppArmorProfile#HOST}: a task
         * put into its profile would be refused this process's sockets, so it runs as before.
         */
        OUTSIDE,

        /** Loaded, held by the kernel, and this process wears {@value AppArmorProfile#HOST}. */
        INSTALLED
    }

    private final Path proc;

    private final Path sys;

    /**
     * Constructor.
     *
     * @param proc Where procfs is.
     * @param sys Where sysfs is.
     */
    AppArmorProfile(Path proc, Path sys) {
        this.proc = proc;
        this.sys = sys;
    }

    /**
     * Returns the answers for this machine and this process.
     *
     * @return The profile as this process sees it.
     */
    public static AppArmorProfile real() {
        return new AppArmorProfile(Path.of("/proc"), Path.of("/sys"));
    }

    /**
     * Returns the answers over a {@code /proc} and {@code /sys} of a caller's own.
     *
     * @param proc Where procfs is.
     * @param sys Where sysfs is.
     * @return The profile as seen there.
     */
    public static AppArmorProfile at(Path proc, Path sys) {
        return new AppArmorProfile(proc, sys);
    }

    /**
     * Says whether AppArmor runs.
     *
     * @return {@code true} where the module's parameter says {@code Y}.
     */
    public boolean enabled() {
        try {
            return Files.readString(sys.resolve("module/apparmor/parameters/enabled"))
                    .strip()
                    .startsWith("Y");
        } catch (IOException ex) {
            // No module at all, which is the answer for Fedora and RHEL.
            return false;
        }
    }

    /**
     * Says whether a profile is loaded.
     *
     * <p>securityfs keeps one directory per loaded profile, named after it with a number appended,
     * and the profile's name in a file inside it; the name is compared, so a profile whose name
     * only begins the same is another one.
     *
     * @param profile The profile's name.
     * @return {@code true} if the kernel has it.
     */
    public boolean loaded(String profile) {
        final Path profiles = sys.resolve("kernel/security/apparmor/policy/profiles");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(profiles, profile + ".*")) {
            for (final Path entry : entries) {
                if (profile.equals(read(entry.resolve("name")))) {
                    return true;
                }
            }
            return false;
        } catch (IOException ex) {
            // Not there, or not readable: either way nothing can be put into it from here.
            return false;
        }
    }

    /**
     * Says whether the kernel mediates unix sockets, without which a peer rule is not held.
     *
     * @return {@code true} where the feature is there.
     */
    public boolean mediatesUnixSockets() {
        // A file holding "yes" on Ubuntu 26.04 (kernel 7.0), where the feature directories around
        // it are directories: whatever it is, being there is the answer.
        return Files.exists(sys.resolve("kernel/security/apparmor/features/network/af_unix"));
    }

    /**
     * Returns the label the calling thread wears, without its mode.
     *
     * <p>The kernel prints {@code porelo (unconfined)} for a profile flagged {@code unconfined},
     * {@code porelo-task (enforce)} for an enforcing one, and {@code unconfined} for none.
     *
     * @return The label, or an empty string where it cannot be read.
     */
    public String label() {
        final String printed = read(proc.resolve("thread-self/attr/apparmor/current"));
        if (printed == null) {
            return "";
        }
        final int mode = printed.indexOf(" (");
        return mode < 0 ? printed : printed.substring(0, mode);
    }

    /**
     * Returns the answer {@code doctor} gives.
     *
     * @return The state.
     */
    public State state() {
        if (!enabled()) {
            return State.NOT_NEEDED;
        }
        if (!loaded(TASK)) {
            return State.MISSING;
        }
        if (!mediatesUnixSockets()) {
            return State.ABSENT_ON_KERNEL;
        }
        if (!HOST.equals(label())) {
            return State.OUTSIDE;
        }
        return State.INSTALLED;
    }

    /**
     * Returns the profile a task's agent is to be put into.
     *
     * @return {@value #TASK} where the lock holds; {@code null} where a task runs as before.
     */
    public @Nullable String taskProfile() {
        return state() == State.INSTALLED ? TASK : null;
    }

    /**
     * Says whether this process is to enter {@value #HOST} itself: where AppArmor runs, the profile
     * is loaded, and the process is not in it.
     *
     * <p>A binary under {@code /usr} wears the label by the profile's attachment; a developer's
     * build and an account install wear it only this way. Once in it, this says {@code false}, so a
     * re-executed binary does not re-execute again.
     *
     * @return {@code true} if the process should enter it and re-execute.
     */
    public boolean shouldEnter() {
        return enabled() && loaded(HOST) && !HOST.equals(label());
    }

    /**
     * Asks the kernel to put the calling thread into a profile at its next {@code exec}.
     *
     * <p>Per thread: the {@code exec} must happen on the thread that called this. The kernel
     * refuses an unknown profile, and a profile with no {@code change_profile} rule refuses any,
     * which is what keeps a task in {@value #TASK}.
     *
     * @param profile The profile's name.
     * @return {@code true} if the kernel took it.
     */
    public boolean enterOnExec(String profile) {
        // One write: the kernel reads the attribute as one command.
        try (FileOutputStream out =
                new FileOutputStream(
                        proc.resolve("thread-self/attr/apparmor/exec").toFile(), false)) {
            out.write(("exec " + profile).getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Puts the calling thread into a profile now, for good where that profile has no way out.
     *
     * <p>Per thread, as every AppArmor label is: the other threads of the process stay as they are.
     *
     * @param profile The profile's name.
     * @return {@code true} if the kernel took it.
     */
    public boolean changeProfile(String profile) {
        try (FileOutputStream out =
                new FileOutputStream(
                        proc.resolve("thread-self/attr/apparmor/current").toFile(), false)) {
            out.write(("changeprofile " + profile).getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.US_ASCII).replace("\0", "").strip();
        } catch (IOException ex) {
            return null;
        }
    }
}
