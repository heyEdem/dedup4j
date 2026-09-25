package com.edem.dedup4j.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreModuleBoundaryTest {

    private static final Map<String, String> FORBIDDEN_PACKAGES = Map.of(
            "org/springframework/", "Spring",
            "jakarta/persistence/", "JPA",
            "software/amazon/awssdk/", "AWS SDK",
            "com/azure/", "Azure SDK"
    );

    @Test
    void coreHasNoSpringJpaOrProviderDependencies() throws IOException {
        List<String> violations = classPathEntries()
                .flatMap(CoreModuleBoundaryTest::findForbiddenPackages)
                .toList();

        assertTrue(violations.isEmpty(), () -> "Forbidden core dependencies found:\n" + String.join("\n", violations));
    }

    private static Stream<Path> classPathEntries() {
        return Stream.of(
                        System.getProperty("surefire.test.class.path", ""),
                        System.getProperty("java.class.path", "")
                )
                .flatMap(value -> Arrays.stream(value.split(Pattern.quote(File.pathSeparator))))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .distinct();
    }

    private static Stream<String> findForbiddenPackages(Path classPathEntry) {
        try {
            if (Files.isDirectory(classPathEntry)) {
                return findForbiddenPackagesInDirectory(classPathEntry).stream();
            }
            if (classPathEntry.getFileName().toString().endsWith(".jar")) {
                return findForbiddenPackagesInJar(classPathEntry).stream();
            }
            return Stream.empty();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect classpath entry " + classPathEntry, exception);
        }
    }

    private static List<String> findForbiddenPackagesInDirectory(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            List<String> classNames = files
                    .filter(Files::isRegularFile)
                    .map(directory::relativize)
                    .map(Path::toString)
                    .map(name -> name.replace(File.separatorChar, '/'))
                    .filter(name -> name.endsWith(".class"))
                    .toList();

            return violationsFor(directory, classNames.stream());
        }
    }

    private static List<String> findForbiddenPackagesInJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return violationsFor(jarPath, jar.stream().map(entry -> entry.getName()));
        }
    }

    private static List<String> violationsFor(Path classPathEntry, Stream<String> classNames) {
        List<String> names = classNames.toList();
        return FORBIDDEN_PACKAGES.entrySet().stream()
                .filter(forbidden -> names.stream().anyMatch(name -> name.startsWith(forbidden.getKey())))
                .map(forbidden -> forbidden.getValue() + " classes in " + classPathEntry)
                .toList();
    }
}
