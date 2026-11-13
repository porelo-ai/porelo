package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.fuin.porelo.wire.HelperPid;

/**
 * A task's helper as a unit test starts it: it records itself in its {@code --pid-file} as a helper
 * does, and blocks until it is stopped.
 *
 * <p><strong>Not this build's helpers.</strong> A gate served by this build's {@code porelo} took
 * the project of that name the account itself has, and outlived the test; one that never recorded
 * its pid made a start that waits for it wait out its patience and fail. The stand-in comes up at
 * once and serves nothing.
 *
 * <p>Each stand-in also records itself under its directory's {@code stand-ins}, so {@link #stop}
 * ends exactly the ones started there, by the identity they recorded, and nothing else of the
 * account's.
 */
final class HelperStandIn {

    /** How long a stand-in nobody stops waits before it exits on its own. */
    private static final int BLOCKS_SECONDS = 300;

    private HelperStandIn() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the shell lines that record the running shell as {@link HelperPid#record} would, in
     * the file after {@code --pid-file} when {@code recordsItsPid}, and in the registry always,
     * then block.
     *
     * @param registry Where every stand-in records itself.
     * @param recordsItsPid Whether it writes its {@code --pid-file}; one that does not never comes
     *     up.
     * @return The lines, ending in an {@code exec}.
     */
    static String lines(final Path registry, final boolean recordsItsPid) {
        return """
               pid_file=
               while [ $# -gt 0 ]; do
                 [ "$1" = --pid-file ] && pid_file=$2
                 shift
               done
               read -r stat < /proc/$$/stat
               set -- ${stat##*) }
               started=$(( $(sed -n 's/^btime //p' /proc/stat) * 1000 + ${20} * 1000 / $(getconf CLK_TCK) ))
               mkdir -p '%1$s'
               printf '%%s %%s' $$ "$started" > '%1$s'/$$
               %2$s
               exec sleep %3$d
               """
                .formatted(
                        registry,
                        recordsItsPid
                                ? "[ -n \"$pid_file\" ] && printf '%s %s' $$ \"$started\" >"
                                        + " \"$pid_file\""
                                : ": never records its pid",
                        BLOCKS_SECONDS);
    }

    /**
     * Writes a stand-in program into a directory.
     *
     * @param directory Where it goes, with its registry.
     * @param recordsItsPid Whether it writes its {@code --pid-file}.
     * @return The program.
     * @throws IOException If it cannot be written.
     */
    static Path write(final Path directory, final boolean recordsItsPid) throws IOException {
        Files.createDirectories(directory);
        final Path program =
                directory.resolve(recordsItsPid ? "helper-stand-in" : "silent-helper-stand-in");
        Files.writeString(
                program,
                "#!/bin/sh\n" + lines(registry(directory), recordsItsPid),
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return program;
    }

    /**
     * Returns where the stand-ins of a directory record themselves.
     *
     * @param directory The directory.
     * @return The registry.
     */
    static Path registry(final Path directory) {
        return directory.resolve("stand-ins");
    }

    /**
     * Returns whether any stand-in of a directory still runs.
     *
     * @param directory The directory.
     * @return Whether one does.
     * @throws IOException If the registry cannot be read.
     */
    static boolean anyRunning(final Path directory) throws IOException {
        return running(directory) > 0;
    }

    /**
     * Returns how many stand-ins of a directory still run.
     *
     * @param directory The directory.
     * @return How many do.
     * @throws IOException If the registry cannot be read.
     */
    static long running(final Path directory) throws IOException {
        final Path registry = registry(directory);
        if (!Files.isDirectory(registry)) {
            return 0;
        }
        try (Stream<Path> recorded = Files.list(registry)) {
            return recorded.filter(file -> HelperPid.verified(file).isPresent()).count();
        }
    }

    /**
     * Returns how many stand-ins of a directory were started.
     *
     * @param directory The directory.
     * @return How many recorded themselves.
     * @throws IOException If the registry cannot be read.
     */
    static long started(final Path directory) throws IOException {
        final Path registry = registry(directory);
        if (!Files.isDirectory(registry)) {
            return 0;
        }
        try (Stream<Path> recorded = Files.list(registry)) {
            return recorded.count();
        }
    }

    /**
     * Stops every stand-in of a directory that still runs, by the identity it recorded.
     *
     * @param directory The directory.
     * @throws IOException If the registry cannot be read.
     */
    static void stop(final Path directory) throws IOException {
        final Path registry = registry(directory);
        if (!Files.isDirectory(registry)) {
            return;
        }
        try (Stream<Path> recorded = Files.list(registry)) {
            for (final Path file : recorded.toList()) {
                HelperPid.verified(file).ifPresent(HelperPid::stop);
                Files.deleteIfExists(file);
            }
        }
    }
}
