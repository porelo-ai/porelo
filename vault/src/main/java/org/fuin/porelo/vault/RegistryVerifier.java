package org.fuin.porelo.vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.CommandRunner;

/** Verifies a registry using only the host admin's pin and the history brought with it. */
public final class RegistryVerifier {
    private final CommandRunner runner;

    /** Creates a verifier whose external Git checks use this runner. */
    public RegistryVerifier(CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * Checks the host paths' signed history, current host card, and pinned people's card chains.
     *
     * @param source A whole repository bundle in a regular file, never a symlink.
     * @param host The lower-case host label.
     * @param pinned The host admin's SHA256 fingerprint at the applied commit, or the initial pin.
     * @param since A trusted applied commit the head must descend from, or an empty string.
     * @return The verified state, using the names of the sync contract.
     * @throws IOException If the repository cannot be read or the owned clone cannot be removed.
     * @throws CardException If the registry fails a trust or ancestry check.
     * @throws IllegalArgumentException If an option is malformed.
     */
    public Map<String, Object> verify(Path source, String host, String pinned, String since)
            throws IOException {
        validateOptions(host, pinned, since);
        if (source.toString().isBlank()) {
            throw new IllegalArgumentException("--repository must name a bundle file");
        }
        final Path scratch = Files.createTempDirectory("porelo-registry-");
        try (RegistryRepository repository = new RegistryRepository(scratch, runner)) {
            repository.cloneFrom(source);
            final String head = repository.head();
            final String applied = checkSince(repository, head, since);
            if (!repository
                    .require("rev-parse", "--is-shallow-repository")
                    .strip()
                    .equals("false")) {
                throw new CardException(
                        head + ": a shallow registry cannot prove its host paths' history");
            }
            final String cardPath = "hosts/" + host + "/card";
            if (!applied.isEmpty()) {
                hostCard(repository, applied, cardPath, host, pinned);
            }
            final String signer = checkHostHistory(repository, head, host, pinned, applied);
            final HostCard card = hostCard(repository, head, cardPath, host, signer);
            final String accessPath = "hosts/" + host + "/access.yml";
            final RegistryAccess access =
                    RegistryAccess.read(
                            repository.readRequired(head, accessPath), head + " " + accessPath);
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("commit", head);
            answer.put("host_card", hostAnswer(card, signer));
            answer.put("admins", access.admins());
            answer.put(
                    "projects",
                    access.projects().stream()
                            .map(
                                    project ->
                                            Map.of(
                                                    "name",
                                                    project.name(),
                                                    "admin",
                                                    project.admin(),
                                                    "access_repository",
                                                    project.accessRepository()))
                            .toList());
            final List<Map<String, Object>> persons = new ArrayList<>();
            for (var admin : access.admins().entrySet()) {
                persons.add(person(repository, head, admin.getKey(), admin.getValue()));
            }
            answer.put("persons", List.copyOf(persons));
            return answer;
        }
    }

    /** Refuses options that could change Git's meaning or escape the selected host's paths. */
    public static void validateOptions(String host, String pinned, String since) {
        if (!host.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("--host must be a lower-case DNS label");
        }
        if (!pinned.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            throw new IllegalArgumentException("--pinned must be a key's SHA256 fingerprint");
        }
        if (!since.isEmpty() && !since.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "--since must be a commit's full lowercase object name");
        }
    }

    private static String checkSince(RegistryRepository repository, String head, String since) {
        if (since.isEmpty()) {
            return "";
        }
        final var resolved = repository.git("cat-file", "-t", since);
        if (!resolved.successful()
                || !resolved.trimmedOutput().equals("commit")
                || !repository
                        .require("rev-list", "--first-parent", head)
                        .lines()
                        .anyMatch(since::equals)) {
            throw new CardException(
                    "registry head "
                            + head
                            + " does not continue the first-parent line of --since "
                            + since
                            + " (or that commit is missing)");
        }
        return since;
    }

    private static String checkHostHistory(
            RegistryRepository repository, String head, String host, String pinned, String applied)
            throws IOException {
        String current = pinned;
        final String prefix = "hosts/" + host + "/";
        final List<String> history =
                new ArrayList<>(
                        List.of("rev-list", "--parents", "--first-parent", "--reverse", head));
        if (!applied.isEmpty()) {
            history.add("^" + applied);
        }
        for (String entry : repository.require(history.toArray(String[]::new)).lines().toList()) {
            final String[] ancestry = entry.split(" ");
            final String commit = ancestry[0];
            final String parent = ancestry.length > 1 ? ancestry[1] : "";
            final List<String> diff =
                    new ArrayList<>(
                            List.of(
                                    "diff-tree",
                                    "--no-commit-id",
                                    "--no-renames",
                                    "--name-only",
                                    "-r",
                                    "-z"));
            if (parent.isEmpty()) {
                diff.add("--root");
            } else {
                diff.add(parent);
            }
            diff.add(commit);
            diff.add("--");
            diff.add("hosts/" + host);
            final List<String> paths =
                    List.of(repository.require(diff.toArray(String[]::new)).split("\u0000"))
                            .stream()
                            .filter(path -> !path.isEmpty())
                            .toList();
            if (paths.isEmpty()) {
                continue;
            }
            checkCommit(repository, commit, paths.getFirst(), current);
            if (paths.contains(prefix + "card")) {
                final HostCard card = hostCard(repository, commit, prefix + "card", host, "");
                current = card.signer();
            }
        }
        return current;
    }

    private static void checkCommit(
            RegistryRepository repository, String commit, String path, String pinned)
            throws IOException {
        final String raw = repository.require("cat-file", "commit", commit);
        final StringBuilder signature = new StringBuilder();
        boolean collecting = false;
        for (String line : raw.lines().toList()) {
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith("gpgsig ")) {
                collecting = true;
                signature.append(line.substring(7)).append('\n');
            } else if (collecting && line.startsWith(" ")) {
                signature.append(line.substring(1)).append('\n');
            } else {
                collecting = false;
            }
        }
        final byte[] blob = SshSignature.signerOf(signature.toString());
        if (blob == null) {
            throw new CardException(commit + " " + path + ": commit has no readable SSH signature");
        }
        final String key = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob);
        final String signer;
        try {
            signer = PersonCard.fingerprint(key);
        } catch (CardException ex) {
            throw new CardException(commit + " " + path + ": " + ex.getMessage());
        }
        if (!signer.equals(pinned)) {
            throw new CardException(
                    commit + " " + path + ": commit signed by " + signer + ", expected " + pinned);
        }
        Files.writeString(repository.signers(), "host-admin namespaces=\"git\" " + key + "\n");
        if (!repository
                .git(
                        "-c",
                        "gpg.ssh.allowedSignersFile=" + repository.signers(),
                        "verify-commit",
                        commit)
                .successful()) {
            throw new CardException(
                    commit
                            + " "
                            + path
                            + ": commit's SSH signature does not verify against "
                            + pinned);
        }
    }

    private static HostCard hostCard(
            RegistryRepository repository, String commit, String path, String host, String pinned) {
        try {
            final HostCard card =
                    HostCard.read(repository.readRequired(commit, path), commit + " " + path);
            if (!card.host().equals(host)) {
                throw new CardException("host card names " + card.host() + ", expected " + host);
            }
            card.checkSignedBy(pinned.isEmpty() ? card.signer() : pinned);
            return card;
        } catch (CardException ex) {
            throw new CardException(commit + " " + path + ": " + ex.getMessage());
        }
    }

    private static Map<String, Object> hostAnswer(HostCard card, String signer) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("host", card.host());
        answer.put("address", card.address());
        answer.put("ssh_port", card.sshPort());
        answer.put("ssh_host_key", card.sshHostKey());
        answer.put(
                "services",
                Map.of(
                        "forge",
                        Map.of("web_port", card.forgeWebPort(), "git_port", card.forgeGitPort()),
                        "matrix",
                        Map.of("port", card.matrixPort())));
        answer.put("signed_by", signer);
        return answer;
    }

    private static Map<String, Object> person(
            RegistryRepository repository, String head, String login, String pinned) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("login", login);
        answer.put("ssh", "");
        answer.put("expires", "");
        answer.put("revoked", true);
        final String path = "people/" + login + ".card";
        try {
            final var text = repository.read(head, path);
            if (text.isEmpty()) {
                return answer;
            }
            final PersonCard card = PersonCard.read(text.orElseThrow(), head + " " + path);
            if (!card.login().equals(login)) {
                return answer;
            }
            final List<String> history =
                    repository
                            .require(
                                    "rev-list",
                                    "--full-history",
                                    "--max-count=" + (PersonCardChain.MAX_STEPS + 1),
                                    head,
                                    "--",
                                    path)
                            .lines()
                            .toList();
            if (history.size() > PersonCardChain.MAX_STEPS) {
                return answer;
            }
            final List<PersonCard> earlier = new ArrayList<>();
            for (String commit : history) {
                try {
                    repository
                            .read(commit, path)
                            .ifPresent(
                                    body ->
                                            earlier.add(
                                                    PersonCard.read(body, commit + " " + path)));
                } catch (CardException ex) {
                    // An unusable older card grants no link in the chain.
                }
            }
            PersonCardChain.check(card, earlier, pinned);
            answer.put("ssh", card.key());
            answer.put("expires", card.expires().toString());
            answer.put("revoked", false);
        } catch (CardException ex) {
            // Missing, malformed or untrusted person cards withdraw access without hiding the
            // login.
        }
        return answer;
    }
}
