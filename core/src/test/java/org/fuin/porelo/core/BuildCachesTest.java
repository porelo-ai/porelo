package org.fuin.porelo.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The build takes what it downloads from outside GitHub from the Actions cache, and each cache
 * keeps only its newest entry.
 *
 * <p>An entry is never deleted by the build: each key names what it holds (a digest, a hash of the
 * files that pin it, or a day), so a moved pin is saved under a new key and the old entry is no
 * longer read, and GitHub drops an entry seven days after its last read. A {@code restore-keys}
 * prefix reads the newest entry under it, never an older one.
 *
 * <p>Tagged {@code documents}, so the shared rules check runs it on a change to {@code .github/}
 * alone, which starts no build.
 */
@Tag("documents")
class BuildCachesTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    private static final String MUSL_CACHE = "musl-cache";

    @Test
    void theMuslToolchainIsRestoredAndFetchedOnlyOnAMiss() throws IOException {
        final List<Map<?, ?>> steps = steps((Map<?, ?>) jobs(workflow()).get("native"));
        final int restore = indexOf(steps, "id", MUSL_CACHE);
        final int install = indexOf(steps, "name", "The musl toolchain");
        final int build = indexOf(steps, "name", "Build the native binaries");
        assertThat(restore).as("the restore").isNotNegative().isLessThan(install);
        final Map<?, ?> restored = steps.get(restore);
        assertThat(String.valueOf(restored.get("uses")))
                .as("restored by the cache's restore action alone")
                .startsWith("actions/cache/restore@");
        assertThat(with(restored).containsKey("restore-keys")).as("an exact key alone").isFalse();

        final String onAMiss = "steps." + MUSL_CACHE + ".outputs.cache-hit != 'true'";
        assertThat(steps.get(install).get("if"))
                .as("the install runs on a miss alone")
                .isEqualTo(onAMiss);

        final int save = install + 1;
        assertThat(save).as("saved right after the install, before the build").isLessThan(build);
        final Map<?, ?> saved = steps.get(save);
        assertThat(String.valueOf(saved.get("uses")))
                .as("saved by the cache's save action alone")
                .startsWith("actions/cache/save@");
        assertThat(saved.get("if")).as("saved on a miss alone").isEqualTo(onAMiss);
        assertThat(with(saved).get("key"))
                .as("saved under the key it is restored by")
                .isEqualTo(with(restored).get("key"));
        assertThat(with(saved).get("path"))
                .as("saved from the folder it is restored to")
                .isEqualTo(with(restored).get("path"));

        assertThat(String.valueOf(with(restored).get("key")))
                .as("keyed by the toolchain's and zlib's pinned digests")
                .matches("musl-\\$\\{\\{ runner\\.os }}-[0-9a-f]{64}-[0-9a-f]{64}");
        assertThat(with(restored).get("path"))
                .as("where the pom looks for it")
                .isEqualTo(pomMuslHome().replace("${user.home}", "~"));
    }

    @Test
    void everyCacheKeepsOnlyItsNewestEntry() throws IOException {
        final List<Map<?, ?>> caches = new ArrayList<>();
        jobs(workflow()).values().forEach(job -> caches.addAll(caches(steps((Map<?, ?>) job))));
        caches.addAll(
                caches(
                        steps(
                                (Map<?, ?>)
                                        yaml(ROOT.resolve(".github/actions/pinned-jdk/action.yml"))
                                                .get("runs"))));
        assertThat(caches).as("the caches found").hasSizeGreaterThanOrEqualTo(10);
        for (final Map<?, ?> cache : caches) {
            final String key = String.valueOf(with(cache).get("key"));
            assertThat(key)
                    .as("a key names what it holds: " + key)
                    .matches(".*([0-9a-f]{64}|\\$\\{\\{ (hashFiles|steps)\\(?[^}]*}}).*");
            final Object prefix = with(cache).get("restore-keys");
            if (prefix != null) {
                assertThat(key)
                        .as("restore-keys is the key's own prefix")
                        .startsWith((String) prefix);
            }
        }
    }

    private static List<Map<?, ?>> caches(final List<Map<?, ?>> steps) {
        return steps.stream()
                .filter(step -> String.valueOf(step.get("uses")).startsWith("actions/cache"))
                .toList();
    }

    private static int indexOf(
            final List<Map<?, ?>> steps, final String field, final String value) {
        for (int i = 0; i < steps.size(); i++) {
            if (value.equals(steps.get(i).get(field))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<?, ?> with(final Map<?, ?> step) {
        return (Map<?, ?>) step.get("with");
    }

    private static List<Map<?, ?>> steps(final Map<?, ?> job) {
        final List<Map<?, ?>> steps = new ArrayList<>();
        for (final Object step : (List<?>) job.get("steps")) {
            steps.add((Map<?, ?>) step);
        }
        return steps;
    }

    private static String pomMuslHome() throws IOException {
        final Matcher matcher =
                Pattern.compile("<musl\\.home>([^<]+)</musl\\.home>")
                        .matcher(Files.readString(ROOT.resolve("pom.xml")));
        assertThat(matcher.find()).as("the pom names musl.home").isTrue();
        return matcher.group(1);
    }

    private static Map<?, ?> jobs(final Map<?, ?> workflow) {
        return (Map<?, ?>) workflow.get("jobs");
    }

    private static Map<?, ?> workflow() throws IOException {
        return yaml(ROOT.resolve(".github/workflows/build.yml"));
    }

    private static Map<?, ?> yaml(final Path file) throws IOException {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
    }
}
