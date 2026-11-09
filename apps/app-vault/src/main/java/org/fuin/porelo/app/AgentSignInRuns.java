package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.jspecify.annotations.Nullable;

/**
 * The sign-ins of agents a daemon starts, one per agent on a machine, and what the last one of each
 * ended with, so a client can wait on the sign-in that makes the credential it needs.
 *
 * <p>Whether a sign-in stored anything is read from the vault afterwards, never from the login's
 * exit code: an agent's login exits however the person leaves it.
 */
public final class AgentSignInRuns {

    /** How long a wait on a sign-in lasts: a person signing in at a browser, not a run. */
    public static final Duration LIMIT = Duration.ofMinutes(30);

    /** How often a sign-in started at a terminal is looked at, through its lock. */
    private static final Duration POLL = Duration.ofMillis(500);

    /** Whether an agent's credential is in the vault, as far as can be told without asking. */
    public enum State {

        /** The vault holds the credential the agent's sign-in makes. */
        SIGNED_IN,

        /** The vault is readable and holds none. */
        NOT_SIGNED_IN,

        /** The vault is shut: whether it holds one cannot be said until it is opened. */
        VAULT_LOCKED,

        /**
         * The agent's default provider cannot be used, so which credential it needs is the start's.
         */
        CANNOT_SAY
    }

    /** What sign-ins run against: the machine, or a test's stand-in for it. */
    public interface Machine {

        /**
         * Runs a detached sign-in to its end.
         *
         * @param agent The agent.
         * @param force Signs in although the machine's own agent directory holds a sign-in.
         * @param remote Whether the person reaches this machine from another one.
         * @param started Told the container's name once the login runs.
         * @return What happened.
         */
        AgentLogin.Result signIn(
                String agent, boolean force, boolean remote, Consumer<String> started);

        /**
         * Returns the container of a sign-in of the agent that runs now, from any process.
         *
         * @param agent The agent.
         * @return Empty when none runs; the container, or "" while it has none yet.
         */
        Optional<String> running(String agent);

        /**
         * Returns whether the vault holds the agent's credential now.
         *
         * @param agent The agent.
         * @param provider The provider a sign-in stored it for, or {@code null} for the agent's
         *     default.
         * @return Its state.
         */
        State state(String agent, @Nullable String provider);
    }

    /**
     * What a start answers.
     *
     * @param container The login container, to attach to; "" while it has no name yet.
     * @param started Whether this call started it; {@code false} when one ran already.
     */
    public record Started(String container, boolean started) {}

    /**
     * What a wait answers.
     *
     * @param stored Whether the vault holds the agent's credential once the sign-in ended.
     * @param reason Why not: the sign-in's outcome, a {@link State}, {@code NONE_RUNNING} or {@code
     *     TIMED_OUT}; "" when stored.
     */
    public record Ended(boolean stored, String reason) {}

    /** A sign-in that could not start, with the outcome that says why. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Why, as a value. */
        private final AgentLogin.Outcome outcome;

        /**
         * Constructor.
         *
         * @param outcome Why, as a value.
         * @param detail Why, in words.
         */
        Refused(final AgentLogin.Outcome outcome, final String detail) {
            super(detail);
            this.outcome = outcome;
        }

        /**
         * Returns why, as a value.
         *
         * @return The login's outcome.
         */
        public AgentLogin.Outcome outcome() {
            return outcome;
        }
    }

    private final Machine machine;

    private final Duration limit;

    private final Duration poll;

    /**
     * One sign-in this daemon runs.
     *
     * @param started Its container's name, once it runs.
     * @param ended Its result, once it ended.
     */
    private record Run(
            CompletableFuture<String> started, CompletableFuture<AgentLogin.Result> ended) {}

    /** The sign-ins this daemon runs, by agent. */
    private final Map<String, Run> here = new ConcurrentHashMap<>();

    /** What the last sign-in of each agent ended with, so a wait after it answers at once. */
    private final Map<String, AgentLogin.Result> last = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param machine What sign-ins run against.
     * @param limit How long a wait lasts.
     * @param poll How often a sign-in from a terminal is looked at.
     */
    AgentSignInRuns(final Machine machine, final Duration limit, final Duration poll) {
        this.machine = machine;
        this.limit = limit;
        this.poll = poll;
    }

    /**
     * Returns the sign-ins of a daemon on this machine.
     *
     * @param context The machine.
     * @return Them.
     */
    public static AgentSignInRuns of(final PoreloContext context) {
        return new AgentSignInRuns(
                new Machine() {
                    @Override
                    public AgentLogin.Result signIn(
                            final String agent,
                            final boolean force,
                            final boolean remote,
                            final Consumer<String> started) {
                        // Nobody reads a detached login's progress; its result says what it did.
                        return AgentLogin.signIn(
                                context,
                                agent,
                                force,
                                remote,
                                started,
                                new PrintWriter(Writer.nullWriter()));
                    }

                    @Override
                    public Optional<String> running(final String agent) {
                        return AgentLogin.running(context, agent);
                    }

                    @Override
                    public State state(final String agent, final @Nullable String provider) {
                        try (var agents = context.agents()) {
                            return agents.find(agent)
                                    .map(
                                            found ->
                                                    AgentSignInRuns.state(
                                                            context.providers(),
                                                            found.definition(),
                                                            context.readableCredentials(),
                                                            provider))
                                    .orElse(State.CANNOT_SAY);
                        }
                    }
                },
                LIMIT,
                POLL);
    }

    /**
     * Says whether the vault holds the credential an agent's sign-in makes, by the rule a login
     * stores by and a start reads by: under its default provider, or its own name where an older
     * vault or an agent that is not brokered keeps it.
     *
     * @param context The machine.
     * @param definition The agent.
     * @return Its state.
     */
    public static State state(final PoreloContext context, final AgentDefinition definition) {
        return state(context.providers(), definition, context.readableCredentials());
    }

    /**
     * Says whether the vault holds the credential an agent's sign-in makes, from the vault read
     * once for several agents.
     *
     * @param providers The providers this machine declares.
     * @param definition The agent.
     * @param stored The vault's entries, or empty when it could not be read.
     * @return Its state.
     */
    public static State state(
            final Map<String, org.fuin.porelo.agent.api.ProviderDefinition> providers,
            final AgentDefinition definition,
            final Optional<Map<String, org.fuin.porelo.vault.VaultEntry>> stored) {
        return state(providers, definition, stored, null);
    }

    /**
     * Says whether the vault holds an agent's credential for a provider.
     *
     * @param providers The providers this machine declares.
     * @param definition The agent.
     * @param stored The vault's entries, or empty when it could not be read.
     * @param provider The provider a sign-in stored it for, or {@code null} for the default.
     * @return Its state.
     */
    static State state(
            final Map<String, org.fuin.porelo.agent.api.ProviderDefinition> providers,
            final AgentDefinition definition,
            final Optional<Map<String, org.fuin.porelo.vault.VaultEntry>> stored,
            final @Nullable String provider) {
        final SelectedProvider.Choice choice =
                SelectedProvider.consider(providers, definition, provider);
        if (choice.refusal() != null) {
            return State.CANNOT_SAY;
        }
        if (stored.isEmpty()) {
            return State.VAULT_LOCKED;
        }
        final String key =
                SelectedProvider.credentialKey(
                        stored.get().keySet(), definition.name(), choice.wanted());
        final SelectedProvider selection = choice.selection();
        final Map<String, String> headers =
                selection == null ? Map.of() : selection.definition().credentialHeaders();
        final org.fuin.porelo.vault.VaultEntry entry = stored.get().get(key);
        // As a start judges it: a sign-in lacking what its provider sends beside the token is
        // refused there.
        return entry != null && CredentialHeaders.lacking(entry, headers) == null
                ? State.SIGNED_IN
                : State.NOT_SIGNED_IN;
    }

    /**
     * Returns the container of a sign-in of the agent that runs now.
     *
     * @param agent The agent.
     * @return Empty when none runs; the container, or "" while it has none yet.
     */
    public Optional<String> running(final String agent) {
        final Run run = here.get(agent);
        if (run != null) {
            return Optional.of(run.started().getNow(""));
        }
        return machine.running(agent);
    }

    /**
     * Starts a sign-in of an agent, detached, unless one runs: then answers that one once its
     * container runs.
     *
     * <p>Returns once the login's container runs, or the login refused.
     *
     * @param agent The agent.
     * @param force Signs in although the machine's own agent directory holds a sign-in.
     * @param remote Whether the person reaches this machine from another one.
     * @return The container to attach to, and whether this call started it; "" when the running one
     *     ended before it had a container.
     * @throws Refused When the login refused, before anything ran.
     */
    public Started start(final String agent, final boolean force, final boolean remote) {
        final long deadline = System.nanoTime() + limit.toNanos();
        final Run mine = new Run(new CompletableFuture<>(), new CompletableFuture<>());
        final Run other;
        final Optional<String> terminal;
        synchronized (this) {
            other = here.get(agent);
            terminal = other == null ? machine.running(agent) : Optional.empty();
            if (other == null && terminal.isEmpty()) {
                here.put(agent, mine);
            }
        }
        if (other != null) {
            if (!settled(other, deadline)) {
                throw new Refused(AgentLogin.Outcome.FAILED, "the sign-in did not start in time");
            }
            if (other.started().isDone()) {
                return new Started(other.started().join(), false);
            }
            // It ended without running - refused for its caller's reasons, not this one's.
            return start(agent, force, remote);
        }
        if (terminal.isPresent()) {
            return named(agent, terminal.get(), deadline);
        }
        // A thread of its own, not the caller's: the sign-in outlives this call by the minutes a
        // person takes in the browser.
        Thread.ofPlatform()
                .name("sign-in " + agent)
                .daemon(true)
                .start(() -> run(agent, force, remote, mine));
        if (!settled(mine, deadline)) {
            throw new Refused(AgentLogin.Outcome.FAILED, "the sign-in did not start in time");
        }
        if (mine.started().isDone()) {
            return new Started(mine.started().join(), true);
        }
        final AgentLogin.Result result = mine.ended().join();
        if (result.outcome() == AgentLogin.Outcome.RUNNING) {
            // One from a terminal took the lock between the look above and the login's own.
            return named(agent, machine.running(agent).orElse(""), deadline);
        }
        throw new Refused(result.outcome(), result.detail());
    }

    /** Runs a sign-in to its end, and ends its run whatever it throws. */
    private void run(final String agent, final boolean force, final boolean remote, final Run run) {
        AgentLogin.Result result =
                new AgentLogin.Result(
                        AgentLogin.Outcome.FAILED, "", "", 0, "the sign-in ended without a result");
        try {
            result = machine.signIn(agent, force, remote, run.started()::complete);
        } catch (RuntimeException ex) {
            result =
                    new AgentLogin.Result(
                            AgentLogin.Outcome.FAILED, "", "", 0, String.valueOf(ex.getMessage()));
        } finally {
            // Only one that ran is a sign-in's end; a refusal is its caller's alone.
            if (run.started().isDone()) {
                last.put(agent, result);
            }
            here.remove(agent, run);
            run.ended().complete(result);
        }
    }

    /** Waits until a run's container runs or the run ended; {@code false} past the deadline. */
    private static boolean settled(final Run run, final long deadline) {
        try {
            CompletableFuture.anyOf(run.started(), run.ended())
                    .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException ex) {
            return false;
        }
    }

    /** Answers a sign-in from a terminal once it names its container, or once it ended. */
    private Started named(final String agent, final String first, final long deadline) {
        String name = first;
        while (name.isEmpty() && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(poll);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            final Optional<String> now = machine.running(agent);
            if (now.isEmpty()) {
                break;
            }
            name = now.get();
        }
        return new Started(name, false);
    }

    /**
     * Waits for a sign-in of an agent to end and answers whether the vault holds its credential
     * then; at once when none runs.
     *
     * @param agent The agent.
     * @return Whether it is stored, and why not.
     */
    public Ended await(final String agent) {
        final long deadline = System.nanoTime() + limit.toNanos();
        final Run mine = here.get(agent);
        if (mine != null) {
            final AgentLogin.Result result;
            try {
                result =
                        mine.ended()
                                .get(
                                        Math.max(0, deadline - System.nanoTime()),
                                        TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new Ended(false, "TIMED_OUT");
            } catch (ExecutionException | TimeoutException ex) {
                return new Ended(false, "TIMED_OUT");
            }
            if (mine.started().isDone()) {
                return ended(agent, result);
            }
            // It never ran: a terminal's sign-in holds the lock, or it was refused.
        }
        if (machine.running(agent).isPresent()) {
            // Started at a terminal, by another process: its lock is all that says it ends.
            while (machine.running(agent).isPresent()) {
                if (System.nanoTime() - deadline > 0) {
                    return new Ended(false, "TIMED_OUT");
                }
                try {
                    Thread.sleep(poll);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return new Ended(false, "TIMED_OUT");
                }
            }
            final State after = machine.state(agent, null);
            return after == State.SIGNED_IN ? new Ended(true, "") : new Ended(false, after.name());
        }
        return ended(agent, last.get(agent));
    }

    private Ended ended(final String agent, final AgentLogin.@Nullable Result result) {
        final boolean stored = result != null && result.outcome() == AgentLogin.Outcome.STORED;
        // The provider it stored under, as the agent's extractor named it - not the default.
        final State state =
                machine.state(
                        agent,
                        stored && !java.util.Objects.requireNonNull(result).name().isEmpty()
                                ? result.name()
                                : null);
        if (state == State.SIGNED_IN) {
            return new Ended(true, "");
        }
        if (state == State.VAULT_LOCKED) {
            return new Ended(false, State.VAULT_LOCKED.name());
        }
        if (result == null) {
            return new Ended(false, state == State.CANNOT_SAY ? state.name() : "NONE_RUNNING");
        }
        return new Ended(false, stored ? state.name() : result.outcome().name());
    }
}
