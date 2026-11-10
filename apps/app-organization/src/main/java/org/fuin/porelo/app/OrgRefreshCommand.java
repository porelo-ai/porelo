package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** A detached display refresh; its result grants no authority to a caller. */
@Command(name = "refresh-view", hidden = true)
public class OrgRefreshCommand extends OrgReading {
    private static final Duration RETRY = Duration.ofSeconds(10);

    Duration limit = Duration.ofMinutes(2);

    @Option(names = "--repository", required = true)
    private String repository = "";

    @Option(names = "--after", required = true)
    private String after = "";

    @Override
    Map<String, @Nullable Object> answer(PoreloContext machine) throws IOException {
        final var worker =
                Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon(true).name("organization-refresh").factory());
        final var future = worker.submit(() -> refresh(machine));
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed();
        } catch (TimeoutException ex) {
            throw failed();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Refused refused) throw refused;
            throw failed();
        } finally {
            future.cancel(true);
            worker.shutdownNow();
            boolean interrupted = Thread.interrupted();
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (!worker.isTerminated() && System.nanoTime() < until) {
                    try {
                        worker.awaitTermination(
                                Math.max(1, until - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (!worker.isTerminated()) throw failed();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private Map<String, @Nullable Object> refresh(PoreloContext machine) throws IOException {
        if (!repository.equals(OrgAccess.repository(machine).orElse(null)))
            return outcome("superseded");
        final Path state = machine.paths().xdg().state().resolve("organization");
        return FileLocks.holdingIfFree(
                        state.resolve("refresh.lock"),
                        () -> {
                            if (!repository.equals(OrgAccess.repository(machine).orElse(null)))
                                return outcome("superseded");
                            final OrgAccess.Opened opened = opener.open(machine, repository);
                            final Optional<GitTreeStore.Checked> last =
                                    opened.store().lastChecked();
                            if (last.isPresent()
                                    && last.orElseThrow().checkedAt() != null
                                    && !String.valueOf(last.orElseThrow().checkedAt())
                                            .equals(after)) {
                                return outcome("refreshed");
                            }
                            final Path receipt = state.resolve("refresh.json");
                            if (recent(receipt)) return outcome("joined");
                            try {
                                final Optional<OrganizationQuestions> refreshed =
                                        opened.store()
                                                .refresh(
                                                        opened.identities(),
                                                        () ->
                                                                repository.equals(
                                                                        OrgAccess.repository(
                                                                                        machine)
                                                                                .orElse(null)));
                                if (refreshed.isEmpty()) return outcome("superseded");
                                final OrganizationQuestions read = refreshed.orElseThrow();
                                final String result = read.whole() ? "refreshed" : "incomplete";
                                receipt(receipt, result);
                                if (!read.whole()) throw failed();
                                return outcome(result);
                            } catch (IOException | RuntimeException ex) {
                                // Remote diagnostics can quote credentials; the helper keeps only a
                                // safe outcome.
                                receipt(receipt, "failed");
                                throw failed();
                            }
                        })
                .orElseGet(() -> outcome("joined"));
    }

    private boolean recent(Path receipt) throws IOException {
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) || Files.size(receipt) > 16384)
            return false;
        try {
            final Object parsed = Json.parse(Files.readString(receipt));
            return parsed instanceof Map<?, ?> held
                    && repository.equals(held.get("repository"))
                    && after.equals(held.get("after"))
                    && held.get("attempted_at") instanceof String when
                    && Duration.between(Instant.parse(when), Instant.now()).compareTo(RETRY) < 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void receipt(Path file, String result) throws IOException {
        final Path staged =
                Files.createTempFile(
                        file.getParent(),
                        ".refresh-",
                        ".json",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(
                    staged,
                    Json.write(
                            Map.of(
                                    "repository",
                                    repository,
                                    "after",
                                    after,
                                    "attempted_at",
                                    Instant.now().toString(),
                                    "outcome",
                                    result)));
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static Map<String, @Nullable Object> outcome(String result) {
        return Map.of("outcome", result);
    }

    private static Refused failed() {
        return new Refused(
                Refused.CANNOT_SAY,
                "the organization refresh did not complete; the display stays stale. Run porelo org --refresh to try again",
                null);
    }

    @Override
    void text(Map<String, @Nullable Object> answer) {
        line("Organization refresh: " + answer.get("outcome"));
    }
}
