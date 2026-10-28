package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBAssessor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Section;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.*;

class HybridSectionTableModelTest {
    private static AssessedCandidate candidate(boolean available) {
        var points=List.of(new MetricPoint(0,0),new MetricPoint(10,0),new MetricPoint(20,0),new MetricPoint(30,0));
        var choices=new ArrayList<SectionChoice>();
        var rows=new ArrayList<SectionSupport>();
        var finding=new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.SEARCH_TRUNCATED,
                FinalGeometryEvaluator.Severity.REVIEW,0,3,0);
        for(int i=0;i<3;i++) {
            var section=new Section("part-"+i,"legacy",new OccurrenceRange(i+7,i+8),points.subList(i,i+2),
                    Map.of(i+7,0,i+8,1),false);
            var choice=new SectionChoice(section,new VertexRange(i,i+1),i==1?Source.LEGACY:Source.B,
                    i==1?Outcome.LEGACY_RETAINED:Outcome.B_REFINED,i==1?Reason.NO_ROUTE:Reason.B_REFINED,
                    i==1?Optional.empty():Optional.of("b"));
            choices.add(choice);
            rows.add(new SectionSupport(choice,10,available?OptionalDouble.of(9):OptionalDouble.empty(),
                    OptionalDouble.empty(),Collections.nCopies(20,finding)));
        }
        var candidate=new Candidate("hybrid","legacy",points,Map.of(7,0,8,1,9,2,10,3),choices);
        var ids=new ArrayList<FinalRoutePointId>();
        var assignments=new LinkedHashMap<FinalRoutePointId,MetricPoint>();
        for(int i=0;i<4;i++) {
            var id=new FinalRoutePointId.GeneratedCandidatePoint("hybrid",i);
            ids.add(id); assignments.put(id,points.get(i));
        }
        return new AssessedCandidate(candidate,ids,assignments,new FinalGeometryEvaluator.Result("hybrid",
                FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,List.of(finding),30,27,1,.1,0),rows,
                available?OptionalDouble.of(27):OptionalDouble.empty());
    }

    @Test void summaryAndRowsDescribeActualSourcesAndSeparateOriginalAndFinalRanges() {
        var model=new HybridSectionTableModel(); model.setCandidate(candidate(true));
        assertEquals("B refined 2 of 3 sections; 1 retained Legacy",model.summary());
        assertEquals(3,model.getRowCount());
        assertEquals("9.0 m of 10.0 m",model.getValueAt(1,2));
        assertTrue(model.getValueAt(1,3).toString().contains("Legacy retained"));
        assertEquals(new OccurrenceRange(8,9),model.originalRange(1));
        assertEquals(new VertexRange(1,2),model.selectedVertexRange(1));
        assertFalse(model.isCellEditable(1,3));
    }

    @Test void unavailableMeasurementsStayUnavailableAndTableWrapsInsideBoundedViewport() {
        var model=new HybridSectionTableModel(); model.setCandidate(candidate(false));
        assertEquals("Unavailable (10.0 m total)",model.getValueAt(0,2));
        var table=model.createTable();
        var renderer=table.getCellRenderer(0,4).getTableCellRendererComponent(table,
                model.getValueAt(0,4),false,false,0,4);
        var text=assertInstanceOf(JTextArea.class,renderer);
        assertTrue(text.getLineWrap()); assertTrue(text.getWrapStyleWord());
        assertTrue(table.getPreferredScrollableViewportSize().height<=200);
        assertEquals("Search truncated",model.getValueAt(0,4));
        model.setCandidate(null);
        assertEquals(0,model.getRowCount());
    }
}
