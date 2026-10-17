package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationAcceptance.*;

/** Analytic acceptance controls cannot be replaced by scores or guessed mode correspondence. */
class V022BIdentificationAcceptanceTest {
    @Test void unequalWidthQuantilesUseActualConditionalCellMassesAndExactCdfTies() {
        var profile=profile(-2,3,false);
        var lattice=lattice(new double[]{-1,1},new double[]{2,3});
        var row=component(new double[]{.125,.25});
        var q=quantiles(profile,lattice,row,false,CancellationProbe.NONE).orElseThrow();
        assertEquals(0,q.q25Meters(),1e-14);assertEquals(1,q.medianMeters(),1e-14);assertEquals(2,q.q75Meters(),1e-14);
    }
    @Test void zeroDensityGapKeepsTheLeftContinuousInverseAtAnExactCdfTie() {
        var q=quantiles(profile(0,3,false),lattice(new double[]{.5,1.5,2.5},new double[]{1,1,1}),
                component(new double[]{.25,0,.75}),false,CancellationProbe.NONE).orElseThrow();
        assertEquals(1,q.q25Meters(),1e-14);
        assertEquals(2+1.0/3,q.medianMeters(),1e-14);assertEquals(2+2.0/3,q.q75Meters(),1e-14);
    }
    @Test void reversedPhysicalAxisRebuildsCdfInsteadOfNegatingAGapMedian() {
        var p=profile(-2,2,false);var cells=lattice(new double[]{-1.5,-.5,.5,1.5},new double[]{1,1,1,1});
        var q=quantiles(p,cells,component(new double[]{.5,0,0,.5}),true,CancellationProbe.NONE).orElseThrow();
        assertEquals(-1,q.medianMeters(),1e-14);assertEquals(-1.5,q.q25Meters(),1e-14);assertEquals(1.5,q.q75Meters(),1e-14);
    }
    @Test void inconsistentOriginalCellWidthsAreRejectedRatherThanSilentlyReinterpreted() {
        assertThrows(IllegalArgumentException.class,()->quantiles(profile(-2,3,false),lattice(new double[]{-1,1},new double[]{1,4}),
                component(new double[]{.2,.2}),false,CancellationProbe.NONE));
    }
    @Test void onlyRegisteredSameNativeAcquisitionPairsAreAdmitted() {
        var a=definition("a","v0",1,false);var b=definition("b","v6",2,false);
        assertTrue(bindPair(a,key(),b,key()).isPresent());
        assertTrue(bindPair(a,key(),definition("phase-changed","v1",2,false),key()).isEmpty());
        assertTrue(bindPair(a,key(),b,new SourceKey("source","1".repeat(64),"frame",Capability.KNOWN_NATIVE)).isEmpty());
        assertTrue(bindPair(a,key(),b,new SourceKey("source","0".repeat(64),"other-frame",Capability.KNOWN_NATIVE)).isEmpty());
        assertTrue(bindPair(a,key(),b,new SourceKey("source","0".repeat(64),"frame",Capability.PHYSICAL_SAMPLING_ONLY)).isEmpty());
    }
    @Test void actualConditionalMedianControlsAcceptanceEvenWhenRawCoresAreIdentical() {
        assertFalse(compare(mode("m",.4,1,0,1),mode("m",.4,1,1.01,1)).passed());
        assertTrue(compare(mode("m",.4,1,0,1),mode("m",.4,1,1,1)).passed());
    }
    @Test void massAndInterquartileThresholdsRemainStrictAtTheirRegisteredBounds() {
        assertTrue(compare(mode("m",.4,1,0,1),mode("m",.42,1,0,.9)).passed());
        assertFalse(compare(mode("m",.4,1,0,1),mode("m",.420001,1,0,.9)).passed());
        assertFalse(compare(mode("m",.4,1,0,1),mode("m",.4,1,0,.899999)).passed());
    }
    @Test void centerMustMeetBothResolutionDescriptors() {
        assertFalse(compare(mode("m",.4,2,0,1),mode("m",.4,.5,.6,1)).passed());
    }
    @Test void missingAmbiguousOrZeroConditionalModeCorrespondenceFails() {
        var a=mode("a",.4,1,0,1);var b=mode("b",.4,1,0,1);
        assertFalse(compareProfiles(List.of(a),List.of()).passed());
        assertFalse(compareProfiles(List.of(a),List.of(a,b)).passed());
        var absent=new ModeSummary("a",Optional.of(new Interval(-1,1)),Optional.of(new Interval(-2,2)),0,OptionalDouble.of(1),Optional.empty());
        assertFalse(compareProfiles(List.of(a),List.of(absent)).passed());
    }
    @Test void anchorsAreExcludedFromMassAndQuantileComparisonButPositiveGroupStillNeedsAMatch() {
        var a=definition("a","v0",1,false);var b=definition("b","v6",2,false);
        var binding=bindPair(a,key(),b,key()).orElseThrow();
        var anchorA=new ProfileSummary(0,new MetricPoint(0,0),new MetricPoint(0,1),true,List.of(mode("m",.1,1,0,1)));
        var anchorB=new ProfileSummary(0,new MetricPoint(0,0),new MetricPoint(0,1),true,List.of(mode("m",.9,1,5,.1)));
        var observed=new ProfileSummary(1,new MetricPoint(1,0),new MetricPoint(0,1),false,List.of(mode("m",.4,1,0,1)));
        assertTrue(comparePair(binding,new SceneSummary(a,key(),List.of(anchorA,observed)),new SceneSummary(b,key(),List.of(anchorB,observed)),CancellationProbe.NONE).passed());
        assertFalse(comparePair(binding,new SceneSummary(a,key(),List.of(anchorA)),new SceneSummary(b,key(),List.of(anchorB)),CancellationProbe.NONE).passed());
    }
    @Test void exactAnchorGeometryIsCheckedIndependently() {
        var p=profile(-2,3,true);
        assertTrue(exactAnchors(List.of(p),List.of(new MetricPoint(0,0)),CancellationProbe.NONE).passed());
        assertFalse(exactAnchors(List.of(p),List.of(new MetricPoint(0,.01)),CancellationProbe.NONE).passed());
    }
    @Test void truthfulApexDistanceRejectsAnEarlyHighPointThatPassesTheOldHeightSelector() {
        var truth=scene(List.of(List.of(new MetricPoint(0,30),new MetricPoint(60,33),new MetricPoint(120,30))));
        var early=List.of(new MetricPoint(0,30),new MetricPoint(58,33),new MetricPoint(60,30),new MetricPoint(120,30));
        assertFalse(geometry(GeometrySelector.APEX_X60_PM2,truth,early,.5,CancellationProbe.NONE).passed());
        assertTrue(geometry(GeometrySelector.APEX_X60_PM2,truth,List.of(new MetricPoint(0,30),new MetricPoint(60,33),new MetricPoint(120,30)),.5,CancellationProbe.NONE).passed());
    }
    @Test void emptyApexAndDisplacedSelectorsFailInsteadOfReportingZeroError() {
        var truth=scene(List.of(List.of(new MetricPoint(0,34),new MetricPoint(120,34))));
        var outside=List.of(new MetricPoint(0,34),new MetricPoint(20,34));
        for(var selector:List.of(GeometrySelector.APEX_X60_PM2,GeometrySelector.DISPLACED_X40_80)) {
            var checks=geometry(selector,truth,outside,1,CancellationProbe.NONE);
            assertFalse(checks.passed());assertTrue(checks.checks().stream().anyMatch(c->c.code()==Code.EMPTY_SELECTOR&&c.verdict()==Verdict.FAIL));
        }
        // Crossing segments supply the displaced selector even without an interior vertex.
        assertTrue(geometry(GeometrySelector.DISPLACED_X40_80,truth,List.of(new MetricPoint(0,34),new MetricPoint(120,34)),1,CancellationProbe.NONE).passed());
        assertFalse(geometry(GeometrySelector.DISPLACED_X40_80,truth,List.of(new MetricPoint(0,30),new MetricPoint(120,30)),1,CancellationProbe.NONE).passed());
    }
    @Test void parallelSegmentUnionRejectsAValleyCrossingWhoseVerticesAreOnRealBranches() {
        var truth=scene(List.of(List.of(new MetricPoint(0,26),new MetricPoint(120,26)),List.of(new MetricPoint(0,34),new MetricPoint(120,34))));
        assertFalse(geometry(GeometrySelector.PARALLEL_SEGMENTS_X40_80,truth,List.of(new MetricPoint(40,26),new MetricPoint(80,34)),1,CancellationProbe.NONE).passed());
        assertTrue(geometry(GeometrySelector.PARALLEL_SEGMENTS_X40_80,truth,List.of(new MetricPoint(40,26),new MetricPoint(80,26)),1,CancellationProbe.NONE).passed());
    }
    @Test void cancellationDoesNotPublishPartialAcceptance() {
        assertThrows(CancellationException.class,()->quantiles(profile(-2,3,false),lattice(new double[]{-1,1},new double[]{2,3}),component(new double[]{.125,.25}),false,()->true));
        assertThrows(CancellationException.class,()->geometry(GeometrySelector.DISPLACED_X40_80,scene(List.of(List.of(new MetricPoint(0,34),new MetricPoint(120,34)))),List.of(new MetricPoint(40,34),new MetricPoint(80,34)),1,()->true));
    }
    @Test void validNullUsesFullDomainUniformWithoutMeasuredAuthorityAndReleasesOwnedPayload() {
        var p=profile(-2,3,false);var original=lattice(new double[]{-1,1},new double[]{2,3});
        var cells=new ProbabilisticStateLattice(original.cells(),List.of(new ObservationComponent("u","u",ObservationComponent.Kind.MISSING,1,false)),1,false);
        var mixture=BObservationMixture.create(List.of(BObservationMixture.Component.create("u","u",ObservationComponent.Kind.MISSING,1,new double[]{-StrictMath.log(5),-StrictMath.log(5)},null)),null);
        var facts=new BIdentificationFacts.ProfileFacts(p,List.of(),BIdentificationFacts.VERSION);
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var inspected=inspectProfile(facts,cells,mixture,new BIdentifiedObservationKernel.Parameters(1,1,0),CancellationProbe.NONE,owner);
            assertTrue(inspected.checks().passed());assertTrue(inspected.summary().modes().isEmpty());
            assertEquals(StrictMath.log(5),inspected.rows().unaryCost(0));assertTrue(ledger.currentBytes()>0);
        }
        assertEquals(0,ledger.currentBytes());
        var tiny=new AttemptMemoryLedger(1);
        try(var owner=tiny.rootOwner()) {
            assertThrows(RuntimeException.class,()->inspectProfile(facts,cells,mixture,new BIdentifiedObservationKernel.Parameters(1,1,0),CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
    }
    @Test void positiveMeasuredDensityOnMissingSamplesFailsTheStructuralInvalidSupportGate() {
        var mode=new ProbabilisticProfile.Mode("m","m",-.1,.1,1,1,1,List.of(0.0),List.of(0.0),false);
        var p=new ProbabilisticProfile(0,0,new MetricPoint(0,0),new MetricPoint(0,1),-2,3,1,true,0,
                List.of(new ProbabilisticProfile.Sample(-2,Double.NaN,false),new ProbabilisticProfile.Sample(3,Double.NaN,false)),List.of(mode),List.of(),OptionalDouble.empty(),List.of(),0);
        var original=lattice(new double[]{-1,1},new double[]{2,3});
        var cells=new ProbabilisticStateLattice(original.cells(),List.of(new ObservationComponent("u","u",ObservationComponent.Kind.MISSING,.5,false),new ObservationComponent("m","m",ObservationComponent.Kind.MEASURED,.5,false)),1,false);
        var mixture=BObservationMixture.create(List.of(BObservationMixture.Component.create("u","u",ObservationComponent.Kind.MISSING,.5,new double[]{-StrictMath.log(5),-StrictMath.log(5)},null),
                BObservationMixture.Component.create("m","m",ObservationComponent.Kind.MEASURED,.5,new double[]{-StrictMath.log(5),-StrictMath.log(5)},null)),null);
        var inspected=inspectProfile(new BIdentificationFacts.ProfileFacts(p,List.of(),BIdentificationFacts.VERSION),cells,mixture,new BIdentifiedObservationKernel.Parameters(1,1,0),CancellationProbe.NONE,null);
        assertFalse(inspected.checks().passed());
        assertTrue(inspected.checks().checks().stream().anyMatch(c->c.code()==Code.INVALID_MEASURED_SUPPORT&&c.verdict()==Verdict.FAIL));
    }
    @Test void censoredMassAndConditionalRowMustRetainTheOriginalBaselineExactly() {
        var censored=new ProbabilisticProfile.CensoredMode("c","c",ProbabilisticProfile.CensorSide.RIGHT,0,1,true,false,0,Double.NaN,.5);
        var p=new ProbabilisticProfile(0,0,new MetricPoint(0,0),new MetricPoint(0,1),-2,3,1,true,0,
                List.of(new ProbabilisticProfile.Sample(-2,Double.NaN,false),new ProbabilisticProfile.Sample(3,Double.NaN,false)),
                List.of(),List.of(censored),OptionalDouble.empty(),List.of(),0);
        var c=new ObservationComponent("c","c",ObservationComponent.Kind.CENSORED,.6,false);
        var cells=new ProbabilisticStateLattice(lattice(new double[]{-1,1},new double[]{2,3}).cells(),
                List.of(c,new ObservationComponent("u","u",ObservationComponent.Kind.MISSING,.4,false)),1,false);
        var row=new double[2];ProbabilisticObservationModel.fillBaselineCensoredRow(row,p,cells.cells(),c,CancellationProbe.NONE);
        var masses=ProbabilisticObservationModel.effectiveComponentPriors(p,cells.components());
        for(boolean corrupted:List.of(false,true)) {
            var mixture=BObservationMixture.create(List.of(
                    BObservationMixture.Component.create("c","c",ObservationComponent.Kind.CENSORED,masses[0],
                            corrupted?new double[]{-StrictMath.log(5),-StrictMath.log(5)}:row,null),
                    BObservationMixture.Component.create("u","u",ObservationComponent.Kind.MISSING,masses[1],
                            new double[]{-StrictMath.log(5),-StrictMath.log(5)},null)),null);
            var checks=inspectProfile(new BIdentificationFacts.ProfileFacts(p,List.of(),BIdentificationFacts.VERSION),cells,mixture,
                    new BIdentifiedObservationKernel.Parameters(1,1,0),CancellationProbe.NONE,null).checks();
            assertEquals(!corrupted,checks.passed());
            assertTrue(checks.checks().stream().anyMatch(check->check.code()==Code.CENSORED_PARITY
                    &&check.verdict()==(corrupted?Verdict.FAIL:Verdict.PASS)));
        }
    }
    @Test void dedicatedReversalUsesReversedPhysicalChainageAndRequiresTheSameAnchor() {
        var left=new CaseDefinition(new CaseKey("left","generator","geometry","source",Split.SELECT,"recipe",1),
                "multiple-physical-pitches-vs-reversed-source-order","left","v0",0,1,1,false,"analytic-test",List.of("scalar"));
        var right=new CaseDefinition(new CaseKey("right","generator","geometry","source",Split.SELECT,"recipe",1),
                left.contrastId(),"right","v0",0,1,1,true,"analytic-test",List.of("scalar"));
        var binding=bindPair(left,key(),right,key()).orElseThrow();
        var m=List.of(mode("m",.4,1,0,1));
        var first=List.of(new ProfileSummary(0,new MetricPoint(0,0),new MetricPoint(0,1),false,m),
                new ProfileSummary(10,new MetricPoint(10,0),new MetricPoint(0,1),false,m));
        var reversed=List.of(new ProfileSummary(0,new MetricPoint(10,0),new MetricPoint(0,1),false,m),
                new ProfileSummary(10,new MetricPoint(0,0),new MetricPoint(0,1),false,m));
        assertTrue(comparePair(binding,new SceneSummary(left,key(),first),new SceneSummary(right,key(),reversed),CancellationProbe.NONE).passed());
        assertFalse(comparePair(binding,new SceneSummary(left,key(),first),new SceneSummary(right,key(),first),CancellationProbe.NONE).passed());
    }
    @Test void geometryOwnerRefusalAndCancellationRetainNoPartialPayload() {
        var truth=scene(List.of(List.of(new MetricPoint(0,34),new MetricPoint(120,34))));
        var points=List.of(new MetricPoint(0,34),new MetricPoint(120,34));
        var tiny=new AttemptMemoryLedger(1);
        try(var owner=tiny.rootOwner()) {
            assertThrows(RuntimeException.class,()->geometry(GeometrySelector.DISPLACED_X40_80,truth,points,1,CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            assertTrue(geometry(GeometrySelector.DISPLACED_X40_80,truth,points,1,CancellationProbe.NONE,owner).passed());
            assertTrue(ledger.currentBytes()>0);
        }
        assertEquals(0,ledger.currentBytes());
    }
    private static Checks compare(ModeSummary a,ModeSummary b) {return compareProfiles(List.of(a),List.of(b));}
    private static Checks compareProfiles(List<ModeSummary> a,List<ModeSummary> b) {
        var first=definition("a","v0",1,false);var second=definition("b","v6",2,false);
        var binding=bindPair(first,key(),second,key()).orElseThrow();
        return comparePair(binding,new SceneSummary(first,key(),List.of(new ProfileSummary(1,new MetricPoint(1,0),new MetricPoint(0,1),false,a))),
                new SceneSummary(second,key(),List.of(new ProfileSummary(1,new MetricPoint(1,0),new MetricPoint(0,1),false,b))),CancellationProbe.NONE);
    }
    private static ModeSummary mode(String id,double mass,double rho,double median,double iqr) {
        return new ModeSummary(id,Optional.of(new Interval(-1,1)),Optional.of(new Interval(-2,2)),mass,OptionalDouble.of(rho),Optional.of(new Quantiles(median-iqr/2,median,median+iqr/2)));
    }
    private static SourceKey key() {return new SourceKey("source","0".repeat(64),"frame",Capability.KNOWN_NATIVE);}
    private static CaseDefinition definition(String id,String variant,int over,boolean reversed) {
        return new CaseDefinition(new CaseKey(id,"generator","geometry","source",Split.SELECT,"recipe",1),"concentrated-band-vs-diffuse-band","left",variant,0,1,over,reversed,"analytic-test",List.of("scalar"));
    }
    private static ProbabilisticProfile profile(double minimum,double maximum,boolean anchor) {
        return new ProbabilisticProfile(0,0,new MetricPoint(0,0),new MetricPoint(0,1),minimum,maximum,1,true,0,
                List.of(new ProbabilisticProfile.Sample(minimum,0,true),new ProbabilisticProfile.Sample(maximum,0,true)),List.of(),List.of(),
                anchor?OptionalDouble.of(0):OptionalDouble.empty(),List.of(),0);
    }
    private static ProbabilisticStateLattice lattice(double[] offsets,double[] widths) {
        var cells=new ArrayList<LateralStateCell>();
        for(int i=0;i<offsets.length;i++)cells.add(new LateralStateCell(offsets[i],widths[i],false,true,"unlocalized"));
        return new ProbabilisticStateLattice(cells,List.of(new ObservationComponent("m","m",ObservationComponent.Kind.MEASURED,1,false)),1,false);
    }
    private static BObservationMixture.Component component(double[] densities) {
        return BObservationMixture.Component.create("m","m",ObservationComponent.Kind.MEASURED,1,Arrays.stream(densities).map(StrictMath::log).toArray(),null);
    }
    private static Scene scene(List<List<MetricPoint>> curves) {
        return new Scene(V022LocalizationEvidenceTest.snapshot((x,y)->0),List.of(new MetricPoint(0,30),new MetricPoint(120,30)),
                new Truth(List.of(new LabelRegion(MetricRegion.rectangle(-10,0,130,60),StructureLabel.OBSERVED_STRUCTURE,RasterLabel.VALID,
                        curves.size()>1?AssociationLabel.AMBIGUOUS:AssociationLabel.UNIQUE,curves))));
    }
}
