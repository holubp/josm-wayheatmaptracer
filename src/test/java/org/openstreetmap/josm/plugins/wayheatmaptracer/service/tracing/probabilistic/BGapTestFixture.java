package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.util.function.DoubleBinaryOperator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

/** Declared toy observations/owned inert coordinate carriers; never passed to inference. */
final class BGapTestFixture implements AutoCloseable {
    final AttemptMemoryLedger ledger;
    final AttemptMemoryLedger.Owner owner;
    final BGapEvidence.Input input;
    BGapTestFixture(int oversampling,DoubleBinaryOperator scalar,boolean invalidInterior,boolean complete,int rows,int states) {
        this(oversampling,scalar,invalidInterior,complete,rows,states,false,false);
    }
    BGapTestFixture(int oversampling,DoubleBinaryOperator scalar,boolean invalidInterior,boolean complete,int rows,int states,
            boolean halfPhase,boolean vertical) {
        this(oversampling,scalar,invalidInterior,complete,rows,states,halfPhase,vertical,0);
    }
    BGapTestFixture(int oversampling,DoubleBinaryOperator scalar,boolean invalidInterior,boolean complete,int rows,int states,
            boolean halfPhase,boolean vertical,int shape) {
        this(oversampling,scalar,invalidInterior,complete,rows,states,halfPhase,vertical,shape,null);
    }
    BGapTestFixture(int oversampling,DoubleBinaryOperator scalar,boolean invalidInterior,boolean complete,int rows,int states,
            boolean halfPhase,boolean vertical,int shape,int[] stateCounts) {
        ledger=new AttemptMemoryLedger(BGapEvidence.BYTES);owner=ledger.rootOwner();
        int w=2*oversampling+1+(halfPhase?1:0),h=10*oversampling+1+(halfPhase?1:0);
        double shift=halfPhase?-.25:0;
        double[] values=new double[w*h];boolean[] valid=new boolean[w*h];Arrays.fill(valid,true);
        boolean[] support=new boolean[(w-1)*(h-1)];Arrays.fill(support,true);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)values[y*w+x]=scalar.applyAsDouble(shift+(double)x/oversampling,-4+shift+(double)y/oversampling);
        if(invalidInterior)support[4*oversampling*(w-1)]=false;
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var transform=vertical?RasterMetricTransform.metricGrid(new MetricPoint(4,0),0,1.0/oversampling,-1.0/oversampling,0)
                :RasterMetricTransform.metricGrid(new MetricPoint(shift,-4+shift),1.0/oversampling,0,0,1.0/oversampling);
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        var field=new ScalarEvidenceField(w,h,values,valid,support,lineage);
        var region=vertical?MetricRegion.rectangle(-6,0,4,2):MetricRegion.rectangle(0,-4,2,6);
        var affine=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(-0.0,4),1,0,0,1);
        var provenance=oversampling==1?RasterResamplingProvenance.direct(w,h):RasterResamplingProvenance.exactInverseBilinear(
                affine.kindId(),affine.parameterIdentity(),3,11,w,h);
        var evidence=new EvidenceSnapshot("gap-toy",frame,transform,EvidenceResolution.nativeSource(1,1).resampledTo(1.0/oversampling),
                region,region,Map.of("scalar",field),provenance,"gap-toy-source");
        var facts=new ArrayList<ProfileFacts>();var profiles=new ArrayList<InferenceProfile>();
        for(int row=0;row<rows;row++) {
            var anchor=vertical?new MetricPoint(0,2.0*row/(rows-1)):new MetricPoint(2.0*row/(rows-1),0);
            if(shape==1&&row==1)anchor=new MetricPoint(anchor.xMeters(),.25);
            if(shape==2)anchor=new MetricPoint(row==1?1.5:row==2?1:0,0);
            if(shape==3&&row==1)anchor=new MetricPoint(.75,0);
            var normal=vertical?new MetricPoint(-1,0):new MetricPoint(0,1);
            var samples=new ArrayList<ProbabilisticProfile.Sample>();
            for(int i=0;i<11;i++)samples.add(new ProbabilisticProfile.Sample(i-4,observed(i-4),true));
            var modes=List.of(mode("lower",-2),mode("upper",4));
            var raw=new ProbabilisticProfile(row,2.0*row/(rows-1),anchor,normal,-4,6,1,true,0,
                    samples,modes,List.of(),OptionalDouble.empty(),List.of(),0);
            var mf=new ArrayList<ModeFacts>();
            for(int m=0;m<2;m++) {
                double center=m==0?-2:4,peak=m==0?.75:.625,left=center-1,right=center+1;
                var scalars=new ArrayList<ScalarFact>();
                for(var feature:Feature.values()) {
                    Double value=switch(feature) {
                        case PEAK -> peak;case BACKGROUND -> .25;case RMS_DESCRIPTOR -> .125;
                        case FILTER_DESCRIPTOR -> .0625;case SHOULDER_THRESHOLD -> .25;
                        case SHOULDER_MINIMUM -> left;case SHOULDER_MAXIMUM -> right;
                        case RAW_CENTER,B3_CENTER,B5_CENTER -> center;case NATIVE_PITCH -> 1.0;
                        case CORE_MINIMUM,CORE_MAXIMUM -> center;case LEFT_DIRECT_SPAN,RIGHT_DIRECT_SPAN -> 0.0;
                        default -> null;
                    };
                    scalars.add(new ScalarFact(feature,expectedUnit(feature),value==null?Availability.UNAVAILABLE:Availability.MEASURED,
                            value==null?OptionalDouble.empty():OptionalDouble.of(value)));
                }
                int peakIndex=(int)center+4;
                var receipts=new ArrayList<Receipt>();
                receipts.add(new RawReceipt(peakIndex,peakIndex,peakIndex));
                receipts.add(new FlankReceipt(peakIndex-1,peakIndex+1,complete));
                for(var stage:LocalScalarProfileExtractor.ObservationStage.values())receipts.add(new StageReceipt(
                        new LocalScalarProfileExtractor.NestedCenterReceipt(stage,true,true,peakIndex,peakIndex,peakIndex,
                                peak,peak-.25,center),center,center));
                receipts.add(new CorrelationReceipt(evidence.sourceIdentity(),transform.transformId(),true));
                mf.add(new ModeFacts(modes.get(m).evidenceLineage(),scalars,receipts));
            }
            var fact=new ProfileFacts(raw,mf,VERSION);
            long charge=1024+96L*samples.size()+8192L*modes.size();
            for(var mode:modes)charge+=4L*(mode.id().length()+mode.evidenceLineage().length());
            try(var reservation=owner.reserve(charge)){reservation.adopt(fact);}
            facts.add(fact);
            var cells=new ArrayList<LateralStateCell>();
            int count=stateCounts==null?states:stateCounts[row];
            for(int state=0;state<count;state++)cells.add(new LateralStateCell(.5+state*.5,1,false,false,"unlocalized"));
            profiles.add(InferenceProfile.accounted(raw.chainageMeters(),anchor,normal,cells,new double[count],Map.of(),Map.of(),
                    ObservationOwnership.NO_SIGNAL_VALID_RASTER,true,new double[count],new double[count][0],owner));
        }
        input=new BGapEvidence.Input(evidence,"scalar",facts,profiles,BGapEvidence.approvedContract());
    }
    BGapEvidence.Input changeMode(int row,int mode,java.util.function.UnaryOperator<ModeFacts> edit) {
        var facts=new ArrayList<>(input.facts());var original=facts.get(row);var modes=new ArrayList<>(original.modes());
        modes.set(mode,edit.apply(modes.get(mode)));var changed=new ProfileFacts(original.baseline(),modes,VERSION);
        long bytes=1024+96L*original.baseline().samples().size()+8192L*modes.size();
        for(var raw:original.baseline().modes())bytes+=4L*(raw.id().length()+raw.evidenceLineage().length());
        try(var reservation=owner.reserve(bytes)){reservation.adopt(changed);}facts.set(row,changed);
        return new BGapEvidence.Input(input.evidence(),input.fieldKey(),facts,input.profiles(),input.contract());
    }
    static double observed(double y) {
        if(y==-2)return .75;if(y==4)return .625;
        if(y==-3||y==-1||y==3||y==5)return .25;
        return .125;
    }
    static ProbabilisticProfile.Mode mode(String id,double center) {
        return new ProbabilisticProfile.Mode(id,id,center,center,.5,1,1,List.of(center),List.of(center),false);
    }
    static BGapTestFixture standard() {return new BGapTestFixture(1,(x,y)->observed(y),false,true,2,1);}
    /** Test-only identity census: byte equality alone cannot detect leaked empty owners. */
    static Set<AttemptMemoryLedger.Owner> ownerTree(AttemptMemoryLedger.Owner owner) throws ReflectiveOperationException {
        Set<AttemptMemoryLedger.Owner> result=Collections.newSetFromMap(new IdentityHashMap<>());
        collectOwners(owner,result);return result;
    }
    @SuppressWarnings("unchecked")
    private static void collectOwners(AttemptMemoryLedger.Owner owner,Set<AttemptMemoryLedger.Owner> result) throws ReflectiveOperationException {
        if(!result.add(owner))throw new AssertionError("Repeated owner in tree");
        var field=AttemptMemoryLedger.Owner.class.getDeclaredField("children");field.setAccessible(true);
        for(var child:(Set<AttemptMemoryLedger.Owner>)field.get(owner))collectOwners(child,result);
    }
    public void close(){owner.close();}
}
