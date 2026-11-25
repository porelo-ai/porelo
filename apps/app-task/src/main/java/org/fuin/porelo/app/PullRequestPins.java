package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestPinning;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.runtime.ContainerSummary;

/**
 * Pins a pull request for one reviewer's run: asks the forge's adapter for its facts, fetches its
 * head read-only into the task's mirror with the owner account's read token lent for that fetch
 * alone, and pins the head and its merge base for the run.
 *
 * <p>The token reaches git only through its environment, as {@link HostGitTransport} hands it on;
 * it is in no argument, no file and no message, and never reaches the task. A later run fetches and
 * pins afresh; nothing of an earlier run is read.
 */
public final class PullRequestPins implements PullRequestPinning {

    /** The variable of git's environment the lent read token is in for the fetch. */
    static final String TOKEN_VARIABLE = "PORELO_FORGE_READ_TOKEN";

    /**
     * Lends the read credential a vault entry holds, for one call.
     *
     * <p>A stand-in answers a fixed credential.
     */
    @FunctionalInterface
    public interface Credentials {

        /**
         * Lends the credential of an entry.
         *
         * @param entry The entry's name, such as {@code git.token.github.com}.
         * @return The credential, to be used for one call and dropped.
         * @throws PlaceUnreadable As {@code MissingForgeToken} when the vault holds no such entry.
         */
        ForgeCredential lend(String entry) throws PlaceUnreadable;
    }

    /**
     * Finds a reviewer task's gate.
     *
     * <p>A stand-in answers a fixed place.
     */
    @FunctionalInterface
    public interface Gates {

        /**
         * Finds the gate of a task.
         *
         * @param task The task's recorded container id.
         * @return Its gate.
         * @throws PlaceUnreadable As {@code NotInProject} when no task or no gate mirror is there.
         */
        TaskGate locate(String task) throws PlaceUnreadable;
    }

    /**
     * A reviewer task's gate as a pin needs it.
     *
     * @param mirror The gate's mirror.
     * @param state The task's state directory, where its role and place are kept.
     * @param taskName The task's name, which names its pinned ref.
     * @param project The task's project.
     * @param repository The repository in the project.
     * @param push The name of the push its run reviews.
     */
    public record TaskGate(
            Path mirror,
            Path state,
            String taskName,
            String project,
            String repository,
            String push) {

        /** Refuses a missing part. */
        public TaskGate {
            Objects.requireNonNull(mirror, "mirror");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(taskName, "taskName");
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(push, "push");
        }
    }

    private final ForgeReview forge;

    private final Credentials credentials;

    private final Gates gates;

    private final CommandRunner git;

    private final Function<String, Map<String, String>> trust;

    private final Duration fetchLimit;

    /**
     * Constructor.
     *
     * @param forge The forge adapter's review calls.
     * @param credentials Lends the owner account's read credential per call.
     * @param gates Finds a task's gate.
     * @param git Runs git on the host.
     * @param trust Git's added trust for an https address, such as {@code GIT_SSL_CAINFO}.
     * @param fetchLimit How long the fetch may take.
     */
    public PullRequestPins(
            final ForgeReview forge,
            final Credentials credentials,
            final Gates gates,
            final CommandRunner git,
            final Function<String, Map<String, String>> trust,
            final Duration fetchLimit) {
        this.forge = Objects.requireNonNull(forge, "forge");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.gates = Objects.requireNonNull(gates, "gates");
        this.git = Objects.requireNonNull(git, "git");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.fetchLimit = Objects.requireNonNull(fetchLimit, "fetchLimit");
    }

    @Override
    public Push pin(final PullRequestRef ref, final String task) throws PlaceUnreadable {
        final TaskGate gate = gates.locate(task);
        final String entry = ForgeInstance.CREDENTIAL_PREFIX + ref.forge();
        final PullRequestFacts facts = forge.facts(ref, credentials.lend(entry));
        final String scope = GitCredentialNames.helperScopeOf(facts.repository());
        if (scope == null || !scope.equals("https://" + ref.url().getRawAuthority())) {
            // The owner's token is the forge's: never sent to a repository elsewhere.
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the forge's adapter named a repository that is not on " + ref.forge());
        }
        fetch(gate.mirror(), facts, scope, credentials.lend(entry));
        if (!holds(gate.mirror(), "rev-parse", "--verify", "--quiet", facts.head() + "^{commit}")) {
            throw headNotFetched(facts.head());
        }
        if (!holds(gate.mirror(), "merge-base", "--is-ancestor", facts.mergeBase(), facts.head())) {
            throw headNotFetched(facts.mergeBase());
        }
        try {
            ReviewerPin.pin(
                    git,
                    gate.mirror(),
                    gate.state(),
                    gate.taskName(),
                    new RunPlace.Pin(facts.head(), facts.mergeBase(), facts.repository()));
        } catch (IOException ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the run could not be pinned to " + facts.head());
        }
        return new Push(
                gate.project(),
                gate.repository(),
                gate.push(),
                facts.head(),
                facts.mergeBase(),
                task);
    }

    /**
     * Fetches the head, and with it its merge base, into the mirror without writing a ref: the pin
     * right after makes it reachable, and git prunes nothing that young.
     *
     * <p>By its commit id: github.com serves that over protocol v2 even for a head that lives only
     * in a fork's pull request; GitLab and Forgejo are not measured.
     */
    private void fetch(
            final Path mirror,
            final PullRequestFacts facts,
            final String scope,
            final ForgeCredential lent)
            throws PlaceUnreadable {
        final List<String> arguments =
                new ArrayList<>(HostGitTransport.arguments("https", scope, TOKEN_VARIABLE, mirror));
        arguments.addAll(
                List.of(
                        "fetch",
                        "--quiet",
                        "--no-tags",
                        "--no-recurse-submodules",
                        "--no-write-fetch-head",
                        "--end-of-options",
                        facts.repository(),
                        facts.head()));
        final Map<String, String> added = new LinkedHashMap<>(trust.apply(facts.repository()));
        added.put(TOKEN_VARIABLE, lent.token());
        final CommandResult fetched;
        try {
            fetched =
                    git.run(
                            Command.of(arguments)
                                    .withEnvironment(added)
                                    .withChosenEnvironment(HostGitTransport.environment())
                                    .withTimeout(fetchLimit));
        } catch (CommandException ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the fetch from " + facts.repository() + " did not end in time");
        }
        if (!fetched.successful()) {
            final String said = fetched.standardError();
            // Read only to tell a missing commit from a forge that did not answer; never shown.
            if (said.contains(facts.head()) || said.contains("not our ref")) {
                throw headNotFetched(facts.head());
            }
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the fetch from "
                            + facts.repository()
                            + " failed with exit code "
                            + fetched.exitCode());
        }
    }

    /** Asks the mirror a question git answers by its exit code alone. */
    private boolean holds(final Path mirror, final String... question) {
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "--git-dir",
                                mirror.toString()));
        arguments.addAll(List.of(question));
        return git.run(Command.of(arguments).withChosenEnvironment(HostGitTransport.environment()))
                .successful();
    }

    private static PlaceUnreadable headNotFetched(final String commit) {
        return new PlaceUnreadable(
                PlaceUnreadable.Reason.HEAD_NOT_FETCHED,
                "the forge's repository did not give the commit " + commit);
    }

    /**
     * Returns how a task's gate is found on this machine: the task by its recorded id among the
     * runtime's tasks, its project's gate mirror for its repository, and the push its run reviews.
     *
     * @param context The machine.
     * @return The finder.
     */
    public static Gates gatesOn(final PoreloContext context) {
        return task -> {
            final ContainerSummary summary =
                    context.podman().taskCandidates().stream()
                            .filter(each -> task.equals(each.id()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new PlaceUnreadable(
                                                    PlaceUnreadable.Reason.NOT_IN_PROJECT,
                                                    "no task " + task + " on this machine"));
            final Path state = context.paths().tasks().containerState(summary.name());
            final RunPlace place = RunPlace.readFrom(state);
            if (summary.project() == null || summary.task() == null || place == null) {
                throw new PlaceUnreadable(
                        PlaceUnreadable.Reason.NOT_IN_PROJECT,
                        "task " + task + " records no project or run");
            }
            try {
                final Project project = GateSupport.byName(context, summary.project());
                final Repository repository =
                        summary.repository() == null || summary.repository().isBlank()
                                ? project.ownRepository()
                                : project.repository(summary.repository());
                if (repository == null) {
                    throw new PlaceUnreadable(
                            PlaceUnreadable.Reason.NOT_IN_PROJECT,
                            "project " + project.name() + " no longer names its repository");
                }
                final Path mirror =
                        GateSupport.gate(context, project, repository, null, null).mirror();
                if (!Files.isDirectory(mirror.resolve("objects"))) {
                    throw new PlaceUnreadable(
                            PlaceUnreadable.Reason.NOT_IN_PROJECT,
                            "task " + task + " has no gate mirror");
                }
                return new TaskGate(
                        mirror,
                        state,
                        summary.task(),
                        project.name(),
                        repository.name(),
                        place.name());
            } catch (ProjectException ex) {
                throw new PlaceUnreadable(
                        PlaceUnreadable.Reason.NOT_IN_PROJECT,
                        "project " + summary.project() + " cannot be read");
            }
        };
    }
}
