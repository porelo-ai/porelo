package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Installs a {@code pre-push} hook that says when a push carries unapproved agent work.
 *
 * <p><strong>A deliberate act, in somebody else's repository.</strong> The operator's checkout is
 * not Porelo's to change, so this is a command they run rather than something a task start does. It
 * writes one file, says what it wrote, and {@code --remove} takes it away again.
 *
 * <p><strong>What it can and cannot promise.</strong> Measured: the hook fires on a first push, an
 * ordinary push and a force push, and can see exactly which commits are leaving. Four things go
 * round it - {@code --no-verify}, a {@code core.hooksPath} pointing elsewhere, a fresh clone (hooks
 * are per clone and not committed), and a client built on a git library rather than the git
 * command. So this is a loud accident-catcher and not a control, and anything that presented it as
 * prevention would be the lie this requirement's own notes warn against.
 */
@picocli.CommandLine.Command(
        name = "protect",
        mixinStandardHelpOptions = true,
        description = "Installs a pre-push hook that catches unapproved agent work.")
public class GateProtectCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--repo",
            paramLabel = "<dir>",
            description = "The checkout to protect. Default: this directory.")
    private Path repository = Path.of(".");

    @Option(names = "--remove", description = "Takes the hook away again.")
    private boolean remove;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /**
     * Returns the hook's text.
     *
     * <p>Two lines of shell and nothing more: everything that could be wrong is decided by {@code
     * porelo gate check}, which is testable, and a hook nobody can read is a hook nobody trusts.
     * The marker line is how {@code --remove} knows it wrote this one rather than somebody else's.
     *
     * @return The script.
     */
    static String hook() {
        return """
                #!/bin/sh
                # porelo-gate-protect
                # Refuses a push carrying commits an agent wrote that nobody approved at the gate.
                # Remove with 'porelo gate protect --remove', or bypass once with 'git push --no-verify'.
                exec porelo gate check --quiet "$@"
                """;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // --git-common-dir, not --git-dir. Measured in a worktree: git runs pre-push out of the
        // common directory, and the worktree's own git dir has no hooks directory at all - so the
        // obvious query would create one, report success, and never fire.
        final var top =
                context.runner()
                        .run(
                                Command.of(
                                        List.of(
                                                "git",
                                                "-C",
                                                repository.toString(),
                                                "rev-parse",
                                                "--git-common-dir")));
        if (!top.successful()) {
            err.println("porelo: " + repository.toAbsolutePath() + " is not a git repository");
            err.flush();
            return 2;
        }
        final Path gitDir = repository.resolve(top.standardOutput().strip());
        final Path hookFile = gitDir.resolve("hooks").resolve("pre-push");

        if (remove) {
            try {
                final String present =
                        Files.isRegularFile(hookFile)
                                ? Files.readString(hookFile, StandardCharsets.UTF_8)
                                : null;
                final String without = present == null ? null : withoutAppended(present);
                if (without != null) {
                    // Appended to somebody else's hook: only the lines it added go, the rest is
                    // theirs.
                    Files.writeString(hookFile, without, StandardCharsets.UTF_8);
                    out.println(
                            "removed   the check from "
                                    + hookFile
                                    + "; the rest of it is left as it was");
                } else if (present != null
                        && present.contains("porelo-gate-protect")
                        && !present.contains(BEGIN_APPENDED)) {
                    Files.delete(hookFile);
                    out.println("removed   " + hookFile);
                } else {
                    // Never deletes somebody else's hook. A command that tidied away a file it did
                    // not write would be worse than the problem it solves.
                    out.println("nothing   no hook of ours at " + hookFile);
                }
                out.flush();
                return 0;
            } catch (IOException ex) {
                err.println("porelo: cannot remove " + hookFile + ": " + ex.getMessage());
                err.flush();
                return 70;
            }
        }

        try {
            Files.createDirectories(hookFile.getParent());
            if (Files.isRegularFile(hookFile)
                    && Files.readString(hookFile, StandardCharsets.UTF_8)
                            .contains(BEGIN_APPENDED)) {
                // Somebody else's hook the check was appended to: it carries the marker, and is
                // still theirs.
                out.println(
                        "appended  the check is in "
                                + hookFile
                                + " already; the rest of it is left as it was");
                out.flush();
                return 0;
            }
            if (Files.isRegularFile(hookFile)
                    && !Files.readString(hookFile, StandardCharsets.UTF_8)
                            .contains("porelo-gate-protect")) {
                if (appendOffer(
                        hookFile,
                        Offer.atTerminal()
                                ? new Offer(Offer.Asker.terminal(), false, false, err)
                                : Offer.NOBODY)) {
                    Files.writeString(
                            hookFile,
                            appended(Files.readString(hookFile, StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8);
                    out.println(
                            "appended  the check to "
                                    + hookFile
                                    + "; 'porelo gate protect --remove' takes it out again");
                    out.flush();
                    return 0;
                }
                err.println(
                        "porelo: "
                                + hookFile
                                + " already exists and is not ours - it was left"
                                + " alone. Move it aside, or add 'porelo gate check --quiet' to it.");
                err.flush();
                return 2;
            }
            Files.writeString(hookFile, hook(), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(hookFile, PosixFilePermissions.fromString("rwxr-xr-x"));
            out.println("installed " + hookFile);
        } catch (IOException ex) {
            err.println("porelo: cannot write " + hookFile + ": " + ex.getMessage());
            err.flush();
            return 70;
        }

        // Measured: with core.hooksPath set elsewhere, the repository's own hook is never found
        // and a push goes through with no output at all. Reporting success here without saying so
        // would leave somebody believing they are protected - which is worse than not installing.
        final var hooksPath =
                context.runner()
                        .run(
                                Command.of(
                                        List.of(
                                                "git",
                                                "-C",
                                                repository.toString(),
                                                "config",
                                                "core.hooksPath")));
        if (hooksPath.successful() && !hooksPath.standardOutput().isBlank()) {
            err.println();
            err.println(
                    "porelo: WARNING - core.hooksPath is set to "
                            + hooksPath.standardOutput().strip()
                            + ", so git will NOT run the hook just"
                            + " installed. Until that setting is changed or the hook is placed there"
                            + " instead, this repository is not protected.");
            err.flush();
            out.flush();
            return 0;
        }

        out.println("catches   a push carrying commits an agent wrote that nobody approved");
        out.println(
                "bypass    'git push --no-verify', which is how somebody who means it proceeds");
        out.println("note      hooks are per clone: a fresh clone of this repository has none");
        out.flush();
        return 0;
    }

    /** Where the lines added to somebody else's hook begin and end. */
    private static final String BEGIN_APPENDED = "# porelo-gate-protect: appended";

    private static final String END_APPENDED = "# porelo-gate-protect: end";

    /**
     * Returns somebody else's hook with the check appended: run after what it does, and stopping
     * the push when it fails.
     *
     * @param theirs The hook as it is.
     * @return The hook with the check at its end.
     */
    static String appended(final String theirs) {
        return theirs
                + (theirs.endsWith("\n") ? "" : "\n")
                + BEGIN_APPENDED
                + "\n"
                + "porelo gate check --quiet \"$@\" || exit $?\n"
                + END_APPENDED
                + "\n";
    }

    /**
     * Returns somebody else's hook without the lines {@link #appended} added.
     *
     * @param hook The hook.
     * @return The hook as it was before, or {@code null} when nothing was appended to it.
     */
    static @org.jspecify.annotations.Nullable String withoutAppended(final String hook) {
        final int begin = hook.indexOf(BEGIN_APPENDED + "\n");
        final int end = begin < 0 ? -1 : hook.indexOf(END_APPENDED, begin);
        if (begin < 0 || end < 0) {
            return null;
        }
        // Its own line end, or the file's end where an editor dropped the last one.
        final int after = end + END_APPENDED.length();
        if (after < hook.length() && hook.charAt(after) != '\n') {
            return null;
        }
        return hook.substring(0, begin) + hook.substring(Math.min(after + 1, hook.length()));
    }

    /**
     * Asks whether to append the check to somebody else's hook. Their file changes, so no by
     * default, naming it - and saying that a hook which exits before its end never reaches the
     * check.
     *
     * @param hookFile The hook.
     * @param offer Who is asked.
     * @return Whether to append it.
     */
    static boolean appendOffer(final Path hookFile, final Offer offer) {
        return offer != Offer.NOBODY
                && offer.confirm(
                        hookFile + " is somebody else's pre-push hook",
                        "Append 'porelo gate check' to its end?",
                        hookFile
                                + " (two lines at its end; a hook that exits earlier"
                                + " never reaches them)",
                        true);
    }
}
