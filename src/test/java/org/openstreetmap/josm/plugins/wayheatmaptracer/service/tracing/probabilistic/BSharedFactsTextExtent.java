package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.NestedCenterReceipt;

/**
 * Structural UTF16 output upper for the native record strings reached by the
 * historical factsHash. Does NOT render/hash, price native formatting scratch,
 * issue source/runtime authority, or reject from an upper>MUTF65535 alone.
 * Repeated aliases are traversed with their actual rendering multiplicity.
 */
final class BSharedFactsTextExtent {
    static final long DECIMAL=32,INTEGER=11,BOOLEAN=5,OWN_BYTES=216;
    private final BSharedWork work;
    private BSharedPositionEvidence.CapturedInput source;
    private long recordChars,listChars,numericChars,optionalChars,records,lists,numerics,optionals,fields;
    private int maximumDepth;
    record Extent(long characters,long recordCharacters,long listCharacters,long numericCharacters,
            long optionalCharacters,long recordCount,long listCount,long numericCount,
            long optionalPresentCount,long fieldCount,int maximumDepth) { }
    private BSharedFactsTextExtent(BSharedWork work){this.work=work;}

    static BSharedFactsTextExtent workspaceData(BSharedWork work){
        return workspaceData(work,null);
    }
    static BSharedFactsTextExtent workspaceData(BSharedWork work,BSharedPositionEvidence.CapturedInput source){
        if(work==null)throw new IllegalArgumentException("Missing extent work");
        if(source!=null)source.requireFactsDigestLiveData(work);
        work.binding(8192);work.poll();work.ensureOpen();
        if(source!=null)source.requireFactsDigestLiveData(work);
        work.metadata(112);
        return new BSharedFactsTextExtent(work);
    }
    long measureData(Object record){
        return measureData(record,null);
    }
    long measureData(Object record,BSharedPositionEvidence.CapturedInput captured){
        source=captured;
        try{
            live();headerData(record);
            recordChars=listChars=numericChars=optionalChars=records=lists=numerics=optionals=fields=0;maximumDepth=0;
            long result=value(record,1);live();return result;
        }finally{source=null;}
    }
    long fieldCountData(){work.ensureOpen();return fields;}

    static Extent analyzeData(Object record,BSharedWork work){
        if(work==null)throw new IllegalArgumentException("Missing extent work");
        work.binding(8192);work.poll();work.ensureOpen();headerData(record);
        //Accumulator2refs+9long+int=O92=112; result10long+int=O84=104.
        work.metadata(OWN_BYTES);var state=new BSharedFactsTextExtent(work);
        long characters=state.value(record,1);work.poll();work.ensureOpen();
        var result=new Extent(characters,state.recordChars,state.listChars,state.numericChars,state.optionalChars,
            state.records,state.lists,state.numerics,state.optionals,state.fields,state.maximumDepth);
        work.poll();work.ensureOpen();return result;
    }
    private void tick(int depth){
        work.binding(4096);live();maximumDepth=Math.max(maximumDepth,depth);
    }
    private void live(){
        if(source!=null)source.requireFactsDigestLiveData(work);
        work.poll();work.ensureOpen();
        if(source!=null)source.requireFactsDigestLiveData(work);
    }
    private static long add(long a,long b){return Math.addExact(a,b);}
    private long numeric(int doubles,int integers){
        long count=add(doubles,integers),chars=add(Math.multiplyExact(DECIMAL,doubles),Math.multiplyExact(INTEGER,integers));
        numerics=add(numerics,count);numericChars=add(numericChars,chars);return chars;
    }
    private long value(Object v,int depth){
        tick(depth);
        if(v==null)return 4;
        if(v instanceof String s)return s.length();
        if(v instanceof Enum<?> e)return e.name().length();
        if(v instanceof Double)return numeric(1,0);
        if(v instanceof Integer)return numeric(0,1);
        if(v instanceof Boolean)return BOOLEAN;
        if(v instanceof OptionalDouble optional){
            if(optional.isEmpty())return 20; //borrowed constant OptionalDouble.empty
            long n=add(16,numeric(1,0));optionals=add(optionals,1);optionalChars=add(optionalChars,n);return n;
        }
        if(v instanceof List<?> list){
            long n=list.isEmpty()?2:add(2,Math.multiplyExact(2L,list.size()-1L));
            for(int i=0;i<list.size();i++)n=add(n,value(list.get(i),depth+1));
            lists=add(lists,1);listChars=add(listChars,n);return n;
        }
        long n=headerData(v).length();int count;
        if(v instanceof ProbabilisticProfile p){
            count=14;n=add(n,add(numeric(5,1),BOOLEAN));
            n=add(n,value(p.anchor(),depth+1));n=add(n,value(p.normalUnit(),depth+1));
            n=add(n,value(p.samples(),depth+1));n=add(n,value(p.modes(),depth+1));
            n=add(n,value(p.censoredModes(),depth+1));n=add(n,value(p.exactAnchorOffsetMeters(),depth+1));
            n=add(n,value(p.orientationSupport(),depth+1));
        }else if(v instanceof MetricPoint){count=2;n=add(n,numeric(2,0));
        }else if(v instanceof ProbabilisticProfile.Sample){count=3;n=add(n,add(numeric(2,0),BOOLEAN));
        }else if(v instanceof ProbabilisticProfile.Mode m){
            count=16;n=add(n,add(numeric(10,0),BOOLEAN));
            n=add(n,value(m.id(),depth+1));n=add(n,value(m.evidenceLineage(),depth+1));
            n=add(n,value(m.peakOffsetsMeters(),depth+1));n=add(n,value(m.nestedCenterOffsetsMeters(),depth+1));
            n=add(n,value(m.orientationSupport(),depth+1));
        }else if(v instanceof ProbabilisticProfile.CensoredMode m){
            count=10;n=add(n,add(numeric(5,0),2*BOOLEAN));
            n=add(n,value(m.id(),depth+1));n=add(n,value(m.evidenceLineage(),depth+1));n=add(n,value(m.side(),depth+1));
        }else if(v instanceof ImageOrientationSupport s){
            count=3;n=add(n,numeric(1,0));n=add(n,value(s.status(),depth+1));n=add(n,value(s.modes(),depth+1));
        }else if(v instanceof ImageOrientationSupport.AngularMode){count=4;n=add(n,numeric(4,0));
        }else if(v instanceof NestedCenterReceipt r){
            count=9;n=add(n,add(numeric(3,3),2*BOOLEAN));n=add(n,value(r.stage(),depth+1));
        }else if(v instanceof BIdentificationFacts.RawReceipt){count=3;n=add(n,numeric(0,3));
        }else if(v instanceof BIdentificationFacts.FlankReceipt){count=3;n=add(n,add(numeric(0,2),BOOLEAN));
        }else if(v instanceof BIdentificationFacts.StageReceipt r){
            count=3;n=add(n,numeric(2,0));n=add(n,value(r.original(),depth+1));
        }else if(v instanceof BIdentificationFacts.CorrelationReceipt r){
            count=3;n=add(n,BOOLEAN);n=add(n,value(r.fieldLineage(),depth+1));n=add(n,value(r.transformId(),depth+1));
        }else if(v instanceof LateralStateCell c){
            count=5;n=add(n,add(numeric(2,0),2*BOOLEAN));n=add(n,value(c.branchLabel(),depth+1));
        }else if(v instanceof ObservationComponent c){
            count=5;n=add(n,add(numeric(1,0),BOOLEAN));
            n=add(n,value(c.id(),depth+1));n=add(n,value(c.evidenceLineage(),depth+1));n=add(n,value(c.kind(),depth+1));
        }else throw new IllegalArgumentException("Unsupported legacy record");
        records=add(records,1);fields=add(fields,count);recordChars=add(recordChars,n);return n;
    }
    static String headerData(Object value){
        if(value instanceof ProbabilisticProfile)return "ProbabilisticProfile[profileIndex=, chainageMeters=, anchor=, normalUnit=, minimumOffsetMeters=, maximumOffsetMeters=, sourcePitchMeters=, nativePitchKnown=, noiseFloor=, samples=, modes=, censoredModes=, exactAnchorOffsetMeters=, orientationSupport=]";
        if(value instanceof MetricPoint)return "MetricPoint[xMeters=, yMeters=]";
        if(value instanceof ProbabilisticProfile.Sample)return "Sample[offsetMeters=, intensity=, valid=]";
        if(value instanceof ProbabilisticProfile.Mode)return "Mode[id=, evidenceLineage=, coreMinimumMeters=, coreMaximumMeters=, localizationSigmaMeters=, existenceConfidence=, localizationConfidence=, peakOffsetsMeters=, nestedCenterOffsetsMeters=, groupedParent=, orientationSupport=, scalarAmplitudeReliability=, branchCoherence=, positionalReliability=, leftDirectSpanMeters=, rightDirectSpanMeters=]";
        if(value instanceof ProbabilisticProfile.CensoredMode)return "CensoredMode[id=, evidenceLineage=, side=, observedBoundaryMeters=, existenceConfidence=, gradientSupported=, inwardExtentMeasured=, inwardSupportOffsetMeters=, shoulderThreshold=, directionalReliability=]";
        if(value instanceof ImageOrientationSupport)return "ImageOrientationSupport[status=, modes=, certainty=]";
        if(value instanceof ImageOrientationSupport.AngularMode)return "AngularMode[startRadians=, endRadians=, peakBearingRadians=, response=]";
        if(value instanceof NestedCenterReceipt)return "NestedCenterReceipt[stage=, available=, intervalMeasured=, winnerSampleIndex=, firstCoreSampleIndex=, lastCoreSampleIndex=, localMaximum=, threshold=, centerOffsetMeters=]";
        if(value instanceof BIdentificationFacts.RawReceipt)return "RawReceipt[firstPeakIndex=, lastPeakIndex=, winnerIndex=]";
        if(value instanceof BIdentificationFacts.FlankReceipt)return "FlankReceipt[leftIndex=, rightIndex=, complete=]";
        if(value instanceof BIdentificationFacts.StageReceipt)return "StageReceipt[original=, firstOffsetMeters=, lastOffsetMeters=]";
        if(value instanceof BIdentificationFacts.CorrelationReceipt)return "CorrelationReceipt[fieldLineage=, transformId=, nativePitchKnown=]";
        if(value instanceof LateralStateCell)return "LateralStateCell[offsetMeters=, quadratureWidthMeters=, exactAnchor=, mandatory=, branchLabel=]";
        if(value instanceof ObservationComponent)return "ObservationComponent[id=, evidenceLineage=, kind=, priorWeight=, groupedParent=]";
        throw new IllegalArgumentException("Unsupported legacy record");
    }
}
