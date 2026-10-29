package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Section;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

class LegacyBOccurrenceReconstructionTest {
    @Test void deficitSubdivisionRespectsAssignedSlotsWhenAnotherSectionChangesGlobalChainage() {
        var old = LegacyBProductionRunnerTest.fixture(false,false).input();
        var way = old.request().selectedWayKey(); var frame = old.evidence().coordinateFrame();
        var projection = Projections.getProjectionByCode(old.projectionCode());
        var values = new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();
        var keys = new ArrayList<PrimitiveKey>(); var original = new ArrayList<MetricPoint>();
        var projected = new ArrayList<ProjectedPoint>(); var facts = new ArrayList<Occurrence>();
        for (int index = 0; index < 6; index++) {
            var key = PrimitiveKey.existing(PrimitiveKey.Type.NODE,100+index); keys.add(key);
            var point = new ProjectedPoint(16*index,0); projected.add(point);
            var ll = projection.eastNorth2latlon(new EastNorth(point.east(),point.north()));
            var geographic = new GeographicPoint(ll.lat(),ll.lon()); original.add(frame.toMetric(geographic));
            values.put(key,new DetachedNode(key,geographic,Map.of(),false,false));
            boolean fixed = index == 0 || index == 4 || index == 5;
            facts.add(new Occurrence(index,fixed,fixed));
        }
        values.put(way,new DetachedWay(way,keys,Map.of("highway","path"),false,false));
        var movable = Set.of(keys.get(1),keys.get(2),keys.get(3));
        var editable = new HashSet<>(movable); editable.add(way);
        var range = new OccurrenceRange(0,5); var region = old.evidence().evidenceRegion();
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,"multi-anchor-deficit",
                values.keySet(),editable,movable,Set.of(keys.get(0),keys.get(4),keys.get(5)),Set.of(),
                Map.of(way,List.of(range)),List.of(),region,region,true,true,true,true);
        var network = new NetworkSnapshot("multi-anchor",SnapshotRole.CAPTURED_BEFORE,"synthetic",1,closure,
                values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var r = old.request();
        var request = new TraceRequest(way,range,r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),
                r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),
                new ProbabilisticProfileFactory().profileChainage(original,2),r.evidenceResolution());
        var input = new LegacyBProductionRunner.Input(request,network,old.evidence(),old.fieldName(),projected,
                original,old.projectionCode(),facts,List.of(),false,0);
        var ll = projection.eastNorth2latlon(new EastNorth(72,Math.sqrt(Math.pow(64.0/3,2)-64)));
        var points = List.of(original.get(0),original.get(1),original.get(3),original.get(4),
                frame.toMetric(new GeographicPoint(ll.lat(),ll.lon())),original.get(5));
        var section = new Section("first","seed",new OccurrenceRange(0,4),points.subList(0,4),Map.of(0,0,4,3),false);
        var last = new Section("last","seed",new OccurrenceRange(4,5),points.subList(3,6),Map.of(4,0,5,2),false);
        var choices = List.of(new SectionChoice(section,new VertexRange(0,3),Source.LEGACY,Outcome.LEGACY_RETAINED,
                Reason.LEGACY_ALTERNATIVE,Optional.empty()),new SectionChoice(last,new VertexRange(3,5),Source.LEGACY,
                Outcome.LEGACY_RETAINED,Reason.LEGACY_ALTERNATIVE,Optional.empty()));
        var candidate = new Candidate("candidate","seed",points,Map.of(0,0,4,3,5,5),choices);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var result = LegacyBOccurrenceReconstruction.reuseAvailableVertices(input,candidate,CancellationProbe.NONE,owner);
            assertEquals(Set.of(0,1,2,3,4,5),result.originalToVertex().keySet());
            assertEquals(1,result.subdivisions().size(),"Only the one missing slot needs reconstruction");
            assertEquals(points,result.nativeVertexIndexes().stream().map(result.points()::get).toList());
        }
    }
}
