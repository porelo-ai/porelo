package org.fuin.porelo.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.BranchScheme;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * A bare mirror an agent pushes into, and from which nothing leaves unreviewed.
 *
 * <p>The agent never has the upstream's credentials and never reaches the upstream: it pushes to
 * this mirror over a local HTTP endpoint. Forwarding is a separate, explicit act by the operator.
 *
 * <p><strong>Agent pushes land under {@code refs/porelo/incoming/}, never on a branch.</strong> A
 * push to {@code refs/heads/main} would make the mirror's own branches move under the operator
 * while they are reading them, and would let an agent rewrite history that had already been
 * reviewed. Incoming refs are inert: nothing reads them but the review command.
 */
public class GitGate {

    /** Namespace every agent push lands in. */
    public static final String INCOMING = "refs/porelo/incoming/";

    /** Private ownership records, hidden from every task's Git endpoint. */
    public static final String FORWARDED = "refs/porelo/forwarded/";

    /**
     * Where a task's work landed, by the branch it was forwarded to: {@code <task>/<branch>} at the
     * forwarded commit. Hidden from every task but a reader started naming that task.
     */
    public static final String LANDED = "refs/porelo/landed/";

    /**
     * Where a task's work landed.
     *
     * @param branch The branch it was forwarded to.
     * @param commit The commit forwarded there.
     */
    public record Landed(String branch, String commit) {}

    /**
     * After a task's own ref: where what it never handed back is rescued to when the task is
     * removed - beside its reviewed work, never in it.
     */
    public static final String RESCUED = "-rescued";

    /**
     * Beside the mirror's refs, never served: a directory holding, per task, the commit a host's
     * pass-on last pushed to the host's forge, which the next push of that task leases on.
     */
    public static final String HOST_PUSHED = "porelo-host-pushed";

    /**
     * Ends the name, in {@link #HOST_PUSHED}, of what keeps that a task's last pass-on to its
     * host's forge ended with the forge not holding it or not saying: {@code .<task>} and this.
     */
    public static final String HOST_PUSH_UNSETTLED = ".unsettled";

    private final CommandRunner runner;

    private final Path mirror;

    private final GateMode mode;

    @Nullable private final String upstreamUrl;

    @Nullable private final String seedUrl;

    private BranchScheme taskBranches = BranchScheme.DEFAULT;

    /** Where a credential for a remote comes from. Nothing, until a caller that has one says so. */
    private GitCredentials lending = GitCredentials.NONE;

    private @Nullable PullRequests pullRequests;

    /** How long a push passed on to the upstream may wait for it. */
    private java.time.Duration passOnLimit = org.fuin.porelo.core.project.Limits.DEFAULT_PASS_ON;

    /** How long the gate asks after a push the forge did not answer, at most. */
    private java.time.Duration askingAfter = ASKING_AFTER;

    /**
     * Returns this gate with its own time limits under the account's factors.
     *
     * @param limits The factors.
     * @return This gate.
     */
    public GitGate limitedBy(final org.fuin.porelo.wire.LimitFactors limits) {
        this.askingAfter = askingAfter(limits);
        return this;
    }

    /**
     * Returns how long the gate asks after a push the forge did not answer, at most.
     *
     * @param limits The factors.
     * @return {@link #ASKING_AFTER} under {@code services}.
     */
    static java.time.Duration askingAfter(final org.fuin.porelo.wire.LimitFactors limits) {
        return limits.services(ASKING_AFTER);
    }

    /** Opens or reuses the review of a successfully pushed branch. */
    @FunctionalInterface
    public interface PullRequests {

        /**
         * Returns the review's address, refusing if the forge cannot open it.
         *
         * @param task The task whose reviewed work was pushed.
         * @param branch The pushed branch.
         * @param subject The pushed commit's subject.
         * @return The pull request's address.
         */
        String open(String task, String branch, String subject);
    }

    /**
     * Requires a forge review before a pushed approval leaves the pending queue.
     *
     * @param reviews The forge's pull requests.
     * @return This gate.
     */
    public GitGate pullRequests(PullRequests reviews) {
        pullRequests = reviews;
        return this;
    }

    /**
     * Constructor for a gate whose mirror is seeded from the upstream it forwards to.
     *
     * @param runner Runs git.
     * @param mirror Directory of the bare mirror.
     * @param mode What the gate may do.
     * @param upstreamUrl Upstream to forward to, or {@code null} if there is none.
     */
    public GitGate(CommandRunner runner, Path mirror, GateMode mode, @Nullable String upstreamUrl) {
        this(runner, mirror, mode, upstreamUrl, upstreamUrl);
    }

    /**
     * Constructor.
     *
     * <p>The two URLs are separate because a mirror may be seeded from a checkout on this machine,
     * which is never where an approved push should be forwarded.
     *
     * @param runner Runs git.
     * @param mirror Directory of the bare mirror.
     * @param mode What the gate may do.
     * @param upstreamUrl Upstream to forward to, or {@code null} if there is none.
     * @param seedUrl Repository the mirror is first cloned from, or {@code null} to start empty.
     */
    public GitGate(
            CommandRunner runner,
            Path mirror,
            GateMode mode,
            @Nullable String upstreamUrl,
            @Nullable String seedUrl) {
        this.runner = runner;
        this.mirror = mirror;
        this.mode = mode;
        this.upstreamUrl = upstreamUrl;
        this.seedUrl = seedUrl;
    }

    /**
     * Returns this gate keeping the task branches a project's scheme names out of the mirror.
     *
     * <p>A task's work reaches the forge under such a name, so another task's would come back into
     * the mirror every task fetches from; a person's own branches stay.
     *
     * @param scheme How the project names its tasks' branches; {@code porelo/<task>} by default.
     * @return This gate.
     */
    private @Nullable String forwardRefused;

    /**
     * Returns this gate ending a push it passes on, or an approval forwards, when the upstream has
     * not taken it within the limit, which the agent or the person approving is then told: the
     * agent's own push waits for it, and holds one of the gate's slots meanwhile.
     *
     * @param limit The project's {@code limits.pass_on}, two minutes unless it says otherwise.
     * @return This gate.
     */
    public GitGate passingOnWithin(final java.time.Duration limit) {
        this.passOnLimit = limit;
        return this;
    }

    public GitGate taskBranches(final BranchScheme scheme) {
        this.taskBranches = scheme;
        return this;
    }

    /**
     * Returns this gate refusing to forward anything, for the given reason; what stays on this
     * machine still works.
     *
     * @param why Why nothing may go upstream from here.
     * @return This gate.
     */
    public GitGate refusingForward(final String why) {
        this.forwardRefused = why;
        return this;
    }

    /**
     * Returns this gate with a credential to reach the upstream with.
     *
     * <p>Only the commands that talk to a real remote use it. Everything else here is local to this
     * machine and has nothing to authenticate to.
     *
     * @param lending Where a credential for a URL comes from.
     * @return This gate.
     */
    public GitGate using(final GitCredentials lending) {
        this.lending = lending;
        return this;
    }

    /**
     * Returns the mirror directory.
     *
     * @return Mirror path.
     */
    public Path mirror() {
        return mirror;
    }

    /**
     * Returns the mode.
     *
     * @return Gate mode.
     */
    public GateMode mode() {
        return mode;
    }

    /**
     * Creates the bare mirror if it is not there, cloning the seed when one is configured.
     *
     * @throws GateException If the mirror cannot be created.
     */
    public void initialize() {
        if (Files.isDirectory(mirror.resolve("objects"))) {
            return;
        }
        // Made beside it, then moved in whole: a failure never removes another start's mirror.
        final Path sibling =
                mirror.toAbsolutePath()
                        .resolveSibling(
                                "."
                                        + mirror.getFileName()
                                        + ".new-"
                                        + java.util.UUID.randomUUID().toString().substring(0, 8));
        try {
            try {
                Directories.create(sibling);
            } catch (java.io.IOException ex) {
                throw new GateException("Cannot create " + sibling, ex);
            }
            new GitGate(runner, sibling, mode, upstreamUrl, seedUrl)
                    .using(lending)
                    .taskBranches(taskBranches)
                    .make();
            try {
                Files.move(sibling, mirror, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException ex) {
                if (!Files.isDirectory(mirror.resolve("objects"))) {
                    throw new GateException("Cannot put the mirror in place at " + mirror, ex);
                }
                // Another start's mirror came first and serves the same repository.
            }
        } finally {
            if (Files.exists(sibling)) {
                new GitGate(runner, sibling, mode, upstreamUrl, seedUrl).deleteMirror();
            }
        }
    }

    /** Makes a mirror where this gate points, which nothing serves from yet. */
    private void make() {
        if (seedUrl == null) {
            git("init", "--bare", "--initial-branch=main", mirror.toString());
        } else {
            // Through the lending, as every later call to the upstream is: an ssh seed needs the
            // machine's host-key policy and its own known hosts. A bare 'git clone' had neither, so
            // a host this machine had vouched for failed as "Host key verification failed" and the
            // task started with no gate.
            try (GitCredentials.Lease lease = lending.forUrl(seedUrl)) {
                final List<String> all = new ArrayList<>(List.of("git"));
                all.addAll(lease.arguments());
                // Copies, not hard links of a local seed: its objects would be the person's own
                // files under a second name, at their mode, whatever the umask says here.
                all.addAll(
                        List.of(
                                "clone",
                                "--bare",
                                "--no-hardlinks",
                                "--end-of-options",
                                seedUrl,
                                mirror.toString()));
                runner.runOrFail(Command.of(all).withEnvironment(lease.environment()));
            } catch (CommandException ex) {
                throw new GateException(
                        ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
            }
        }
        dropApprovedBranches();
        headOnABranch();
        // The agent pushes over HTTP, and git refuses that on a bare repository unless told the
        // repository is meant to be served.
        gitIn("config", "http.receivepack", "true");
        gitIn("config", "receive.denyCurrentBranch", "ignore");
    }

    /**
     * Brings the mirror's branches up to the upstream's, before a task starts from them.
     *
     * <p><strong>A mirror is cloned from the upstream once, and approved work goes back to the
     * upstream, never into the mirror.</strong> So without this the mirror's {@code main} stayed
     * where it was cloned: the next task started from the old base, without the change approved
     * before it, and its own forward was then refused as no fast-forward (found walking a real
     * repository, 2026-10-01). The branches are taken as the upstream has them; what tasks pushed
     * waits under {@code refs/porelo/incoming/}, which this does not touch.
     *
     * <p>Only where the mirror was cloned from the upstream it forwards to, and never in an {@code
     * offline} project, which reaches no upstream. Never fatal: a task can still start from what
     * the mirror holds, and is told so.
     *
     * @return "" when it is up to date or has nothing to follow; otherwise what could not be done,
     *     to say.
     */
    public String refresh() {
        if (mode == GateMode.OFFLINE
                || upstreamUrl == null
                || !upstreamUrl.equals(seedUrl)
                || !Files.isDirectory(mirror.resolve("objects"))) {
            return "";
        }
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            // Not fetched at all under today's names; under a project's own scheme, whose pattern a
            // refspec cannot say, fetched and dropped before a task fetches: the gate refreshes
            // before it serves.
            if (taskBranches.equals(BranchScheme.DEFAULT)) {
                gitWith(
                        lease,
                        "fetch",
                        "--quiet",
                        "--prune",
                        "--end-of-options",
                        upstreamUrl,
                        "+refs/heads/*:refs/heads/*",
                        "^" + APPROVED + "*");
            } else {
                fetchOnlyOthers(lease, upstreamUrl);
            }
            dropApprovedBranches();
            refreshHead(lease, upstreamUrl);
            return "";
        } catch (GateException ex) {
            return "the mirror could not be brought up to the upstream's branches, so this task starts from what it"
                    + " holds, which may be behind: "
                    + ex.getMessage();
        }
    }

    /**
     * Brings one branch of a mirror the person's checkout seeded up to the checkout's, when a task
     * starts from that checkout.
     *
     * <p>A project with no upstream has its mirror cloned once from the checkout, and {@link
     * #refresh()} follows only an upstream: a start that said it started from the checkout gave the
     * task the commit the mirror was first cloned at (found when the red main of 2026-11-14 scanned
     * an earlier scenario's tree). Fast-forward only: a mirror ahead of the checkout (one reset
     * since, or another checkout of the same project) is never moved back; that, a diverged branch
     * or a fetch that fails keeps the mirror's, and is said with git's reason. A task's branch is
     * never brought in, as {@link #refresh()} keeps it out of the mirror every task fetches from.
     *
     * <p>A gate that forwards to an upstream is left to {@link #refresh()}, whatever the project's
     * class; one with none follows its checkout, which is on this machine, offline too.
     *
     * @param checkout The checkout's top directory.
     * @param branch The branch the task starts from, without {@code refs/heads/}.
     * @return "" when the mirror holds the checkout's commit now, or has nothing to follow;
     *     otherwise the sentence to say.
     */
    public String followCheckout(final Path checkout, final String branch) {
        if (upstreamUrl != null || !Files.isDirectory(mirror.resolve("objects"))) {
            return "";
        }
        final String ref = "refs/heads/" + branch;
        if (taskBranch(ref)) {
            return "your checkout is on '"
                    + branch
                    + "', a task's branch, which the mirror never holds; this task starts from"
                    + " the mirror's, if it has one - name another with --from";
        }
        // No '+': git refuses to move a branch other than forward.
        final CommandResult fetched =
                runner.run(
                        Command.of(
                                List.of(
                                        "git",
                                        "--git-dir",
                                        mirror.toString(),
                                        "fetch",
                                        "--quiet",
                                        "--no-tags",
                                        "--end-of-options",
                                        checkout.toString(),
                                        ref + ":" + ref)));
        if (fetched.successful()) {
            return "";
        }
        final String here = commit(List.of("git", "--git-dir", mirror.toString()), ref);
        final String there = commit(List.of("git", "-C", checkout.toString()), ref);
        final String reason = fetched.standardError().strip();
        if (here.isEmpty()
                || there.isEmpty()
                // Quiet, git says nothing of a refused update and exits 1; anything else is fatal.
                || fetched.exitCode() != 1) {
            final String last =
                    reason.isEmpty() ? "" : reason.lines().reduce((a, b) -> b).orElse("");
            return "the mirror could not be brought to your checkout's "
                    + branch
                    + (last.isEmpty() ? "" : " (" + last + ")")
                    + ", so this task starts from the mirror's, which may be behind";
        }
        return "the mirror's "
                + branch
                + " is not your checkout's: "
                + here
                + " in the mirror, "
                + there
                + " in your checkout, and it is not moved back or over; this task starts from the"
                + " mirror's";
    }

    /** The first twelve characters of a ref's commit, or "" when it does not resolve. */
    private String commit(final List<String> git, final String ref) {
        final List<String> all = new ArrayList<>(git);
        all.addAll(List.of("rev-parse", "--verify", "--quiet", ref + "^{commit}"));
        final CommandResult result = runner.run(Command.of(all));
        final String commit = result.standardOutput().strip();
        return result.successful() && commit.length() >= 12 ? commit.substring(0, 12) : "";
    }

    /**
     * Says why an online task may not pass its work on as a branch: the upstream holds it already,
     * so a push with force would replace someone else's work, or the project's pattern matches the
     * upstream's default branch, so the mirror would hide it from every task.
     *
     * <p>Asked once, at the start, with the upstream's own answer and nothing cached.
     *
     * @param branch The task's branch, without {@code refs/heads/}.
     * @return Why it is refused, or {@code null}; {@code null} too for a gate with no upstream.
     */
    public @Nullable String taken(final String branch) {
        if (upstreamUrl == null) {
            return null;
        }
        final String answer;
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            answer =
                    gitWith(
                                    lease,
                                    "ls-remote",
                                    "--symref",
                                    "--end-of-options",
                                    upstreamUrl,
                                    "HEAD",
                                    "refs/heads/" + branch)
                            .standardOutput();
        } catch (GateException ex) {
            return "the upstream could not be asked whether the branch '"
                    + branch
                    + "' is free, so no work is passed on to it: "
                    + ex.getMessage();
        }
        for (final String line : answer.lines().toList()) {
            if (line.startsWith("ref: refs/heads/") && line.endsWith("\tHEAD")) {
                final String main =
                        line.substring(
                                "ref: refs/heads/".length(), line.length() - "\tHEAD".length());
                if (taskBranches.accepts(main)) {
                    return "the project's branch.pattern "
                            + taskBranches.pattern()
                            + " matches the upstream's default branch '"
                            + main
                            + "', which the gate would then keep from every task; narrow the pattern";
                }
            }
        }
        for (final String line : answer.lines().toList()) {
            if (line.endsWith("\trefs/heads/" + branch)) {
                return "the branch '"
                        + branch
                        + "' exists at the upstream already, and passing work on would replace what it"
                        + " holds; name another with --branch or other values";
            }
        }
        return null;
    }

    /**
     * Fetches the upstream's branches but the task branches the project's scheme names: asked for
     * by name, so none of them is ever in the mirror, not even between a fetch and its removal,
     * while another task's gate serves a fetch from it. Branches gone from the upstream go here
     * too.
     */
    private void fetchOnlyOthers(final GitCredentials.Lease lease, final String upstream) {
        final List<String> wanted = new ArrayList<>();
        for (final String line :
                gitWith(lease, "ls-remote", "--heads", "--end-of-options", upstream)
                        .standardOutput()
                        .lines()
                        .toList()) {
            final int tab = line.indexOf('\t');
            if (tab > 0) {
                // As git prints it: a ref name may end in a character strip() would take.
                final String ref = line.substring(tab + 1);
                if (ref.startsWith("refs/heads/") && !taskBranch(ref)) {
                    wanted.add(ref);
                }
            }
        }
        if (!wanted.isEmpty()) {
            // On standard input: an upstream with thousands of branches would exceed a command
            // line.
            final StringBuilder refspecs = new StringBuilder();
            wanted.forEach(
                    ref -> refspecs.append('+').append(ref).append(':').append(ref).append('\n'));
            gitWithInput(
                    lease,
                    refspecs.toString(),
                    "fetch",
                    "--quiet",
                    "--stdin",
                    "--end-of-options",
                    upstream);
        }
        // Only once the fetch has brought what replaces them: a refresh that fails keeps what the
        // mirror held.
        for (final String ref :
                gitIn("for-each-ref", "--format=%(refname)", "refs/heads/")
                        .standardOutput()
                        .lines()
                        .filter(line -> !line.isEmpty())
                        .toList()) {
            if (!wanted.contains(ref)) {
                gitIn("update-ref", "-d", ref);
            }
        }
    }

    /**
     * Points the mirror's HEAD at a branch it holds when the one it names was a task's or is not
     * there: {@code main} when there is one, else its only branch, else {@code main} unborn, which
     * a start then refuses naming the branches there are rather than guessing one.
     */
    private void headOnABranch() {
        final CommandResult symbolic =
                runner.run(
                        Command.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "symbolic-ref",
                                "--quiet",
                                "HEAD"));
        if (!symbolic.successful()) {
            // A detached HEAD: the default branch is the start's to refuse, not this to repair.
            return;
        }
        final String current = symbolic.standardOutput().strip();
        final List<String> heads =
                gitIn("for-each-ref", "--format=%(refname)", "refs/heads/")
                        .standardOutput()
                        .lines()
                        .filter(line -> !line.isEmpty())
                        .toList();
        if (!taskBranch(current) && (heads.contains(current) || heads.isEmpty())) {
            return;
        }
        gitIn(
                "symbolic-ref",
                "HEAD",
                heads.contains("refs/heads/main") || heads.size() != 1
                        ? "refs/heads/main"
                        : heads.getFirst());
    }

    private void refreshHead(GitCredentials.Lease lease, String upstream) {
        try {
            final String answer =
                    gitWith(lease, "ls-remote", "--symref", "--end-of-options", upstream, "HEAD")
                            .standardOutput();
            for (final String line : answer.lines().toList()) {
                if (!line.startsWith("ref: refs/heads/") || !line.endsWith("\tHEAD")) {
                    continue;
                }
                final String ref =
                        line.substring("ref: ".length(), line.length() - "\tHEAD".length());
                if (taskBranch(ref) || ref.substring("refs/heads/".length()).startsWith("-")) {
                    return;
                }
                gitIn("check-ref-format", ref);
                gitIn("rev-parse", "--verify", "--quiet", ref + "^{commit}");
                gitIn("symbolic-ref", "HEAD", ref);
                return;
            }
        } catch (GateException ex) {
            // An unavailable or unusable upstream HEAD must not undo a successful fetch.
        }
    }

    /**
     * Returns where the mirror was cloned from.
     *
     * <p>Recorded by {@code git clone --bare} itself, so it is the mirror that answers rather than
     * any configuration that might since have changed.
     *
     * @return The seed URL, or {@code null} for a mirror that was created empty.
     */
    /**
     * Returns the mirror's branches and the commit each names, to tell what a refresh moved.
     *
     * @return Branch name to commit; empty for a mirror with no branch yet.
     */
    public java.util.Map<String, String> branches() {
        final java.util.Map<String, String> branches = new java.util.LinkedHashMap<>();
        if (!Files.isDirectory(mirror.resolve("objects"))) {
            return branches;
        }
        for (final String line :
                gitIn("for-each-ref", "--format=%(refname:short) %(objectname)", "refs/heads")
                        .standardOutput()
                        .lines()
                        .toList()) {
            final int space = line.indexOf(' ');
            if (space > 0) {
                branches.put(line.substring(0, space), line.substring(space + 1).strip());
            }
        }
        return branches;
    }

    /**
     * Where approved work lands in a checkout that is its own source: never brought back as a
     * branch of the gate.
     */
    static final String APPROVED = "refs/heads/porelo/";

    /**
     * Removes the task branches the mirror holds: work approved into a source, and work another
     * task passed on to the forge under the project's scheme. Brought back, a task saw its own
     * approved work again as {@code porelo/porelo/<task>}, and every other task's.
     */
    private void dropApprovedBranches() {
        for (final String ref :
                gitIn("for-each-ref", "--format=%(refname)", "refs/heads/")
                        .standardOutput()
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .filter(this::taskBranch)
                        .toList()) {
            gitIn("update-ref", "-d", ref);
        }
    }

    /**
     * Says whether a branch is a task's: under {@code porelo/}, or named by the project's scheme.
     *
     * @param ref A full ref.
     * @return {@code true} for a task's branch, never read by or handed to a task.
     */
    private boolean taskBranch(final String ref) {
        return ref.startsWith(APPROVED)
                || ref.startsWith("refs/heads/")
                        && taskBranches.accepts(ref.substring("refs/heads/".length()));
    }

    @Nullable
    public String seededFrom() {
        final CommandResult result =
                runner.run(
                        Command.of(
                                List.of(
                                        "git",
                                        "--git-dir",
                                        mirror.toString(),
                                        "config",
                                        "--get",
                                        "remote.origin.url")));
        if (!result.successful()) {
            return null;
        }
        final String url = result.standardOutput().strip();
        return url.isEmpty() ? null : url;
    }

    /**
     * Returns the refs an agent has pushed and nobody has reviewed.
     *
     * @return Incoming refs, without the namespace prefix.
     */
    public List<String> pending() {
        final CommandResult result = gitIn("for-each-ref", "--format=%(refname)", INCOMING);
        final List<String> refs = new ArrayList<>();
        // As git names them: a name ending in a Unicode space is not the name without it.
        result.standardOutput()
                .lines()
                .filter(line -> line.startsWith(INCOMING))
                // An online task's ref was passed on as it was pushed and waits for nobody; its
                // rescue never went on.
                .filter(line -> mode != GateMode.ONLINE || line.endsWith(RESCUED))
                .forEach(line -> refs.add(line.substring(INCOMING.length())));
        return List.copyOf(refs);
    }

    /**
     * Returns the pending pushes with the information needed to judge them, oldest first.
     *
     * <p>Ordered by age rather than by name, because the oldest is the one most likely to have been
     * forgotten, and a forgotten queue is how a gate turns into a rubber stamp.
     *
     * @return Pending pushes.
     */
    public List<PendingPush> pendingDetail() {
        final CommandResult result =
                gitIn(
                        "for-each-ref",
                        "--format=%(refname)\t%(objectname)\t%(committerdate:unix)\t%(objecttype)\t%(contents:subject)",
                        "--sort=committerdate",
                        INCOMING);
        final List<PendingPush> pushes = new ArrayList<>();
        result.standardOutput()
                .lines()
                .forEach(
                        line -> {
                            final String[] fields = line.split("\t", 5);
                            if (fields.length == 5
                                    && fields[0].startsWith(INCOMING)
                                    && (mode != GateMode.ONLINE || fields[0].endsWith(RESCUED))) {
                                // Listed whatever the agent pushed: receive-pack refuses a tag or a
                                // blob only under refs/heads, and one that made this throw took the
                                // review queue away from every task of the project.
                                final String subject =
                                        "commit".equals(fields[3])
                                                ? fields[4]
                                                : "(a "
                                                        + fields[3]
                                                        + ", not a commit: nothing to review or approve)";
                                pushes.add(
                                        new PendingPush(
                                                fields[0].substring(INCOMING.length()),
                                                fields[1],
                                                subject,
                                                committed(fields[2])));
                            }
                        });
        return List.copyOf(pushes);
    }

    /** A commit's time as git gave it; the epoch when there is none, or one no instant can hold. */
    private static java.time.Instant committed(String seconds) {
        try {
            return java.time.Instant.ofEpochSecond(Long.parseLong(seconds.strip()));
        } catch (NumberFormatException | java.time.DateTimeException ex) {
            return java.time.Instant.EPOCH;
        }
    }

    /**
     * Writes a bundle holding everything in the mirror, including the pending pushes.
     *
     * <p>A git bundle rather than a copy of the directory: it is a single file, it is verifiable
     * with {@code git bundle verify}, and it can be cloned from directly. A tarball of a live
     * repository can catch it mid-write.
     *
     * @param bundle Where to write it.
     * @throws GateException If the bundle cannot be written.
     */
    public void backup(Path bundle) {
        try {
            Directories.create(bundle.toAbsolutePath().getParent());
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot create " + bundle.getParent(), ex);
        }
        if (gitIn("for-each-ref").standardOutput().isBlank()) {
            // git refuses to bundle an empty repository, and an operator who asked for a backup
            // should be told that rather than shown a git error about a bad revision.
            throw new GateException("The mirror has no refs yet, so there is nothing to back up");
        }
        gitIn("bundle", "create", bundle.toString(), "--all");
    }

    /**
     * Tells whether a bundle is intact.
     *
     * @param bundle The bundle.
     * @return {@code true} if git can verify it.
     */
    public boolean verifyBackup(Path bundle) {
        // git refuses to verify a bundle without a repository to verify it against - it has to
        // check whether the bundle's prerequisites are present. So this needs a git directory, and
        // restore() creates an empty one before calling it.
        if (!Files.isDirectory(mirror.resolve("objects"))) {
            throw new GateException(
                    "Cannot verify "
                            + bundle
                            + " without a repository: git needs one to check the bundle's prerequisites");
        }
        return runner.run(
                        Command.of(
                                List.of(
                                        "git",
                                        "--git-dir",
                                        mirror.toString(),
                                        "bundle",
                                        "verify",
                                        bundle.toString())))
                .successful();
    }

    /**
     * Restores a mirror from a bundle.
     *
     * <p>Refuses to write over an existing mirror. Restoring in place would silently discard
     * whatever the agent has pushed since the backup, which is precisely the work an operator
     * restoring a backup is least able to reconstruct.
     *
     * @param bundle The bundle to restore from.
     * @throws GateException If the mirror already exists or the bundle is unusable.
     */
    public void restore(Path bundle) {
        if (!Files.isRegularFile(bundle)) {
            throw new GateException("No bundle at " + bundle);
        }
        if (Files.isDirectory(mirror.resolve("objects"))) {
            throw new GateException(
                    "A mirror already exists at "
                            + mirror
                            + ". Move it aside first: restoring over it would discard whatever has been"
                            + " pushed since the backup");
        }
        // The empty repository has to exist before the bundle can be verified: git checks the
        // bundle's prerequisites against an object store, and refuses outright without one.
        git("init", "--bare", mirror.toString());
        if (!verifyBackup(bundle)) {
            deleteMirror();
            throw new GateException(bundle + " is not a usable git bundle");
        }
        // Not 'clone --bare': that takes refs/heads/* and silently drops everything else, including
        // every pending push - which is exactly the work an operator restoring a backup is least
        // able to reconstruct. Fetching refs/*:refs/* takes all of it.
        gitIn("fetch", bundle.toString(), "refs/*:refs/*");
        // A task's branch in the bundle is no more a task's to see than one fetched.
        dropApprovedBranches();
        restoreHead(bundle);
        headOnABranch();
        gitIn("config", "http.receivepack", "true");
        gitIn("config", "receive.denyCurrentBranch", "ignore");
    }

    /** A restored default must name a branch tasks can actually start from. */
    private void restoreHead(Path bundle) {
        final Map<String, String> heads = new TreeMap<>();
        for (final String line :
                gitIn("bundle", "list-heads", bundle.toString())
                        .standardOutput()
                        .lines()
                        .toList()) {
            final int space = line.indexOf(' ');
            if (space > 0) {
                heads.put(line.substring(space + 1), line.substring(0, space));
            }
        }
        final String head = heads.get("HEAD");
        final List<String> branches =
                heads.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith("refs/heads/"))
                        .filter(entry -> !taskBranch(entry.getKey()))
                        .filter(entry -> head == null || head.equals(entry.getValue()))
                        .map(Map.Entry::getKey)
                        .toList();
        if (branches.isEmpty() || (head == null && branches.size() != 1)) {
            return;
        }
        final String current = gitIn("symbolic-ref", "HEAD").standardOutput().strip();
        String ref = branches.getFirst();
        if (branches.contains(current)) {
            ref = current;
        } else if (branches.contains("refs/heads/main")) {
            ref = "refs/heads/main";
        }
        try {
            gitIn("check-ref-format", ref);
            gitIn("rev-parse", "--verify", "--quiet", ref + "^{commit}");
            gitIn("symbolic-ref", "HEAD", ref);
        } catch (GateException ex) {
            // An unusable default must not undo the restoration of the bundle's refs.
        }
    }

    /**
     * Tells whether a ref exists in the mirror.
     *
     * @param ref Ref name.
     * @return {@code true} if git can resolve it.
     */
    public boolean resolves(String ref) {
        return runner.run(
                        Command.of(
                                List.of(
                                        "git",
                                        "--git-dir",
                                        mirror.toString(),
                                        "rev-parse",
                                        "--verify",
                                        "--quiet",
                                        ref)))
                .successful();
    }

    /**
     * Returns what an incoming ref would change, as a patch.
     *
     * <p>A mirror that was created empty has no branch to compare against, so the first review of
     * every new project would otherwise fail with a git error about an ambiguous argument. When the
     * base is missing the whole history is shown, from nothing to the ref, which is the right
     * answer: all of it is new, and every commit {@link #log} lists is in it.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} for the whole history of the ref.
     * @return Unified diff.
     */
    public String review(String name, @Nullable String against) {
        final Waiting waiting = pendingWork(name);
        return gitIn(join(List.of("diff"), List.of(), refs(waiting.commit(), reviewBase(against))))
                .standardOutput();
    }

    /**
     * Returns what a review compares: from where the task's work and the base last met, so what the
     * base gained since - a later task's start brings the mirror up to the upstream - is not shown
     * as the task removing it. Two histories that never met are compared as they stand. Without a
     * base, from the empty tree, so the patch holds every commit of the history the log lists.
     */
    private List<String> refs(final String commit, final @Nullable String base) {
        return base == null ? List.of(emptyTree(), commit) : comparedObjects(base, commit);
    }

    /** The tree with nothing in it, in the mirror's object format. */
    private String emptyTree() {
        return gitWithInput(GitCredentials.Lease.EMPTY, "", "hash-object", "-t", "tree", "--stdin")
                .trimmedOutput();
    }

    private List<String> comparedObjects(final String against, final String head) {
        return runner.run(
                                Command.of(
                                        List.of(
                                                "git",
                                                "--git-dir",
                                                mirror.toString(),
                                                "merge-base",
                                                against,
                                                head)))
                        .successful()
                ? List.of(against + "..." + head)
                : List.of(against, head);
    }

    /**
     * The blob ids of a pending push's files, and the object format they are in.
     *
     * @param format {@code sha1} or {@code sha256}, as the mirror reports it.
     * @param byPath Each asked path the push holds, with its blob id.
     */
    public record Blobs(String format, java.util.Map<String, String> byPath) {}

    /**
     * Returns the blob id of each named file as the pending push holds it.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param paths Paths in the repository; one the push does not hold is left out.
     * @return The ids, by path.
     */
    public Blobs blobs(String name, java.util.Collection<String> paths) {
        return blobs(pendingWork(name), paths);
    }

    /**
     * Returns file blobs from the captured revision, even if another push has arrived.
     *
     * @param waiting The captured commit.
     * @param paths Paths to read.
     * @return The ids, by path.
     */
    public Blobs blobs(Waiting waiting, java.util.Collection<String> paths) {
        final String format = gitIn("rev-parse", "--show-object-format").standardOutput().strip();
        final java.util.Map<String, String> byPath = new java.util.LinkedHashMap<>();
        if (paths.isEmpty()) {
            return new Blobs(format, byPath);
        }
        // The whole tree, matched here: a path given to git is a pathspec, where a leading ':' is
        // magic, and a push of many files would not fit on one command line.
        final java.util.Set<String> wanted = new java.util.HashSet<>(paths);
        // Read with -z, as the review's counts are: a quoted path would match no delivery.
        for (final String entry :
                gitIn("ls-tree", "-r", "-z", "--full-tree", waiting.commit())
                        .standardOutput()
                        .split("\0")) {
            final int tab = entry.indexOf('\t');
            final String[] head = tab < 0 ? new String[0] : entry.substring(0, tab).split(" ");
            final String path = tab < 0 ? "" : entry.substring(tab + 1);
            // A regular file alone: a link's blob is its target's name, which no delivery is.
            if (head.length == 3
                    && ("100644".equals(head[0]) || "100755".equals(head[0]))
                    && "blob".equals(head[1])
                    && wanted.contains(path)) {
                byPath.put(path, head[2]);
            }
        }
        return new Blobs(format, byPath);
    }

    /**
     * Returns what an incoming ref would change, ranked by what reading it is worth, and the patch
     * in that order.
     *
     * <p>Over the same range as {@link #review}. Renames are shown as a removal and an addition, so
     * a file moved into a place that runs - a workflow directory - is ranked by where it arrives.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} for the whole history of the ref.
     * @return The ranked files and the patch.
     */
    public ReviewRanking.Review rankedReview(String name, @Nullable String against) {
        return rankedReview(name, against, false);
    }

    /**
     * Returns a ranked review with raw and end-of-line-whitespace-ignoring counts.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} for the whole history of the ref.
     * @param ignoreLineEndings Whether the patch ignores whitespace at line endings.
     * @return The ranked files and the patch, over the same range as {@link #review}.
     */
    public ReviewRanking.Review rankedReview(
            String name, @Nullable String against, boolean ignoreLineEndings) {
        return rankedReview(pendingWork(name), reviewBase(against), ignoreLineEndings);
    }

    /**
     * Returns the ranked patch for one captured revision.
     *
     * @param waiting The captured commit.
     * @param against The captured base, or {@code null} for the whole history to the commit, as
     *     {@link #log(Waiting, String)} lists it.
     * @param ignoreLineEndings Whether the patch ignores whitespace at line endings.
     * @return The ranked files and patch from the same objects.
     * @throws GateException If {@code against} names nothing in the mirror.
     */
    public ReviewRanking.Review rankedReview(
            Waiting waiting, @Nullable String against, boolean ignoreLineEndings) {
        final List<String> verb = List.of("diff", "--no-renames");
        // Pinned once: the ranking, the counts and the patch are read from the same objects, so a
        // push that moves meanwhile cannot rank one change and show another, and whatever part of
        // the patch a reply later shows, the ranking was made from the whole change.
        final String pinned = waiting.commit();
        final List<String> refs =
                refs(pinned, against == null || against.isBlank() ? null : namedBase(against));
        // Each answer is read whole from the file git writes it to, never from its standard output,
        // which the runner keeps only the end of beyond a bound; together they hold at most what
        // this gate reads of one review.
        final DiffReading reading = new DiffReading(verb, refs);
        // Read with -z: without it git quotes a path holding a byte beyond ASCII, a '"' or a tab,
        // and the quoted name matched no dangerous place - a workflow ranked ordinary.
        final Map<String, int[]> counts =
                numstat(reading.read(List.of("--numstat", "-z"), "list of changed files"));
        final Map<String, int[]> countsIgnoringLineEndings =
                numstat(
                        reading.read(
                                List.of("--numstat", "-z", "--ignore-space-at-eol"),
                                "list of changed files"));
        final Set<String> beyondWhitespace =
                numstat(
                                reading.read(
                                        List.of("--numstat", "-z", "-w", "--ignore-blank-lines"),
                                        "list of changed files"))
                        .keySet();
        final String[] raw =
                reading.read(List.of("--raw", "-z", "--no-abbrev"), "list of changed files")
                        .split("\0");
        // ":<old mode> <new mode> <old blob> <new blob> <status>" NUL "<path>" NUL - no renames,
        // so one path each.
        final List<String[]> entries = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        for (int at = 0; at + 1 < raw.length; at += 2) {
            if (raw[at].startsWith(":")) {
                entries.add(raw[at].substring(1).split(" "));
                paths.add(raw[at + 1]);
            }
        }
        // The heads of the first files, one program for all of them.
        final List<String> blobs = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < HEADS_READ; i++) {
            if (!"D".equals(entries.get(i)[4])) {
                blobs.add(entries.get(i)[3]);
            }
        }
        final Map<String, String> heads = heads(blobs);
        final List<ReviewRanking.Change> changes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            final String[] fields = entries.get(i);
            final String path = paths.get(i);
            final String status = fields[4];
            final int[] count = counts.getOrDefault(path, new int[] {0, 0});
            final int[] ignoringLineEndings =
                    countsIgnoringLineEndings.getOrDefault(path, new int[] {0, 0});
            // Whitespace alone: something changed, and nothing is left of it once whitespace is
            // ignored.
            final boolean whitespaceOnly =
                    "M".equals(status)
                            && fields[0].equals(fields[1])
                            && (count[0] > 0 || count[1] > 0)
                            && !beyondWhitespace.contains(path);
            final String head = "D".equals(status) || i >= HEADS_READ ? null : heads.get(fields[3]);
            changes.add(
                    new ReviewRanking.Change(
                            path,
                            status,
                            fields[0],
                            fields[1],
                            count[0],
                            count[1],
                            ignoringLineEndings[0],
                            ignoringLineEndings[1],
                            whitespaceOnly,
                            head));
        }
        final List<ReviewRanking.File> files = ReviewRanking.rank(changes);
        // Read in git's own order and put in the ranking's here: git's -O matches every path
        // against every line of an order file, which took seconds for a push of many files.
        final List<String> options =
                ignoreLineEndings
                        ? List.of("--patch", "--ignore-space-at-eol")
                        : List.of("--patch");
        final String patch =
                PatchFiles.inOrder(
                        reading.read(options, "patch"),
                        files.stream().map(ReviewRanking.File::path).toList());
        return new ReviewRanking.Review(files, patch);
    }

    /**
     * Reads the diffs of one review whole, each through the file {@code git diff --output} writes,
     * and all of them together up to what this gate reads of one review.
     *
     * <p>Not through standard output: the runner keeps a long output's end alone, and a patch past
     * that - a vendored tree, a generated lock file - was refused for it. The file is read as
     * written, every byte, so a {@code -z} answer keeps a path's line feed exactly.
     */
    private final class DiffReading {

        private final List<String> verb;

        private final List<String> refs;

        private long held;

        private DiffReading(final List<String> verb, final List<String> refs) {
            this.verb = verb;
            this.refs = refs;
        }

        /**
         * Returns one diff whole.
         *
         * @param options The diff's options, without {@code --output}.
         * @param what What it is, for a refusal.
         * @return Git's answer, every character.
         * @throws GateException If it, with what was read before, holds more than the gate reads.
         */
        private String read(final List<String> options, final String what) {
            final Path out;
            try {
                out = Files.createTempFile("porelo-review", ".diff");
            } catch (IOException ex) {
                throw new GateException("could not read the review: " + ex.getMessage(), ex);
            }
            try {
                final List<String> written = new ArrayList<>(options);
                written.add("--output=" + out);
                gitIn(join(verb, written, refs));
                final String answer = readAtMost(out, readBound - held);
                if (answer == null) {
                    throw changeTooLarge(out, what);
                }
                held += answer.length();
                return answer;
            } finally {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ex) {
                    // A leftover file holds what the person is about to read anyway, owner-only.
                }
            }
        }

        /** Refuses a change past the bound, naming the answer's size and how many files changed. */
        private GateException changeTooLarge(final Path out, final String what) {
            long size;
            try {
                size = Files.size(out);
            } catch (IOException ex) {
                size = -1;
            }
            final String stat = gitIn(join(verb, List.of("--shortstat"), refs)).trimmedOutput();
            final java.util.regex.Matcher files =
                    java.util.regex.Pattern.compile("(\\d+) files? changed").matcher(stat);
            return new GateException(
                    "the review is too large to read: its "
                            + what
                            + (size < 0 ? "" : " alone is " + size + " bytes")
                            + ", over "
                            + (files.find() ? files.group(1) : "an unknown number of")
                            + " changed files, more than the "
                            + readBound
                            + " characters the gate reads of one review; review it against a"
                            + " nearer base");
        }
    }

    /**
     * Reads a file as UTF-8, malformed bytes replaced as an output's are, or {@code null} once it
     * holds more than so many characters, without holding more than that.
     */
    private static @Nullable String readAtMost(final Path file, final long most) {
        final StringBuilder text = new StringBuilder();
        try (java.io.Reader reader =
                new java.io.InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            final char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (text.length() + (long) read > most) {
                    return null;
                }
                text.append(buffer, 0, read);
            }
        } catch (IOException ex) {
            throw new GateException("could not read the review: " + ex.getMessage(), ex);
        }
        return text.toString();
    }

    /** How many files' heads are read to see whether they say they were generated. */
    private static final int HEADS_READ = 500;

    /** The most of a file's start that is read as its head, in characters. */
    private static final int HEAD_LENGTH = 2048;

    /**
     * The most bytes of blobs one {@code cat-file --batch} answers, well under what the runner
     * keeps of a program's output; a larger blob is read alone.
     */
    private static final long HEADS_WINDOW = 4L * 1024 * 1024;

    private static final java.security.SecureRandom NONCES = new java.security.SecureRandom();

    /**
     * Reads the start of each blob, through one {@code cat-file --batch-check} for their sizes and
     * as few {@code cat-file --batch} as their sizes allow, instead of one program per blob.
     *
     * <p>Each blob's answer starts with a header holding a number this call drew at random, so a
     * blob's content, which an agent wrote, cannot end another's early or forge its head.
     *
     * @param blobs The blobs, by full object name.
     * @return The first {@value #HEAD_LENGTH} characters of each blob read; one missing, not a
     *     blob, or whose answer came back cut has none.
     */
    private Map<String, String> heads(final List<String> blobs) {
        final Map<String, String> heads = new java.util.HashMap<>();
        if (blobs.isEmpty()) {
            return heads;
        }
        final List<String> unique = List.copyOf(new java.util.LinkedHashSet<>(blobs));
        final Map<String, Long> sizes = new java.util.HashMap<>();
        final String checked =
                gitWithInput(
                                GitCredentials.Lease.EMPTY,
                                String.join("\n", unique) + "\n",
                                "cat-file",
                                "--batch-check=%(objectname) %(objecttype) %(objectsize)")
                        .standardOutput();
        for (final String line : checked.split("\n")) {
            final String[] fields = line.split(" ");
            if (fields.length == 3 && "blob".equals(fields[1]) && fields[2].matches("\\d{1,18}")) {
                sizes.put(fields[0], Long.parseLong(fields[2]));
            }
        }
        final byte[] random = new byte[16];
        NONCES.nextBytes(random);
        final String nonce = java.util.HexFormat.of().formatHex(random);
        final List<String> group = new ArrayList<>();
        long held = 0;
        for (final String blob : unique) {
            final Long size = sizes.get(blob);
            if (size == null) {
                continue;
            }
            if (!group.isEmpty() && held + size > HEADS_WINDOW) {
                readHeads(group, nonce, heads);
                group.clear();
                held = 0;
            }
            group.add(blob);
            held += size;
        }
        if (!group.isEmpty()) {
            readHeads(group, nonce, heads);
        }
        return heads;
    }

    /** Reads the heads of one group of blobs with one {@code cat-file --batch}. */
    private void readHeads(
            final List<String> group, final String nonce, final Map<String, String> heads) {
        final List<String> headers = new ArrayList<>(group.size());
        final StringBuilder input = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            final String header = group.get(i) + " " + nonce + "/" + i;
            headers.add(header);
            input.append(header).append('\n');
        }
        final CommandResult result =
                gitWithInput(
                        GitCredentials.Lease.EMPTY,
                        input.toString(),
                        "cat-file",
                        "--batch=%(objectname) %(rest)");
        if (result.standardOutputCut()) {
            // Its start is lost, and with it the heads: none is guessed from what is left.
            return;
        }
        final String out = result.standardOutput();
        int at = 0;
        for (int i = 0; i < group.size(); i++) {
            final String header = headers.get(i) + "\n";
            if (!out.startsWith(header, at)) {
                return;
            }
            final int start = at + header.length();
            final int end;
            if (i + 1 < group.size()) {
                end = out.indexOf("\n" + headers.get(i + 1) + "\n", start);
                if (end < 0) {
                    return;
                }
                at = end + 1;
            } else {
                end = out.endsWith("\n") ? Math.max(start, out.length() - 1) : out.length();
            }
            heads.put(group.get(i), out.substring(start, Math.min(end, start + HEAD_LENGTH)));
        }
    }

    private static Map<String, int[]> numstat(String output) {
        final Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        // With -z each entry is "<added>\t<deleted>\t<path>" ended by a NUL, the path as it is.
        for (final String line : output.split("\0")) {
            final String[] fields = line.split("\t", 3);
            if (fields.length == 3) {
                counts.put(
                        fields[2],
                        new int[] {
                            "-".equals(fields[0]) ? -1 : Integer.parseInt(fields[0]),
                            "-".equals(fields[1]) ? -1 : Integer.parseInt(fields[1])
                        });
            }
        }
        return counts;
    }

    private static String[] join(List<String> verb, List<String> options, List<String> refs) {
        final List<String> all = new ArrayList<>(verb);
        all.addAll(options);
        all.addAll(refs);
        return all.toArray(String[]::new);
    }

    /**
     * Who made a commit and what its message says of its help.
     *
     * <p>Every field is the agent's own bytes, so each is cut to a length a Pending reply can carry
     * for every task at once: {@value #LONGEST_FIELD} code points for the subject, a maker and each
     * trailer, {@value #MOST_TRAILERS} trailers, {@value #LONGEST_MESSAGE} code points of message.
     *
     * @param commit In full.
     * @param subject The message's first line.
     * @param author {@code name <email>}, as git has it.
     * @param committer {@code name <email>}, as git has it.
     * @param trailers The message's trailer lines, unfolded, as git reads them.
     * @param message The whole message, or {@code null} when git's answer could not be matched to
     *     the commits one by one.
     */
    public record Made(
            String commit,
            String subject,
            String author,
            String committer,
            List<String> trailers,
            @Nullable String message) {

        /** Kept a copy. */
        public Made {
            trailers = List.copyOf(trailers);
        }

        /** What it holds, in characters, as a review's bound counts it. */
        long size() {
            long size = commit.length() + subject.length() + author.length() + committer.length();
            for (final String trailer : trailers) {
                size += trailer.length();
            }
            return message == null ? size : size + message.length();
        }
    }

    /** The most code points kept of a subject, a maker or one trailer. */
    public static final int LONGEST_FIELD = 1000;

    /** The most trailers kept of one commit. */
    public static final int MOST_TRAILERS = 50;

    /** The most code points kept of one commit's message. */
    public static final int LONGEST_MESSAGE = 16 * 1024;

    private static final java.util.regex.Pattern COMMIT_ID =
            java.util.regex.Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /**
     * Returns each commit of a pending push with its makers, newest first, over the range {@link
     * #log} shows.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} for the whole history of the ref.
     * @return One per commit.
     * @throws GateException If git's answer does not have the shape it guarantees, rather than a
     *     list that could miss a commit or name the wrong maker.
     */
    public List<Made> made(String name, @Nullable String against) {
        return made(pendingWork(name), reviewBase(against));
    }

    /**
     * Returns the makers and messages belonging to the captured review.
     *
     * @param waiting The captured commit.
     * @param against The captured base, or {@code null} for the whole history.
     * @return Each commit, newest first.
     * @throws GateException If {@code against} names nothing in the mirror.
     */
    public List<Made> made(Waiting waiting, @Nullable String against) {
        final String range = range(waiting, against);
        final List<Made> made = new ArrayList<>();
        long held = 0;
        int window = LOG_WINDOW;
        while (true) {
            final List<Made> part = made(range, made.size(), window);
            if (part == null) {
                window = smaller(window, range);
                continue;
            }
            made.addAll(part);
            for (final Made one : part) {
                held += one.size();
            }
            if (held > readBound / 2) {
                throw tooLarge(range);
            }
            if (part.size() < window) {
                return List.copyOf(made);
            }
        }
    }

    /**
     * How many commits one {@code git log} of a review reads at most. A command's output is kept
     * only up to a bound, its start cut away beyond it, so a long history is read in windows of
     * commits that each stay under it, and a window that still comes back cut is read again halved.
     */
    static final int LOG_WINDOW = 5_000;

    /**
     * The most one review reads of its commits and its log together, in characters, and again of
     * its patch and its lists of changed files together: an agent can push a history or a change of
     * any size, and the daemon holding it is shared.
     */
    static final long READ_BOUND = 256L * 1024 * 1024;

    /**
     * What this gate's reviews read at most: half for the commits, half for the log, and all of it
     * for the patch and the file lists.
     */
    private long readBound = READ_BOUND;

    /**
     * Returns this gate with another bound on what one review reads.
     *
     * @param bound The most characters of commits and log together, and of the patch and the file
     *     lists together.
     * @return This gate.
     */
    GitGate readingAtMost(final long bound) {
        this.readBound = bound;
        return this;
    }

    /** Refuses a review that holds more than this gate reads of one, naming how many commits. */
    private GateException tooLarge(final String range) {
        final String count = gitIn("rev-list", "--count", range, "--").trimmedOutput();
        return new GateException(
                "the review is too large to read: its "
                        + count
                        + " commits hold more than the "
                        + readBound
                        + " characters the gate reads of one review; review it against a nearer"
                        + " base");
    }

    /** Returns half a window that came back cut, refusing when one commit alone did. */
    private static int smaller(final int window, final String range) {
        if (window == 1) {
            throw new GateException(
                    "git log gave one commit of '" + range + "' larger than the gate reads whole");
        }
        return window / 2;
    }

    /**
     * Whether git's answer is whole: the runner keeps a long output's end alone and opens what it
     * kept with a line saying how much it cut, which nothing git prints for a review starts with.
     */
    private static boolean cutShort(final String output) {
        return CUT_BEFORE.matcher(output).lookingAt();
    }

    private static final java.util.regex.Pattern CUT_BEFORE =
            java.util.regex.Pattern.compile("\\[\\d+ bytes cut before this]\n");

    /**
     * The commits a review lists: those the base lacks, or the whole history to the captured commit
     * without one - the same the patch of {@link #rankedReview(Waiting, String, boolean)} covers.
     */
    private String range(final Waiting waiting, final @Nullable String against) {
        return against == null || against.isBlank()
                ? waiting.commit()
                : namedBase(against) + ".." + waiting.commit();
    }

    /**
     * Returns the head commit of a pending push with its makers.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @return It, or {@code null} when the ref is not a commit or git's answer cannot be trusted.
     */
    public @Nullable Made madeHead(String name) {
        try {
            // 'git log' peels an annotated tag, and would name the makers of a commit the ref
            // does not hold.
            if (!"commit".equals(gitIn("cat-file", "-t", INCOMING + name).trimmedOutput())) {
                return null;
            }
            final List<Made> head = made(INCOMING + name, 1);
            return head.isEmpty() ? null : head.getFirst();
        } catch (GateException ex) {
            // A blob an agent pushed, or an answer of the wrong shape: listed all the same, with
            // nobody named.
            return null;
        }
    }

    private List<Made> made(String range, int most) {
        final List<Made> made = made(range, 0, most);
        if (made == null) {
            throw new GateException("git log gave the makers of '" + range + "' cut short");
        }
        return made;
    }

    /**
     * Returns the makers of a window of commits, or {@code null} when git's answer came back cut.
     */
    private @Nullable List<Made> made(String range, int skip, int most) {
        // No separator byte cuts the agent's bytes apart: an agent can write any of them into its
        // commit. What git guarantees is that an ident and a subject hold no newline, so each
        // commit is exactly four lines, its id first.
        final String output =
                logged(range, skip, most, false, "%H%n%an <%ae>%n%cn <%ce>%n%s").standardOutput();
        if (cutShort(output)) {
            return null;
        }
        if (!output.isEmpty() && !output.endsWith("\n")) {
            throw new GateException("git log gave the makers of '" + range + "' cut short");
        }
        // On '\n' alone: a '\r' is the agent's, and splitting on it too would shift the lines.
        final String[] lines =
                output.isEmpty()
                        ? new String[0]
                        : output.substring(0, output.length() - 1).split("\n", -1);
        if (lines.length % 4 != 0) {
            throw new GateException("git log gave the makers of '" + range + "' out of shape");
        }
        final List<String> commits = new ArrayList<>();
        for (int index = 0; index < lines.length; index += 4) {
            if (!COMMIT_ID.matcher(lines[index]).matches()) {
                throw new GateException("git log gave the makers of '" + range + "' out of shape");
            }
            commits.add(lines[index]);
        }
        // Trailers from git's own reading of the whole message, in one more call for all commits
        // rather than an interpret-trailers per commit: a long push stays three processes, and the
        // trailers are those of the message, not of the cut copy below.
        final Fields read = byCommit(range, skip, most, "%B", commits);
        final Fields unfolded = byCommit(range, skip, most, "%(trailers:only,unfold)", commits);
        if (read.cut() || unfolded.cut()) {
            // Never messages left empty for an answer that came back cut: a smaller window.
            return null;
        }
        final List<String> messages = read.each();
        final List<String> trailers = unfolded.each();
        final List<Made> made = new ArrayList<>();
        for (int index = 0; index < commits.size(); index++) {
            made.add(
                    new Made(
                            commits.get(index),
                            cut(lines[4 * index + 3], LONGEST_FIELD),
                            cut(lines[4 * index + 1], LONGEST_FIELD),
                            cut(lines[4 * index + 2], LONGEST_FIELD),
                            trailers == null
                                    ? List.of()
                                    : java.util.Arrays.stream(trailers.get(index).split("\n"))
                                            .filter(line -> !line.isBlank())
                                            .limit(MOST_TRAILERS)
                                            .map(line -> cut(line, LONGEST_FIELD))
                                            .toList(),
                            messages == null ? null : cut(messages.get(index), LONGEST_MESSAGE)));
        }
        return List.copyOf(made);
    }

    /**
     * Reads one field of each commit with NUL between commits, and keeps it only when the records
     * are exactly the given commits in order, each led by its own id: a message holds any line, so
     * nothing else shows where one commit's ends.
     */
    private Fields byCommit(String range, int skip, int most, String format, List<String> commits) {
        final String output = logged(range, skip, most, true, "%H%n" + format).standardOutput();
        if (cutShort(output)) {
            return new Fields(null, true);
        }
        return new Fields(byCommit(output, commits), false);
    }

    /**
     * One field of each commit.
     *
     * @param each The fields, or {@code null} when git's answer could not be matched to the
     *     commits.
     * @param cut Whether git's answer came back cut, so a smaller window is read instead.
     */
    private record Fields(@Nullable List<String> each, boolean cut) {}

    private static @Nullable List<String> byCommit(String output, List<String> commits) {
        if (!output.isEmpty() && !output.endsWith("\0")) {
            return null;
        }
        final String[] records =
                output.isEmpty()
                        ? new String[0]
                        : output.substring(0, output.length() - 1).split("\0", -1);
        if (records.length != commits.size()) {
            return null;
        }
        final List<String> fields = new ArrayList<>();
        for (int index = 0; index < records.length; index++) {
            final String lead = commits.get(index) + "\n";
            if (!records[index].startsWith(lead)) {
                return null;
            }
            fields.add(records[index].substring(lead.length()));
        }
        return fields;
    }

    private CommandResult logged(String range, int skip, int most, boolean nulls, String format) {
        final List<String> arguments = new ArrayList<>(List.of("log"));
        if (skip > 0) {
            arguments.add("--skip=" + skip);
        }
        if (most > 0) {
            arguments.add("-" + most);
        }
        if (nulls) {
            arguments.add("-z");
        }
        // A person's log.showSignature would add lines of its own and fail every review.
        arguments.add("--no-show-signature");
        arguments.add("--format=" + format);
        arguments.add(range);
        arguments.add("--");
        return gitIn(arguments.toArray(String[]::new));
    }

    /** At most so many code points, so the cut never parts a surrogate pair. */
    private static String cut(String text, int most) {
        if (text.length() <= most || text.codePointCount(0, text.length()) <= most) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, most));
    }

    /**
     * Returns the commits an incoming ref adds.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} to list the whole history of the ref.
     * @return One line per commit.
     */
    public String log(String name, @Nullable String against) {
        return log(pendingWork(name), reviewBase(against));
    }

    /**
     * Captures a default comparison base so every part of a review uses the same objects.
     *
     * <p>For a base Porelo chose: a mirror made empty has none, and its review is the whole
     * history. A base a person named goes through {@link #namedBase} instead, which refuses one
     * that names nothing.
     *
     * @param against Ref to compare against, or {@code null}.
     * @return Its full commit, or {@code null} when no base exists.
     */
    public @Nullable String reviewBase(@Nullable String against) {
        return against == null || against.isBlank() ? null : commitOf(against);
    }

    /**
     * Captures a comparison base a person or a client named, refusing one the mirror does not hold.
     *
     * <p>Taken as no base, a mistyped or unfetched branch showed less than the work: the review
     * fell back to another range and still offered the approval.
     *
     * @param against The ref or commit named.
     * @return Its full commit.
     * @throws GateException If it names no commit in the mirror.
     */
    public String namedBase(String against) {
        final String commit = commitOf(against);
        if (commit == null) {
            throw new GateException(
                    "'"
                            + against
                            + "' names no commit in the gate's mirror, so there is nothing to compare"
                            + " the work with; name a branch or commit the mirror holds, or leave"
                            + " --against out");
        }
        return commit;
    }

    /**
     * Returns the log belonging to the captured review.
     *
     * @param waiting The captured commit.
     * @param against The captured base, or {@code null} for the whole history.
     * @return One full commit id and subject per line.
     */
    public String log(Waiting waiting, @Nullable String against) {
        final String range = range(waiting, against);
        // Each commit in full: it is what 'gate approve --commit' takes, and seven characters are
        // not, since another commit an agent makes can be made to share them. One line each, as a
        // subject holds no newline: read in windows, so no answer comes back cut.
        final StringBuilder log = new StringBuilder();
        int read = 0;
        int window = LOG_WINDOW;
        while (true) {
            final String part = logged(range, read, window, false, "%H %s").standardOutput();
            if (cutShort(part)) {
                window = smaller(window, range);
                continue;
            }
            if ((long) log.length() + part.length() > readBound / 2) {
                throw tooLarge(range);
            }
            final int lines = (int) part.chars().filter(c -> c == '\n').count();
            log.append(part);
            read += lines;
            if (lines < window) {
                return log.toString();
            }
        }
    }

    /**
     * Materialises an incoming ref into a working copy somebody can open.
     *
     * <p><strong>This exists so the safe way is also the convenient one.</strong> The unsafe path -
     * fetch the agent's branch into your own checkout and push it - is two ordinary git commands
     * and needs no privilege, so nothing prevents it. What can be changed is which path is easier,
     * and an accident of convenience is only ever fixed by convenience.
     *
     * <p><strong>Safe by construction rather than by warning:</strong>
     *
     * <ul>
     *   <li><strong>The only remote is the mirror.</strong> There is nowhere to push except back to
     *       the gate, so a push from here cannot reach the upstream by mistake - it is not a
     *       restriction that can be forgotten, it is an address that is not there.
     *   <li><strong>{@code core.hooksPath} points at an empty directory</strong>, so no hook runs
     *       in this copy whatever put one there. An agent's work is about to be opened in an editor
     *       that runs git on it, and a hook is code that runs without being read.
     *   <li><strong>It is a detached checkout of the ref</strong>, not a branch, so nothing here
     *       looks like work in progress that somebody should continue.
     * </ul>
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param into Directory to create. Must not exist.
     * @return The full commit id actually checked out.
     * @throws GateException If the ref is not there, or the copy cannot be made.
     */
    public String checkout(String name, Path into) {

        requirePending(name);
        if (Files.exists(into)) {
            throw new GateException(
                    into
                            + " already exists; a checkout writes a new directory so"
                            + " it cannot quietly mix with something already there");
        }
        final String target = into.toString();
        git("init", "--quiet", target);

        // The mirror, under the name the container's own working copy uses, so the two read the
        // same to anybody who has seen both.
        gitAt(target, "remote", "add", "porelo", mirror.toString());
        gitAt(target, "fetch", "--quiet", "porelo", INCOMING + name);

        // Set before anything is checked out: a hook cannot run in a tree that does not exist yet,
        // and after the checkout would be one command too late.
        final Path noHooks = into.resolve(".git").resolve("porelo-no-hooks");
        try {
            Directories.create(noHooks);
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot make " + noHooks, ex);
        }
        gitAt(target, "config", "core.hooksPath", noHooks.toString());

        gitAt(target, "checkout", "--quiet", "--detach", "FETCH_HEAD");
        return gitAt(target, "rev-parse", "--verify", "HEAD").trimmedOutput();
    }

    /**
     * Writes the tree of the mirror's main into a new directory, as files and nothing else.
     *
     * <p>For a task that reads its project: what it is given is what was reviewed, so only the
     * branch the mirror's HEAD names - the upstream's default, as a task's clone chooses - or
     * {@code refs/heads/main} when HEAD names none or a task's, and never what tasks pushed under
     * {@link #INCOMING}. A tree, not a clone: without a {@code .git} nothing in it is a repository
     * git would run hooks or config of.
     *
     * <p>Streamed from {@code git archive} into {@code tar}, never through a file: the directory
     * written into is the one a task is given, so a file beside a copy would double what it takes
     * on disk, clash with a repository of that name, and stay where the task sees it whenever its
     * deletion failed. Run without the system's or the account's git configuration, so no filter or
     * attribute file of this machine's changes what is written.
     *
     * @param into Directory to create. Must not exist.
     * @return The commit written.
     * @throws GateException If the mirror has no main, or it cannot be written.
     */
    public String readMain(final Path into) {
        if (Files.exists(into, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new GateException(into + " already exists; a reading copy is written new");
        }
        final String commit = mainCommit();
        try {
            Directories.create(into);
            // The paths and the commit are the script's arguments, never part of its text, so no
            // name can be read as shell. A pipeline answers for its last command alone, and dash
            // has no pipefail: git's own status comes back on descriptor 3. A tree git holds never
            // names '.git'; refused here too, so a crafted one cannot put a repository where the
            // host might run git.
            runner.runOrFail(
                    Command.of(
                                    "sh",
                                    "-c",
                                    READ_MAIN,
                                    "porelo-read-main",
                                    mirror.toString(),
                                    commit,
                                    into.toString())
                            .withEnvironment(OWN_CONFIGURATION_ONLY));
            return commit;
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot make " + into, ex);
        } catch (CommandException ex) {
            throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
        }
    }

    /**
     * Streams a commit's tree from a mirror ($1, $2) into a directory ($3): no file between, and
     * readable by every uid.
     */
    static final String READ_MAIN =
            "archived=$( { { git --git-dir \"$1\" archive --format=tar \"$2\";"
                    + " printf '%s' \"$?\" >&3; }"
                    + " | tar --extract --no-same-owner --exclude=.git --directory \"$3\" >&2; }"
                    + " 3>&1 ) || exit $?;"
                    + " [ \"$archived\" = 0 ] || exit \"$archived\";"
                    // Written as the person, root in a task's container, whose agent is another
                    // uid: it reads every file and enters every directory, whatever the umask.
                    + " chmod -R a+rX -- \"$3\"";

    /** What git reads besides the mirror's own configuration: nothing. */
    private static final Map<String, String> OWN_CONFIGURATION_ONLY =
            Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null");

    /**
     * Returns the commit of the mirror's main: the branch its HEAD names, when that is a branch, no
     * task's, and holds a commit; else {@code refs/heads/main}.
     *
     * <p>HEAD first: it follows the upstream's default on every refresh, while a {@code main} the
     * upstream left behind on moving to another default stays in the mirror and is stale.
     */
    private String mainCommit() {
        final CommandResult head =
                runner.run(
                        Command.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "symbolic-ref",
                                "--quiet",
                                "HEAD"));
        final String named = head.successful() ? head.standardOutput().strip() : "";
        if (named.startsWith("refs/heads/") && !taskBranch(named)) {
            final String hash = commitOf(named);
            if (hash != null) {
                return hash;
            }
        }
        final String hash = commitOf("refs/heads/main");
        if (hash == null) {
            throw new GateException(
                    named.startsWith("refs/heads/") && !taskBranch(named)
                            ? "the mirror's "
                                    + named.substring("refs/heads/".length())
                                    + " holds no commit yet, and it has no main"
                            : "the mirror has no main branch");
        }
        return hash;
    }

    /** Returns the commit a branch holds, or {@code null} when it holds none. */
    private @Nullable String commitOf(final String branch) {
        final CommandResult commit =
                runner.run(
                        Command.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "rev-parse",
                                "--verify",
                                "--quiet",
                                "--end-of-options",
                                branch + "^{commit}"));
        final String hash = commit.successful() ? commit.standardOutput().strip() : "";
        return hash.matches("[0-9a-f]{40,64}") ? hash : null;
    }

    private CommandResult gitAt(String directory, String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory));
        command.addAll(List.of(arguments));
        return runner.runOrFail(Command.of(command));
    }

    // Every address the project file names goes to git after '--end-of-options': written as
    // '--upload-pack=<command>', an upstream reached 'git fetch' as an option and ran that command
    // on the host. The project file refuses such a value; this is so that no other way in can.

    /**
     * Forwards the commit a person reviewed to the upstream.
     *
     * <p>The only method besides {@link #approveSigned} that sends anything off the machine, and it
     * is never called by anything the agent can reach. The task's gate stays up while a person
     * reads, so the ref can move under them: forwarding the ref as it stood at the approval sent a
     * push made after the review, unseen, and deleting it afterwards destroyed one that landed in
     * between. So the commit the person read is named, always, and is the one that goes; the ref is
     * removed only while it still points at what went.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to push to.
     * @param reviewed The full id of the commit the person reviewed.
     * @return The pull request's address, or {@code null} without a forge review.
     * @throws GateException If the mode forbids forwarding, no upstream is configured, no commit is
     *     named, or the ref moved since the review.
     */
    public @Nullable String approve(String name, String branch, String reviewed) {
        final String commit = forwardable(name, reviewed);
        final String upstream = forwardingTo();
        // The one command here that reaches the forge. It ran with nothing: an approved change
        // could not be forwarded to a private upstream unless the account happened to have a key of
        // its own lying about, which is exactly the arrangement the vault exists to replace.
        try (GitCredentials.Lease lease = lending.forPush(upstream)) {
            final String owned = refuseEarlierWork(lease, upstream, branch, commit, name);
            final List<String> push = new ArrayList<>(List.of("push"));
            if (owned != null) {
                // A changed head after the check belongs to another writer, even on this task's
                // branch.
                push.add("--force-with-lease=refs/heads/" + branch + ":" + owned);
            }
            push.addAll(List.of("--end-of-options", upstream, commit + ":refs/heads/" + branch));
            final List<String> arguments = new ArrayList<>(List.of("--git-dir", mirror.toString()));
            arguments.addAll(push);
            forwardWithin(lease, arguments, branch, commit);
        }
        return completeApproval(name, branch, commit, commit);
    }

    private @Nullable String completeApproval(
            String name, String branch, String reviewed, String pushed) {
        // A refused API call still leaves a push to recognize on the next approval.
        gitIn("update-ref", forwardedRef(name, branch), pushed);
        recordLanded(name, branch, pushed);
        final PullRequests reviews = pullRequests;
        final String url =
                reviews == null
                        ? null
                        : reviews.open(
                                name,
                                branch,
                                gitIn("show", "-s", "--format=%s", pushed)
                                        .standardOutput()
                                        .strip());
        removeIfStill(name, reviewed);
        return url;
    }

    private String forwardedRef(String task, String branch) {
        // The same commit on another branch or upstream grants no permission to replace it there.
        final String destination =
                gitWithInput(
                                GitCredentials.Lease.EMPTY,
                                upstreamUrl + "\n" + branch,
                                "hash-object",
                                "--stdin")
                        .standardOutput()
                        .strip();
        return FORWARDED + task + "/" + destination;
    }

    /**
     * Returns the first of {@code <branch>-2}, {@code -3}, ... the upstream does not hold, for a
     * person whose approve met earlier work: a suggestion, never a force over the branch that is
     * taken.
     *
     * @param taken The branch that holds earlier work.
     * @return The next free branch; {@code <taken>-2} when the upstream cannot be asked.
     */
    public String nextFreeBranch(String taken) {
        final String upstream = upstreamUrl;
        if (upstream == null) {
            return taken + "-2";
        }
        try (GitCredentials.Lease lease = lending.forUrl(upstream)) {
            final String listed =
                    gitWith(lease, "ls-remote", "--heads", "--end-of-options", upstream)
                            .standardOutput();
            for (int next = 2; next < 100; next++) {
                final String candidate = taken + "-" + next;
                if (!listed.contains("refs/heads/" + candidate + "\n")
                        && !listed.endsWith("refs/heads/" + candidate)) {
                    return candidate;
                }
            }
        } catch (GateException ex) {
            return taken + "-2";
        }
        return taken + "-2";
    }

    /**
     * Refuses by name a branch that holds work the reviewed commit did not grow from, before git
     * refuses it as a non-fast-forward in its own words. A branch that is not there, or that the
     * work grew from, passes. The task may replace this gate's own last forward with a lease.
     *
     * @param lease The upstream's credential.
     * @param upstream The upstream.
     * @param branch The branch to push to.
     * @param commit The reviewed commit.
     * @param task The task whose last forward may be replaced.
     * @return The head to lease for this task's last forward, or {@code null} for a plain push.
     * @throws GateException.BranchExists When it holds earlier work.
     */
    private @Nullable String refuseEarlierWork(
            GitCredentials.Lease lease,
            String upstream,
            String branch,
            String commit,
            String task) {
        final String listed =
                gitWith(lease, "ls-remote", "--end-of-options", upstream, "refs/heads/" + branch)
                        .standardOutput()
                        .strip();
        if (listed.isEmpty()) {
            return null;
        }
        final String at = listed.split("\\s+")[0];
        final String forwarded = forwardedRef(task, branch);
        // Only where the branch is the task's own and a pull request carries it to the shared one:
        // elsewhere the branch may be main, and an amended approval forced over it would rewrite
        // history others already took.
        if (pullRequests != null
                && resolves(forwarded)
                && at.equals(gitIn("rev-parse", "--verify", forwarded).standardOutput().strip())) {
            return at;
        }
        if (at.equals(commit)) {
            return null;
        }
        gitWith(lease, "fetch", "--quiet", "--end-of-options", upstream, "refs/heads/" + branch);
        final CommandResult grewFrom =
                runner.run(
                        Command.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "merge-base",
                                "--is-ancestor",
                                at,
                                commit));
        if (!grewFrom.successful()) {
            throw new GateException.BranchExists(branch, at);
        }
        return null;
    }

    /**
     * Records that a task's work landed on a branch.
     *
     * @param task The task's name.
     * @param branch The branch it was forwarded to.
     * @param commit The commit forwarded.
     */
    public void recordLanded(String task, String branch, String commit) {
        // git checks the name: a task or branch that makes no ref name records nothing.
        gitIn("update-ref", LANDED + task + "/" + branch, commit);
    }

    /**
     * Says where a task's work landed.
     *
     * @param task The task's name.
     * @return One entry per branch, none while its work waits or when it never landed.
     */
    public List<Landed> landed(String task) {
        final String under = LANDED + task + "/";
        return gitIn("for-each-ref", "--format=%(objectname) %(refname)", under)
                .standardOutput()
                .lines()
                .map(String::strip)
                .filter(line -> line.indexOf(' ') > 0)
                .map(
                        line ->
                                new Landed(
                                        line.substring(line.indexOf(' ') + 1 + under.length()),
                                        line.substring(0, line.indexOf(' '))))
                .toList();
    }

    /**
     * Says where a task's work landed by reading the mirror's files, with no git process: a fleet
     * view asks for every task twice a second.
     *
     * @param mirror The bare mirror, which need not exist.
     * @param task The task's name; anything that is no plain name has nothing.
     * @return One entry per branch, sorted by branch, as {@link #landed(String)} says them.
     */
    public static List<Landed> landedIn(Path mirror, String task) {
        if (!task.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || task.contains("..")) {
            return List.of();
        }
        final String under = LANDED + task + "/";
        final Map<String, String> found = new TreeMap<>();
        try {
            final Path packed = mirror.resolve("packed-refs");
            if (Files.isRegularFile(packed)) {
                for (final String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                    final int space = line.indexOf(' ');
                    if (space == 40 && line.startsWith(under, space + 1)) {
                        found.put(
                                line.substring(space + 1 + under.length()), line.substring(0, 40));
                    }
                }
            }
            // A loose ref is newer than its packed line, so it wins.
            final Path loose = mirror.resolve(under);
            if (Files.isDirectory(loose)) {
                try (var files = Files.walk(loose)) {
                    for (final Path file : files.filter(Files::isRegularFile).toList()) {
                        final String commit =
                                Files.readString(file, StandardCharsets.UTF_8).strip();
                        if (commit.matches("[0-9a-f]{40}")) {
                            found.put(loose.relativize(file).toString().replace('\\', '/'), commit);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            // A ref written while it was read: the next look sees it whole.
            return List.of();
        }
        return found.entrySet().stream()
                .map(entry -> new Landed(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Forgets where an earlier task of this name landed its work, and nothing else of it: a new
     * task of the name has landed nothing, and its row and a reader naming it would otherwise take
     * the earlier task's work for its own. The task's own ref stays, which in a guarded project may
     * hold work nobody reviewed yet.
     *
     * @param name The task's name, without the namespace prefix.
     */
    public void forgetLanded(String name) {
        for (final Landed landed : landedIn(mirror, name)) {
            gitIn("update-ref", "-d", LANDED + name + "/" + landed.branch());
        }
    }

    /**
     * Forgets the ref a task left at its gate: for an {@code online} task, whose push went on and
     * so waits for nobody, when it is removed or a new task of its name starts. Its rescue stays:
     * that work never went on, and waits. A new task of the same name otherwise met the old ref,
     * its plain push was refused with git's "fetch first", and an agent that followed the hint
     * pulled a removed task's work into its own.
     *
     * <p>What a host's pass-on last pushed for the name goes with it: a new task of the name would
     * otherwise lease its first push on the removed task's commit, refused as stale for good once
     * the forge's branch was deleted. So does where it landed ({@link #forgetLanded}).
     *
     * @param name The task's name, without the namespace prefix.
     */
    public void forget(String name) {
        if (resolves(INCOMING + name)) {
            gitIn("update-ref", "-d", INCOMING + name);
        }
        forgetLanded(name);
        final Path pushed = mirror.resolve(HOST_PUSHED);
        final Path record = pushed.resolve(name).normalize();
        if (!pushed.normalize().equals(record.getParent())) {
            // A name that is not one path segment names no record of a task.
            return;
        }
        try {
            Files.deleteIfExists(record);
            // Else a new task of the name asks the forge first and leases on the removed task's
            // branch, which it then replaces.
            Files.deleteIfExists(pushed.resolve("." + name + HOST_PUSH_UNSETTLED));
        } catch (IOException ex) {
            throw new GateException(
                    "Could not forget what was last pushed for " + name + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Captures the full commit and subject waiting under a task's name, in every gate mode.
     *
     * @param name The task's name, without the namespace prefix.
     * @return The work, or {@code null} when no ref of that name exists.
     */
    public @Nullable Waiting waiting(String name) {
        if (!resolves(INCOMING + name)) {
            return null;
        }
        final String[] parts =
                gitIn("log", "-1", "--format=%H%n%s", INCOMING + name)
                        .standardOutput()
                        .strip()
                        .split("\n", 2);
        return new Waiting(parts[0], parts.length > 1 ? parts[1] : "");
    }

    /**
     * A task's work that waits at the gate, in full.
     *
     * @param commit The commit it holds.
     * @param subject Its subject line.
     */
    public record Waiting(String commit, String subject) {
        /** Refuses anything that could select a different object when used by Git. */
        public Waiting {
            if (!COMMIT_ID.matcher(commit).matches()) {
                throw new GateException("name the reviewed commit in full");
            }
        }
    }

    /**
     * Returns the work of a task's name that waits at the gate, for a start refused by name.
     *
     * @param name The task's name, without the namespace prefix.
     * @return The work, or {@code null} when nothing of that name waits.
     */
    public @org.jspecify.annotations.Nullable Waiting waitingWork(String name) {
        return mode == GateMode.ONLINE ? null : waiting(name);
    }

    /**
     * Gives the upstream a task's work at once, as an {@code online} task's gate does while the
     * agent's push runs.
     *
     * <p>With force, onto the task's own branch, which only the task writes: an agent that rebases
     * pushes over its own earlier work, and nothing of anybody else's is under that name. With the
     * key the host lends for the upstream; the container never holds it.
     *
     * @param branch The branch at the upstream, without {@code refs/heads/}.
     * @param commit What it is to point at.
     * @return {@code null} when the upstream took it, else why not: what it said when it refused,
     *     or that it did not answer within the gate's limit.
     */
    public @org.jspecify.annotations.Nullable String passOn(String branch, String commit) {
        if (mode != GateMode.ONLINE) {
            return "this project's security class does not pass a push on; it waits for approval";
        }
        if (forwardRefused != null) {
            return forwardRefused;
        }
        if (upstreamUrl == null) {
            return "no upstream is configured for this project";
        }
        try (GitCredentials.Lease lease = lending.forPush(upstreamUrl)) {
            final List<String> all = new ArrayList<>(List.of("git"));
            all.addAll(lease.arguments());
            all.addAll(List.of("--git-dir", mirror.toString()));
            all.addAll(
                    List.of(
                            "push",
                            "--force",
                            "--end-of-options",
                            upstreamUrl,
                            commit + ":refs/heads/" + branch));
            try {
                runner.runOrFail(
                        Command.of(all)
                                .withEnvironment(lease.environment())
                                .withTimeout(passOnLimit));
                return null;
            } catch (CommandException ex) {
                final java.time.Duration waited = ex.limit();
                if (waited != null) {
                    // It may have landed before the forge stalled: what it holds now decides.
                    final Boolean held = holds(lease, branch, commit);
                    if (Boolean.TRUE.equals(held)) {
                        return null;
                    }
                    return new GateException.ForgeSilent(
                                    waited, held != null, GateException.ForgeSilent.Again.PUSH)
                            .getMessage();
                }
                final String help = lease.failure(ex).getMessage();
                return help == null || help.equals(ex.getMessage())
                        ? refusal(ex)
                        : helped(help, ex.getCommand());
            }
        } catch (GateException ex) {
            return ex.getMessage();
        }
    }

    /** The most of a forge's or git's words a refusal copies. */
    private static final int MOST_SAID = 2000;

    /**
     * An absolute path in a command's arguments or environment, such as a key file ssh is given;
     * never the path of an address after its scheme.
     */
    private static final java.util.regex.Pattern PATH =
            java.util.regex.Pattern.compile("(?<![\\w.:/])/[^\\s'\"]+");

    /** What an address carries before its host: a user, and a password or token with it. */
    private static final java.util.regex.Pattern USER_INFO =
            java.util.regex.Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s'\"]*@");

    /**
     * Says why a push to the upstream failed in the forge's own words - its {@code remote:} lines
     * and each refused ref - on one line, else in git's, never with Porelo's git command line, the
     * mirror's path, a key file's path or an address's credential.
     *
     * @param failed The failed push.
     * @return The sentence.
     */
    String refusal(final CommandException failed) {
        final Command command = failed.getCommand();
        final CommandResult result = failed.getResult();
        if (result == null) {
            // Git never ran: the exception's own reason, as its message names the command.
            final Throwable cause = failed.getCause();
            final String why = cause == null ? null : cause.getMessage();
            return "git could not be run: "
                    + said(why == null || why.isBlank() ? "no reason was given" : why, command);
        }
        final List<String> lines =
                failed.standardError()
                        .lines()
                        .map(line -> line.strip().replaceAll("\\s+", " "))
                        .filter(line -> !line.isEmpty())
                        .toList();
        final List<String> forge =
                lines.stream()
                        .filter(line -> line.startsWith("remote: ") || line.startsWith("! ["))
                        .toList();
        if (!forge.isEmpty()) {
            return "the forge refused it: " + said(String.join(" ", forge), command);
        }
        // Not the forge's words, as when it was never reached: git's own.
        return "the push to the upstream failed: "
                + said(
                        lines.isEmpty()
                                ? "git exited with code " + result.exitCode()
                                : String.join(" ", lines),
                        command);
    }

    /**
     * Says a credential's help for a refused push as Porelo's own words, without the command it
     * names for a person at a terminal, which holds the mirror's and the key's paths.
     */
    private String helped(final String help, final Command command) {
        final int run = help.indexOf(" Run: ");
        return "the push was not taken: "
                + said((run < 0 ? help : help.substring(0, run)).strip(), command);
    }

    /**
     * Returns words copied into a refusal as the agent and {@code gate.log} may read them:
     * printable characters only, the mirror named as such, no path the command gave git or ssh, no
     * address's credential, and at most {@value #MOST_SAID} characters.
     */
    private String said(final String words, final Command command) {
        String text = words;
        text = text.replace(mirror.toAbsolutePath().normalize().toString(), "the mirror");
        text = text.replace(mirror.toString(), "the mirror");
        final java.util.Set<String> paths =
                new java.util.TreeSet<>(
                        java.util.Comparator.comparingInt(String::length)
                                .reversed()
                                .thenComparing(java.util.Comparator.naturalOrder()));
        final List<String> given = new ArrayList<>(command.arguments());
        given.addAll(command.environment().values());
        for (final String each : given) {
            final java.util.regex.Matcher path = PATH.matcher(each);
            while (path.find()) {
                if (!path.group().equals(upstreamUrl) && !path.group().equals("/dev/null")) {
                    paths.add(path.group());
                }
            }
        }
        for (final String path : paths) {
            text = text.replace(path, "a local file");
        }
        text = USER_INFO.matcher(text).replaceAll("$1");
        final StringBuilder printable = new StringBuilder(text.length());
        text.codePoints()
                .filter(
                        point ->
                                !Character.isISOControl(point)
                                        && Character.getType(point) != Character.FORMAT)
                .forEach(printable::appendCodePoint);
        return printable.length() <= MOST_SAID
                ? printable.toString()
                : printable.substring(0, MOST_SAID) + " ... (cut at " + MOST_SAID + " characters)";
    }

    /**
     * Asks the upstream, after a push that ended at its limit, whether its branch holds the commit.
     *
     * @return Whether it does, or {@code null} when it did not answer that either.
     */
    private @org.jspecify.annotations.Nullable Boolean holds(
            final GitCredentials.Lease lease, final String branch, final String commit) {
        final String upstream = upstreamUrl;
        if (upstream == null) {
            return null;
        }
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(lease.arguments());
        all.addAll(
                List.of(
                        "--git-dir",
                        mirror.toString(),
                        "ls-remote",
                        "--end-of-options",
                        upstream,
                        "refs/heads/" + branch));
        try {
            final String said =
                    runner.runOrFail(
                                    Command.of(all)
                                            .withEnvironment(lease.environment())
                                            .withTimeout(
                                                    passOnLimit.compareTo(askingAfter) < 0
                                                            ? passOnLimit
                                                            : askingAfter))
                            .standardOutput();
            return said.lines().anyMatch(line -> line.equals(commit + "\trefs/heads/" + branch));
        } catch (CommandException ex) {
            return null;
        }
    }

    /**
     * How long the gate asks after a push the forge did not answer, at most: it was slow already,
     * and a shorter pass-on limit holds here too.
     */
    private static final java.time.Duration ASKING_AFTER = java.time.Duration.ofSeconds(30);

    /**
     * Returns why nothing can be forwarded from this gate at all, before any push is looked at.
     *
     * <p>So that no hint offers an approval that will be refused: a review in an offline project,
     * or one without an upstream, says this instead of the approve command.
     *
     * @return Why, or {@code null} when this gate can forward.
     */
    public @Nullable String forwardRefusal() {
        if (!mode.canForward()) {
            return "This project's security class is offline, so nothing is forwarded upstream";
        }
        if (forwardRefused != null) {
            return forwardRefused;
        }
        if (upstreamUrl == null) {
            return "No upstream is configured for this project";
        }
        return null;
    }

    /**
     * Captures the work a person may approve under a name: what waits there now, refused as an
     * approval would refuse it, before anybody is asked or shown a command to run.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @return The work, with its full commit.
     * @throws GateException If nothing can be forwarded from this gate, or nothing of that name is
     *     pending.
     */
    public Waiting approvable(String name) {
        final String refused = forwardRefusal();
        if (refused != null) {
            throw new GateException(refused);
        }
        return pendingWork(name);
    }

    /**
     * Captures the work pending under a name, in this gate's sense of pending: in an {@code online}
     * gate only a rescued push waits.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @return The work, with its full commit.
     * @throws GateException If nothing of that name is pending, naming what is.
     */
    public Waiting pendingWork(String name) {
        requirePending(name);
        final Waiting waiting = waiting(name);
        if (waiting == null) {
            // Approved or rejected meanwhile: said as any other name that does not wait.
            throw notPending(name);
        }
        return waiting;
    }

    /** The upstream an approval goes to; refused when there is none. */
    private String forwardingTo() {
        final String url = upstreamUrl;
        if (url == null) {
            throw new GateException("No upstream is configured for this project");
        }
        return url;
    }

    /** Refuses as {@link #approvable} does, then binds the approval to the named commit. */
    private String forwardable(final String name, final String reviewed) {
        final String refused = forwardRefusal();
        if (refused != null) {
            throw new GateException(refused);
        }
        requirePending(name);
        return reviewedCommit(name, reviewed);
    }

    /**
     * Returns the commit an incoming ref holds, refusing when it is not the one a person reviewed.
     *
     * @param name Incoming ref name.
     * @param reviewed The reviewed commit, in full.
     * @return The full commit id.
     * @throws GateException If no commit is named, it is not named in full, or the ref holds
     *     another.
     */
    private String reviewedCommit(String name, String reviewed) {
        if (reviewed.isBlank()) {
            // Never the ref as it stands now: that is whatever was pushed last, seen or not.
            throw new GateException(
                    "name the commit you reviewed with --commit, as 'gate review' and 'gate pending'"
                            + " show it; nothing is forwarded without it");
        }
        final String now =
                gitIn("rev-parse", "--verify", INCOMING + name + "^{commit}")
                        .standardOutput()
                        .strip();
        if (!reviewed.strip().matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            // In full: the seven characters a listing shows can be met by another commit the agent
            // grinds out.
            throw new GateException(
                    "name the reviewed commit in full, as 'gate review' and 'gate pending' show it:"
                            + " '"
                            + reviewed.strip()
                            + "' is not a full commit id");
        }
        if (!now.equals(reviewed.strip())) {
            throw new GateException.MovedSinceReview(name, reviewed.strip(), now);
        }
        return now;
    }

    /**
     * Removes an incoming ref only while it still holds what went, so a push that came after stays
     * waiting.
     */
    private void removeIfStill(String name, String commit) {
        try {
            gitIn("update-ref", "-d", INCOMING + name, commit);
        } catch (GateException ex) {
            // It moved after the forward: what came after waits for its own review.
        }
    }

    /**
     * Forwards the commit a person reviewed to the upstream as a merge commit signed by the person
     * approving.
     *
     * <p><strong>For what the project's configuration is made of</strong> - a machine's enrolment,
     * say: every machine accepts a configuration only when its commit is signed with the project's
     * key, which is a person's, never a machine's. Approving otherwise only pushes the reviewed
     * commit, unsigned. Here the reviewed work is merged onto the upstream branch with {@code git
     * merge --no-ff -S}, in a scratch clone, so git signs with the key the person's own git is set
     * up to sign with - an ssh-agent included - and the machine never holds it. A merge that comes
     * out unsigned is not pushed.
     *
     * <p>See {@link #approve(String, String, String)} for why the commit is named: the merge is
     * made of that commit alone, and a push that arrives while the upstream is fetched is {@link
     * GateException.MovedSinceReview}, as before.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to merge onto and push to.
     * @param scratch An empty directory to merge in; removed afterwards by the caller.
     * @param reviewed The full id of the commit the person reviewed.
     * @return The pull request's address, or {@code null} without a forge review.
     * @throws GateException If the mode forbids forwarding, no upstream is configured, no commit is
     *     named, the ref moved since the review, the merge cannot be made or signed, or the push is
     *     refused.
     */
    public @Nullable String approveSigned(
            String name, String branch, Path scratch, String reviewed) {
        final String commit = forwardable(name, reviewed);
        final String upstream = forwardingTo();
        final String target = scratch.toString();
        git("init", "--quiet", target);
        final Path noHooks = scratch.resolve(".git").resolve("porelo-no-hooks");
        try {
            Directories.create(noHooks);
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot make " + noHooks, ex);
        }
        gitAt(target, "config", "core.hooksPath", noHooks.toString());
        final String pushed;
        try (GitCredentials.Lease lease = lending.forPush(upstream)) {
            final String listed =
                    gitWith(
                                    lease,
                                    "ls-remote",
                                    "--end-of-options",
                                    upstream,
                                    "refs/heads/" + branch)
                            .standardOutput()
                            .strip();
            gitAtWith(
                    lease,
                    target,
                    "fetch",
                    "--quiet",
                    "--end-of-options",
                    upstream,
                    listed.isEmpty() ? "HEAD" : "refs/heads/" + branch);
            gitAt(target, "checkout", "--quiet", "-B", branch, "FETCH_HEAD");
            gitAt(target, "fetch", "--quiet", mirror.toString(), INCOMING + name);
            final String fetched =
                    gitAt(target, "rev-parse", "FETCH_HEAD").standardOutput().strip();
            if (!commit.equals(fetched)) {
                // Typed, as before the fetch: a client says it in its own words and offers the
                // review again, naming both commits.
                throw new GateException.MovedSinceReview(name, commit, fetched);
            }
            final CommandResult merged;
            try {
                merged =
                        gitAt(
                                target,
                                "merge",
                                "--no-ff",
                                "-S",
                                "-m",
                                "Merge " + name + ", reviewed and signed",
                                "FETCH_HEAD");
            } catch (CommandException ex) {
                throw new GateException(
                        "The merge of '"
                                + name
                                + "' could not be made or signed: "
                                + ex.getMessage()
                                + ". Signing uses your own git: set user.signingkey (and gpg.format ssh"
                                + " for an ssh key) where you approve",
                        ex);
            }
            // Whether it carries a signature at all - not whether this machine can verify it: '%G?'
            // answers "N" for an ssh signature whenever no allowed-signers file is configured,
            // which is the ordinary case here.
            final boolean signed =
                    gitAt(target, "cat-file", "commit", "HEAD")
                            .standardOutput()
                            .lines()
                            .anyMatch(line -> line.startsWith("gpgsig "));
            if (!signed) {
                throw new GateException(
                        "The merge of '"
                                + name
                                + "' came out unsigned, so it is not pushed; set"
                                + " user.signingkey where you approve ("
                                + merged.standardOutput().strip()
                                + ")");
            }
            pushed = gitAt(target, "rev-parse", "HEAD").standardOutput().strip();
            forwardWithin(
                    lease,
                    List.of(
                            "-C",
                            target,
                            "push",
                            "--end-of-options",
                            upstream,
                            "HEAD:refs/heads/" + branch),
                    branch,
                    pushed);
            gitIn("fetch", "--quiet", "--end-of-options", target, "HEAD");
        }
        return completeApproval(name, branch, commit, pushed);
    }

    /**
     * Runs the push an approval forwards, ended at the gate's pass-on limit as a push an online
     * task passes on is: a forge that takes the connection and never answers would otherwise hold
     * the person's approval as long as the runner allows any command.
     *
     * @param lease The upstream's credential.
     * @param arguments What follows {@code git} and the lease's own arguments.
     * @param branch The upstream branch pushed to.
     * @param commit The commit the push sets it to.
     * @throws GateException.ForgeSilent When the forge did not answer within the limit and, asked
     *     after, does not hold the commit or does not say.
     */
    private void forwardWithin(
            final GitCredentials.Lease lease,
            final List<String> arguments,
            final String branch,
            final String commit) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(lease.arguments());
        all.addAll(arguments);
        try {
            runner.runOrFail(
                    Command.of(all).withEnvironment(lease.environment()).withTimeout(passOnLimit));
        } catch (CommandException ex) {
            final java.time.Duration waited = ex.limit();
            if (waited == null) {
                throw lease.failure(ex);
            }
            // It may have landed before the forge stalled: what it holds now decides.
            final Boolean held = holds(lease, branch, commit);
            if (!Boolean.TRUE.equals(held)) {
                throw new GateException.ForgeSilent(
                        waited, held != null, GateException.ForgeSilent.Again.APPROVE);
            }
        }
    }

    private CommandResult gitAtWith(
            GitCredentials.Lease lease, String directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(lease.arguments());
        all.addAll(List.of("-C", directory));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(Command.of(all).withEnvironment(lease.environment()));
        } catch (CommandException ex) {
            throw lease.failure(ex);
        }
    }

    /**
     * The largest bundle {@link #bundle} hands out: a varlink reply is one message, and base64 adds
     * a third.
     */
    public static final long BUNDLE_LIMIT = 16L * 1024 * 1024;

    /**
     * Writes pending work as a git bundle, for somebody who merges it elsewhere - a person who
     * signs the merge on their own computer. Only what the branch in the mirror does not have yet,
     * when the mirror has it; the whole history otherwise.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch The branch it is to be merged onto.
     * @param into The bundle file to write.
     * @return The bundle's size in bytes.
     * @throws GateException If nothing is pending under that name, or the bundle cannot be written.
     */
    public long bundle(String name, String branch, Path into) {
        requirePending(name);
        final List<String> arguments =
                new ArrayList<>(List.of("bundle", "create", into.toString(), INCOMING + name));
        if (resolves("refs/heads/" + branch)) {
            arguments.addAll(List.of("--not", "refs/heads/" + branch));
        }
        gitIn(arguments.toArray(String[]::new));
        try {
            return Files.size(into);
        } catch (java.io.IOException ex) {
            throw new GateException("The bundle of '" + name + "' was not written", ex);
        }
    }

    /**
     * What {@link #landed} found.
     *
     * @param landed Whether the pending work is on the branch, and the pending ref was cleared.
     * @param commit The pending commit.
     * @param detail Why not, in words; empty when it landed.
     */
    public record Landing(boolean landed, String commit, String detail) {}

    /**
     * Clears pending work that somebody merged elsewhere - a person's signed merge pushed from
     * their own computer - once, and only once, the upstream's branch holds it.
     *
     * <p>Never recorded as rejected, which is what {@link #reject} says: the work went, just not
     * through here.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch The upstream branch it was merged onto.
     * @return Whether it landed, and why not when it did not.
     * @throws GateException If nothing is pending under that name.
     */
    public Landing landed(String name, String branch) {
        requirePending(name);
        final String commit = gitIn("rev-parse", INCOMING + name).standardOutput().strip();
        if (upstreamUrl == null) {
            return new Landing(
                    false,
                    commit,
                    "no upstream is configured for this project, so nothing can have landed");
        }
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            gitWith(
                    lease,
                    "fetch",
                    "--quiet",
                    "--end-of-options",
                    upstreamUrl,
                    "refs/heads/" + branch);
        } catch (GateException ex) {
            return new Landing(
                    false,
                    commit,
                    "the upstream's " + branch + " could not be fetched: " + ex.getMessage());
        }
        final CommandResult ancestor =
                runner.run(
                        Command.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "merge-base",
                                "--is-ancestor",
                                commit,
                                "FETCH_HEAD"));
        if (!ancestor.successful()) {
            return new Landing(
                    false,
                    commit,
                    "it is not on the upstream's "
                            + branch
                            + " yet: push the merge first,"
                            + " or it went to another branch");
        }
        gitIn("update-ref", "-d", INCOMING + name);
        return new Landing(true, commit, "");
    }

    /**
     * Discards an incoming ref without forwarding it.
     *
     * @param name Incoming ref name, without the namespace prefix.
     */
    public void reject(String name) {
        reject(name, false, false);
    }

    /**
     * Discards an incoming ref without forwarding it.
     *
     * <p><strong>Refused when the upstream already has the work.</strong> A push made by hand does
     * what {@code approve} does to the upstream and not what it does to the queue, so the ref stays
     * behind. Somebody tidying up then marks it discarded while the code is live - the record says
     * the opposite of what happened, which is the dangerous one of the three things a hand push
     * breaks. Whoever means it says {@code force}.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param alreadyUpstream Whether the upstream is known to have this work.
     * @param force Whether to discard it anyway.
     * @throws GateException If it is upstream and force was not given.
     */
    public void reject(String name, boolean alreadyUpstream, boolean force) {
        if (alreadyUpstream && !force) {
            throw new GateException(
                    "'"
                            + name
                            + "' is already on the upstream, so discarding it"
                            + " would record the opposite of what happened. Somebody pushed it by hand"
                            + " rather than approving it here. Use force to clear the ref anyway.");
        }
        gitIn("update-ref", "-d", INCOMING + name);
    }

    /**
     * Removes the mirror, so a restore has somewhere to write.
     *
     * <p><strong>Irreversible, and the caller has to have earned it.</strong> Unreviewed pushes
     * exist only here - not on the upstream, not in a workspace, not in the bundle - so this
     * destroys the only copy of whatever was waiting. {@link #restore(Path)} deliberately refuses
     * to write over a mirror rather than calling this itself.
     */
    public void deleteMirror() {
        // The half-made repository would otherwise sit there and make the next restore refuse,
        // telling the operator a mirror exists when what exists is the wreckage of this attempt.
        try (var paths = Files.walk(mirror)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (java.io.IOException ex) {
                                    // Best effort on a cleanup path.
                                }
                            });
        } catch (java.io.IOException ex) {
            // Best effort on a cleanup path.
        }
    }

    private void requirePending(String name) {
        // Otherwise every mistyped name produces a git error about an ambiguous argument, which
        // says nothing about what the operator actually got wrong.
        if (!pending().contains(name)) {
            throw notPending(name);
        }
    }

    private GateException notPending(String name) {
        final List<String> pending = pending();
        return new GateException(
                "There is no pending push named '"
                        + name
                        + "'"
                        + (pending.isEmpty()
                                ? ", nothing is pending"
                                : ", try: " + String.join(", ", pending)));
    }

    private CommandResult git(String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(Command.of(all));
        } catch (CommandException ex) {
            throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
        }
    }

    private CommandResult gitIn(String... arguments) {
        return gitWith(GitCredentials.Lease.EMPTY, arguments);
    }

    private CommandResult gitWithInput(
            GitCredentials.Lease lease, String input, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(lease.arguments());
        all.addAll(List.of("--git-dir", mirror.toString()));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(
                    Command.of(all).withEnvironment(lease.environment()).withInput(input));
        } catch (CommandException ex) {
            throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
        }
    }

    private CommandResult gitWith(GitCredentials.Lease lease, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        // Before the verb, which is where git takes '-c'. Never a secret: it names a vault entry.
        all.addAll(lease.arguments());
        all.addAll(List.of("--git-dir", mirror.toString()));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(Command.of(all).withEnvironment(lease.environment()));
        } catch (CommandException ex) {
            throw lease.failure(ex);
        }
    }
}
