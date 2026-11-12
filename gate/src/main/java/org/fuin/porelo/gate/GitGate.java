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

    private final CommandRunner runner;

    private final Path mirror;

    private final GateMode mode;

    @Nullable private final String upstreamUrl;

    @Nullable private final String seedUrl;

    private BranchScheme taskBranches = BranchScheme.DEFAULT;

    /** Where a credential for a remote comes from. Nothing, until a caller that has one says so. */
    private GitCredentials lending = GitCredentials.NONE;

    private @Nullable PullRequests pullRequests;

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
        try {
            Directories.create(mirror);
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot create " + mirror, ex);
        }
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
     * base is missing the whole ref is shown instead, which is the right answer: all of it is new.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} for the whole ref.
     * @return Unified diff.
     */
    public String review(String name, @Nullable String against) {
        requirePending(name);
        if (against == null || against.isBlank() || !resolves(against)) {
            return gitIn("show", "--patch", INCOMING + name).standardOutput();
        }
        return gitIn(join(List.of("diff"), List.of(), compared(against, name))).standardOutput();
    }

    /**
     * Returns what a review compares: from where the task's work and the branch last met, so what
     * the branch gained since - a later task's start brings the mirror up to the upstream - is not
     * shown as the task removing it. Two histories that never met are compared as they stand.
     */
    private List<String> compared(final String against, final String name) {
        return runner.run(
                                Command.of(
                                        List.of(
                                                "git",
                                                "--git-dir",
                                                mirror.toString(),
                                                "merge-base",
                                                against,
                                                INCOMING + name)))
                        .successful()
                ? List.of(against + "..." + INCOMING + name)
                : List.of(against, INCOMING + name);
    }

    /**
     * Returns what an incoming ref would change, ranked by what reading it is worth, and the patch
     * in that order.
     *
     * <p>Over the same range as {@link #review}. Renames are shown as a removal and an addition, so
     * a file moved into a place that runs - a workflow directory - is ranked by where it arrives.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} to show the ref's own last commit.
     * @return The ranked files and the patch.
     */
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
        requirePending(name);
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
                gitIn("ls-tree", "-r", "-z", "--full-tree", INCOMING + name)
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

    public ReviewRanking.Review rankedReview(String name, @Nullable String against) {
        return rankedReview(name, against, false);
    }

    /**
     * Returns a ranked review with raw and end-of-line-whitespace-ignoring counts.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} for the ref's own last commit.
     * @param ignoreLineEndings Whether the patch ignores whitespace at line endings.
     * @return The ranked files and the patch, over the same range as {@link #review}.
     */
    public ReviewRanking.Review rankedReview(
            String name, @Nullable String against, boolean ignoreLineEndings) {
        requirePending(name);
        final boolean compare = against != null && !against.isBlank() && resolves(against);
        final List<String> verb =
                compare
                        ? List.of("diff", "--no-renames")
                        : List.of("show", "--format=", "--no-renames");
        final List<String> refs =
                compare && against != null ? compared(against, name) : List.of(INCOMING + name);
        // Read with -z: without it git quotes a path holding a byte beyond ASCII, a '"' or a tab,
        // and the quoted name matched no dangerous place - a workflow ranked ordinary.
        final Map<String, int[]> counts =
                numstat(gitIn(join(verb, List.of("--numstat", "-z"), refs)).standardOutput());
        final Map<String, int[]> countsIgnoringLineEndings =
                numstat(
                        gitIn(join(verb, List.of("--numstat", "-z", "--ignore-space-at-eol"), refs))
                                .standardOutput());
        final Set<String> beyondWhitespace =
                numstat(
                                gitIn(
                                                join(
                                                        verb,
                                                        List.of(
                                                                "--numstat",
                                                                "-z",
                                                                "-w",
                                                                "--ignore-blank-lines"),
                                                        refs))
                                        .standardOutput())
                        .keySet();
        final List<ReviewRanking.Change> changes = new ArrayList<>();
        final String[] raw =
                gitIn(join(verb, List.of("--raw", "-z", "--no-abbrev"), refs))
                        .standardOutput()
                        .split("\0");
        for (int at = 0; at + 1 < raw.length; at += 2) {
            // ":<old mode> <new mode> <old blob> <new blob> <status>" NUL "<path>" NUL - no
            // renames, so one path each.
            final String line = raw[at];
            if (!line.startsWith(":")) {
                continue;
            }
            final String[] fields = line.substring(1).split(" ");
            final String path = raw[at + 1];
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
            final String head =
                    "D".equals(status) || changes.size() >= HEADS_READ ? null : head(fields[3]);
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
        final Path order;
        try {
            order = Files.createTempFile("porelo-review-order", ".txt");
            Files.writeString(order, ReviewRanking.orderFile(files), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GateException("could not order the review: " + ex.getMessage(), ex);
        }
        try {
            final List<String> options =
                    ignoreLineEndings
                            ? List.of("--patch", "-O" + order, "--ignore-space-at-eol")
                            : List.of("--patch", "-O" + order);
            final String patch = gitIn(join(verb, options, refs)).standardOutput();
            return new ReviewRanking.Review(files, patch);
        } finally {
            try {
                Files.deleteIfExists(order);
            } catch (IOException ex) {
                // A leftover order file names paths the operator is about to read anyway; not worth
                // failing for.
            }
        }
    }

    /** How many files' heads are read to see whether they say they were generated. */
    private static final int HEADS_READ = 500;

    private String head(String blob) {
        final String content = gitIn("cat-file", "-p", blob).standardOutput();
        return content.length() > 2048 ? content.substring(0, 2048) : content;
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
        requirePending(name);
        final String range =
                against == null || against.isBlank() || !resolves(against)
                        ? INCOMING + name
                        : against + ".." + INCOMING + name;
        return made(range, -1);
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
        // No separator byte cuts the agent's bytes apart: an agent can write any of them into its
        // commit. What git guarantees is that an ident and a subject hold no newline, so each
        // commit is exactly four lines, its id first.
        final String output =
                logged(range, most, false, "%H%n%an <%ae>%n%cn <%ce>%n%s").standardOutput();
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
        final List<String> messages = byCommit(range, most, "%B", commits);
        final List<String> trailers = byCommit(range, most, "%(trailers:only,unfold)", commits);
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
    private @Nullable List<String> byCommit(
            String range, int most, String format, List<String> commits) {
        final String output = logged(range, most, true, "%H%n" + format).standardOutput();
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

    private CommandResult logged(String range, int most, boolean nulls, String format) {
        final List<String> arguments = new ArrayList<>(List.of("log"));
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
        requirePending(name);
        final String range =
                against == null || against.isBlank() || !resolves(against)
                        ? INCOMING + name
                        : against + ".." + INCOMING + name;
        // Each commit in full: it is what 'gate approve --commit' takes, and seven characters are
        // not, since another commit an agent makes can be made to share them.
        return gitIn("log", "--format=%H %s", range).standardOutput();
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
     * @throws GateException If the ref is not there, or the copy cannot be made.
     */
    public void checkout(String name, Path into) {

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
     * Forwards an incoming ref to the upstream.
     *
     * <p>The only method that sends anything off the machine, and it is never called by anything
     * the agent can reach.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to push to.
     * @return The pull request's address, or {@code null} without a forge review.
     * @throws GateException If the mode forbids forwarding, or no upstream is configured.
     */
    public @Nullable String approve(String name, String branch) {
        return approve(name, branch, null);
    }

    /**
     * Forwards the commit a person reviewed to the upstream.
     *
     * <p>The task's gate stays up while a person reads, so the ref can move under them: forwarding
     * the ref as it stood at the approval sent a push made after the review, unseen, and deleting
     * it afterwards destroyed one that landed in between. The commit is pinned first; with {@code
     * reviewed} it must be the one the person read, and the ref is removed only while it still
     * points at what went.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to push to.
     * @param reviewed The commit the person reviewed, or {@code null} for whatever the ref holds
     *     now.
     * @return The pull request's address, or {@code null} without a forge review.
     * @throws GateException If the mode forbids forwarding, no upstream is configured, or the ref
     *     moved since the review.
     */
    public @Nullable String approve(
            String name, String branch, @org.jspecify.annotations.Nullable String reviewed) {
        if (!mode.canForward()) {
            throw new GateException(
                    "This project's security class is offline, so nothing is"
                            + " forwarded upstream");
        }
        if (forwardRefused != null) {
            throw new GateException(forwardRefused);
        }
        if (upstreamUrl == null) {
            throw new GateException("No upstream is configured for this project");
        }
        requirePending(name);
        final String commit = reviewedCommit(name, reviewed);
        // The one command here that reaches the forge. It ran with nothing: an approved change
        // could not be forwarded to a private upstream unless the account happened to have a key of
        // its own lying about, which is exactly the arrangement the vault exists to replace.
        try (GitCredentials.Lease lease = lending.forPush(upstreamUrl)) {
            final String owned = refuseEarlierWork(lease, upstreamUrl, branch, commit, name);
            final List<String> push = new ArrayList<>(List.of("push"));
            if (owned != null) {
                // A changed head after the check belongs to another writer, even on this task's
                // branch.
                push.add("--force-with-lease=refs/heads/" + branch + ":" + owned);
            }
            push.addAll(List.of("--end-of-options", upstreamUrl, commit + ":refs/heads/" + branch));
            gitWith(lease, push.toArray(String[]::new));
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
        } catch (IOException ex) {
            throw new GateException(
                    "Could not forget what was last pushed for " + name + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Returns the commit and subject of a task's work that waits at the gate, for a start that
     * would otherwise build a new task of the same name on top of it.
     *
     * @param name The task's name, without the namespace prefix.
     * @return {@code "<commit> <subject>"}, or {@code null} when nothing of that name waits.
     */
    public @org.jspecify.annotations.Nullable String waiting(String name) {
        if (mode == GateMode.ONLINE || !resolves(INCOMING + name)) {
            return null;
        }
        return gitIn("log", "-1", "--format=%h %s", INCOMING + name).standardOutput().strip();
    }

    /**
     * A task's work that waits at the gate, in full.
     *
     * @param commit The commit it holds.
     * @param subject Its subject line.
     */
    public record Waiting(String commit, String subject) {}

    /**
     * Returns the work of a task's name that waits at the gate, for a start refused by name.
     *
     * @param name The task's name, without the namespace prefix.
     * @return The work, or {@code null} when nothing of that name waits.
     */
    public @org.jspecify.annotations.Nullable Waiting waitingWork(String name) {
        if (mode == GateMode.ONLINE || !resolves(INCOMING + name)) {
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
     * Gives the upstream a task's work at once, as an {@code online} task's gate does while the
     * agent's push runs.
     *
     * <p>With force, onto the task's own branch, which only the task writes: an agent that rebases
     * pushes over its own earlier work, and nothing of anybody else's is under that name. With the
     * key the host lends for the upstream; the container never holds it.
     *
     * @param branch The branch at the upstream, without {@code refs/heads/}.
     * @param commit What it is to point at.
     * @return {@code null} when the upstream took it, else what it said.
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
            gitWith(
                    lease,
                    "push",
                    "--force",
                    "--end-of-options",
                    upstreamUrl,
                    commit + ":refs/heads/" + branch);
            return null;
        } catch (GateException ex) {
            return ex.getMessage();
        }
    }

    /**
     * Returns the commit an incoming ref holds, refusing when it is not the one a person reviewed.
     *
     * @param name Incoming ref name.
     * @param reviewed The reviewed commit, or {@code null}.
     * @return The full commit id.
     */
    private String reviewedCommit(String name, @org.jspecify.annotations.Nullable String reviewed) {
        final String now =
                gitIn("rev-parse", "--verify", INCOMING + name + "^{commit}")
                        .standardOutput()
                        .strip();
        if (reviewed != null
                && !reviewed.isBlank()
                && !reviewed.strip().matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            // In full: the seven characters a listing shows can be met by another commit the agent
            // grinds out.
            throw new GateException(
                    "name the reviewed commit in full, as 'gate review' and 'gate pending' show it:"
                            + " '"
                            + reviewed.strip()
                            + "' is not a full commit id");
        }
        if (reviewed != null && !reviewed.isBlank() && !now.equals(reviewed.strip())) {
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
     * Forwards an incoming ref to the upstream as a merge commit signed by the person approving.
     *
     * <p><strong>For what the project's configuration is made of</strong> - a machine's enrolment,
     * say: every machine accepts a configuration only when its commit is signed with the project's
     * key, which is a person's, never a machine's. Approving otherwise only pushes the reviewed
     * commit, unsigned. Here the reviewed work is merged onto the upstream branch with {@code git
     * merge --no-ff -S}, in a scratch clone, so git signs with the key the person's own git is set
     * up to sign with - an ssh-agent included - and the machine never holds it. A merge that comes
     * out unsigned is not pushed.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to merge onto and push to.
     * @param scratch An empty directory to merge in; removed afterwards by the caller.
     * @return The pull request's address, or {@code null} without a forge review.
     * @throws GateException If the mode forbids forwarding, no upstream is configured, the merge
     *     cannot be made or signed, or the push is refused.
     */
    public @Nullable String approveSigned(String name, String branch, Path scratch) {
        return approveSigned(name, branch, scratch, null);
    }

    /**
     * Forwards the commit a person reviewed as a merge signed by them; see {@link #approve(String,
     * String, String)} for why the commit is pinned.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to merge onto and push to.
     * @param scratch An empty directory to merge in; removed afterwards by the caller.
     * @param reviewed The commit the person reviewed, or {@code null} for whatever the ref holds
     *     now.
     * @throws GateException As {@link #approveSigned(String, String, Path)}, and when the ref moved
     *     since the review.
     * @return The pull request's address, or {@code null} without a forge review.
     */
    public @Nullable String approveSigned(
            String name,
            String branch,
            Path scratch,
            @org.jspecify.annotations.Nullable String reviewed) {
        if (!mode.canForward()) {
            throw new GateException(
                    "This project's security class is offline, so nothing is"
                            + " forwarded upstream");
        }
        if (forwardRefused != null) {
            throw new GateException(forwardRefused);
        }
        if (upstreamUrl == null) {
            throw new GateException("No upstream is configured for this project");
        }
        requirePending(name);
        final String commit = reviewedCommit(name, reviewed);
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
        try (GitCredentials.Lease lease = lending.forPush(upstreamUrl)) {
            final String listed =
                    gitWith(
                                    lease,
                                    "ls-remote",
                                    "--end-of-options",
                                    upstreamUrl,
                                    "refs/heads/" + branch)
                            .standardOutput()
                            .strip();
            gitAtWith(
                    lease,
                    target,
                    "fetch",
                    "--quiet",
                    "--end-of-options",
                    upstreamUrl,
                    listed.isEmpty() ? "HEAD" : "refs/heads/" + branch);
            gitAt(target, "checkout", "--quiet", "-B", branch, "FETCH_HEAD");
            gitAt(target, "fetch", "--quiet", mirror.toString(), INCOMING + name);
            if (!commit.equals(gitAt(target, "rev-parse", "FETCH_HEAD").standardOutput().strip())) {
                throw new GateException(
                        "'"
                                + name
                                + "' moved while it was being approved; nothing was forwarded."
                                + " Review it again");
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
            gitAtWith(
                    lease,
                    target,
                    "push",
                    "--end-of-options",
                    upstreamUrl,
                    "HEAD:refs/heads/" + branch);
            pushed = gitAt(target, "rev-parse", "HEAD").standardOutput().strip();
            gitIn("fetch", "--quiet", "--end-of-options", target, "HEAD");
        }
        return completeApproval(name, branch, commit, pushed);
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
            throw new GateException(
                    "There is no pending push named '"
                            + name
                            + "'"
                            + (pending().isEmpty()
                                    ? ", nothing is pending"
                                    : ", try: " + String.join(", ", pending())));
        }
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
