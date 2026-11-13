package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Proposes a repository from a checkout. Porelo changes no project file that exists: for a project
 * it answers the entry a project admin adds, and {@code --yes} refuses with it.
 */
@picocli.CommandLine.Command(
        name = "init",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Proposes a project's repository from a checkout.")
public class ProjectInitCommand extends OrgReading {
    @Parameters(paramLabel = "CHECKOUT", description = "The checkout to read.")
    private Path checkout = Path.of("");

    @Option(names = "--name", description = "The repository's name.")
    private @Nullable String name;

    @Option(names = "--project", description = "The project whose file the entry is for.")
    private @Nullable String project;

    @Option(
            names = "--yes",
            description =
                    "Refused: Porelo changes no project file; says the entry a project admin"
                            + " writes.")
    private boolean yes;

    @Option(names = "--central", description = "Where a host takes the repository from.")
    private @Nullable String central;

    @Option(
            names = "--upstream",
            description =
                    "Where a task reads it, on a host its own forge's address. The checkout's"
                            + " address is then where a host takes it from.")
    private @Nullable String upstreamOption;

    @Option(
            names = "--review",
            description = "Whether a merge needs a review: required or optional.")
    private @Nullable String review;

    /** Asks the installed plugin; tests can exercise refusals without a socket. */
    EcosystemDetector detector = EcosystemDetector.INSTALLED;

    /** Asks a person at a terminal whether to store a credential the draft names; tests answer. */
    Offer.Asker asker = Offer.atTerminal() ? Offer.Asker.terminal() : Offer.Asker.NOBODY;

    /** The machine the answer was made on, for the offers that follow a person's reading. */
    private @Nullable PoreloContext proposedOn;

    @Override
    Map<String, @Nullable Object> answer(PoreloContext machine) throws IOException {
        proposedOn = machine;
        if (yes && project == null) throw new Refused(Refused.USAGE, "--yes needs --project", null);
        if (project != null) {
            final String cramped = ProjectProposal.crampedProject(project);
            if (cramped != null) throw new Refused(Refused.USAGE, cramped, null);
        }
        checkout = checkout.toAbsolutePath().normalize();
        if (!Files.isDirectory(checkout))
            throw new Refused(Refused.USAGE, "CHECKOUT must be a directory", null);
        final var origin = machine.runner().run(LocalGit.origin(checkout));
        final String address = origin.successful() ? origin.trimmedOutput() : "";
        final boolean password = address.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*://[^/@]*:[^/@]*@.*");
        final boolean user =
                !address.matches("^(ssh://git@[^/@?#]+/|git@[^/:@]+:).*")
                        && (address.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*://[^/]*@.*")
                                || address.matches("^[^/:@]+@[^/:]+:.*"));
        final boolean unsafe = user || address.contains("?") || address.contains("#");
        String chosen = name;
        if (chosen == null) {
            final String path = address.replaceAll("[?#].*$", "").replaceAll("/+$", "");
            chosen =
                    address.isEmpty()
                            ? checkout.getFileName().toString()
                            : path.substring(
                                            Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'))
                                                    + 1)
                                    .replaceFirst("\\.git$", "");
        }
        final String checkedOut = address.isEmpty() || unsafe ? null : address;
        final String upstream = upstreamOption != null ? upstreamOption : checkedOut;
        final String from = central == null && upstreamOption != null ? checkedOut : central;
        final ProjectProposal made =
                new ProjectProposal(
                        chosen,
                        upstream,
                        from,
                        review == null ? null : ProjectRepositoryCommand.review(review),
                        project);
        final List<Map<String, Object>> plugins = new ArrayList<>();
        // A yes is refused, as it would change a file that exists: nothing is made, so nothing is
        // looked up, and the proxy goes nowhere.
        final List<String> notes =
                made.propose(machine, checkout, detector, !yes, done -> plugins.add(done.asMap()));
        final Map<String, @Nullable Object> answer = made.answer();
        answer.put("plugins", plugins);
        answer.put("notes", notes);
        if (unsafe)
            answer.put(
                    "upstream_refusal",
                    password
                            ? "origin holds a password; upstream was left out"
                            : "origin holds a user or token part; upstream was left out");
        if (project != null) {
            answer.put("project", project);
            // The row names the repository only; the project's image and egress stay as the
            // project's file says, so what was found for them is said rather than silently lost.
            answer.put("by_hand", made.byHand());
            final String entry = made.entry();
            answer.put("entry", entry);
            if (yes)
                // Porelo changes no project file that exists: a project admin writes the entry.
                throw new Refused(
                        Refused.BY_HAND,
                        ProjectRepositoryCommand.words(project, "add this entry:\n" + entry),
                        null,
                        entry);
        }
        return answer;
    }

    @Override
    void text(Map<String, @Nullable Object> answer) {
        for (final String each : String.valueOf(answer.get("draft")).split("\n", -1)) line(each);
        for (String field :
                List.of(
                        "upstream_refusal",
                        "disagreements",
                        "unplaced",
                        "not_placed",
                        "by_hand",
                        "credentials_needed",
                        "refused",
                        "notes",
                        "plugins"))
            if (answer.containsKey(field))
                line(
                        (field.equals("plugins") ? "adapters" : field)
                                + ": "
                                + org.fuin.porelo.wire.Json.write(answer.get(field)));
        if (answer.get("entry") instanceof String entry) {
            line("entry: add this to the project.yml of the project's own repository:");
            for (final String each : entry.stripTrailing().split("\n", -1)) line(each);
        }
        // Only here, where a person reads the answer: with --json a program reads it, and nobody
        // is asked.
        final PoreloContext machine = proposedOn;
        if (machine != null) offerCredentials(machine, answer);
    }

    /**
     * Offers each credential the draft names and the vault does not hold yet, once, at a terminal:
     * accepted, the command that stores it runs on this terminal - {@code porelo vault put} or a
     * declared credential's own - and reads the value itself, without echo. The value never passes
     * through this command, so it is in no answer, line or argument here.
     *
     * @param machine The machine.
     * @param answer The answer the person read.
     */
    private void offerCredentials(
            final PoreloContext machine, final Map<String, @Nullable Object> answer) {
        if (asker == Offer.Asker.NOBODY) return;
        final List<String> ids = new ArrayList<>();
        if (answer.get("credentials_needed") instanceof List<?> needed)
            for (final Object each : needed)
                if (each instanceof Map<?, ?> credential
                        && credential.get("id") instanceof String id) ids.add(id);
        if (ids.isEmpty()) return;
        final var held = machine.readableCredentials();
        if (held.isEmpty()) {
            // Not an error: the proposal stands, and storing waits until the vault is open.
            saying(
                    "the vault is locked or does not open, so no credential is offered; unlock it"
                            + " with 'porelo vault unlock', then run 'porelo vault put <id>'");
            return;
        }
        for (final String id : ids) {
            if (held.get().containsKey(id)) continue;
            final List<String> command = storeCommand(machine, id);
            if (command == null) continue;
            final String said = "porelo " + String.join(" ", command);
            final String given = asker.ask("'" + id + "' is not in the vault. Store it now? [y/N]");
            if (given == null) return;
            final String chosen = given.strip().toLowerCase(java.util.Locale.ROOT);
            if (!chosen.equals("y") && !chosen.equals("yes")) continue;
            if (command.contains("--from-file")) {
                // A key is a file and cannot be typed: its path is asked, as credentials check
                // asks it.
                final String path = asker.ask("Path of the private key to store:");
                if (path == null || path.isBlank()) {
                    saying("'" + id + "' was not stored; run '" + said + "'");
                    continue;
                }
                command.set(command.size() - 1, path.strip());
            }
            final List<String> run = new ArrayList<>(List.of(PoreloBinary.path()));
            run.addAll(command);
            if (machine.exec().applyAsInt(run) != 0)
                saying("'" + id + "' was not stored; run '" + said + "'");
        }
    }

    /**
     * Returns the arguments after {@code porelo} that store a credential here: its declared
     * record's, else {@code vault put}'s; a key's ends in {@code --from-file} and a path to fill.
     *
     * @param machine The machine.
     * @param id The credential's id.
     * @return The arguments, or {@code null} when the value is not this machine's vault's to hold.
     */
    private static @Nullable List<String> storeCommand(
            final PoreloContext machine, final String id) {
        List<org.fuin.porelo.core.credential.Credential> declarations;
        try {
            declarations = machine.credentialRegistry().all();
        } catch (final RuntimeException ex) {
            // A registry that cannot be read is the start's to report, as vault put leaves it.
            declarations = List.of();
        }
        for (final org.fuin.porelo.core.credential.Credential declared : declarations) {
            if (!declared.id().equals(id)) continue;
            final List<String> arguments =
                    CredentialDeclarations.argumentsOf(
                            CredentialDeclarations.storeCommandFor(declared));
            if (arguments.isEmpty()) return null;
            final List<String> command = new ArrayList<>(arguments.subList(1, arguments.size()));
            if (declared.kind() == org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY) {
                command.add("--from-file");
                command.add("<path>");
            }
            return command;
        }
        return new ArrayList<>(List.of("vault", "put", id));
    }
}
