package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ImageCostFieldSegmentSupportTest {
    @Test void completeAffineCellWalkAndSharedBudgetAreFactual() {
        var image=field(-1,-1); var budget=new ImageCostField.SegmentSupportBudget(100);
        assertEquals(ImageCostField.SegmentSupport.COMPLETE,image.certifySegmentSupport(
                new MetricPoint(.2,.4),new MetricPoint(4.2,3.4),budget,CancellationProbe.NONE));
        assertTrue(budget.visitedCells()>0);
        var exhausted=new ImageCostField.SegmentSupportBudget(1);
        assertEquals(ImageCostField.SegmentSupport.RESOURCE_LIMIT,image.certifySegmentSupport(
                new MetricPoint(.2,.4),new MetricPoint(4.2,3.4),exhausted,CancellationProbe.NONE));
    }
    @Test void invalidCellBetweenValidEndpointsIsRejected() {
        var image=field(2,2); var a=new MetricPoint(.1,1.9); var b=new MetricPoint(3.9,1.9);
        assertTrue(image.sample(a).isPresent());assertTrue(image.sample(b).isPresent());
        assertEquals(ImageCostField.SegmentSupport.UNAVAILABLE,image.certifySegmentSupport(a,b,
                new ImageCostField.SegmentSupportBudget(100),CancellationProbe.NONE));
    }
    @Test void exactCornerAndBoundaryUseConservativeSupercoverAndCancellation() {
        var image=field(2,0);
        assertEquals(ImageCostField.SegmentSupport.UNAVAILABLE,image.certifySegmentSupport(
                new MetricPoint(.1,.1),new MetricPoint(4.1,4.1),
                new ImageCostField.SegmentSupportBudget(100),CancellationProbe.NONE));
        assertEquals(ImageCostField.SegmentSupport.UNAVAILABLE,image.certifySegmentSupport(
                new MetricPoint(.1,.1),new MetricPoint(4.1,4.1-1e-9),
                new ImageCostField.SegmentSupportBudget(100),CancellationProbe.NONE));
        assertEquals(ImageCostField.SegmentSupport.UNAVAILABLE,field(-1,-1).certifySegmentSupport(
                new MetricPoint(0,1),new MetricPoint(0,4),
                new ImageCostField.SegmentSupportBudget(100),CancellationProbe.NONE));
        assertThrows(java.util.concurrent.CancellationException.class,()->image.certifySegmentSupport(
                new MetricPoint(.1,.1),new MetricPoint(4.1,4.1),
                new ImageCostField.SegmentSupportBudget(100),()->true));
    }
    @Test void shearedRotatedFrameUsesItsActualInverseAndCumulativeCellBudget() {
        var transform=new RasterMetricTransform("sheared-cell",RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(10,20),1,.2,-.3,1,1);
        var image=field(-1,-1,transform);var budget=new ImageCostField.SegmentSupportBudget(100);
        assertEquals(ImageCostField.SegmentSupport.COMPLETE,image.certifySegmentSupport(
                transform.pixelCenterToMetric(.2,.4),transform.pixelCenterToMetric(4.2,3.4),budget,CancellationProbe.NONE));
        long first=budget.visitedCells();
        assertEquals(ImageCostField.SegmentSupport.COMPLETE,image.certifySegmentSupport(
                transform.pixelCenterToMetric(4.2,3.4),transform.pixelCenterToMetric(.2,.4),budget,CancellationProbe.NONE));
        assertTrue(budget.visitedCells()>first);
    }
    private static ImageCostField field(int invalidX,int invalidY) {
        return field(invalidX,invalidY,new RasterMetricTransform("affine-cell",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,new MetricPoint(0,0),1,0,0,1,1));
    }
    private static ImageCostField field(int invalidX,int invalidY,RasterMetricTransform transform) {
        double[] values=new double[64];Arrays.fill(values,.8);
        boolean[] valid=new boolean[64];Arrays.fill(valid,true);
        if(invalidX>=0)valid[invalidY*8+invalidX]=false;
        var scalar=new ScalarEvidenceField(8,8,values,valid,new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "exact-cell",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        return new ImageCostField(scalar,transform,MetricRegion.rectangle(-100,-100,100,100),1);
    }
}
