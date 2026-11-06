package org.fuin.porelo.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.vault.HostCard;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Changes the organization's hosts. */
@Command(
        name = "host",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Changes the organization's hosts.",
        subcommands = {
            OrgHostCommand.Add.class,
            OrgHostCommand.Remove.class,
            OrgHostCommand.Change.class,
            OrgHostCommand.Admin.class,
            OrgHostCommand.Carry.class,
            OrgHostCommand.Uncarry.class
        })
public class OrgHostCommand {

    private static final String NONE = "none";

    /** Adds a host, with its first admin. */
    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Adds a host, with its first admin.")
    public static class Add extends OrgWriting {

        @Parameters(paramLabel = "HOST", description = "The host.")
        private String host = "";

        @Option(names = "--admin", required = true, description = "Its first admin.")
        private String admin = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().addHost(change, host, admin);
        }
    }

    /** Removes a host. */
    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Removes a host.")
    public static class Remove extends OrgWriting {

        @Parameters(paramLabel = "HOST", description = "The host.")
        private String host = "";

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().removeHost(change, host);
        }
    }

    /** Files a host's card, what is not named staying as it is. */
    @Command(
            name = "change",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Files a host's card, what is not named staying as it is.")
    public static class Change extends OrgWriting {

        @Parameters(paramLabel = "HOST", description = "The host.")
        private String host = "";

        @Option(names = "--address", description = "Where the host is.")
        private @Nullable String address;

        @Option(names = "--ssh-port", description = "Its ssh port.")
        private @Nullable Integer sshPort;

        @Option(names = "--host-key", description = "Its ssh host key.")
        private @Nullable String hostKey;

        @Option(names = "--forge-web-port", description = "Its forge's web port, or none.")
        private @Nullable String forgeWebPort;

        @Option(names = "--forge-git-port", description = "Its forge's git port, or none.")
        private @Nullable String forgeGitPort;

        @Option(names = "--matrix-port", description = "Its Matrix port, or none.")
        private @Nullable String matrixPort;

        /** Replaced by a test, which has no forge to read the card now held from. */
        CardReader reader = OrgHostCommand::heldCard;

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            final Organization.@Nullable Card held = reader.card(machine, host);
            final String addressNow =
                    address != null ? address : held == null ? null : held.address();
            final String keyNow =
                    hostKey != null ? hostKey : held == null ? null : held.sshHostKey();
            final Integer sshNow = sshPort != null ? sshPort : held == null ? null : held.sshPort();
            if (addressNow == null || keyNow == null || sshNow == null) {
                throw new Refused(
                        Refused.USAGE,
                        "a first card names --address, --ssh-port and --host-key",
                        null);
            }
            final String card;
            try {
                card =
                        HostCard.writeOffered(
                                host,
                                addressNow,
                                sshNow,
                                keyNow,
                                port(
                                        forgeWebPort,
                                        held == null ? HostCard.NO_SERVICE : held.forgeWebPort()),
                                port(
                                        forgeGitPort,
                                        held == null ? HostCard.NO_SERVICE : held.forgeGitPort()),
                                port(
                                        matrixPort,
                                        held == null ? HostCard.NO_SERVICE : held.matrixPort()),
                                opened.key());
            } catch (org.fuin.porelo.vault.CardException ex) {
                // A card's own messages may quote what it holds.
                throw new Refused(Refused.USAGE, "those values make no host's card", null);
            }
            return opened.writes().changeHost(change, host, card);
        }
    }

    /** Reads the card a host holds now. */
    @FunctionalInterface
    interface CardReader {
        Organization.@Nullable Card card(PoreloContext machine, String host) throws IOException;
    }

    private static Organization.@Nullable Card heldCard(
            final PoreloContext machine, final String host) throws IOException {
        final OrganizationQuestions state = OrgAccess.read(machine, OrgAccess::real).answers();
        for (final OrganizationQuestions.Host each : state.getHosts()) {
            if (each.host().equals(host)) {
                return each.card();
            }
        }
        throw new Refused(Refused.NOT_FOUND, "no such host", state.revision());
    }

    private static int port(final @Nullable String given, final int held) {
        if (given == null) {
            return held;
        }
        if (NONE.equals(given)) {
            return HostCard.NO_SERVICE;
        }
        if (!given.matches("[0-9]{1,5}")
                || Integer.parseInt(given) < 1
                || Integer.parseInt(given) > 65_535) {
            throw new Refused(Refused.USAGE, "a port is 1 to 65535, or none", null);
        }
        return Integer.parseInt(given);
    }

    /** Changes a host's admins. */
    @Command(
            name = "admin",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Changes a host's admins.",
            subcommands = {OrgHostCommand.Admin.Add.class, OrgHostCommand.Admin.Remove.class})
    public static class Admin {

        /** Adds an admin. */
        @Command(
                name = "add",
                mixinStandardHelpOptions = true,
                exitCodeOnInvalidInput = 64,
                description = "Adds an admin.")
        public static class Add extends OrgWriting {

            @Parameters(paramLabel = "HOST", description = "The host.")
            private String host = "";

            @Parameters(paramLabel = "LOGIN", description = "The login.")
            private String login = "";

            @Override
            OrganizationWrites.Written write(
                    final PoreloContext machine,
                    final Session opened,
                    final OrganizationWrites.Change change)
                    throws IOException {
                return opened.writes().addHostAdmin(change, host, login);
            }
        }

        /** Removes an admin. */
        @Command(
                name = "remove",
                mixinStandardHelpOptions = true,
                exitCodeOnInvalidInput = 64,
                description = "Removes an admin.")
        public static class Remove extends OrgWriting {

            @Parameters(paramLabel = "HOST", description = "The host.")
            private String host = "";

            @Parameters(paramLabel = "LOGIN", description = "The login.")
            private String login = "";

            @Override
            OrganizationWrites.Written write(
                    final PoreloContext machine,
                    final Session opened,
                    final OrganizationWrites.Change change)
                    throws IOException {
                return opened.writes().removeHostAdmin(change, host, login);
            }
        }
    }

    /** Puts a project on a host. */
    @Command(
            name = "carry",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Puts a project on a host, whole or by some of its repositories.")
    public static class Carry extends OrgWriting {

        @Parameters(paramLabel = "HOST", description = "The host.")
        private String host = "";

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Option(
                names = "--repository",
                paramLabel = "NAME",
                description =
                        "A repository of the project to carry, added to those the host carries;"
                                + " without one, the whole project.")
        private List<String> repositories = new ArrayList<>();

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes().addProjectToHost(change, host, project, named(repositories));
        }

        @Override
        void more(final Map<String, @Nullable Object> answer) {
            answer.put("project", project);
            answer.put("repositories", named(repositories));
        }
    }

    /** The repositories named, each once and each a name a repository can have. */
    static List<String> named(final List<String> repositories) {
        final List<String> named = new ArrayList<>();
        for (final String each : repositories) {
            if (!each.matches(Repository.NAME_PATTERN)) {
                throw new Refused(
                        Refused.USAGE,
                        "--repository names something no repository can be called",
                        null);
            }
            if (!named.contains(each)) {
                named.add(each);
            }
        }
        return List.copyOf(named);
    }

    /** Takes a project off a host. */
    @Command(
            name = "uncarry",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Takes a project off a host, whole or some of its repositories.")
    public static class Uncarry extends OrgWriting {

        @Parameters(paramLabel = "HOST", description = "The host.")
        private String host = "";

        @Parameters(paramLabel = "PROJECT", description = "The project.")
        private String project = "";

        @Option(
                names = "--repository",
                paramLabel = "NAME",
                description =
                        "A repository the host carries of the project, to take off; without one,"
                                + " the whole project.")
        private List<String> repositories = new ArrayList<>();

        @Override
        OrganizationWrites.Written write(
                final PoreloContext machine,
                final Session opened,
                final OrganizationWrites.Change change)
                throws IOException {
            return opened.writes()
                    .removeProjectFromHost(change, host, project, named(repositories));
        }

        @Override
        void more(final Map<String, @Nullable Object> answer) {
            // The host deletes the read key it made for each repository taken off.
            answer.put("project", project);
            answer.put("repositories", named(repositories));
        }
    }
}
