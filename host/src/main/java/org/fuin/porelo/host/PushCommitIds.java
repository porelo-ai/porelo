package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

/** Checks uniqueness on disk, retaining at most one small run of object names in memory. */
final class PushCommitIds {
    private static final int RUN = 4096;
    private final Path directory;
    private final long deadline;

    PushCommitIds(Path directory, long deadline) {
        this.directory = directory;
        this.deadline = deadline;
    }

    void check(Path ids) throws IOException {
        long runs = 0;
        try (var input = Files.newBufferedReader(ids)) {
            final var chunk = new ArrayList<String>(RUN);
            String line;
            while ((line = input.readLine()) != null) {
                checkDeadline();
                chunk.add(line);
                if (chunk.size() == RUN) {
                    write(chunk, run(0, runs++));
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) write(chunk, run(0, runs++));
        }
        int level = 0;
        while (runs > 1) {
            long next = 0;
            for (long index = 0; index < runs; index += 2) {
                checkDeadline();
                final Path first = run(level, index);
                final Path destination = run(level + 1, next++);
                if (index + 1 == runs) Files.move(first, destination);
                else {
                    final Path second = run(level, index + 1);
                    merge(first, second, destination);
                    Files.delete(first);
                    Files.delete(second);
                }
            }
            level++;
            runs = next;
        }
        if (runs == 0) return;
        try (var input = Files.newBufferedReader(run(level, 0))) {
            String previous = "";
            String line;
            while ((line = input.readLine()) != null) {
                checkDeadline();
                if (line.equals(previous)) throw new IOException("Duplicate commit verdict");
                previous = line;
            }
        }
    }

    private Path run(int level, long index) {
        return directory.resolve("ids-" + level + "-" + index);
    }

    private static void write(ArrayList<String> chunk, Path path) throws IOException {
        chunk.sort(Comparator.naturalOrder());
        Files.write(path, chunk);
    }

    private void merge(Path first, Path second, Path destination) throws IOException {
        try (var left = Files.newBufferedReader(first);
                var right = Files.newBufferedReader(second);
                var output = Files.newBufferedWriter(destination)) {
            String a = left.readLine();
            String b = right.readLine();
            while (a != null || b != null) {
                checkDeadline();
                if (b == null || a != null && a.compareTo(b) <= 0) {
                    output.write(java.util.Objects.requireNonNull(a));
                    a = left.readLine();
                } else {
                    output.write(b);
                    b = right.readLine();
                }
                output.newLine();
            }
        }
    }

    void checkDeadline() throws IOException {
        if (deadline - System.nanoTime() <= 0) throw new IOException("Push judgement timed out");
    }
}
