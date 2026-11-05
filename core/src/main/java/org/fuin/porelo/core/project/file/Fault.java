package org.fuin.porelo.core.project.file;

/**
 * One thing wrong with a file's model, at the key it is about.
 *
 * @param path The key's full path, such as {@code repositories.backend.central}.
 * @param message What is wrong, and where it can say so, what to write instead.
 */
public record Fault(String path, String message) {}
