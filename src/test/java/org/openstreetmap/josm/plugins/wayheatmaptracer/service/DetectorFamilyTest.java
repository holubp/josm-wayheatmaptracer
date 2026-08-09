package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests centralized source-palette compatibility metadata. */
class DetectorFamilyTest {
    @Test
    void hotFamilyIsNativeAndCrossPaletteMappingsAreAlternative() {
        assertTrue(DetectorFamily.isNative("hot", "hot"));
        assertTrue(DetectorFamily.isNative("hot", "hot-corridor"));
        assertFalse(DetectorFamily.isNative("hot", "bluered"));
        assertEquals(1, DetectorFamily.sourceTier("hot", "hot"));
        assertEquals(0, DetectorFamily.sourceTier("hot", "bluered"));
    }

    @Test
    void semanticDualPaletteVariantsStayInTheirNativeFamilies() {
        assertTrue(DetectorFamily.isNative("bluered", "bluered-combined"));
        assertTrue(DetectorFamily.isNative("gray", "gray-magenta"));
        assertTrue(DetectorFamily.isNative("blue", "blue-corridor"));
        assertTrue(DetectorFamily.isNative("purple", "purple-corridor"));
        assertTrue(DetectorFamily.isNative("purple", "purple-strict"));
        assertEquals(1, DetectorFamily.sourceTier("hot", "all-colors-combined"));
    }
}
