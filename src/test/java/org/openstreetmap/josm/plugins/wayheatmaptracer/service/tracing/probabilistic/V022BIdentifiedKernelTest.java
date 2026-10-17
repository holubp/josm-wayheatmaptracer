package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BIdentifiedKernelTest {
    private final BIdentifiedObservationKernel kernel = new BIdentifiedObservationKernel(
            new BIdentifiedObservationKernel.Parameters(1,1,0));
    @Test void sameJointFieldDeterminesBothMassAndConditionalLocation() {
        var mixture=kernel.evaluate(facts(1,false),lattice(),CancellationProbe.NONE,null);
        var reduced=mixture.reduce(lattice(),CancellationProbe.NONE,null);
        // Core [-1,1], native pitch1 => h1.5, normalized tent [.2,.6,.2].
        // Own excess [.4,.9,.4] => joint [.08,.54,.08], integral H=.7.
        assertEquals(-Math.log((1.0/3+.08)/1.7),reduced.unaryCost(0),1e-12);
        assertEquals(-Math.log((1.0/3+.54)/1.7),reduced.unaryCost(1),1e-12);
        assertEquals(.54/(1.0/3+.54),reduced.responsibility(1,1),1e-12);
    }
    @Test void weakLimitIsContinuousAndInvalidCellsKeepUniformOnly() {
        double previous=Double.POSITIVE_INFINITY;
        for(double amplitude:new double[]{1,.1,.01,.001,0}) {
            var reduced=kernel.evaluate(facts(amplitude,false),lattice(),CancellationProbe.NONE,null)
                    .reduce(lattice(),CancellationProbe.NONE,null);
            double influence=Math.abs(reduced.unaryCost(1)-Math.log(3));
            assertTrue(influence<=previous+1e-12);previous=influence;
        }
        var reduced=kernel.evaluate(facts(1,true),lattice(),CancellationProbe.NONE,null)
                .reduce(lattice(),CancellationProbe.NONE,null);
        assertEquals(0,reduced.responsibility(1,1),0);
        assertEquals(-Math.log(1.0/(3*1.16)),reduced.unaryCost(1),1e-12);
    }
    @Test void unavailableOwnMeasurementsCannotBecomeNumericZero() {
        var original=facts(1,false);
        var mode=original.modes().get(0);
        var values=new ArrayList<>(mode.values());
        values.set(Feature.BACKGROUND.ordinal(),new ScalarFact(Feature.BACKGROUND,Unit.SCALAR,
                Availability.UNAVAILABLE,OptionalDouble.empty()));
        var missing=new ProfileFacts(original.baseline(),List.of(new ModeFacts("m",values,mode.provenance())),VERSION);
        var reduced=kernel.evaluate(missing,lattice(),CancellationProbe.NONE,null).reduce(lattice(),CancellationProbe.NONE,null);
        for(int i=0;i<3;i++) assertEquals(Math.log(3),reduced.unaryCost(i),1e-12);
    }
    @Test void cancellationOrRefusalPublishesNoPartialKernelRows() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            int[] work={0};
            assertThrows(RuntimeException.class,()->kernel.evaluate(facts(1,false),lattice(),()->++work[0]>3,owner));
            assertEquals(0,ledger.currentBytes());
        }
        var tiny=new AttemptMemoryLedger(8);
        try(var owner=tiny.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->kernel.evaluate(facts(1,false),lattice(),CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
    }
    @Test void identityBindsParametersAndRejectsUnregisteredConfigurations() {
        assertTrue(kernel.identityHash().matches("[a-f0-9]{64}"));
        assertNotEquals(kernel.identityHash(),new BIdentifiedObservationKernel(
                new BIdentifiedObservationKernel.Parameters(4,1,0)).identityHash());
        assertThrows(IllegalArgumentException.class,()->new BIdentifiedObservationKernel.Parameters(2,1,0));
    }
    private static ProbabilisticStateLattice lattice() {
        return new ProbabilisticStateLattice(List.of(new LateralStateCell(-1,1,false,false,"m"),
                new LateralStateCell(0,1,false,false,"m"),new LateralStateCell(1,1,false,false,"m")),
                List.of(new ObservationComponent("missing","missing",ObservationComponent.Kind.MISSING,.5,false),
                new ObservationComponent("m","m",ObservationComponent.Kind.MEASURED,.5,false)),1,false);
    }
    private static ProfileFacts facts(double amplitude,boolean invalidCenter) {
        var samples=List.of(new ProbabilisticProfile.Sample(-2,.1*amplitude,true),
                new ProbabilisticProfile.Sample(-1,.5*amplitude,true),
                new ProbabilisticProfile.Sample(0,amplitude,!invalidCenter),
                new ProbabilisticProfile.Sample(1,.5*amplitude,true),
                new ProbabilisticProfile.Sample(2,.1*amplitude,true));
        var mode=new ProbabilisticProfile.Mode("m","m",-.25,.25,.5,1,1,List.of(0.0),List.of(0.0),false);
        var profile=new ProbabilisticProfile(0,0,new MetricPoint(0,0),new MetricPoint(0,1),-2,2,1,true,
                .1*amplitude,samples,List.of(mode),List.of(),OptionalDouble.empty(),List.of(),0);
        var values=new ArrayList<ScalarFact>();
        for(var feature:Feature.values()) values.add(new ScalarFact(feature,
                switch(feature){case PEAK,BACKGROUND,PROMINENCE,NOISE_DESCRIPTOR,QUANTIZATION,
                    SHOULDER_THRESHOLD,RMS_DESCRIPTOR,FILTER_DESCRIPTOR->Unit.SCALAR;
                    case INDEPENDENT_SAMPLE_COUNT->Unit.DIMENSIONLESS;default->Unit.METRE;},
                Availability.UNAVAILABLE,OptionalDouble.empty()));
        for(var feature:List.of(Feature.BACKGROUND,Feature.RMS_DESCRIPTOR,Feature.FILTER_DESCRIPTOR,
                Feature.RAW_CENTER,Feature.B3_CENTER,Feature.B5_CENTER,Feature.NATIVE_PITCH,
                Feature.SHOULDER_MINIMUM,Feature.SHOULDER_MAXIMUM,Feature.LEFT_DIRECT_SPAN,Feature.RIGHT_DIRECT_SPAN)) {
            double value=switch(feature){case BACKGROUND->.1*amplitude;case NATIVE_PITCH->1;
                case SHOULDER_MINIMUM->-2;case SHOULDER_MAXIMUM->2;default->0;};
            var old=values.get(feature.ordinal());
            values.set(feature.ordinal(),new ScalarFact(feature,old.unit(),Availability.MEASURED,OptionalDouble.of(value)));
        }
        var stages=new ArrayList<Receipt>();
        for(var stage:ObservationStage.values()) stages.add(new StageReceipt(
                new NestedCenterReceipt(stage,true,true,2,1,3,amplitude,.9*amplitude,0),-1,1));
        return new ProfileFacts(profile,List.of(new ModeFacts("m",values,stages)),VERSION);
    }
}
