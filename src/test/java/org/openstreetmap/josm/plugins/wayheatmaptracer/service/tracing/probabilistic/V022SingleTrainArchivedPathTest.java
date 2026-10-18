package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

class V022SingleTrainArchivedPathTest {
    @SuppressWarnings("unchecked") @Test void exactArchivedIndicesDecodeOnlyAgainstTheirBoundOriginalGrid() throws Exception {
        Set<List<?>> classes=new HashSet<>();
        for(var entry:BSingleTrainDiagnosticManifest.RECEIPTS.entrySet()) {
            byte[] bytes;
            try(var stream=getClass().getResourceAsStream("/b-single-train-geometry/"+entry.getKey()+".json")){assertNotNull(stream);bytes=stream.readAllBytes();}
            assertEquals(entry.getValue(),BIdentificationReceipt.hash(bytes));
            var receipt=BIdentificationReceipt.parse(bytes);var input=(Map<String,Object>)receipt.get("input");
            var raw=(Map<String,Object>)receipt.get("rawGeometry");var indices=(List<Number>)raw.get("mapStateIndices");classes.add(indices);
            List<List<MetricPoint>> rows=new ArrayList<>();
            for(int i=0;i<81;i++){var row=new ArrayList<MetricPoint>();for(int s=0;s<36;s++)row.add(new MetricPoint(i,s));rows.add(row);}
            var points=BSingleTrainGeometryDiagnosticTool.decode(entry.getKey(),bytes,input,rows);
            assertEquals(81,points.size());
            for(int i=0;i<81;i++)assertSame(rows.get(i).get(indices.get(i).intValue()),points.get(i));
            var changed=new TreeMap<>(input);changed.put("factsHash","0".repeat(64));
            assertThrows(SecurityException.class,()->BSingleTrainGeometryDiagnosticTool.decode(entry.getKey(),bytes,changed,rows));
            byte[] tampered=bytes.clone();tampered[0]=' ';
            assertThrows(SecurityException.class,()->BSingleTrainGeometryDiagnosticTool.decode(entry.getKey(),tampered,input,rows));
            var shortRow=new ArrayList<>(rows);shortRow.set(1,List.of(new MetricPoint(1,0)));
            assertThrows(IllegalArgumentException.class,()->BSingleTrainGeometryDiagnosticTool.decode(entry.getKey(),bytes,input,shortRow));
        }
        assertEquals(2,classes.size());
    }
}
