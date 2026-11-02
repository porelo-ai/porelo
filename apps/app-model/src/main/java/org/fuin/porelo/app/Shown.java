package org.fuin.porelo.app;

import java.util.List;

/**
 * What a refusal shows at once where it would name the command that shows it: the tasks there are,
 * the projects placed, why a placed project is not in force. The answer was known; making a person
 * type the command to read it was a step with no decision in it.
 */
public final class Shown {

    private Shown() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a list said in one line, or what is said when it is empty.
     *
     * @param what What the names are, as the line begins.
     * @param names The names.
     * @param none What is said when there are none.
     * @return One line.
     */
    static String names(final String what, final List<String> names, final String none) {
        return names.isEmpty() ? none : what + ": " + String.join(", ", names);
    }

    /**
     * Returns the tasks on this machine, said in one line.
     *
     * @param context The machine.
     * @return One line.
     */
    public static String tasks(final PoreloContext context) {
        return names(
                "the tasks here",
                new TaskInventory(context).tasks().stream().map(TaskInventory.Task::name).toList(),
                "there is no task here");
    }
}
