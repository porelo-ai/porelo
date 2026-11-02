package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.vault.SshSignature;
import org.jspecify.annotations.Nullable;

/**
 * An owned bare clone in a scratch directory: only objects come over, so nothing of a checkout's or
 * the caller's configuration, hooks or environment changes what it reads.
 */
final class TreeRepository implements AutoCloseable {

    /** Only what fetches an organization's repository; {@code ext::} would run a program. */
    private static final String PROTOCOLS = "file:ssh:https";

    private static final String BRANCH = "refs/heads/main";

    private static final int LIMIT = 1024 * 1024;

    private final Path scratch;

    private final Path repository;

    private final CommandRunner runner;

    private TreeRepository(final Path scratch, final CommandRunner runner) {
        this.scratch = scratch;
        this.repository = scratch.resolve("organization.git");
        this.runner = runner;
    }

    /**
     * Fetches the repository's main branch into a new scratch clone, removed again on failure.
     *
     * @param address Where the repository is.
     * @param runner How git runs.
     * @param access What the fetch may use, lent for its length.
     * @return The clone.
     * @throws IOException When it cannot be fetched.
     */
    static TreeRepository fetch(
            final String address, final CommandRunner runner, final FetchAccess access)
            throws IOException {
        final TreeRepository clone =
                new TreeRepository(Files.createTempDirectory("porelo-organization-"), runner);
        try {
            clone.fill(address, access);
            return clone;
        } catch (IOException | RuntimeException ex) {
            clone.close();
            throw ex;
        }
    }

    private void fill(final String address, final FetchAccess access) throws IOException {
        final Path template = Files.createDirectory(scratch.resolve("empty-template"));
        final CommandResult init =
                git("init", "-q", "--bare", "--template=" + template, repository.toString());
        if (!init.successful()) {
            throw new IOException("cannot make the organization's clone");
        }
        try (FetchAccess.Lease lease = access.lend(address)) {
            final List<String> arguments = new ArrayList<>(lease.arguments());
            arguments.addAll(
                    List.of(
                            "fetch",
                            "-q",
                            "--no-tags",
                            "--no-recurse-submodules",
                            "--no-write-fetch-head",
                            "--update-shallow",
                            "--",
                            address,
                            BRANCH + ":" + BRANCH));
            final CommandResult fetched =
                    git(lease.environment(), arguments.toArray(String[]::new));
            if (!fetched.successful()) {
                throw new IOException(
                        "cannot read the organization's repository: "
                                + RegistryText.printable(fetched.standardError().strip()));
            }
        }
    }

    /**
     * Returns the first-parent line, oldest first, each with its parent.
     *
     * @param revision Where the line ends, or {@code null} for the branch's tip.
     * @return Each commit and its first parent, "" for the founding one.
     * @throws CardException When the history is shallow, and so proves nothing, or the revision is
     *     not on the line.
     */
    List<Map.Entry<String, String>> line(final @Nullable String revision) {
        if (!require("rev-parse", "--is-shallow-repository").strip().equals("false")) {
            throw new CardException("a shallow organization cannot prove its history");
        }
        final List<Map.Entry<String, String>> line = new ArrayList<>();
        for (final String entry :
                require("rev-list", "--parents", "--first-parent", "--reverse", BRANCH)
                        .lines()
                        .toList()) {
            final String[] ancestry = entry.split(" ");
            line.add(Map.entry(ancestry[0], ancestry.length > 1 ? ancestry[1] : ""));
            if (ancestry[0].equals(revision)) {
                return List.copyOf(line);
            }
        }
        if (revision != null) {
            throw new CardException("the revision asked for is not on the organization's line");
        }
        return List.copyOf(line);
    }

    /**
     * Returns the paths a commit changed against its first parent.
     *
     * @param commit The commit.
     * @param parent Its first parent, or "" for the founding one.
     * @return The paths.
     */
    List<String> changed(final String commit, final String parent) {
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
        }
        diff.add("--end-of-options");
        if (!parent.isEmpty()) {
            diff.add(parent);
        }
        diff.add(commit);
        return List.of(require(diff.toArray(String[]::new)).split("\u0000"));
    }

    /**
     * Returns the blob a regular file at this path is.
     *
     * @param commit The commit.
     * @param path The path.
     * @return The blob, or empty when the commit has no such path.
     * @throws CardException When the path holds anything but a regular file.
     */
    Optional<String> object(final String commit, final String path) {
        final String entry = require("ls-tree", "-z", "--end-of-options", commit, "--", path);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) {
            throw new CardException("expected a regular file");
        }
        return Optional.of(entry.substring(entry.indexOf("blob ") + 5, entry.indexOf('\t')));
    }

    /**
     * Returns a blob's text.
     *
     * @param object The blob.
     * @return Its text.
     * @throws CardException When it is too large, or no UTF-8 text.
     */
    String text(final String object) {
        final long size =
                Long.parseLong(require("cat-file", "-s", "--end-of-options", object).strip());
        if (size > LIMIT) {
            throw new CardException("the file exceeds 1 MiB");
        }
        final String text = require("cat-file", "blob", "--end-of-options", object);
        if (text.indexOf('�') >= 0) {
            throw new CardException("the file is not UTF-8 text");
        }
        return text;
    }

    /**
     * Returns the key that verifiably signed a commit.
     *
     * @param commit The commit.
     * @return The key, {@code ssh-ed25519 AAAA...}.
     * @throws CardException When it carries no signature that verifies against the key it names.
     * @throws IOException When the allowed-signers file cannot be written.
     */
    String signer(final String commit) throws IOException {
        final String key = claimedKey(require("cat-file", "commit", "--end-of-options", commit));
        final Path signers = scratch.resolve("allowed-signers");
        Files.writeString(
                signers, "signer namespaces=\"git\" " + key + "\n", StandardCharsets.UTF_8);
        final boolean verified =
                git(
                                "-c",
                                "gpg.ssh.allowedSignersFile=" + signers,
                                "verify-commit",
                                "--end-of-options",
                                commit)
                        .successful();
        if (!verified) {
            throw new CardException(
                    "the commit's signature does not verify against "
                            + PersonCard.fingerprint(key));
        }
        return key;
    }

    private static String claimedKey(final String raw) {
        final StringBuilder signature = new StringBuilder();
        boolean collecting = false;
        for (final String line : raw.lines().toList()) {
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
            throw new CardException("the commit has no readable SSH signature");
        }
        final String key = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob);
        PersonCard.fingerprint(key);
        return key;
    }

    private String require(final String... arguments) {
        final CommandResult result = git(arguments);
        if (!result.successful()) {
            throw new CardException(
                    "cannot read the organization's history: "
                            + RegistryText.printable(result.standardError().strip()));
        }
        return result.standardOutput();
    }

    private CommandResult git(final String... arguments) {
        return git(Map.of(), arguments);
    }

    private CommandResult git(final Map<String, String> variables, final String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--no-replace-objects",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "core.sshCommand=ssh",
                                "-c",
                                "credential.helper=",
                                "-c",
                                "http.followRedirects=false",
                                "-c",
                                "gpg.format=ssh",
                                "-c",
                                "gpg.ssh.program=ssh-keygen",
                                "-c",
                                "gpg.ssh.revocationFile=/dev/null"));
        if (Files.isDirectory(repository)) {
            all.add("--git-dir=" + repository);
        }
        all.addAll(List.of(arguments));
        return runner.run(
                Command.of(all)
                        .in(scratch)
                        .withEnvironment(variables)
                        .withChosenEnvironment(
                                Map.of(
                                        "PATH",
                                        System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                                        "LC_ALL",
                                        "C",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1",
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_ATTR_NOSYSTEM",
                                        "1",
                                        "GIT_NO_REPLACE_OBJECTS",
                                        "1",
                                        "GIT_NO_LAZY_FETCH",
                                        "1",
                                        "GIT_ALLOW_PROTOCOL",
                                        PROTOCOLS,
                                        "GIT_TERMINAL_PROMPT",
                                        "0")));
    }

    @Override
    public void close() throws IOException {
        try (Stream<Path> paths = Files.walk(scratch)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
