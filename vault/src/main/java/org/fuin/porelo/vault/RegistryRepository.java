package org.fuin.porelo.vault;

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
                        bundle.toString(),
                        repository.toString());
        if (!result.successful()) {
            throw new IOException(
                    "cannot read registry '" + source + "': " + result.standardError().strip());
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
                    "cannot read registry history: " + result.standardError().strip());
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
        final String entry = require("ls-tree", "-z", commit, "--", path);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) {
            throw new CardException(commit + " " + path + ": expected a regular file");
        }
        final String object = entry.substring(entry.indexOf("blob ") + 5, entry.indexOf('\t'));
        final long size = Long.parseLong(require("cat-file", "-s", object).strip());
        if (size > 1024 * 1024) {
            throw new CardException(commit + " " + path + ": file exceeds 1 MiB");
        }
        final String text = require("cat-file", "blob", object);
        if (text.indexOf('\uFFFD') >= 0) {
            throw new CardException(commit + " " + path + ": file is not UTF-8 text");
        }
        return Optional.of(text);
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
