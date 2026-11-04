package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Public synthetic historical R4 source; construction never executes the optional A graph. */
final class NativeAOriginalR4Fixture implements AutoCloseable {
    final PhysicalGeometryFixture.R4SyntheticFrame source=PhysicalGeometryFixture.r4SyntheticFrame(4);
    final AttemptMemoryLedger ledger=AttemptMemoryLedger.production();
    final AttemptMemoryLedger.Owner root=ledger.rootOwner();
    final SampledEmissionInput input;
    final double[] pixels;
    NativeAOriginalR4Fixture(){this(CancellationProbe.NONE);}
    NativeAOriginalR4Fixture(CancellationProbe cancellation) {
        int w=93,h=86;pixels=new double[w*h];boolean[] valid=new boolean[pixels.length];Arrays.fill(valid,true);
        var full=source.fullScalarGrid().copiedValues();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels[y*w+x]=full[(y+17)*128+x+18];
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(18,17),1,0,0,1);
        var field=new ScalarEvidenceField(w,h,pixels,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var bounds=MetricRegion.rectangle(18,17,110,102);
        var evidence=new EvidenceSnapshot("native-connector-test",frame,transform,EvidenceResolution.nativeSource(1,1),bounds,bounds,
            Map.of("native",field),"declared-only");
        var locations=new ArrayList<DetachedProfileSamplingLocation>();var distances=new ArrayList<Double>();
        double d=0;RasterPoint previous=null;
        for(int j=-9;j<=9;j++) {
            var raster=new RasterPoint(64+j-18,64-9.7+j*j/8.-17);
            if(previous!=null)d+=Math.hypot(raster.x()-previous.x(),raster.y()-previous.y());
            var metric=transform.pixelCenterToMetric(raster.x(),raster.y());
            locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(metric),metric,raster,frame,transform,d));
            distances.add(d);previous=raster;
        }
        var trace=new CorridorTraceInput(locations,1);var chainage=new ProfileChainage(distances,1);
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),cancellation);
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"native",trace,chainage,25,budget,root)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"native",trace.profileLocations(),25,1,1,budget,permit);
            var truth=new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(source.fullScalarGrid(),18,17,1,0,List.of());
            var geometry=OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit,retained,source.profiles().subList(1,18),
                java.util.stream.IntStream.range(1,18).boxed().toList(),1,truth);
            input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,geometry);
        }
    }
    @Override public void close(){input.close();root.close();assertEquals(0,ledger.currentBytes());}
}
