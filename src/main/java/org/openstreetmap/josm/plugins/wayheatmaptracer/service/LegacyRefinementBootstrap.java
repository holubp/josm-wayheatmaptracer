package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Lazy, admitted conversion of an actual retained original Legacy acquisition. Never runs Legacy again. */
public final class LegacyRefinementBootstrap {
    public record Seed(LiveBPreviewService.LegacyRootSeed facts, AlignmentResult original,
            AlignmentConfig config, TileHeatmapSampler.AcquisitionReceipt receipt,
            RefinementSourcePlanner.Settings settings, String selectedChoice,
            AttemptMemoryLedger.Owner memory, long payloadLimit) { }
    public record Root(RefinementRootAuthority authority, Stage stage,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture.CapturedSnapshot receipt) { }

    public Seed capture(LegacyReviewOwner review, TileFetchCoordinator coordinator, AttemptMemoryLedger.Owner memory) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Original facts require EDT");
        review.requireOriginalAttemptCurrent();
        var receipt=review.managedReceipt();
        if(receipt==null)throw new IllegalStateException("The original managed source receipt is unavailable");
        var original=review.originalResult();var config=review.originalConfig();
        receipt.requireCurrent(coordinator,original.tileMosaics());
        var mosaic=original.tileMosaics().require(AlignmentTileSourcePlan.from(config.heatmap()).selectedColor());
        double length=0;for(int i=1;i<original.sourcePolyline().size();i++)length+=original.sourcePolyline().get(i).distance(original.sourcePolyline().get(i-1));
        var shape=TileHeatmapSampler.legacySamplingShape(length,original.sourcePolyline().size(),mosaic.parameters());
        // This is the original reference decision window, not the native acquisition padding.
        double radius=shape.referenceHalfWidthPx()*RenderedHeatmapSampler.RASTER_SCALE/mosaic.virtualRasterScale()*mosaic.parameters().metersPerPixel();
        var seed=new LiveBPreviewService().captureLegacyRootSeed(review.dataSet(),original.selection(),config,radius,
            mosaic.parameters().sampleStepMeters(),review.sourceIdentity(),memory);
        long maximum=RefinementLimits.defaults().maxPayloadBytes();
        long estimate=RefinementPayload.estimateBytes(original);
        if(estimate>maximum)throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
        memory.reserve(estimate).adopt(original);
        var settings=RefinementSourcePlanner.Settings.capture(true,config.heatmap(),AlignmentService.alternativeDetectorMappings());
        review.requireOriginalAttemptCurrent();receipt.requireCurrent(coordinator,original.tileMosaics());
        return new Seed(seed.value(),original,config,receipt,settings,review.choiceId(review.selectedCandidate()),memory,maximum);
    }

    public Root complete(Seed seed,TileFetchCoordinator coordinator,CancellationProbe cancellation) {
        if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Original pixels require worker thread");
        cancellation.checkpoint();seed.receipt().requireCurrent(coordinator,seed.original().tileMosaics());
        var facts=seed.facts();var config=seed.config();var sourcePlan=AlignmentTileSourcePlan.from(config.heatmap());
        var mosaic=seed.original().tileMosaics().require(sourcePlan.selectedColor());
        var grid=ManagedModernPreviewSource.managedOutputGrid(facts.frame(),facts.sourceMetric(),mosaic.zoom(),facts.decisionRadiusMeters());
        long cells=Math.addExact((long)mosaic.image().getWidth()*mosaic.image().getHeight(),(long)grid.width()*grid.height());
        var scratch=seed.memory().reserve(Math.addExact(1048576L,Math.multiplyExact(cells,96L)));
        var retained=seed.memory().reserve(Math.addExact(1048576L,Math.multiplyExact((long)grid.width()*grid.height(),80L)));
        try {
            var valid=new boolean[Math.multiplyExact(mosaic.image().getWidth(),mosaic.image().getHeight())];Arrays.fill(valid,true);
            var mode=config.heatmap().intensitySamplingMode();if(mode==null)mode=IntensitySamplingMode.COLOR_MAPPING;
            final var intensity=mode;
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                mode.usesColorMapping()?EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING:EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                sourcePlan.selectedColor(),EvidenceCorrelationGroup.STRAVA_RENDERINGS,false);
            var field=RasterEvidenceCapture.FieldSpec.direct("native",argb->ManagedModernPreviewSource.scalarIntensity(argb,sourcePlan.selectedColor(),intensity),lineage);
            var transform=SupportedInputRasterTransform.webMercator(mosaic.zoom(),mosaic.originWorldPxX(),mosaic.originWorldPxY(),1.0);
            var evidence=new RasterEvidenceCapture().captureWithMetricSource(facts.network().snapshotId()+"/original-evidence",mosaic.image(),valid,
                facts.sourceGeographic(),facts.sourceMetric(),transform,grid,EvidenceResolution.nativeSource(mosaic.parameters().metersPerPixel(),grid.pitchMeters()),
                facts.decisionRadiusMeters(),facts.sourceIdentity(),EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,List.of(field),cancellation);
            var chainage=new ProbabilisticProfileFactory().profileChainage(facts.sourceMetric(),facts.sampleStepMeters());
            var request=new TraceRequest(facts.specification().selectedWayKey(),facts.specification().selectedRange(),TrackerMode.LEGACY_V02,
                config.heatmap().alignmentMode(),facts.specification().permissions(),TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),
                facts.network().snapshotId(),facts.network().canonicalHash(),facts.settingsHash(),"original-legacy-authority-v1","original-legacy-reference-window-v1",
                facts.sampleStepMeters(),chainage,evidence.resolution());
            var authority=new RefinementRootAuthority(facts.network(),request,evidence,config,facts.projectionCode(),facts.sourceIdentity(),facts.partition());
            var way=(DetachedWay)facts.network().primitives().get(request.selectedWayKey());
            var sourceNodes=way.nodeKeys().stream().map(key->{var n=(DetachedNode)facts.network().primitives().get(key);return new RefinementPayload.SourceNode(key.id(),n.coordinate().latitudeDegrees(),n.coordinate().longitudeDegrees());}).toList();
            var provenance=new RefinementPayload.SourceSelection(way.key().id(),request.selectedRange().firstIndex(),request.selectedRange().lastIndex(),sourceNodes,
                way.nodeKeys().subList(request.selectedRange().firstIndex(),request.selectedRange().lastIndex()+1).stream().map(PrimitiveKey::id).toList());
            var choices=new ArrayList<Candidate>();int index=0;
            for(var original:seed.original().candidates()) {
                cancellation.checkpoint();
                // Per-choice payload isolates duplicate native IDs without relabeling the native geometry.
                var one=new AlignmentResult(seed.original().selection(),null,List.of(original),seed.original().sourcePolyline(),original.finalPreviewPoints(),List.of(),null,null,
                    seed.original().detectorAttempts().stream().filter(a->a.candidateIds().contains(original.id())).toList(),List.of(original));
                var payload=RefinementPayload.captureOriginal(one,provenance,seed.payloadLimit());
                var projected=original.finalPreviewPoints();
                if(projected.size()<2)continue;
                var points=projected.stream().map(p->{var ll=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(facts.projectionCode()).eastNorth2latlon(p);return facts.frame().toMetric(new GeographicPoint(ll.lat(),ll.lon()));}).toList();
                var arcs=correspondence(facts,original,points);
                var palettes=one.detectorAttempts().stream().map(DetectorAttempt::sourcePalette).distinct().toList();
                if(palettes.size()!=1)throw new IllegalStateException("The original candidate source palette is ambiguous");
                var id=new CandidateId("legacy-choice-"+index++);
                choices.add(new Candidate(id,payload,arcs,facts.sourceIdentity()+"/"+id.value(),original.id(),palettes.get(0)));
            }
            var stage=Stage.root(choices,new CandidateId(seed.selectedChoice()));
            retained.adopt(authority);
            cancellation.checkpoint();seed.receipt().requireCurrent(coordinator,seed.original().tileMosaics());
            return new Root(authority,stage,facts.receipt());
        } finally {scratch.close();}
    }
    private static List<ParentArc> correspondence(LiveBPreviewService.LegacyRootSeed facts,CenterlineCandidate candidate,List<MetricPoint> points) {
        var range=facts.specification().selectedRange();var arcs=new ArrayList<ParentArc>();
        if(facts.network().closure().mayCreateNodes()==false) {
            if(points.size()!=range.size())throw new IllegalStateException("Original Move candidate occurrence count differs");
            for(int i=0;i<range.size();i++)arcs.add(new ParentArc(range.firstIndex()+i,Math.min(i,points.size()-2),i==points.size()-1?1:0));
            return arcs;
        }
        // Original precise replacement retains protected/shared identities at their actual candidate-owned targets.
        var way=(DetachedWay)facts.network().primitives().get(facts.specification().selectedWayKey());
        int previous=-1;
        for(int i=0;i<range.size();i++) {
            var key=way.nodeKeys().get(range.firstIndex()+i);
            if(facts.network().closure().removableExistingNodeKeys().contains(key))continue;
            var target=candidate.proposedNodePositions().get(key.id());
            MetricPoint expected=facts.sourceMetric().get(i);
            if(target!=null){var ll=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(facts.projectionCode()).eastNorth2latlon(target);expected=facts.frame().toMetric(new GeographicPoint(ll.lat(),ll.lon()));}
            int best=-1;double distance=Double.POSITIVE_INFINITY;
            for(int j=previous+1;j<points.size();j++){double d=points.get(j).distanceTo(expected);if(d<distance){distance=d;best=j;}}
            if(best<0||distance>1e-6)throw new IllegalStateException("Original protected occurrence lacks exact final-preview correspondence");
            arcs.add(new ParentArc(range.firstIndex()+i,Math.min(best,points.size()-2),best==points.size()-1?1:0));previous=best;
        }
        // Removable occurrences still need geometric lineage. Interpolate their original
        // chainage monotonically BETWEEN the exact protected correspondences. This does
        // not assign a surviving Node identity or grant movement/removal authority.
        double[] sourceChain=new double[range.size()],parentChain=new double[points.size()];
        for(int i=1;i<sourceChain.length;i++)sourceChain[i]=sourceChain[i-1]+facts.sourceMetric().get(i-1).distanceTo(facts.sourceMetric().get(i));
        for(int i=1;i<parentChain.length;i++)parentChain[i]=parentChain[i-1]+points.get(i-1).distanceTo(points.get(i));
        var complete=new ArrayList<ParentArc>();
        for(int interval=0;interval<arcs.size()-1;interval++) {
            var left=arcs.get(interval);var right=arcs.get(interval+1);complete.add(left);
            int first=left.originalOccurrence()-range.firstIndex(),last=right.originalOccurrence()-range.firstIndex();
            double sourceSpan=sourceChain[last]-sourceChain[first];
            double start=parentChain[left.parentEdge()]+left.fraction()*(parentChain[left.parentEdge()+1]-parentChain[left.parentEdge()]);
            double end=parentChain[right.parentEdge()]+right.fraction()*(parentChain[right.parentEdge()+1]-parentChain[right.parentEdge()]);
            if(!(sourceSpan>0)||!(end>start))throw new IllegalStateException("Original occurrence lineage is degenerate");
            for(int i=first+1;i<last;i++) {
                double at=start+(end-start)*(sourceChain[i]-sourceChain[first])/sourceSpan;int edge=left.parentEdge();
                while(edge<points.size()-2&&parentChain[edge+1]<=at)edge++;
                double fraction=(at-parentChain[edge])/(parentChain[edge+1]-parentChain[edge]);
                complete.add(new ParentArc(range.firstIndex()+i,edge,fraction));
            }
        }
        complete.add(arcs.get(arcs.size()-1));return complete;
    }
}
