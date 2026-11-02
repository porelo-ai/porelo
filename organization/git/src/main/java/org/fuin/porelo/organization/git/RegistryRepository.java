package org.fuin.porelo.organization.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/** An owned bare clone: no checkout, inherited Git configuration, helpers or network protocols. */
final class RegistryRepository implements AutoCloseable {
    private final Path scratch;
    private final Path repository;
    private final CommandRunner runner;
    private final Map<String, String> environment;

    RegistryRepository(Path scratch, CommandRunner runner) {
        this.scratch = scratch;
        this.repository = scratch.resolve("registry.git");
        this.runner = runner;
        environment =
                Map.of(
                        "PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                        "LC_ALL", "C",
                        "GIT_CONFIG_NOSYSTEM", "1",
                        "GIT_CONFIG_GLOBAL", "/dev/null",
                        "GIT_ATTR_NOSYSTEM", "1",
                        "GIT_NO_REPLACE_OBJECTS", "1",
                        // A partial checkout would fetch a missing object from its promisor
                        // remote, by whatever program its configuration names.
                        "GIT_NO_LAZY_FETCH", "1",
                        "GIT_ALLOW_PROTOCOL", "file",
                        "GIT_PROTOCOL_FROM_USER", "0",
                        "GIT_TERMINAL_PROMPT", "0");
    }

    void cloneFrom(Path source) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "cannot read registry bundle '"
                            + source
                            + "': expected a regular file, not a symlink");
        }
        final Path bundle = scratch.resolve("registry.bundle");
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(input, bundle);
        }
        try (var input = Files.newInputStream(bundle)) {
            final String header = new String(input.readNBytes(16), StandardCharsets.US_ASCII);
            if (!header.equals("# v2 git bundle\n") && !header.equals("# v3 git bundle\n")) {
                throw new IOException(
                        "cannot read registry bundle '"
                                + source
                                + "': expected a v2 or v3 Git bundle");
            }
        }
        cloneBare(bundle, source);
    }

    /**
     * Fetches the commit a person's checkout has checked out, and its history. Only objects come
     * over: the checkout's configuration, hooks, grafts and shallow boundary stay there, so nothing
     * there changes what this clone reads, and a shallow checkout stays shallow, which the reader
     * refuses.
     *
     * @return The commit the checkout's {@code HEAD} names, on a branch or detached.
     */
    String cloneCheckout(Path checkout) throws IOException {
        if (!Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "cannot read registry checkout '"
                            + RegistryText.printable(checkout.toString())
                            + "': expected a directory, not a symlink");
        }
        // An absolute path, so a colon in it is never read as a host.
        final Path from = checkout.toAbsolutePath().normalize();
        // Exactly what is checked out: a clone's own HEAD is a guess, which may name another
        // branch than the one the person reads, or none.
        final CommandResult head =
                git("-C", from.toString(), "rev-parse", "--verify", "HEAD^{commit}");
        if (!head.successful() || !head.trimmedOutput().matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new CardException("the registry checkout has no commit checked out");
        }
        final String commit = head.trimmedOutput();
        final Path template = Files.createDirectory(scratch.resolve("empty-template"));
        final CommandResult init =
                git("init", "-q", "--bare", "--template=" + template, repository.toString());
        if (!init.successful()) {
            throw new IOException(
                    "cannot read registry '"
                            + RegistryText.printable(checkout.toString())
                            + "': "
                            + RegistryText.printable(init.standardError().strip()));
        }
        final CommandResult fetched =
                git(
                        "fetch",
                        "-q",
                        "--no-tags",
                        "--no-recurse-submodules",
                        "--no-write-fetch-head",
                        "--update-shallow",
                        "--",
                        from.toString(),
                        commit + ":refs/heads/checkout");
        if (!fetched.successful()) {
            throw new IOException(
                    "cannot read registry '"
                            + RegistryText.printable(checkout.toString())
                            + "': "
                            + RegistryText.printable(fetched.standardError().strip()));
        }
        return commit;
    }

    private void cloneBare(Path from, Path source) throws IOException {
        final Path template = Files.createDirectory(scratch.resolve("empty-template"));
        final CommandResult result =
                git(
                        "clone",
                        "--bare",
                        "--no-local",
                        "--no-hardlinks",
                        "--no-recurse-submodules",
                        "--template=" + template,
                        "--",
                        from.toString(),
                        repository.toString());
        if (!result.successful()) {
            throw new IOException(
                    "cannot read registry '"
                            + RegistryText.printable(source.toString())
                            + "': "
                            + RegistryText.printable(result.standardError().strip()));
        }
    }

    CommandResult git(String... args) {
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
                                "protocol.allow=never",
                                "-c",
                                "protocol.file.allow=always",
                                "-c",
                                "gpg.format=ssh",
                                "-c",
                                "gpg.ssh.program=ssh-keygen",
                                "-c",
                                "gpg.ssh.revocationFile=/dev/null"));
        if (Files.isDirectory(repository)) {
            all.add("--git-dir=" + repository);
        }
        all.addAll(List.of(args));
        return runner.run(Command.of(all).in(scratch).withChosenEnvironment(environment));
    }

    String require(String... args) {
        final CommandResult result = git(args);
        if (!result.successful()) {
            throw new CardException(
                    "cannot read registry history: "
                            + RegistryText.printable(result.standardError().strip()));
        }
        return result.standardOutput();
    }

    String head() {
        final CommandResult main = git("rev-parse", "--verify", "refs/heads/main^{commit}");
        if (!main.successful()) {
            throw new CardException("the registry bundle has no main branch");
        }
        return main.trimmedOutput();
    }

    Optional<String> read(String commit, String path) {
        return object(commit, path).map(object -> blob(object, commit + " " + path));
    }

    /** The blob a regular file at this path is, or empty when the commit has no such path. */
    Optional<String> object(String commit, String path) {
        final String entry = require("ls-tree", "-z", "--end-of-options", commit, "--", path);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) {
            throw new CardException(commit + " " + path + ": expected a regular file");
        }
        return Optional.of(entry.substring(entry.indexOf("blob ") + 5, entry.indexOf('\t')));
    }

    /** A blob's text, bounded and UTF-8; the origin names it in a refusal. */
    String blob(String object, String origin) {
        final long size =
                Long.parseLong(require("cat-file", "-s", "--end-of-options", object).strip());
        if (size > 1024 * 1024) {
            throw new CardException(origin + ": file exceeds 1 MiB");
        }
        final String text = require("cat-file", "blob", "--end-of-options", object);
        if (text.indexOf('\uFFFD') >= 0) {
            throw new CardException(origin + ": file is not UTF-8 text");
        }
        return text;
    }

    String readRequired(String commit, String path) {
        return read(commit, path)
                .orElseThrow(() -> new CardException(commit + " " + path + ": file is missing"));
    }

    Path signers() {
        return scratch.resolve("allowed-signers");
    }

    @Override
    public void close() throws IOException {
        final List<IOException> failures = new ArrayList<>();
        Files.walkFileTree(
                scratch,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                        return delete(path, failures);
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path path, IOException failure) {
                        if (!(failure instanceof NoSuchFileException)) {
                            failures.add(failure);
                        }
                        return delete(path, failures);
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                            Path path, @Nullable IOException failure) {
                        if (failure != null) {
                            failures.add(failure);
                        }
                        return delete(path, failures);
                    }
                });
        if (!failures.isEmpty()) {
            final IOException failure = failures.getFirst();
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static FileVisitResult delete(Path path, List<IOException> failures) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            failures.add(ex);
        }
        return FileVisitResult.CONTINUE;
    }
}
