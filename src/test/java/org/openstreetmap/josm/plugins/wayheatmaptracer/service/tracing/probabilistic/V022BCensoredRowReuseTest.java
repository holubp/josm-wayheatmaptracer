package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Frozen censored rows must be reusable without changing density, mass or attempt ownership. */
class V022BCensoredRowReuseTest {
    @Test void unequalWidthsHaveTheAnalyticDirectionalDensity() {
        var profile = profile(ProbabilisticProfile.CensorSide.RIGHT,1,false);
        var cells = cells(false);
        double[] row = row(profile,cells,censored(1));
        double logZ = StrictMath.log(StrictMath.exp(-.5) + 2 + 1);
        assertEquals(-.5-logZ,row[0],1e-15);
        assertEquals(-logZ,row[1],1e-15);
        assertEquals(-logZ,row[2],1e-15);
        double integral=0;
        for(int s=0;s<row.length;s++) integral+=StrictMath.exp(row[s])*cells.get(s).quadratureWidthMeters();
        assertEquals(1,integral,1e-15);
    }

    @Test void isolatedBaselineUnariesExposeExactlyTheSameRowsInBothDirections() {
        for(var side:ProbabilisticProfile.CensorSide.values()) {
            var p=profile(side,1,false); var cells=cells(false);
            var component=censored(1); var row=row(p,cells,component);
            var lattice=lattice(cells,component,0);
            var baseline=new ProbabilisticObservationModel().evaluate(p,lattice,EvidenceModelParameters.defaults());
            for(int s=0;s<row.length;s++) {
                assertEquals(Double.doubleToLongBits(-row[s]),Double.doubleToLongBits(baseline.unaryCost(s)));
                assertEquals(1,baseline.componentResponsibility(s,0));
                assertEquals(0,baseline.componentResponsibility(s,1));
            }
            assertEquals(ObservationOwnership.CORE_CENSORED,baseline.ownership());
            assertTrue(baseline.entirelyMissing());
            assertEquals(row[side==ProbabilisticProfile.CensorSide.RIGHT ? 2:0],row[1]);
            assertTrue(row[side==ProbabilisticProfile.CensorSide.RIGHT ? 0:2]<row[1]);
        }
    }

    @Test void effectiveMassesAndIndependentMixtureExpectationsRemainBaseline() {
        var p=profile(ProbabilisticProfile.CensorSide.RIGHT,.5,false);
        var cells=cells(false); var component=censored(.6); var lattice=lattice(cells,component,.4);
        var priors=ProbabilisticObservationModel.effectiveComponentPriors(p,lattice.components());
        assertEquals(.3,priors[0],1e-15); assertEquals(.7,priors[1],1e-15);
        var row=row(p,cells,component);
        var result=new ProbabilisticObservationModel().evaluate(p,lattice,EvidenceModelParameters.defaults());
        double z=StrictMath.exp(-.5)+3;
        for(int s=0;s<cells.size();s++) {
            double density=(s==0 ? StrictMath.exp(-.5):1)/z;
            double mixture=.7/4+.3*density;
            assertEquals(-StrictMath.log(mixture),result.unaryCost(s),1e-15);
            assertEquals(.3*density/mixture,result.componentResponsibility(s,0),1e-15);
            assertEquals(StrictMath.log(density),row[s],1e-15);
        }
    }

    @Test void anchorsRemainConditionedWithoutChangingTheCensoredRow() {
        var p=profile(ProbabilisticProfile.CensorSide.RIGHT,1,true);
        var cells=cells(true); var component=censored(1);
        assertArrayEquals(row(p,cells(false),component),row(p,cells,component));
        var result=new ProbabilisticObservationModel().evaluate(p,lattice(cells,component,0),EvidenceModelParameters.defaults());
        assertEquals(0,result.unaryCost(1));
        assertEquals(1,result.componentResponsibility(1,0));
    }

    @Test void zeroEffectiveMassStillHasAFiniteFrozenRowAndUniformBaseline() {
        var p=profile(ProbabilisticProfile.CensorSide.RIGHT,0,false);
        var cells=cells(false); var component=censored(.6);
        assertArrayEquals(row(profile(ProbabilisticProfile.CensorSide.RIGHT,1,false),cells,component),row(p,cells,component));
        var lattice=lattice(cells,component,.4);
        assertArrayEquals(new double[]{0,1},ProbabilisticObservationModel.effectiveComponentPriors(p,lattice.components()));
        var result=new ProbabilisticObservationModel().evaluate(p,lattice,EvidenceModelParameters.defaults());
        for(int s=0;s<cells.size();s++) {
            assertEquals(StrictMath.log(4),result.unaryCost(s));
            assertEquals(0,result.componentResponsibility(s,0));
            assertEquals(1,result.componentResponsibility(s,1));
        }
    }

    @Test void invalidOrMissingRowsAreRejectedBeforeCallerStorageChanges() {
        var p=profile(ProbabilisticProfile.CensorSide.RIGHT,1,false);
        double[] target={7,8,9};
        var missing=new ObservationComponent("missing","missing",ObservationComponent.Kind.MISSING,1,false);
        assertThrows(IllegalArgumentException.class,()->ProbabilisticObservationModel.fillBaselineCensoredRow(target,p,cells(false),missing,CancellationProbe.NONE));
        assertArrayEquals(new double[]{7,8,9},target);
        var foreign=new ObservationComponent("foreign","foreign",ObservationComponent.Kind.CENSORED,1,false);
        assertThrows(NoSuchElementException.class,()->ProbabilisticObservationModel.fillBaselineCensoredRow(target,p,cells(false),foreign,CancellationProbe.NONE));
        assertArrayEquals(new double[]{7,8,9},target);
        assertThrows(IllegalArgumentException.class,()->ProbabilisticObservationModel.fillBaselineCensoredRow(target,p,List.of(),censored(1),CancellationProbe.NONE));
        assertThrows(IllegalArgumentException.class,()->ProbabilisticObservationModel.fillBaselineCensoredRow(new double[2],p,cells(false),censored(1),CancellationProbe.NONE));
    }

    @Test void cancellationAndRefusalPreserveCallerBytesAndItsOwnedRow() {
        var p=profile(ProbabilisticProfile.CensorSide.RIGHT,1,false);
        var ledger=new AttemptMemoryLedger(AttemptMemoryLedger.arrayBytes(3,Double.BYTES));
        try(var owner=ledger.rootOwner()) {
            double[] target=ProbabilisticInference.allocated(owner,AttemptMemoryLedger.arrayBytes(3,Double.BYTES),"caller row",()->new double[]{7,8,9});
            long bytes=ledger.currentBytes();
            var probes=new AtomicInteger();
            assertThrows(CancellationException.class,()->ProbabilisticObservationModel.fillBaselineCensoredRow(target,p,cells(false),censored(1),()->probes.incrementAndGet()>=3));
            assertTrue(probes.get()>=3);
            assertArrayEquals(new double[]{7,8,9},target);
            assertEquals(bytes,ledger.currentBytes());
            assertThrows(ProbabilisticInference.MemoryLimit.class,()->new ProbabilisticObservationModel().evaluate(p,lattice(cells(false),censored(1),0),EvidenceModelParameters.defaults(),true,owner));
            assertEquals(bytes,ledger.currentBytes());
            assertArrayEquals(new double[]{7,8,9},target);
            ProbabilisticObservationModel.fillBaselineCensoredRow(target,p,cells(false),censored(1),CancellationProbe.NONE);
            assertEquals(bytes,ledger.currentBytes(),"the reused fill needs no new payload allocation even at the cap");
            assertFalse(Arrays.equals(new double[]{7,8,9},target));
        }
        assertEquals(0,ledger.currentBytes());
    }

    private static double[] row(ProbabilisticProfile p,List<LateralStateCell> cells,ObservationComponent component) {
        double[] result=new double[cells.size()];
        ProbabilisticObservationModel.fillBaselineCensoredRow(result,p,cells,component,CancellationProbe.NONE);
        return result;
    }
    private static List<LateralStateCell> cells(boolean anchor) {
        return List.of(new LateralStateCell(-1,1,false,true,"unlocalized"),new LateralStateCell(0,2,anchor,true,"unlocalized"),new LateralStateCell(1,1,false,true,"unlocalized"));
    }
    private static ObservationComponent censored(double prior) {
        return new ObservationComponent("c","c",ObservationComponent.Kind.CENSORED,prior,false);
    }
    private static ProbabilisticStateLattice lattice(List<LateralStateCell> cells,ObservationComponent component,double uniform) {
        return new ProbabilisticStateLattice(cells,List.of(component,new ObservationComponent("u","u",ObservationComponent.Kind.MISSING,uniform,false)),1,false);
    }
    private static ProbabilisticProfile profile(ProbabilisticProfile.CensorSide side,double reliability,boolean anchor) {
        var band=new ProbabilisticProfile.CensoredMode("c","c",side,0,1,true,false,0,Double.NaN,reliability);
        return new ProbabilisticProfile(0,0,new MetricPoint(0,0),new MetricPoint(0,1),-1,1,1,true,0,
                List.of(new ProbabilisticProfile.Sample(-1,Double.NaN,false),new ProbabilisticProfile.Sample(1,Double.NaN,false)),
                List.of(),List.of(band),anchor ? OptionalDouble.of(0):OptionalDouble.empty(),List.of(),0);
    }
}
