package com.edem.dedup4j.core;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderDependencyBoundaryTest {

    private static final Map<String, String> PROVIDER_OWNERS = Map.of(
            "software.amazon.awssdk", "dedup4j-storage-s3",
            "com.azure", "blob-helper-storage-azure"
    );

    private static final Set<String> STARTER_PROVIDER_ADAPTERS = Set.of(
            "com.edem:dedup4j-storage-local",
            "com.edem:dedup4j-storage-s3",
            "com.edem:blob-helper-storage-azure"
    );

    @Test
    void providerSdksStayInProviderModules() throws Exception {
        Path rootPom = reactorRootPom();
        List<ModulePom> modulePoms = reactorPoms(rootPom);
        List<String> violations = new ArrayList<>();
        Set<String> observedProviderGroups = new HashSet<>();

        for (ModulePom modulePom : modulePoms) {
            for (Dependency dependency : declaredDependencies(modulePom.path())) {
                String expectedOwner = PROVIDER_OWNERS.get(dependency.groupId());
                if (expectedOwner == null) {
                    continue;
                }
                observedProviderGroups.add(dependency.groupId());
                if (!expectedOwner.equals(modulePom.name())) {
                    violations.add(dependency.coordinate() + " declared by " + modulePom.name()
                            + ", expected only in " + expectedOwner);
                }
            }
        }

        ModulePom starter = modulePoms.stream()
                .filter(modulePom -> modulePom.name().equals("blob-helper-spring-boot-starter"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Starter POM is not part of the Maven reactor"));
        Set<String> starterDependencies = declaredDependencies(starter.path()).stream()
                .map(Dependency::coordinate)
                .collect(Collectors.toSet());
        STARTER_PROVIDER_ADAPTERS.stream()
                .filter(adapter -> !starterDependencies.contains(adapter))
                .map(adapter -> "Starter does not depend on provider adapter " + adapter)
                .forEach(violations::add);

        PROVIDER_OWNERS.keySet().stream()
                .filter(groupId -> !observedProviderGroups.contains(groupId))
                .map(groupId -> "No dependency found for provider SDK group " + groupId)
                .forEach(violations::add);

        assertTrue(violations.isEmpty(), () -> "Provider dependency boundary violations:\n"
                + String.join("\n", violations));
    }

    private static Path reactorRootPom() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path pom = directory.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    if (parse(pom).getElementsByTagName("modules").getLength() > 0) {
                        return pom;
                    }
                } catch (Exception failure) {
                    throw new IOException("Could not inspect Maven POM: " + pom, failure);
                }
            }
            directory = directory.getParent();
        }
        throw new IOException("Could not locate the Maven reactor root POM");
    }

    private static List<ModulePom> reactorPoms(Path rootPom) throws Exception {
        Document root = parse(rootPom);
        List<ModulePom> poms = new ArrayList<>();
        poms.add(new ModulePom("<root>", rootPom));

        NodeList modules = root.getElementsByTagName("module");
        for (int index = 0; index < modules.getLength(); index++) {
            String module = modules.item(index).getTextContent().trim();
            poms.add(new ModulePom(module, rootPom.getParent().resolve(module).resolve("pom.xml")));
        }
        return poms;
    }

    private static List<Dependency> declaredDependencies(Path pom) throws Exception {
        Document document = parse(pom);
        NodeList dependencyNodes = document.getElementsByTagName("dependency");
        List<Dependency> dependencies = new ArrayList<>();
        for (int index = 0; index < dependencyNodes.getLength(); index++) {
            Node dependency = dependencyNodes.item(index);
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            if (groupId != null && artifactId != null) {
                dependencies.add(new Dependency(groupId, artifactId));
            }
        }
        return dependencies;
    }

    private static Document parse(Path pom) throws Exception {
        if (!Files.isRegularFile(pom)) {
            throw new IOException("Missing Maven POM: " + pom);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream input = Files.newInputStream(pom)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static String childText(Node parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(name)) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private record ModulePom(String name, Path path) {
    }

    private record Dependency(String groupId, String artifactId) {

        private String coordinate() {
            return groupId + ":" + artifactId;
        }
    }
}
