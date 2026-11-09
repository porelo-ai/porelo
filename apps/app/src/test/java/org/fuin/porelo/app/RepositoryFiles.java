package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Architecture scans never enter build output or another checkout kept in a hidden directory. */
final class RepositoryFiles {
    private RepositoryFiles() {}

    static List<Path> of(Path root) throws IOException {
        final List<Path> files = new ArrayList<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) {
                        final String name = directory.getFileName().toString();
                        return !directory.equals(root)
                                        && (name.equals("target") || name.startsWith("."))
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        files.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
        return List.copyOf(files);
    }
}
