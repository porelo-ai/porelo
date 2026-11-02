package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.forge.forgejo.model.CreateRepoOption;
import org.fuin.porelo.forge.forgejo.model.Repository;
import org.fuin.porelo.forge.github.model.FullRepository;
import org.fuin.porelo.forge.github.model.ReposCreateInOrgRequest;
import org.junit.jupiter.api.Test;

/** The types the forge clients write and read reflectively, declared to the native image. */
class ForgeClientMetadataTest {

    private static final Path METADATA =
            Path.of(
                    "src/main/resources/META-INF/native-image",
                    "org.fuin.porelo/porelo-app/reachability-metadata.json");

    @Test
    void everyTypeOfMakingARepositoryIsDeclaredToTheNativeImage() throws IOException {

        // Found by running the binary: every JVM test passed, while 'project open' in the native
        // image could not write its request and said the forge had not answered. The clients ship
        // no metadata of their own, and an undeclared record has no properties for Jackson there.
        final String metadata = Files.readString(METADATA, StandardCharsets.UTF_8);
        final List<String> missing =
                reachable(
                                CreateRepoOption.class,
                                Repository.class,
                                ReposCreateInOrgRequest.class,
                                FullRepository.class)
                        .stream()
                        .filter(type -> !metadata.contains('"' + type + '"'))
                        .toList();
        assertThat(missing)
                .as(
                        "forge types absent from %s, so 'project open' cannot make a repository"
                                + " in the native image",
                        METADATA)
                .isEmpty();
    }

    private static Set<String> reachable(final Class<?>... roots) {
        final Set<String> seen = new TreeSet<>();
        final Deque<Type> open = new ArrayDeque<>(List.of(roots));
        while (!open.isEmpty()) {
            final Type type = open.pop();
            if (type instanceof ParameterizedType parameterized) {
                open.addAll(List.of(parameterized.getActualTypeArguments()));
            } else if (type instanceof WildcardType wildcard) {
                open.addAll(List.of(wildcard.getUpperBounds()));
            } else if (type instanceof Class<?> named
                    && named.getName().startsWith("org.fuin.porelo.forge.")
                    && seen.add(named.getName())
                    && named.isRecord()) {
                for (final RecordComponent component : named.getRecordComponents()) {
                    open.add(component.getGenericType());
                }
            }
        }
        return seen;
    }
}
