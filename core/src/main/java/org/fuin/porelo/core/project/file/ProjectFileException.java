package org.fuin.porelo.core.project.file;

import java.util.List;
import org.fuin.porelo.core.project.ProjectException;

/** A file was refused: every fault found, each naming the file, its line and its key. */
public class ProjectFileException extends ProjectException {

    private static final long serialVersionUID = 1L;

    private final String[] faults;

    /**
     * Constructor.
     *
     * @param faults Every fault, each as {@code <file>:<line>: <what is wrong>}.
     */
    public ProjectFileException(final List<String> faults) {
        super(String.join("\n", faults));
        this.faults = faults.toArray(String[]::new);
    }

    /**
     * Returns every fault found.
     *
     * @return The faults, in the order found.
     */
    public List<String> faults() {
        return List.of(faults);
    }
}
