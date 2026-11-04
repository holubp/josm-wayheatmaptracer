package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class PolylineMathSourceParameterTest {
    @Test void retainsNativeEndpointRoundingWithoutChangingItsPoint() {
        var source = List.of(new EastNorth(0, 0), new EastNorth(7, 8), new EastNorth(12, 16));
        for (int count : List.of(3, 4)) {
            var nativeRows = PolylineMath.resampleByCount(source, count);
            var parameters = PolylineMath.resampleByCountWithParameters(source, count);
            assertTrue(parameters.get(count - 1).fraction() > 1.0,
                    "The original binary64 fraction remains diagnostic evidence");
            for (int row = 0; row < count; row++) {
                assertEquals(Double.doubleToLongBits(nativeRows.get(row).east()),
                        Double.doubleToLongBits(parameters.get(row).point().east()));
                assertEquals(Double.doubleToLongBits(nativeRows.get(row).north()),
                        Double.doubleToLongBits(parameters.get(row).point().north()));
            }
        }
    }
    @Test void recordsExactLegacyInterpolationOnAnAcuteCornerAndZeroLengthStart() {
        List<EastNorth> source = List.of(new EastNorth(0, 0), new EastNorth(0, 0),
                new EastNorth(11, 0), new EastNorth(11, 13), new EastNorth(0, 14));
        var expected = PolylineMath.resampleByCount(source, 31);
        var parameters = PolylineMath.resampleByCountWithParameters(source, 31);
        assertEquals(expected.size(), parameters.size());
        boolean cornerCrossed = false;
        int previousSegment = -1;
        for (int row = 0; row < parameters.size(); row++) {
            var parameter = parameters.get(row);
            assertEquals(Double.doubleToLongBits(expected.get(row).east()),
                    Double.doubleToLongBits(parameter.point().east()));
            assertEquals(Double.doubleToLongBits(expected.get(row).north()),
                    Double.doubleToLongBits(parameter.point().north()));
            assertTrue(parameter.segmentIndex() >= previousSegment);
            cornerCrossed |= parameter.segmentIndex() > previousSegment && previousSegment >= 0;
            previousSegment = parameter.segmentIndex();
        }
        assertEquals(0, parameters.get(0).segmentIndex());
        assertEquals(0.0, parameters.get(0).fraction());
        assertTrue(cornerCrossed);
        assertEquals(source.get(source.size() - 1), parameters.get(parameters.size() - 1).point());
    }

    @Test void refusesAmbiguousDegenerateSource() {
        assertThrows(IllegalArgumentException.class, () -> PolylineMath.resampleByCountWithParameters(
                List.of(new EastNorth(1, 2), new EastNorth(1, 2)), 5));
    }
}
