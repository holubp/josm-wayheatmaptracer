package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

/** Resolves the exact selected native final preview; never substitutes the result's default preview. */
public final class ParentReferenceBinder {
    public enum Reason { SELECTION_UNAVAILABLE, PALETTE_UNAVAILABLE, FINAL_PREVIEW_UNAVAILABLE, SOURCE_PROVENANCE_UNAVAILABLE, SOURCE_PROVENANCE_MISMATCH }
    public static final class Refused extends IllegalArgumentException {
        private final Reason reason;
        public Refused(Reason reason){super(reason.name());this.reason=reason;}
        public Reason reason(){return reason;}
    }
    public ParentTraceReference bind(RefinementRootAuthority root, StageId stage, Candidate parent) {
        Objects.requireNonNull(root); Objects.requireNonNull(stage); Objects.requireNonNull(parent);
        requireOriginalSource(root,parent.payload());
        var matches=parent.payload().nativeGeometry().stream().filter(c->c.id().equals(parent.nativeCandidateId())).toList();
        if (matches.size()!=1) throw new Refused(Reason.SELECTION_UNAVAILABLE);
        var selected=matches.get(0);
        var palettes=parent.payload().detectorAttempts().stream().filter(a->a.candidateIds().contains(selected.id()))
            .map(DetectorAttempt::sourcePalette).filter(s->!s.isBlank()).distinct().toList();
        String palette=parent.sourcePalette();
        if(palette.isEmpty()) {
            if(palettes.size()!=1)throw new Refused(Reason.PALETTE_UNAVAILABLE);
            palette=palettes.get(0);
        } else if(!palettes.contains(palette))throw new Refused(Reason.PALETTE_UNAVAILABLE);
        final String selectedPalette=palette;
        var sources=parent.payload().detectorAttempts().stream()
            .filter(attempt->attempt.candidateIds().contains(selected.id())&&attempt.sourcePalette().equals(selectedPalette))
            .map(attempt->new RefinementSourcePlanner.SourceRequest(selectedPalette,attempt.mappingName(),
                    attempt.mappingName().equals("all-colors-combined"),""))
            .distinct().toList();
        if(sources.size()!=1||sources.get(0).mapping().isBlank())throw new Refused(Reason.SOURCE_PROVENANCE_UNAVAILABLE);
        if(selected.finalPreviewPoints().size()<2)throw new Refused(Reason.FINAL_PREVIEW_UNAVAILABLE);
        var projection=Projections.getProjectionByCode(root.projectionCode());
        if (projection==null) throw new IllegalArgumentException("Slide-time projection is unavailable");
        var points=selected.finalPreviewPoints().stream().map(point->{
            var geographic=projection.eastNorth2latlon(point);
            return root.evidence().coordinateFrame().toMetric(new GeographicPoint(geographic.lat(),geographic.lon()));
        }).toList();
        var bound=new ParentTraceReference(root,stage,parent.id(),selected.id(),sources.get(0),parent.immutableIdentity(),points,parent.correspondence());
        var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        if(root.request().geometryMode()==AlignmentMode.MOVE_EXISTING_NODES) {
            if(points.size()!=root.request().selectedRange().size())throw new IllegalArgumentException("Move parent must preserve original occurrence count");
            for(var arc:parent.correspondence()) {
                int local=arc.originalOccurrence()-root.request().selectedRange().firstIndex();
                if(arc.parentEdge()!=Math.min(local,points.size()-2)||arc.fraction()!=(local==points.size()-1?1:0))
                    throw new IllegalArgumentException("Move parent occurrence order differs from actual existing-node geometry");
            }
        }
        for(var arc:parent.correspondence()) {
            var target=selected.proposedNodePositions().get(way.nodeKeys().get(arc.originalOccurrence()).id());
            if(target==null)continue; // Legacy maps may be absent; lineage still binds every available occurrence.
            int vertex=arc.fraction()==0?arc.parentEdge():arc.fraction()==1?arc.parentEdge()+1:-1;
            if(vertex>=0&&!target.equals(selected.finalPreviewPoints().get(vertex)))
                throw new IllegalArgumentException("Candidate-owned assignment disagrees with exact parent vertex");
            var geographic=projection.eastNorth2latlon(target);
            var metric=root.evidence().coordinateFrame().toMetric(new GeographicPoint(geographic.lat(),geographic.lon()));
            if(metric.distanceTo(bound.occurrencePosition(arc.originalOccurrence()))>1e-7)
                throw new IllegalArgumentException("Candidate-owned assignment disagrees with exact parent edge position");
        }
        return bound;
    }
    private static void requireOriginalSource(RefinementRootAuthority root,RefinementPayload payload) {
        var source=payload.sourceSelection().orElseThrow(()->new Refused(Reason.SOURCE_PROVENANCE_UNAVAILABLE));
        var request=root.request();var way=(DetachedWay)root.network().primitives().get(request.selectedWayKey());
        var range=request.selectedRange();
        if(request.selectedWayKey().identityKind()!=PrimitiveKey.IdentityKind.OSM_UNIQUE
                ||source.wayId()!=way.key().id()||source.firstOccurrence()!=range.firstIndex()
                ||source.lastOccurrence()!=range.lastIndex()||source.fullWayNodes().size()!=way.nodeKeys().size()
                ||source.selectedNodeIds().size()!=range.size())throw new Refused(Reason.SOURCE_PROVENANCE_MISMATCH);
        for(int i=0;i<way.nodeKeys().size();i++) {
            var key=way.nodeKeys().get(i);var frozen=source.fullWayNodes().get(i);
            if(key.identityKind()!=PrimitiveKey.IdentityKind.OSM_UNIQUE||key.id()!=frozen.id()
                    ||!(root.network().primitives().get(key) instanceof DetachedNode node)
                    ||!Double.isFinite(frozen.latitude())||!Double.isFinite(frozen.longitude())
                    ||Double.doubleToLongBits(node.coordinate().latitudeDegrees())!=Double.doubleToLongBits(frozen.latitude())
                    ||Double.doubleToLongBits(node.coordinate().longitudeDegrees())!=Double.doubleToLongBits(frozen.longitude())
                    ||i>=range.firstIndex()&&i<=range.lastIndex()
                        &&source.selectedNodeIds().get(i-range.firstIndex())!=key.id())
                throw new Refused(Reason.SOURCE_PROVENANCE_MISMATCH);
        }
    }

}
