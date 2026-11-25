package org.fuin.porelo.build.api.review;

import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the document named by the one argument in a JVM of its own, so logging starts fresh. */
final class ReadOnce {

    private ReadOnce() {}

    public static void main(final String[] args) throws Exception {
        SarifReading.read(Files.readAllBytes(Path.of(args[0])));
    }
}
