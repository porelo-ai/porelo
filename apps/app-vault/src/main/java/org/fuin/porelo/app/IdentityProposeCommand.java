package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Proposes a card from public facts without keeping it or asking a question. */
@picocli.CommandLine.Command(
        name = "propose",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Proposes your card from Git, your agent and the forge.")
public class IdentityProposeCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    @Option(
            names = "--forge",
            description = "The forge to ask; defaults to your organization's forge or GitHub.")
    private @Nullable URI forge;

    @Option(names = "--json", description = "Answers the proposal as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;
    private @Nullable PoreloContext context;
    IdentityOrganization.Opener opener = IdentityOrganization::real;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                Objects.requireNonNull(context, "the factory sets the context");
        try {
            final Map<String, @Nullable Object> answer = propose(machine);
            final var out = spec.commandLine().getOut();
            if (json) {
                out.println(Json.write(AnswerText.printable(answer)));
            } else {
                answer.forEach(
                        (field, value) ->
                                out.println(
                                        field + " " + AnswerText.printable(String.valueOf(value))));
            }
            out.flush();
            return 0;
        } catch (IllegalArgumentException | IdentityChange.Refused ex) {
            spec.commandLine().getErr().println("porelo: name a valid forge URL with --forge");
            spec.commandLine().getErr().flush();
            return 64;
        }
    }

    private Map<String, @Nullable Object> propose(final PoreloContext machine)
            throws IdentityChange.Refused {
        URI address = forge;
        ForgeAddress configuredOrigin = null;
        if (address == null) {
            try {
                final var repository = IdentityOrganization.repository(machine);
                if (repository.isPresent()) {
                    address = opener.open(machine, repository.get()).forge();
                    final ForgeAddress found = ForgeAddress.of(repository.get());
                    if (found.forge().equals(address)) configuredOrigin = found;
                }
            } catch (IOException ex) {
                // Unreadable settings supply no forge.
            }
        }
        if (address == null) {
            address = URI.create("https://github.com");
        }
        final ForgeAddress origin =
                configuredOrigin != null
                        ? configuredOrigin
                        : new ForgeAddress(
                                address + "/identity/proposal",
                                address,
                                "identity",
                                address.getPort());
        final HttpForgeDirectory directory =
                new HttpForgeDirectory(
                        address,
                        OrganizationAccess.forgeToken(machine, origin),
                        OrganizationAccess.http(machine));
        final List<String> notes = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        final List<Map<String, Object>> ambiguous = new ArrayList<>();
        String login = directory.account().map(HttpForgeDirectory.Profile::login).orElse(null);
        String loginSource = "token";
        if (login != null && !PersonCard.isLogin(login)) {
            notes.add("The token's login is not a card login.");
            login = null;
        }
        if (login == null) {
            login =
                    sshLogin(
                            machine,
                            Objects.requireNonNull(
                                    address.getHost(), "a validated forge has a host"));
            loginSource = "ssh";
            if (login != null && !PersonCard.isLogin(login)) {
                notes.add("The SSH login is not a card login.");
                login = null;
            }
        }
        final List<AgentKey> served = IdentityFacts.served(machine).orElse(List.of());
        final String configured = config(machine, "user.signingkey");
        final String configuredKey = configuredKey(machine, configured, notes);
        String key = configuredKey;
        String keySource = "git-config";
        if (key == null && login != null) {
            for (final String source : List.of("forge-signing", "forge-authentication")) {
                final List<String> listed =
                        (source.equals("forge-signing")
                                        ? directory.signingKeys(login)
                                        : directory.authenticationKeys(login))
                                .orElse(List.of());
                final List<String> candidates =
                        served.stream()
                                .filter(IdentityFacts::ed25519)
                                .map(PersonCard::keyLine)
                                .filter(listed::contains)
                                .distinct()
                                .toList();
                if (candidates.size() > 1) {
                    ambiguous.add(
                            Map.of(
                                    "field",
                                    "key",
                                    "values",
                                    candidates.stream().map(PersonCard::fingerprint).toList()));
                    break;
                }
                if (candidates.size() == 1) {
                    key = candidates.getFirst();
                    keySource = source;
                    break;
                }
            }
        }
        final String gitName = config(machine, "user.name");
        final String gitEmail = config(machine, "user.email");
        final HttpForgeDirectory.Profile profile =
                login == null || gitName != null && gitEmail != null && !noreply(gitEmail)
                        ? null
                        : directory.profile(login).orElse(null);
        final String profileEmail = profile == null ? null : profile.email();
        final String name = gitName != null ? gitName : profile == null ? null : profile.name();
        String email = gitEmail;
        String emailSource = "git-config";
        if (email == null || noreply(email) && profileEmail != null && !noreply(profileEmail)) {
            email = profileEmail;
            emailSource = "forge-profile";
        }
        final String selected = key;
        final boolean inAgent =
                selected != null
                        && served.stream()
                                .filter(IdentityFacts::ed25519)
                                .map(PersonCard::keyLine)
                                .anyMatch(selected::equals);
        if (login == null) missing.add("login");
        if (name == null) missing.add("name");
        if (key == null) {
            missing.add("key");
            if (served.stream().anyMatch(each -> !IdentityFacts.ed25519(each))) {
                notes.add("The agent serves a key that is not Ed25519; a card cannot name it.");
            }
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("forge", address.toString());
        answer.put("login", value(login, loginSource));
        answer.put("name", value(name, gitName != null ? "git-config" : "forge-profile"));
        answer.put("email", value(email, emailSource));
        answer.put(
                "key",
                key == null
                        ? null
                        : Map.of(
                                "fingerprint",
                                PersonCard.fingerprint(key),
                                "key",
                                key,
                                "source",
                                keySource,
                                "in_agent",
                                inAgent));
        answer.put("missing", missing);
        answer.put("ambiguous", ambiguous);
        answer.put("notes", notes);
        final String path =
                configured == null
                                || configured.startsWith("key::")
                                || configured.startsWith("ssh-")
                        ? null
                        : configured;
        answer.put(
                "ssh_add",
                configuredKey != null && !inAgent
                        ? path == null ? "ssh-add" : sshAdd(machine, path)
                        : null);
        answer.put("ready", missing.isEmpty() && ambiguous.isEmpty() && inAgent);
        return answer;
    }

    private static String sshAdd(final PoreloContext machine, final String configured) {
        String path =
                configured.startsWith("~/")
                        ? machine.paths().xdg().home().resolve(configured.substring(2)).toString()
                        : configured;
        if (path.endsWith(".pub")) path = path.substring(0, path.length() - 4);
        return "ssh-add '" + path.replace("'", "'\\''") + "'";
    }

    private static @Nullable Map<String, String> value(
            final @Nullable String value, final String source) {
        return value == null ? null : Map.of("value", value, "source", source);
    }

    private static boolean noreply(final String email) {
        return email.endsWith("@users.noreply.github.com");
    }

    private static @Nullable String config(final PoreloContext machine, final String field) {
        Command command = LocalGit.config(field);
        final Map<String, String> environment = new LinkedHashMap<>();
        for (final String name :
                List.of("GIT_CONFIG_GLOBAL", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_SYSTEM")) {
            final String value = machine.environment().apply(name);
            if (value != null) environment.put(name, value);
        }
        command = command.withEnvironment(environment);
        try {
            final CommandResult result = machine.runner().run(command);
            return result.successful() && !result.trimmedOutput().isBlank()
                    ? result.trimmedOutput()
                    : null;
        } catch (CommandException ex) {
            return null;
        }
    }

    private static @Nullable String sshLogin(final PoreloContext machine, final String host) {
        Command command =
                Command.of(
                        "timeout",
                        "12s",
                        "ssh",
                        "-T",
                        "-o",
                        "BatchMode=yes",
                        "-o",
                        "ConnectTimeout=5",
                        "-o",
                        "StrictHostKeyChecking=yes",
                        "-o",
                        "UpdateHostKeys=no",
                        "git@" + host);
        final String socket = machine.environment().apply("SSH_AUTH_SOCK");
        if (socket != null) command = command.withEnvironment(Map.of("SSH_AUTH_SOCK", socket));
        try {
            final CommandResult result = machine.runner().run(command);
            if (result.exitCode() != 0 && result.exitCode() != 1) return null;
            final var match =
                    Pattern.compile("Hi (?:there, )?([^!\\s]+)!")
                            .matcher(result.standardOutput() + result.standardError());
            return match.find() ? match.group(1) : null;
        } catch (CommandException ex) {
            return null;
        }
    }

    private static @Nullable String configuredKey(
            final PoreloContext machine,
            final @Nullable String configured,
            final List<String> notes) {
        if (configured == null) return null;
        String line = configured.startsWith("key::") ? configured.substring(5) : configured;
        if (!configured.startsWith("key::")
                && !line.startsWith("ssh-")
                && !line.startsWith("ecdsa-")) {
            Path path =
                    Path.of(
                            line.startsWith("~/")
                                    ? machine.paths()
                                            .xdg()
                                            .home()
                                            .resolve(line.substring(2))
                                            .toString()
                                    : line);
            if (!line.endsWith(".pub")) path = Path.of(path + ".pub");
            try {
                // A link may lead to a private key, which is never read.
                if (Files.isSymbolicLink(path)) {
                    notes.add("The Git signing key's file is a link, which is not followed.");
                    return null;
                }
                if (!Files.isRegularFile(path) || Files.size(path) > 16384) return null;
                line = Files.readString(path).strip();
            } catch (IOException ex) {
                return null;
            }
        }
        final String[] words = line.split("\\s+");
        try {
            if (words.length < 2) {
                notes.add("The Git signing key is not a valid Ed25519 public key.");
                return null;
            }
            if (!words[0].equals("ssh-ed25519")) {
                notes.add("The Git signing key is not an Ed25519 public key.");
                return null;
            }
            final String publicKey = words[0] + " " + words[1];
            PersonCard.fingerprint(publicKey);
            return publicKey;
        } catch (IllegalArgumentException | org.fuin.porelo.vault.CardException ex) {
            notes.add("The Git signing key is not a valid Ed25519 public key.");
            return null;
        }
    }
}
