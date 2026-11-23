package org.fuin.porelo.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.agent.api.InstalledAgents;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Answers whether a push carries work an agent wrote and nobody approved.
 *
 * <p><strong>Meant to be run by a {@code pre-push} hook, and useful on its own.</strong> Reads what
 * git gives a hook on standard input - {@code <local ref> <local sha> <remote ref> <remote sha>}
 * per line - works out which commits are about to leave, and names any whose author is an agent
 * installed here.
 *
 * <p><strong>Author identity, and the reason is measured rather than assumed.</strong> It was once
 * held that an author is "easy to lose in a rebase" and that a trailer added at approval time would
 * be safer. Measured: {@code git rebase} <em>preserves</em> the author and changes only the
 * committer, through an ordinary rebase, an interactive one and an {@code --amend}. So the cheap
 * signal is also the durable one, and nothing has to rewrite commits when work is approved.
 *
 * <p>It is spoofable, and that does not matter: this is protection against an accident, not against
 * the owner of the machine. Somebody who rewrites an author to get past it is exactly the person
 * the requirement says may still do it.
 */
@picocli.CommandLine.Command(
        name = "check",
        mixinStandardHelpOptions = true,
        description = "Reads a pre-push hook's input and refuses agent work nobody approved.")
public class GateCheckCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** A full commit id, as git names one under SHA-1 or SHA-256. */
    private static final java.util.regex.Pattern COMMIT =
            java.util.regex.Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /** git's own way of saying "there is nothing on the far side yet". */
    static final String ABSENT = "0000000000000000000000000000000000000000";

    /**
     * One commit that is leaving and should not be.
     *
     * @param commit Its abbreviated id.
     * @param author The author's address, as recorded.
     * @param subject Its first line.
     */
    record Offender(String commit, String author, String subject) {}

    @Option(names = "--quiet", description = "Says nothing when there is nothing to say.")
    private boolean quiet;

    // git passes the remote's name and URL to a pre-push hook as arguments. The name is what makes
    // "new to the far side" answerable: without it the check falls back to every remote.
    @picocli.CommandLine.Parameters(
            index = "0..*",
            arity = "0..2",
            hidden = true,
            paramLabel = "<remote> <url>",
            description = "What git hands the hook: the remote's name and its URL.")
    private List<String> remote = List.of();

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    private static Command git(@Nullable Path directory, List<String> arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(arguments);
        return new Command(all, directory, Map.of(), null);
    }

    /**
     * Returns the commits a push would send, for one line of a hook's input.
     *
     * @param runner Runs git.
     * @param directory The repository being pushed from, or {@code null} for this one.
     * @param localSha What is being pushed.
     * @param remoteSha What the far side has, or forty zeros.
     * @param remote The remote's name as git named it, or {@code null} when it is not known.
     * @return Commit ids, newest first.
     * @throws IllegalStateException When git cannot list the commits, or names a line that is not a
     *     commit id: what it stood for would go unchecked.
     */
    static List<String> commits(
            CommandRunner runner,
            @Nullable Path directory,
            String localSha,
            String remoteSha,
            @Nullable String remote) {
        if (ABSENT.equals(localSha)) {
            // A deletion. Nothing is being sent, so there is nothing to look at.
            return List.of();
        }
        final List<String> arguments = new ArrayList<>(List.of("rev-list"));
        if (ABSENT.equals(remoteSha)) {
            // A branch the far side does not have, so there is no base to subtract. The first form
            // written here was "--not --all", and measured it answers zero commits every time: the
            // ref being pushed is itself one of --all, so it cancels itself out. That is the worst
            // kind of wrong - it installs, it runs, it never fires. What is new to the far side is
            // what no tracking ref of that remote already has, and where there are no tracking refs
            // that is the whole branch, which is both conservative and literally true.
            arguments.add(localSha);
            arguments.add("--not");
            arguments.add(remote == null || remote.isBlank() ? "--remotes" : "--remotes=" + remote);
        } else {
            arguments.add(remoteSha + ".." + localSha);
        }
        // Streamed, since the listing grows with the branch's history: a result keeps only the end
        // of a long output, and the commits a cut loses are the oldest.
        final List<String> listed = new ArrayList<>();
        var result = runner.streamOutput(git(directory, arguments), line -> listed.add(id(line)));
        if (!result.successful() && !ABSENT.equals(remoteSha)) {
            // The far side's commit is not here - a force push, or one made without fetching - so
            // the range cannot be counted: what no tracking ref of that remote holds is what is new
            // to it, as for a first push.
            listed.clear();
            result =
                    runner.streamOutput(
                            git(
                                    directory,
                                    List.of(
                                            "rev-list",
                                            localSha,
                                            "--not",
                                            remote == null || remote.isBlank()
                                                    ? "--remotes"
                                                    : "--remotes=" + remote)),
                            line -> listed.add(id(line)));
        }
        if (!result.successful()) {
            // No listing is not an empty one: the push's commits would go unchecked.
            throw new IllegalStateException(
                    "git could not list the commits of the push: "
                            + result.standardError().strip());
        }
        return List.copyOf(listed);
    }

    /**
     * Refuses a push the check cannot judge, saying why and how a person pushes anyway.
     *
     * @param err Where the refusal goes.
     * @param why What could not be judged.
     * @return The hook's exit code, which refuses the push.
     */
    static int refused(final PrintWriter err, final String why) {
        err.println("porelo: this push is refused: " + why);
        err.println("  If you meant this, push again with --no-verify.");
        err.flush();
        return 1;
    }

    /** A line of git's listing, which names one commit or is refused, never skipped. */
    private static String id(final String line) {
        if (!COMMIT.matcher(line).matches()) {
            throw new IllegalStateException(
                    "git listed '"
                            + (line.length() > 80 ? line.substring(0, 80) + "..." : line)
                            + "' among the commits of the push, which is not a commit id");
        }
        return line;
    }

    /**
     * Returns the commits in a push that an agent wrote.
     *
     * <p>The whole decision, separated from where the input came from and where the answer goes, so
     * a test can put a real repository and a real push in front of it.
     *
     * @param runner Runs git.
     * @param directory The repository being pushed from, or {@code null} for this one.
     * @param hookInput The lines git hands a pre-push hook.
     * @param agentEmails Addresses the installed agents commit under, lower case.
     * @param remote The remote's name as git named it, or {@code null} when it is not known.
     * @return What an agent wrote, in the order the push would send it.
     */
    static List<Offender> offenders(
            CommandRunner runner,
            @Nullable Path directory,
            List<String> hookInput,
            Set<String> agentEmails,
            @Nullable String remote) {
        final List<Offender> found = new ArrayList<>();
        if (agentEmails.isEmpty()) {
            return found;
        }
        final Set<String> seen = new LinkedHashSet<>();
        for (final String line : hookInput) {
            final String[] fields = line.strip().split("\\s+");
            if (fields.length < 4) {
                // Not a hook line. Anything else on that stream is somebody running this by hand.
                continue;
            }
            for (final String commit : commits(runner, directory, fields[1], fields[3], remote)) {
                if (!seen.add(commit)) {
                    // Two refs pushed together share history. Naming a commit twice would make "3
                    // commits" mean something no count of the repository could confirm.
                    continue;
                }
                final var shown =
                        runner.run(
                                git(
                                        directory,
                                        List.of("show", "--no-patch", "--format=%ae|%s", commit)));
                if (!shown.successful()) {
                    continue;
                }
                final String[] parts = shown.standardOutput().strip().split("\\|", 2);
                if (agentEmails.contains(parts[0].toLowerCase(Locale.ROOT))) {
                    found.add(
                            new Offender(
                                    commit.substring(0, Math.min(9, commit.length())),
                                    parts[0],
                                    parts.length > 1 ? parts[1] : ""));
                }
            }
        }
        return found;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final Set<String> agentEmails = new LinkedHashSet<>();
        try (InstalledAgents agents = context.agents()) {
            for (final InstalledAgent agent : agents.all()) {
                agentEmails.add(agent.definition().gitIdentity().email().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ex) {
            // A machine whose agents cannot be asked cannot answer this question, and a push
            // let through unchecked would be a guard absent without anybody noticing.
            return refused(
                    err,
                    "cannot ask which agents are installed, so this push cannot be checked: "
                            + TalkReadCommand.shown(String.valueOf(ex.getMessage())));
        }
        if (agentEmails.isEmpty()) {
            return 0;
        }

        final List<String> hookInput = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                hookInput.add(line);
            }
        } catch (IOException ex) {
            return refused(
                    err,
                    "could not read what is being pushed, so it cannot be checked: "
                            + TalkReadCommand.shown(String.valueOf(ex.getMessage())));
        }

        final List<Offender> found;
        try {
            found =
                    offenders(
                            context.runner(),
                            null,
                            hookInput,
                            agentEmails,
                            remote.isEmpty() ? null : remote.get(0));
        } catch (RuntimeException ex) {
            // A push whose commits cannot all be named cannot be said to be free of agent work.
            return refused(
                    err,
                    "cannot tell which commits this push sends: "
                            + TalkReadCommand.shown(String.valueOf(ex.getMessage())));
        }
        if (found.isEmpty()) {
            if (!quiet) {
                out.println("nothing an agent wrote is in this push");
                out.flush();
            }
            return 0;
        }

        err.println(
                "porelo: this push carries "
                        + found.size()
                        + " commit(s) an agent wrote, which nobody approved at the gate:");
        // Terminal controls written out: an agent's subject must not rewrite the lines of this
        // refusal.
        found.forEach(
                offender ->
                        err.println(
                                "  "
                                        + offender.commit()
                                        + "  "
                                        + TalkReadCommand.shown(offender.subject())
                                        + "  ("
                                        + TalkReadCommand.shown(offender.author())
                                        + ")"));
        err.println();
        err.println("  The gate exists so somebody reads this before it leaves:");
        err.println("    porelo gate pending                 what is waiting");
        err.println("    porelo gate checkout <name>         open it where it cannot escape");
        err.println(
                "    porelo gate approve --project <project> --repository <repo> --commit <full-id> --branch <branch> <name>  send the reviewed commit up");
        err.println();
        err.println("  If you meant this, push again with --no-verify.");
        err.flush();
        return 1;
    }
}
