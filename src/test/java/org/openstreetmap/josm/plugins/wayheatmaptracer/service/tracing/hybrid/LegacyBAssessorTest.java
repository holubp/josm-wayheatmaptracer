package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

class LegacyBAssessorTest {
    private static MetricPoint p(double x,double y) { return new MetricPoint(x,y); }

    static LegacyBTestFixtures.Fixture fourNodeFixture() {
        var base=LegacyBTestFixtures.alignedEndpointFixture();
        var frame=base.evidence().coordinateFrame();
        var way=base.request().selectedWayKey();
        var keys=List.of(PrimitiveKey.existing(PrimitiveKey.Type.NODE,1),
                PrimitiveKey.existing(PrimitiveKey.Type.NODE,2),PrimitiveKey.existing(PrimitiveKey.Type.NODE,3),
                PrimitiveKey.existing(PrimitiveKey.Type.NODE,7));
        var points=List.of(p(0,0),p(10,0),p(20,0),p(30,0));
        var values=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();
        for (int i=0;i<4;i++) { values.put(keys.get(i),new DetachedNode(keys.get(i),
                frame.toGeographic(points.get(i)),Map.of(),false,false)); }
        values.put(way,new DetachedWay(way,keys,Map.of("highway","path"),false,false));
        var closure=new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,"synthetic-four-node",
                values.keySet(),Set.of(way,keys.get(1),keys.get(2)),Set.of(keys.get(1),keys.get(2)),
                Set.of(keys.get(0),keys.get(3)),Set.of(),Map.of(way,List.of(new OccurrenceRange(0,3))),List.of(),
                base.network().closure().collisionEnvelope(),MetricRegion.rectangle(-10,-10,40,10),
                true,true,true,true);
        var network=new NetworkSnapshot("four-node",SnapshotRole.CAPTURED_BEFORE,"synthetic",1,
                closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
        var evidence=base.evidence();
        var request=new TraceRequest(way,new OccurrenceRange(0,3),TrackerMode.LEGACY_THEN_B,
                AlignmentMode.PRECISE_SHAPE,RecoveryPermissions.disabled(4),TraceBudgets.defaults(),
                evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),
                "settings","legacy-then-b-v1","synthetic",2,
                new ProbabilisticProfileFactory().profileChainage(points,2),evidence.resolution());
        return new LegacyBTestFixtures.Fixture(request,evidence,network,points);
    }

    private static Batch batch(List<MetricPoint> points, boolean frozenMiddle) {
        var seed=new Seed("legacy",points,Map.of(0,0,1,1,2,2,3,3));
        var sections=new LegacyBReferenceFactory().partition(seed,new OccurrenceRange(0,3),Set.of(1,2),
                frozenMiddle?List.of(new OccurrenceRange(1,2)):List.of());
        return new LegacyBPipeline(new Deadline(()->0,300)).refine(List.of(seed),sections,(section,budget,probe)-> {
            var routes=new ArrayList<TraceHypothesis>();
            if(section.originalRange().firstIndex()==0 && frozenMiddle) {
                var geometry=List.of(section.reference().get(0),p(5,.1),section.reference().get(1));
                routes.add(new TraceHypothesis("b","branch",geometry,
                        Collections.nCopies(3,ObservationOwnership.DIRECT_TWO_SIDED),1,
                        OptionalDouble.of(.7),Map.of()));
            }
            var result=new TraceHypothesisSet(TrackerMode.PROBABILISTIC,routes,
                    routes.isEmpty()?TraceHypothesisSet.Status.NO_ROUTE:TraceHypothesisSet.Status.COMPLETE,
                    false,4,2,"synthetic");
            return new SectionExecution(new TraceEngineRun(result,new TraceWorkUsage(1,2,routes.size(),routes.size())),
                    Optional.empty());
        },TraceBudgets.defaults(),CancellationProbe.NONE);
    }

    private static LegacyBAssessor.AssessmentContext context(LegacyBTestFixtures.Fixture fixture) {
        return new LegacyBAssessor.AssessmentContext(fixture.request(),fixture.network(),fixture.evidence(),
                "synthetic",List.of());
    }

    @Test void reconstructedVerticesMustProveExactOrderedSubdivisionOfNativeGeometry() {
        var fixture = fourNodeFixture(); var nativeBatch = batch(fixture.original(), false);
        var original = nativeBatch.candidates().get(0);
        var points = List.of(p(0,0), p(5,0), p(10,0), p(20,0), p(30,0));
        var choices = original.sections().stream().map(choice -> new SectionChoice(choice.section(),
                new VertexRange(choice.vertices().first() == 0 ? 0 : choice.vertices().first() + 1,
                        choice.vertices().last() + 1), choice.source(), choice.outcome(), choice.reason(),
                choice.hypothesisId())).toList();
        var mapping = Map.of(0,0,1,2,2,3,3,4);
        var candidate = new Candidate(original.id(), original.parentSeedId(), points, mapping, choices,
                Map.of(1, new Subdivision(0,.5)));
        var assessed = new LegacyBAssessor().assess(new Batch(List.of(candidate), nativeBatch.sections(),
                nativeBatch.usage(), false, 0), context(fixture), CancellationProbe.NONE);
        assertEquals(30, assessed.get(0).quality().totalLengthMeters(), 1e-12);
        for (var metadata : List.of(Map.of(1, new Subdivision(0,.25)), Map.of(1, new Subdivision(1,.5)))) {
            var forged = new Candidate(candidate.id(), candidate.parentSeedId(), points, mapping, choices, metadata);
            assertThrows(IllegalArgumentException.class, () -> new LegacyBAssessor().assess(
                    new Batch(List.of(forged), nativeBatch.sections(), nativeBatch.usage(), false, 0),
                    context(fixture), CancellationProbe.NONE));
        }
        var alteredPoints = new ArrayList<>(points); alteredPoints.set(1,p(5,.001));
        var altered = new Candidate(candidate.id(), candidate.parentSeedId(), alteredPoints, mapping, choices,
                candidate.subdivisions());
        assertThrows(IllegalArgumentException.class, () -> new LegacyBAssessor().assess(
                new Batch(List.of(altered), nativeBatch.sections(), nativeBatch.usage(), false, 0),
                context(fixture), CancellationProbe.NONE));
    }

    @Test void supportIncludesFallbackAndFrozenSectionsWithoutDuplicatingBoundaryLength() {
        var fixture=fourNodeFixture();
        var assessed=new LegacyBAssessor().assess(batch(fixture.original(),true),context(fixture),CancellationProbe.NONE);
        var refined=assessed.stream().filter(c->c.candidate().sections().get(0).source()==Source.B).findFirst().orElseThrow();
        assertEquals(List.of(Source.B,Source.LEGACY,Source.LEGACY),refined.sections().stream()
                .map(row->row.choice().source()).toList());
        assertEquals(refined.quality().totalLengthMeters(),refined.sections().stream()
                .mapToDouble(LegacyBAssessor.SectionSupport::totalLengthMeters).sum(),1e-9);
        assertEquals(refined.quality().directlySupportedLengthMeters(),refined.sections().stream()
                .mapToDouble(row->row.directlySupportedLengthMeters().orElseThrow()).sum(),1e-9);
        assertTrue(refined.quality().totalLengthMeters()>30);
        assertEquals(.7,refined.sections().get(0).nativeBPosterior().orElseThrow());
        assertTrue(refined.sections().get(1).nativeBPosterior().isEmpty());
        assertTrue(refined.sections().get(2).nativeBPosterior().isEmpty());
    }

    @Test void identicalGeometryHasIdenticalQualityAndLegacyHasNoInventedBPosterior() {
        var fixture=fourNodeFixture();
        var batch=batch(fixture.original(),false);
        var first=batch.candidates().get(0);
        var duplicate=new Candidate("another",first.parentSeedId(),first.points(),first.originalToVertex(),first.sections());
        var assessed=new LegacyBAssessor().assess(new Batch(List.of(first,duplicate),batch.sections(),batch.usage(),false,0),
                context(fixture),CancellationProbe.NONE);
        assertEquals(assessed.get(0).quality().directlySupportedLengthMeters(),
                assessed.get(1).quality().directlySupportedLengthMeters());
        assertEquals(assessed.get(0).quality().meanImageCenterCost(),assessed.get(1).quality().meanImageCenterCost());
        assertTrue(assessed.stream().flatMap(c->c.sections().stream()).allMatch(row->row.nativeBPosterior().isEmpty()));
    }

    @Test void safeIndividualSectionsCannotHideWholeRouteCrossingOrBacktrack() {
        var fixture=fourNodeFixture();
        for(var points:List.of(List.of(p(0,0),p(20,4),p(10,4),p(30,0)),
                List.of(p(0,0),p(10,0),p(9,0),p(30,0)))) {
            var assessed=new LegacyBAssessor().assess(batch(points,false),context(fixture),CancellationProbe.NONE);
            assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,assessed.get(0).quality().disposition());
        }
    }

    @Test void cancellationAndMissingProtectedAuthorityFailBeforeAssessment() {
        var fixture=fourNodeFixture();
        assertThrows(java.util.concurrent.CancellationException.class,()->new LegacyBAssessor().assess(
                batch(fixture.original(),false),context(fixture),()->true));
        var wrong=List.of(p(1,0),p(10,0),p(20,0),p(30,0));
        var result=new LegacyBAssessor().assess(batch(wrong,false),context(fixture),CancellationProbe.NONE);
        assertTrue(result.get(0).quality().has(FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,result.get(0).quality().disposition());
    }

    @Test void unavailableRasterSupportIsNotReportedAsMeasuredZeroOrCompleteSupport() {
        var base=fourNodeFixture(); var old=base.evidence();
        var field=old.fields().get("synthetic");
        var values=new double[field.width()*field.height()]; Arrays.fill(values,Double.NaN);
        var evidence=new EvidenceSnapshot("unavailable",old.coordinateFrame(),old.transform(),old.resolution(),
                old.decisionRegion(),old.evidenceRegion(),Map.of("synthetic",new ScalarEvidenceField(
                        field.width(),field.height(),values,new boolean[values.length],field.lineage())),old.sourceIdentity());
        var r=base.request();
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),
                r.budgets(),evidence.snapshotId(),evidence.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),
                r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),
                r.evidenceResolution());
        var context=new LegacyBAssessor.AssessmentContext(request,base.network(),evidence,"synthetic",List.of());
        var assessed=new LegacyBAssessor().assess(batch(base.original(),false),context,CancellationProbe.NONE).get(0);
        assertTrue(assessed.directlySupportedLengthMeters().isEmpty());
        assertTrue(assessed.sections().stream().allMatch(row->row.directlySupportedLengthMeters().isEmpty()));
        assertTrue(assessed.quality().has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY));
    }

    @Test void alteredFinalGeometryCannotClaimAnUnchangedNativeSectionIdentity() {
        var fixture=fourNodeFixture(); var batch=batch(fixture.original(),true);
        var original=batch.candidates().get(1);
        var altered=new ArrayList<>(original.points()); altered.set(1,p(5,2));
        var forged=new Candidate(original.id(),original.parentSeedId(),altered,
                original.originalToVertex(),original.sections());
        var bad=new Batch(List.of(forged),batch.sections(),batch.usage(),batch.truncated(),batch.omittedParents());
        assertThrows(IllegalArgumentException.class,()->new LegacyBAssessor().assess(bad,context(fixture),CancellationProbe.NONE));
    }
}
