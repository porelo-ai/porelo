package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Logs in with an agent and stores what it produces.
 *
 * <p>For the case the getting-started guide could not answer: a machine where the agent has never
 * been installed by hand, so there is no binary to run a login with and {@code porelo vault put
 * <provider> --type oauth} asks for a value that has no source.
 */
@Command(
        name = "login",
        mixinStandardHelpOptions = true,
        description = "Runs an agent's own login and stores the credential it produces.")
public class VaultLoginCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<agent>",
            description = "Agent to log in with. Default: the only one installed.")
    private @Nullable String agentName;

    @Option(names = "--dry-run", description = "Says what it would run and runs nothing.")
    private boolean dryRun;

    @Option(
            names = "--force",
            description = "Logs in again even though this machine already has a credential.")
    private boolean force;

    @Option(
            names = "--credentials-from",
            paramLabel = "<dir>",
            description =
                    "Keeps the sign-in found in this directory, as the agent's sign-in would have"
                            + " written it, instead of running the sign-in. For a test against a"
                            + " stand-in.")
    private @Nullable Path credentialsFrom;

    @Option(
            names = "--token-url",
            paramLabel = "<url>",
            description =
                    "With --credentials-from: renews against this stand-in, plain http on a"
                            + " loopback address, instead of the agent's endpoint.")
    private @Nullable String tokenUrl;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    /** Runs a login: the agent's own, as {@link AgentLogin#login} does. */
    @FunctionalInterface
    interface Login {
        AgentLogin.Result run(
                PoreloContext context,
                @Nullable String agent,
                boolean dryRun,
                boolean force,
                PrintWriter out);
    }

    /** Copies what an agent signed in with on this machine, as {@code porelo vault import} does. */
    @FunctionalInterface
    interface Importer {
        int run(@Nullable String agent);
    }

    /** Asks a person, or answers {@code null} when nobody is at a terminal to answer. */
    @FunctionalInterface
    interface Asker {
        @Nullable String ask(String question);
    }

    Login login = AgentLogin::login;

    Importer importer =
            agent ->
                    agent == null
                            ? spec.root().commandLine().execute("vault", "import")
                            : spec.root().commandLine().execute("vault", "import", agent);

    Asker asker =
            question -> {
                final java.io.Console console = System.console();
                // A console object alone is no terminal: since Java 22 one exists for a pipe too.
                return console == null || !console.isTerminal()
                        ? null
                        : console.readLine("%s ", question);
            };

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            if (!dryRun
                    && context.vault().exists()
                    && !context.vault().hasPassphrase()
                    && context.openerAsking(true, err).isEmpty()) {
                return 69;
            }
        } catch (final org.fuin.porelo.vault.VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }

        if (tokenUrl != null || credentialsFrom != null) {
            return fromDirectory(out, err);
        }

        AgentLogin.Result result = login.run(context, agentName, dryRun, force, out);
        out.flush();

        if (result.outcome() == AgentLogin.Outcome.ALREADY_SIGNED_IN && !dryRun) {
            // What the refusal would tell a person to type, offered instead where somebody can
            // answer.
            final String answer =
                    asker.ask(
                            "'"
                                    + (agentName == null ? "the agent" : agentName)
                                    + "' is already signed"
                                    + " in on this machine. (i) import what it has, which logs in nowhere; (a) log in again, which"
                                    + " may invalidate the credential here; or (c) cancel? [I/a/c]");
            if (answer != null) {
                switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
                    case "", "i", "import" -> {
                        return importer.run(agentName);
                    }
                    case "a", "again" -> {
                        result = login.run(context, agentName, false, true, out);
                        out.flush();
                    }
                    default -> {
                        out.println("Cancelled - nothing was done.");
                        out.flush();
                        return 0;
                    }
                }
            }
        }

        if (result.outcome() == AgentLogin.Outcome.STORED) {
            // The value is never echoed: what is useful is that it arrived and which kind it is.
            out.println(
                    "Signed in - the credential is stored as '"
                            + result.name()
                            + "' ("
                            + result.type()
                            + ", "
                            + result.length()
                            + " characters). Nothing more to do here.");
            out.flush();
            return 0;
        }
        if (result.outcome() == AgentLogin.Outcome.PREVIEWED) {
            return 0;
        }
        // An agent with no login of its own takes a key: asked here by 'porelo vault put' itself,
        // without echo, rather than sending the person to type it.
        if (result.outcome() == AgentLogin.Outcome.UNSUPPORTED
                && agentName != null
                && Offer.atTerminal()) {
            final String entry = keyEntry(agentName);
            err.println("porelo: " + result.detail());
            if (new Offer(Offer.Asker.terminal(), false, false, err)
                    .confirm(
                            "'" + agentName + "' signs in with a key",
                            "Store its key as '"
                                    + entry
                                    + "' now, as 'porelo vault put "
                                    + entry
                                    + "' does?",
                            "",
                            false)) {
                err.flush();
                return context.exec()
                        .applyAsInt(java.util.List.of(PoreloBinary.path(), "vault", "put", entry));
            }
            err.flush();
            return 69;
        }
        err.println("porelo: " + result.detail());
        err.flush();
        return result.outcome() == AgentLogin.Outcome.FAILED ? 70 : 69;
    }

    private int fromDirectory(final PrintWriter out, final PrintWriter err) {
        final Path directory = credentialsFrom;
        if (dryRun || force) {
            err.println(
                    "porelo: --credentials-from keeps what it reads and runs no sign-in; it takes"
                            + " neither --dry-run nor --force");
            err.flush();
            return 64;
        }
        if (directory == null) {
            err.println(
                    "porelo: --token-url names a stand-in for a sign-in read from a directory;"
                            + " give --credentials-from <dir> with it");
            err.flush();
            return 64;
        }
        final String refused = tokenUrl == null ? null : AgentLogin.standInTokenUrl(tokenUrl);
        if (refused != null) {
            err.println("porelo: --token-url " + refused + " - nothing was kept");
            err.flush();
            return 64;
        }
        final AgentLogin.Result result =
                AgentLogin.fromDirectory(context, agentName, directory, tokenUrl);
        if (result.outcome() == AgentLogin.Outcome.STORED) {
            // Said as what it is, so it is never taken for a sign-in a person made here.
            out.println(
                    "Kept the sign-in read from "
                            + directory
                            + " as '"
                            + result.name()
                            + "' ("
                            + result.type()
                            + ", "
                            + result.length()
                            + " characters), "
                            + (result.detail().isEmpty()
                                    ? "renewable" + (tokenUrl == null ? "" : " against " + tokenUrl)
                                    : result.detail())
                            + ". No sign-in ran here.");
            out.flush();
            return 0;
        }
        err.println("porelo: " + result.detail());
        err.flush();
        return result.outcome() == AgentLogin.Outcome.FAILED ? 70 : 69;
    }

    /**
     * Returns where an agent's key belongs: its provider's name, where it names a default one, else
     * its own.
     *
     * @param agent The agent.
     * @return The vault entry.
     */
    private String keyEntry(final String agent) {
        try (var installed = context.agents()) {
            return installed
                    .find(agent)
                    .map(
                            found ->
                                    found.definition().provider() == null
                                                    || found.definition()
                                                                    .provider()
                                                                    .defaultProvider()
                                                            == null
                                            ? agent
                                            : found.definition().provider().defaultProvider())
                    .orElse(agent);
        }
    }
}
