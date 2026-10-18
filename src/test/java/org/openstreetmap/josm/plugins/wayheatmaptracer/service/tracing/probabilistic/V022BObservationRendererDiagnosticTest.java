package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V022BObservationRendererDiagnosticTest {
    @TempDir Path directory;

    @Test void freshOutputScopePreservesUnrelatedParentFiles() throws Exception {
        Path unrelated = directory.resolve("unrelated.txt");
        Files.writeString(unrelated, "preserve this");
        Path scope = BObservationRendererDiagnosticTool.prepareOutputDirectory(directory);
        assertEquals(directory.resolve("receipt"), scope);
        assertTrue(Files.isDirectory(scope));
        try (var files = Files.list(scope)) { assertEquals(0, files.count()); }
        assertEquals("preserve this", Files.readString(unrelated));
    }

    @Test void existingScopeCannotRelabelOrDeleteEarlierGeometry() throws Exception {
        Path scope = Files.createDirectory(directory.resolve("receipt"));
        Path binding = scope.resolve("binding.json");
        Path geometry = scope.resolve("bound-geometry.csv");
        Files.writeString(binding, "original binding");
        Files.writeString(geometry, "original public geometry");
        assertThrows(FileAlreadyExistsException.class,
                () -> BObservationRendererDiagnosticTool.prepareOutputDirectory(directory));
        assertEquals("original binding", Files.readString(binding));
        assertEquals("original public geometry", Files.readString(geometry));
    }
}
