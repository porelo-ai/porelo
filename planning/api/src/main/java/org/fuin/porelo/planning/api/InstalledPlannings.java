package org.fuin.porelo.planning.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Finds installed planning adapters without naming any implementation. */
public final class InstalledPlannings {

    /** Where packages install adapters. */
    public static final Path PACKAGED = Path.of("/usr/libexec/porelo/planning");

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private static final int DESCRIPTION_BYTES = 65536;

    private InstalledPlannings() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * An installed adapter, including one that could not describe itself.
     *
     * @param name Its file's name.
     * @param executable Where it is installed.
     * @param refusal Why it cannot be used, or null when its description was accepted.
     * @param version The adapter's own version, made plain; null when it is refused or says none.
     * @param protocolVersion The version of the contract it speaks; null when it is refused.
     * @param capabilities Its supported operations, empty when refused.
     * @param credentials Its accepted credential kinds, empty when refused.
     */
    public record Description(
            String name,
            Path executable,
            @Nullable String refusal,
            @Nullable String version,
            @Nullable Integer protocolVersion,
            List<String> capabilities,
            List<String> credentials) {
        /** Copies the lists supplied by the description. */
        public Description {
            capabilities = List.copyOf(capabilities);
            credentials = List.copyOf(credentials);
        }

        /**
         * An adapter described without its versions, as a caller built before them names one.
         *
         * @param name Its file's name.
         * @param executable Where it is installed.
         * @param refusal Why it cannot be used, or null when its description was accepted.
         */
        public Description(
                final String name, final Path executable, final @Nullable String refusal) {
            this(name, executable, refusal, null, null, List.of(), List.of());
        }
    }

    /**
     * Lists adapters in directory order, sorted within each directory, asking each to describe
     * itself. A name in an earlier directory wins even when that adapter is refused.
     *
     * @param directories Locations, the account's own first, then the packaged location.
     * @return One description per name, with each failed adapter named in its refusal.
     * @throws PlanningException When a directory cannot be listed.
     */
    public static List<Description> all(final List<Path> directories) {
        final Set<String> seen = new HashSet<>();
        final List<Description> descriptions = new ArrayList<>();
        for (final Path directory : directories) {
            for (final Path executable : candidates(directory)) {
                final String name = executable.getFileName().toString();
                if (seen.add(name)) {
                    descriptions.add(describe(executable));
                }
            }
        }
        return List.copyOf(descriptions);
    }

    private static List<Path> candidates(final Path directory) {
        if (Files.notExists(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> NAME.matcher(path.getFileName().toString()).matches())
                    .filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new PlanningException("Cannot list " + directory, ex);
        }
    }

    private static Description describe(final Path executable) {
        final String name = executable.getFileName().toString();
        try {
            final ProcessBuilder builder = new ProcessBuilder(executable.toString(), "describe");
            builder.environment().clear();
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            final Process process = builder.start();
            final var reader =
                    Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try {
                final var answer =
                        reader.submit(
                                        () -> {
                                            try (var input = process.getInputStream()) {
                                                final byte[] bytes =
                                                        input.readNBytes(DESCRIPTION_BYTES + 1);
                                                if (bytes.length > DESCRIPTION_BYTES) {
                                                    throw new PlanningException(
                                                            "description exceeds "
                                                                    + DESCRIPTION_BYTES
                                                                    + " bytes");
                                                }
                                                if (process.waitFor() != 0) {
                                                    throw new PlanningException(
                                                            "describe exited with code "
                                                                    + process.exitValue());
                                                }
                                                return Json.parse(
                                                        new String(bytes, StandardCharsets.UTF_8));
                                            }
                                        })
                                .get(InstalledPlanning.READY_SECONDS, TimeUnit.SECONDS);
                if (!(answer instanceof Map<?, ?> map)) {
                    throw new PlanningException("description is not an object");
                }
                final InstalledPlanning.Description described =
                        InstalledPlanning.handshake(executable, map);
                return new Description(
                        name,
                        executable,
                        null,
                        described.version().isBlank() ? null : described.version(),
                        Integer.valueOf(described.protocolVersion()),
                        described.capabilities(),
                        described.credentials());
            } finally {
                process.destroyForcibly();
                reader.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PlanningException("Interrupted while describing " + executable, ex);
        } catch (TimeoutException ex) {
            return new Description(
                    name,
                    executable,
                    executable
                            + " did not describe itself within "
                            + InstalledPlanning.READY_SECONDS
                            + " seconds");
        } catch (ExecutionException ex) {
            final String reason =
                    ex.getCause() instanceof PlanningException failure
                            ? failure.getMessage()
                            : "description is not valid JSON";
            return refused(executable, reason == null ? "description failed" : reason);
        } catch (PlanningException ex) {
            return refused(executable, String.valueOf(ex.getMessage()));
        } catch (IOException | RuntimeException ex) {
            return refused(executable, "cannot start or read describe");
        }
    }

    private static Description refused(Path executable, String detail) {
        return new Description(
                executable.getFileName().toString(),
                executable,
                Wire.plain(
                        detail.startsWith(executable.toString() + " ")
                                ? detail
                                : executable + " could not describe itself: " + detail,
                        InstalledPlanning.MESSAGE));
    }
}
