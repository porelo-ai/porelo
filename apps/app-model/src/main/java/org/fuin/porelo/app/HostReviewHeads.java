package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fetches a review head into a store owned by the reader, never the gate's mirror. */
final class HostReviewHeads {
    private static final int MAXIMUM_PACK = 32 * 1024 * 1024;
    private static final String REF = "refs/porelo/review";

    private HostReviewHeads() {}

    /** Runs under the store's lock, including the caller's subsequent maker read. */
    static void fetch(Path store, URI repository, long number, String head, String token)
            throws IOException {
        final String address =
                "http://127.0.0.1:"
                        + repository.getPort()
                        + repository.getPath().substring("/api/v1/repos".length())
                        + ".git";
        final String format = head.length() == 64 ? "sha256" : "sha1";
        if (Files.exists(store, LinkOption.NOFOLLOW_LINKS) && !format.equals(formatOf(store))) {
            // A store made for the other hash, or broken, would refuse every later fetch.
            remove(store);
        }
        org.fuin.porelo.wire.Directories.create(store);
        run(store, Map.of(), "init", "--bare", "--template=", "--object-format=" + format);
        final String header =
                "Authorization: Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + token)
                                                .getBytes(StandardCharsets.UTF_8));
        // Scoped to this host's forge, just as HostForgeAccess lends it. Nothing is saved in git's
        // configuration, inherited from the daemon, or printed by a failed process.
        final Map<String, String> authorization =
                Map.of(
                        "GIT_CONFIG_COUNT", "2",
                        "GIT_CONFIG_KEY_0",
                                "http.http://127.0.0.1:" + repository.getPort() + "/.extraHeader",
                        "GIT_CONFIG_VALUE_0", header,
                        "GIT_CONFIG_KEY_1", "http.followRedirects",
                        "GIT_CONFIG_VALUE_1", "false");
        run(
                store,
                authorization,
                "fetch",
                "--quiet",
                "--no-tags",
                "--no-write-fetch-head",
                "--no-recurse-submodules",
                "--no-auto-maintenance",
                "--depth=1",
                "--",
                address,
                "+refs/pull/" + number + "/head:" + REF);
        if (!run(store, Map.of(), "rev-parse", "--verify", REF + "^{commit}")
                .strip()
                .equals(head)) {
            throw new IOException("the fetched review ref differs from its row");
        }
    }

    private static @org.jspecify.annotations.Nullable String formatOf(Path store) {
        try {
            return run(store, Map.of(), "rev-parse", "--show-object-format").strip();
        } catch (IOException ex) {
            return null;
        }
    }

    private static void remove(Path store) throws IOException {
        try (var walk = Files.walk(store)) {
            for (final Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }

    private static String run(Path store, Map<String, String> variables, String... arguments)
            throws IOException {
        // Force received objects into a pack, then let the kernel bound every file the fetch can
        // write. A single shallow ref cannot unpack into an unbounded number of loose objects.
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "/usr/bin/prlimit",
                                "--fsize=" + MAXIMUM_PACK + ":" + MAXIMUM_PACK,
                                "--",
                                "git",
                                "--no-replace-objects",
                                "--git-dir=" + store,
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "credential.helper=",
                                "-c",
                                "http.proxy=",
                                "-c",
                                "http.followRedirects=false",
                                "-c",
                                // Git reads 0 as unset and unpacks up to 100 loose objects.
                                "fetch.unpackLimit=1",
                                "-c",
                                "transfer.unpackLimit=1",
                                "-c",
                                "gc.auto=0"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment()
                .putAll(
                        Map.of(
                                "PATH",
                                System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                                "LC_ALL",
                                "C",
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1",
                                "GIT_CONFIG_COUNT",
                                "0",
                                "GIT_TERMINAL_PROMPT",
                                "0",
                                "GIT_ALLOW_PROTOCOL",
                                "http"));
        builder.environment().putAll(variables);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        final Process process = builder.start();
        process.getOutputStream().close();
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            try {
                final var output = readers.submit(() -> process.getInputStream().readNBytes(1025));
                final byte[] bytes = output.get(20, TimeUnit.SECONDS);
                if (bytes.length > 1024
                        || !process.waitFor(
                                Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        || process.exitValue() != 0)
                    throw new IOException("the bounded review fetch failed");
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("the review fetch was interrupted");
            } catch (ExecutionException | TimeoutException ex) {
                throw new IOException("the review fetch did not answer within its bound");
            } finally {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                process.getInputStream().close();
            }
        }
    }
}
