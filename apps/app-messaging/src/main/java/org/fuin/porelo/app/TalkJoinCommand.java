package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Lets a person into a project's conversation - its room - with an account made for them, and shows
 * the login once.
 *
 * <p>Being in the conversation lets them read and write there. Their messages reach a task only
 * once their key is listed for a peer, as anyone's.
 */
@Command(
        name = "join",
        mixinStandardHelpOptions = true,
        description = "Lets a person into a project's conversation, and shows their login once.")
public class TalkJoinCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project name.")
    private String projectName;

    @Parameters(index = "1", paramLabel = "<person>", description = "Whom the account is for.")
    private String person;

    @Option(names = "--reset", description = "Give the person's existing account a new password.")
    private boolean reset;

    @Option(
            names = "--into-vault",
            paramLabel = "<entry>",
            description =
                    "Keeps the person's access token in the vault under this name and shows no"
                            + " password, for an agent whose output is its transcript.")
    private @Nullable String intoVault;

    @Option(
            names = "--display",
            paramLabel = "<name>",
            description =
                    "The name the conversation shows for the person, which is how others address"
                            + " them there.")
    private @Nullable String display;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Project project = GateSupport.byName(context, projectName);
        if (noConversation(project, err)) {
            return 64;
        }
        // Handed on as its own argument, a name like "--reset" would reach the transport as a flag.
        if (display != null && display.startsWith("-")) {
            err.println(
                    "porelo: '"
                            + display
                            + "' cannot be a display name, since it reads as an option");
            err.flush();
            return 64;
        }
        // Asked before the account is made: an account whose login nobody keeps is one too many.
        org.fuin.porelo.vault.VaultFile.@Nullable Opener way = null;
        if (intoVault != null) {
            if (intoVault.isBlank() || TaskSecrets.reserved(intoVault)) {
                err.println("porelo: '" + intoVault + "' is not a name a vault entry can have");
                err.flush();
                return 64;
            }
            if (!context.vault().exists()) {
                err.println(
                        "porelo: there is no vault to keep the login in; 'porelo vault init' makes"
                                + " one");
                err.flush();
                return 69;
            }
            way = context.openerAsking(true, err).orElse(null);
            if (way == null) {
                err.println(
                        "porelo: the vault is shut, so the login could not be kept; 'porelo vault"
                                + " unlock' opens it");
                err.flush();
                return 69;
            }
        }
        final Map<?, ?> said;
        try {
            said =
                    new TaskConversations(context)
                            .join(context, project, person, reset, intoVault != null, display);
        } catch (TransportLifecycle.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return ex.code() == 17 ? 1 : 69;
        }
        if (way != null && intoVault != null) {
            return keep(said, way, intoVault, out, err);
        }
        // Shown once, never kept: the transport's own words, which carry the password.
        out.println(
                said.get("shown") instanceof String shown && !shown.isBlank()
                        ? shown.strip()
                        : "joined    " + person);
        out.flush();
        return 0;
    }

    /**
     * Keeps the one token the transport answered and says only that it did: neither it nor any
     * password reaches a stream.
     */
    private int keep(
            final Map<?, ?> said,
            final org.fuin.porelo.vault.VaultFile.Opener way,
            final String entry,
            final PrintWriter out,
            final PrintWriter err) {
        final String token =
                said.get("secrets") instanceof Map<?, ?> secrets
                                && secrets.size() == 1
                                && secrets.values().iterator().next() instanceof String value
                                && !value.isBlank()
                        ? value
                        : null;
        if (token == null) {
            err.println(
                    "porelo: the transport made the account but answered no single token, so"
                            + " nothing was kept; 'porelo talk join --reset' asks again");
            err.flush();
            return 69;
        }
        try {
            context.vault()
                    .update(
                            way,
                            entries -> {
                                entries.put(
                                        entry,
                                        new org.fuin.porelo.vault.VaultEntry(token, "token"));
                                return entries;
                            });
        } catch (org.fuin.porelo.vault.VaultException ex) {
            err.println("porelo: the login could not be kept: " + ex.getMessage());
            err.flush();
            return 69;
        }
        // The room acts by its id, so the agent learns it here, where no secret is.
        final String room =
                said.get("login") instanceof Map<?, ?> login
                                && login.get("room") instanceof String id
                                && !id.isBlank()
                                && id.chars().noneMatch(Character::isWhitespace)
                                && id.chars().noneMatch(Character::isISOControl)
                        ? " in " + id
                        : "";
        out.println("joined    " + person + room + ", login kept in the vault as '" + entry + "'");
        out.flush();
        return 0;
    }

    static boolean noConversation(Project project, PrintWriter err) {
        if (!project.mail().conversations().isEmpty()) {
            return false;
        }
        err.println(
                "porelo: '"
                        + project.name()
                        + "' has no conversation: no peer is reached through a transport's '<scheme>:' address");
        err.flush();
        return true;
    }
}
