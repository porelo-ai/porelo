package org.fuin.porelo.acceptance.suite;

import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.acceptance.World;
import org.jspecify.annotations.Nullable;

/**
 * The tasks the suite's own steps started in a scenario, by the name a step gives each, and their
 * removal at the scenario's end, whether it passed or not; with the checks every such step puts a
 * name or a list through before it reaches a command.
 *
 * <p>One per scenario, shared by every steps class that names tasks, so a task one class started is
 * found by another and removed once.
 */
public final class ScenarioTasks {

    /** What a task's, project's or transport's name in a step may be: nothing a shell reads. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final World world;

    /** Each task by its name: its container. */
    private final Map<String, String> containers = new LinkedHashMap<>();

    /**
     * Creates the tasks of the scenario's world.
     *
     * @param world The scenario's world.
     */
    public ScenarioTasks(World world) {
        this.world = world;
    }

    /**
     * Records a task a step started or saw started.
     *
     * @param task Its name.
     * @param container Its container.
     */
    void started(String task, String container) {
        containers.put(task, container);
    }

    /**
     * Returns the container of a task the steps recorded.
     *
     * @param task Its name.
     * @return The container.
     * @throws AssertionError When no step recorded it.
     */
    String container(String task) {
        final @Nullable String container = containers.get(task);
        if (container == null) {
            throw new AssertionError("This scenario started no task called " + task);
        }
        return container;
    }

    /**
     * Removes every task the steps recorded, whether the scenario passed or not. Best effort,
     * logged: a removal that fails must not hide why the scenario did.
     *
     * @param scenario The scenario that ended.
     */
    @After
    public void removeTheTasks(Scenario scenario) {
        for (final String container : containers.values()) {
            try {
                world.run("porelo task remove " + quote(container) + " --force > /dev/null 2>&1");
            } catch (final IOException | RuntimeException ex) {
                scenario.log("could not remove " + container + ": " + ex.getMessage());
            }
        }
        containers.clear();
    }

    /**
     * Refuses a name a step would put into a command or a path as more than a name.
     *
     * @param name The name.
     * @return The name.
     */
    static String name(String name) {
        if (!isName(name)) {
            throw new IllegalArgumentException(
                    "Not a name: '" + name + "'. Lower-case letters, digits, '.', '_' and '-'.");
        }
        return name;
    }

    /**
     * Tells whether a text is a name a step may put into a command or a path.
     *
     * @param text The text.
     * @return Whether it is.
     */
    static boolean isName(String text) {
        return NAME.matcher(text).matches();
    }

    /**
     * Returns the words of a step, separated by commas, such as rights or tools.
     *
     * @param named The step's words.
     * @return The words, in order.
     */
    static List<String> words(String named) {
        return Arrays.stream(named.split(","))
                .map(String::strip)
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /**
     * Quotes a value for the shell as one word.
     *
     * @param value The value.
     * @return It, quoted.
     */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
