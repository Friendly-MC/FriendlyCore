package dev.lyy.friendlycore.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledYamlTest {

    @Test
    void everyBundledYamlFileParses() throws Exception {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                checked++;
                try {
                    ConfigService.loadStrict(file.toFile());
                } catch (Exception e) {
                    broken.add(file + ": " + e.getMessage());
                }
            }
        }
        assertTrue(checked > 50, "expected bundled yml files, found " + checked);
        assertEquals(List.of(), broken);
    }
}
