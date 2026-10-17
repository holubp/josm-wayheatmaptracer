package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Independent intersections, proper cell probabilities and physical scene weighting. */
class V022BIdentificationScoreTest {
    private static final MetricRegion REGION=MetricRegion.rectangle(-20,-20,20,20);

    @Test void vertexIntersectionCountsOnceAndCurveReversalPreservesItsTarget() {
        var curve=List.of(new MetricPoint(-2,0),new MetricPoint(0,1),new MetricPoint(2,0));
        var reversed=new ArrayList<>(curve); Collections.reverse(reversed);
        var a=target(truth(curve),profile(0,false,false));
        var b=target(truth(reversed),profile(0,false,false));
        assertEquals(BIdentificationScore.Status.AVAILABLE,a.status());
        assertEquals(1,a.cellIndex()); assertEquals(1,a.offsetMeters().orElseThrow());
        assertEquals(a,b);
    }

    @Test void profileNormalReversalChangesOffsetWithoutChoosingTheOriginalWay() {
        var t=target(truth(line(1)),profile(0,true,false));
        assertEquals(BIdentificationScore.Status.AVAILABLE,t.status());
        assertEquals(-1,t.offsetMeters().orElseThrow()); assertEquals(0,t.cellIndex());
    }

    @Test void twoIntersectionsAreUnavailableRatherThanChoosingTheNearest() {
        var curve=List.of(new MetricPoint(-2,-1),new MetricPoint(2,-1),new MetricPoint(2,2),new MetricPoint(-2,2));
        assertEquals(BIdentificationScore.Status.UNAVAILABLE,target(truth(curve),profile(0,false,false)).status());
    }

    @Test void collinearOverlapCannotInventOneLocation() {
        assertEquals(BIdentificationScore.Status.UNAVAILABLE,target(truth(List.of(new MetricPoint(0,-1),new MetricPoint(0,2))),profile(0,false,false)).status());
    }

    @Test void labelRegionExclusionAndOutsideDomainAreUnavailable() {
        var excluded=new Truth(List.of(label(MetricRegion.rectangle(-2,-2,2,0),AssociationLabel.UNIQUE,List.of(line(1)))));
        assertEquals(BIdentificationScore.Status.UNAVAILABLE,target(excluded,profile(0,false,false)).status());
        assertEquals(BIdentificationScore.Status.UNAVAILABLE,target(truth(line(4)),profile(0,false,false)).status());
        assertEquals(BIdentificationScore.Status.UNAVAILABLE,target(truth(List.of(new MetricPoint(1,0),new MetricPoint(2,1))),profile(0,false,false)).status());
    }

    @Test void exactMidpointBelongsToLowerCellAndWidthsDoNotPickNearestProbability() {
        assertEquals(0,target(truth(line(0)),profile(0,false,false)).cellIndex());
        assertEquals(1,target(truth(line(Math.nextUp(0.0))),profile(0,false,false)).cellIndex());
        assertEquals(1,target(truth(line(3)),profile(0,false,false)).cellIndex());
    }

    @Test void properScoreUsesTheActualUnequalCellProbability() {
        var profiles=List.of(profile(0,false,false),profile(2,false,false));
        var result=score(truth(line(1)),profiles,new double[]{.75,.75});
        assertEquals(-StrictMath.log(.75),result.meanLogLoss().orElseThrow(),1e-14);
        assertEquals(2,result.available()); assertEquals(2,result.scoredLengthMeters());
        assertEquals(0,result.unavailable());
    }

    @Test void physicalVoronoiWeightingIsInvariantToEquivalentLinearResampling() {
        var coarse=score(truth(line(1)),List.of(profile(0,false,false),profile(2,false,false),profile(8,false,false)),
                new double[]{StrictMath.exp(-1),StrictMath.exp(-3),StrictMath.exp(-5)});
        var fine=score(truth(line(1)),List.of(profile(0,false,false),profile(1,false,false),profile(2,false,false),profile(5,false,false),profile(8,false,false)),
                new double[]{StrictMath.exp(-1),StrictMath.exp(-2),StrictMath.exp(-3),StrictMath.exp(-4),StrictMath.exp(-5)});
        assertEquals(3.5,coarse.meanLogLoss().orElseThrow(),1e-13);
        assertEquals(coarse.meanLogLoss().orElseThrow(),fine.meanLogLoss().orElseThrow(),1e-13);
        assertEquals(8,coarse.scoredLengthMeters()); assertEquals(8,fine.scoredLengthMeters());
    }

    @Test void anchorsAreExcludedRatherThanScoredFromConditionedUnaryZero() {
        var result=score(truth(line(1)),List.of(profile(0,false,true),profile(2,false,false)),new double[]{.01,.75});
        assertEquals(1,result.anchors()); assertEquals(1,result.available());
        assertEquals(-StrictMath.log(.75),result.meanLogLoss().orElseThrow(),1e-14);
        assertEquals(1,result.scoredLengthMeters());
    }

    @Test void ambiguousAndAbsentTargetsHaveSeparateCountsAndNoFabricatedMean() {
        var ambiguous=new Truth(List.of(label(REGION,AssociationLabel.AMBIGUOUS,List.of(line(-1),line(1)))));
        var profiles=List.of(profile(0,false,false),profile(2,false,false));
        var a=score(ambiguous,profiles,new double[]{.5,.5});
        assertEquals(2,a.ambiguous()); assertEquals(0,a.available()); assertTrue(a.meanLogLoss().isEmpty());
        var b=score(new Truth(List.of()),profiles,new double[]{.5,.5});
        assertEquals(2,b.unavailable()); assertEquals(0,b.ambiguous()); assertTrue(b.meanLogLoss().isEmpty());
    }

    @Test void zeroTrueProbabilityIsInfiniteAndTinyProbabilityIsNotFloored() {
        var profiles=List.of(profile(0,false,false),profile(2,false,false));
        assertEquals(Double.POSITIVE_INFINITY,score(truth(line(1)),profiles,new double[]{0,0}).meanLogLoss().orElseThrow());
        double tiny=1e-310;
        assertEquals(-StrictMath.log(tiny),score(truth(line(1)),profiles,new double[]{tiny,tiny}).meanLogLoss().orElseThrow(),1e-11);
    }

    @Test void cancellationPublishesNoSceneScore() {
        var profiles=List.of(profile(0,false,false),profile(2,false,false));
        var lattices=profiles.stream().map(V022BIdentificationScoreTest::lattice).toList();
        var rows=lattices.stream().map(l->rows(l,.5)).toList();
        var calls=new AtomicInteger();
        assertThrows(CancellationException.class,()->BIdentificationScore.score(scene(truth(line(1))),profiles,lattices,rows,()->calls.incrementAndGet()>3));
        assertTrue(calls.get()>3);
    }

    @Test void geometricWorkIsRefusedBeforeAnUnboundedProfileCurveProduct() {
        var curve=new ArrayList<MetricPoint>();
        for(int i=0;i<4096;i++) curve.add(new MetricPoint(i-2000,1));
        var profiles=new ArrayList<ProbabilisticProfile>();
        for(int i=0;i<2048;i++) profiles.add(profile(i,false,false));
        var lattice=lattice(profiles.get(0)); var reduced=rows(lattice,.5);
        assertThrows(BIdentificationScore.WorkLimitException.class,()->BIdentificationScore.score(scene(truth(curve)),profiles,
                Collections.nCopies(profiles.size(),lattice),Collections.nCopies(profiles.size(),reduced)));
    }

    private static BIdentificationScore.Target target(Truth truth,ProbabilisticProfile profile) {
        return BIdentificationScore.targetCell(truth,profile,lattice(profile),CancellationProbe.NONE);
    }
    private static BIdentificationScore.SceneScore score(Truth truth,List<ProbabilisticProfile> profiles,double[] probabilities) {
        var lattices=profiles.stream().map(V022BIdentificationScoreTest::lattice).toList();
        var rows=new ArrayList<BObservationMixture.ReducedRows>();
        for(int i=0;i<profiles.size();i++) rows.add(rows(lattices.get(i),probabilities[i]));
        return BIdentificationScore.score(scene(truth),profiles,lattices,rows);
    }
    private static BObservationMixture.ReducedRows rows(ProbabilisticStateLattice lattice,double rightProbability) {
        return BObservationMixture.create(List.of(
                BObservationMixture.Component.create("u","u",ObservationComponent.Kind.MISSING,0,new double[]{-StrictMath.log(5),-StrictMath.log(5)},null),
                BObservationMixture.Component.create("m","m",ObservationComponent.Kind.MEASURED,1,
                        new double[]{StrictMath.log((1-rightProbability)/2),StrictMath.log(rightProbability/3)},null)),null)
                .reduce(lattice,CancellationProbe.NONE,null);
    }
    private static ProbabilisticStateLattice lattice(ProbabilisticProfile p) {
        return new ProbabilisticStateLattice(List.of(new LateralStateCell(-1,2,p.exactAnchorOffsetMeters().isPresent(),true,"unlocalized"),new LateralStateCell(1,3,false,true,"unlocalized")),
                List.of(new ObservationComponent("u","u",ObservationComponent.Kind.MISSING,0,false),new ObservationComponent("m","m",ObservationComponent.Kind.MEASURED,1,false)),1,false);
    }
    private static ProbabilisticProfile profile(double chainage,boolean reverse,boolean anchor) {
        return new ProbabilisticProfile(0,chainage,new MetricPoint(chainage,0),new MetricPoint(0,reverse ? -1:1),-2,3,1,true,0,
                List.of(new ProbabilisticProfile.Sample(-2,0,true),new ProbabilisticProfile.Sample(3,0,true)),List.of(),List.of(),
                anchor ? OptionalDouble.of(-1):OptionalDouble.empty(),List.of(),0);
    }
    private static Truth truth(List<MetricPoint> curve) { return new Truth(List.of(label(REGION,AssociationLabel.UNIQUE,List.of(curve)))); }
    private static LabelRegion label(MetricRegion region,AssociationLabel association,List<List<MetricPoint>> curves) {
        return new LabelRegion(region,StructureLabel.OBSERVED_STRUCTURE,RasterLabel.VALID,association,curves);
    }
    private static List<MetricPoint> line(double y) { return List.of(new MetricPoint(-10,y),new MetricPoint(10,y)); }
    private static Scene scene(Truth truth) {
        return new Scene(V022LocalizationEvidenceTest.snapshot((x,y)->0),List.of(new MetricPoint(0,0),new MetricPoint(10,0)),truth);
    }
}
