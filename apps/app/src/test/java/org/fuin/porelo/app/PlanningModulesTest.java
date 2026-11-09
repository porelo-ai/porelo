package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("documents")
class PlanningModulesTest {
    @Test
    void thePlanningStubBuildsItsNativeFixtureAndRetainsItsResources() throws Exception {
        Path root = Path.of("..", "..");
        var xml =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(root.resolve("planning/stub/pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        assertThat(
                        xpath.evaluate(
                                "/project/profiles/profile[id='native']/properties/porelo.cpu.images",
                                xml))
                .isEqualTo("porelo-planning-stub");
        assertThat(
                        xpath.evaluate(
                                "/project/profiles/profile[id='native']/build/plugins/plugin[artifactId='native-maven-plugin']/executions/execution/configuration/mainClass",
                                xml))
                .isEqualTo("org.fuin.porelo.planning.stub.StubPlanning");
        assertThat(
                        xpath.evaluate(
                                "/project/build/resources/resource[directory='src/main/resources']/directory",
                                xml))
                .isEqualTo("src/main/resources");
        assertThat(Files.readString(root.resolve("planning/api/LICENSE")))
                .contains("Apache License");
    }
}
