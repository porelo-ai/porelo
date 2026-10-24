package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fuin.porelo.wire.Json;

/** Publishes a start's phase whole so inventory readers never see half a transition. */
final class StartStepFile {
    private static final String NAME = "start-step.json";

    private final PrintWriter warnings;
    private final AtomicBoolean warned = new AtomicBoolean();

    StartStepFile(PrintWriter warnings) {
        this.warnings = warnings;
    }

    void write(Path directory, StartStep step) {
        try {
            final Path staged = Files.createTempFile(directory, ".start-step-", ".tmp");
            try {
                final Map<String, Object> record = new java.util.LinkedHashMap<>(step.asMap());
                final ProcessHandle launcher = ProcessHandle.current();
                record.put("pid", launcher.pid());
                record.put(
                        "startedAt",
                        launcher.info().startInstant().map(java.time.Instant::toString).orElse(""));
                Files.writeString(staged, Json.write(record));
                Files.move(
                        staged,
                        directory.resolve(NAME),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException | RuntimeException ex) {
            warn(ex);
        }
    }

    void clear(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve(NAME));
        } catch (IOException | RuntimeException ex) {
            warn(ex);
        }
    }

    String phase(Path directory) {
        final Path file = directory.resolve(NAME);
        try {
            if (Json.parse(Files.readString(file)) instanceof Map<?, ?> read
                    && read.get("text") instanceof String text
                    && !"DONE".equals(read.get("state"))
                    && !"FAILED".equals(read.get("state"))
                    && launcherAlive(read)) {
                return "WAITING".equals(read.get("state"))
                        ? text
                                + " - waiting on "
                                + (read.get("waitingOn") instanceof String dependency
                                        ? dependency
                                        : "")
                        : text;
            }
        } catch (NoSuchFileException ex) {
            return "";
        } catch (IOException | RuntimeException ex) {
            warn(ex);
        }
        return "";
    }

    private static boolean launcherAlive(Map<?, ?> record) {
        if (!(record.get("pid") instanceof Number pid)
                || !(record.get("startedAt") instanceof String startedAt)) {
            return false;
        }
        return ProcessHandle.of(pid.longValue())
                .filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .map(java.time.Instant::toString)
                .filter(startedAt::equals)
                .isPresent();
    }

    private void warn(Exception failure) {
        if (warned.compareAndSet(false, true)) {
            final String reason =
                    java.util.Objects.requireNonNullElse(
                            failure.getMessage(), failure.getClass().getSimpleName());
            warnings.println(
                    "porelo: warning: cannot record the start's step: "
                            + StartStep.cleanCause(reason));
            warnings.flush();
        }
    }
}
