package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.UTFDataFormatException;
import java.security.MessageDigest;
import java.util.List;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.NestedCenterReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Exact historical factsHash DATA via streamed native record text/MUTF.
 * No record/list/optional toString, Formatter, MH adapter, provider lookup,
 * source/runtime permit or caller hash. Lists are borrowed immutable DATA.
 * Direct Double.toString uses one77056B initialized-normal numeric window;
 * current-thread converter/static-cache/cold/native/work/failure qualification
 * remains separate. UNIT oracles are not proof of those runtime premises.
 */
final class BSharedFactsDigest {
    static final long NUMERIC_WINDOW=77056,CONTROL_BYTES=80,BUFFER_BYTES=528,OUTPUT_BYTES=368;
    static String hashData(List<BIdentificationFacts.ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,
            BPhysicalWork physical,MessageDigest digest)throws UTFDataFormatException{
        return hash(facts,lattices,physical,digest,null);
    }
    static String hashCapturedData(List<BIdentificationFacts.ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,
            BPhysicalWork physical,MessageDigest digest,BSharedPositionEvidence.CapturedInput source)throws UTFDataFormatException{
        if(source==null)throw new IllegalArgumentException("Missing captured facts source");
        source.requireFactsDigestData(facts,lattices,physical);
        String result=hash(facts,lattices,physical,digest,source);
        source.requireFactsDigestData(facts,lattices,physical);
        return result;
    }
    private static String hash(List<BIdentificationFacts.ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,
            BPhysicalWork physical,MessageDigest digest,BSharedPositionEvidence.CapturedInput source)throws UTFDataFormatException{
        if(facts==null||lattices==null||facts.size()!=lattices.size())throw new IllegalArgumentException("Matching facts/lattices required");
        try(var out=Output.open(physical,digest,source)){
            out.utf(BIdentificationFacts.VERSION);out.i32(facts.size());
            for(int i=0;i<facts.size();i++){
                out.live();var row=facts.get(i);out.record(row.baseline());
                var modes=row.modes();
                for(int m=0;m<modes.size();m++){
                    out.live();var mode=modes.get(m);out.utf(mode.lineage());var values=mode.values();
                    for(int f=0;f<values.size();f++){
                        out.live();var fact=values.get(f);out.utf(fact.feature().name());out.utf(fact.unit().name());
                        out.utf(fact.availability().name());out.byteValue(fact.value().isPresent()?1:0);
                        if(fact.value().isPresent())out.i64(Double.doubleToLongBits(fact.value().getAsDouble()));
                    }
                    var receipts=mode.provenance();for(int r=0;r<receipts.size();r++){out.live();out.record(receipts.get(r));}
                }
                var lattice=lattices.get(i);var cells=lattice.cells();var components=lattice.components();
                for(int c=0;c<cells.size();c++){out.live();out.record(cells.get(c));}
                for(int c=0;c<components.size();c++){out.live();out.record(components.get(c));}
            }
            return out.finish();
        }
    }
    static String recordHashData(Object record,BPhysicalWork physical,MessageDigest digest)throws UTFDataFormatException{
        BSharedFactsTextExtent.headerData(record);
        try(var out=Output.open(physical,digest,null)){out.record(record);return out.finish();}
    }
    private static final class Output implements AutoCloseable {
        private final BSharedWork work;
        private final MessageDigest digest;
        private final byte[] bytes;
        private final AttemptMemoryLedger.Owner owner;
        private final BSharedFactsTextExtent extent;
        private final BSharedPositionEvidence.CapturedInput source;
        private int used;
        private long utfCount;
        private boolean measuring,closed;
        private Output(BSharedWork work,MessageDigest digest,AttemptMemoryLedger.Owner owner,BSharedFactsTextExtent extent,
                BSharedPositionEvidence.CapturedInput source){
            this.work=work;this.digest=digest;this.owner=owner;this.extent=extent;this.source=source;bytes=new byte[512];
        }
        static Output open(BPhysicalWork physical,MessageDigest digest,BSharedPositionEvidence.CapturedInput source){
            if(physical==null)throw new IllegalArgumentException("Missing facts digest attempt");
            if(Runtime.version().feature()!=17)throw new UnsupportedOperationException("Facts digest DATA requires Java17 source profile");
            var work=physical.sourceWork();
            if(source!=null)source.requireFactsDigestLiveData(work);
            work.binding(8192);work.poll();work.ensureOpen();
            if(source!=null)source.requireFactsDigestLiveData(work);
            if(digest==null||!"SHA-256".equals(digest.getAlgorithm())||digest.getProvider()==null
                    ||!"SUN".equals(digest.getProvider().getName())
                    ||!"sun.security.provider.Sun".equals(digest.getProvider().getClass().getName())
                    ||digest.getProvider().getClass().getClassLoader()!=null
                    ||!"java.security.MessageDigest$Delegate$CloneableDelegate".equals(digest.getClass().getName())
                    ||digest.getClass().getClassLoader()!=null||digest.getDigestLength()!=32)
                throw new IllegalArgumentException("Real borrowed SUN SHA-256 DATA engine required");
            work.requireOwner(physical.owner());work.requireProbe(physical.probe());
            work.metadata(CONTROL_BYTES+BUFFER_BYTES+OUTPUT_BYTES);var extent=BSharedFactsTextExtent.workspaceData(work,source);
            var owner=physical.owner().child("facts-native-decimal-scratch");
            try{
                owner.reserve(NUMERIC_WINDOW);var result=new Output(work,digest,owner,extent,source);
                result.live();digest.reset();result.live();return result;
            }catch(RuntimeException|Error primary){
                try{owner.close();}catch(RuntimeException|Error secondary){/*preserve primary*/}throw primary;
            }
        }
        void live(){
            if(source!=null)source.requireFactsDigestLiveData(work);
            work.poll();work.ensureOpen();
            if(source!=null)source.requireFactsDigestLiveData(work);
            if(closed||owner.isClosed())throw new IllegalStateException("Facts digest closed");
        }
        void byteValue(int value){if(used==bytes.length)flush();bytes[used++]=(byte)value;}
        void i32(int value){for(int shift=24;shift>=0;shift-=8)byteValue(value>>>shift);}
        void i64(long value){for(int shift=56;shift>=0;shift-=8)byteValue((int)(value>>>shift));}
        void flush(){if(used!=0){live();work.binding(512+4L*used);digest.update(bytes,0,used);used=0;live();}}
        void character(char c)throws UTFDataFormatException{
            int size=c>=1&&c<=127?1:c<=2047?2:3;long previous=utfCount;utfCount=Math.addExact(utfCount,size);
            if(utfCount>65535)throw new UTFDataFormatException("Legacy fact text exceeds modified UTF limit");
            if((previous>>>4)!=(utfCount>>>4))live();
            if(measuring)return;
            if(size==1)byteValue(c);
            else if(size==2){byteValue(192|(c>>>6));byteValue(128|(c&63));}
            else {byteValue(224|(c>>>12));byteValue(128|((c>>>6)&63));byteValue(128|(c&63));}
        }
        void text(String value)throws UTFDataFormatException{
            if(value==null)value="null";for(int i=0;i<value.length();i++)character(value.charAt(i));
        }
        void real(double value)throws UTFDataFormatException{
            live();String text=Double.toString(value);text(text);live();
        }
        void integer(int value)throws UTFDataFormatException{
            long n=value;if(n<0){character('-');n=-n;}long divisor=1;
            while(n/divisor>=10)divisor*=10;
            do{character((char)('0'+n/divisor));n%=divisor;divisor/=10;}while(divisor!=0);
        }
        void flag(boolean value)throws UTFDataFormatException{text(value?"true":"false");}
        void utf(String value)throws UTFDataFormatException{
            if(value==null)throw new IllegalArgumentException("Missing typed fact text");
            work.binding(8192+256L*value.length());measuring=true;utfCount=0;text(value);long length=utfCount;
            measuring=false;byteValue((int)(length>>>8));byteValue((int)length);utfCount=0;text(value);
            if(utfCount!=length)throw new IllegalStateException("Fact text changed");
        }
        void record(Object value)throws UTFDataFormatException{
            long characters=extent.measureData(value,source);
            work.binding(Math.addExact(8192,Math.addExact(Math.multiplyExact(256,characters),Math.multiplyExact(512,extent.fieldCountData()))));
            measuring=true;utfCount=0;emit(value,this);long length=utfCount;
            measuring=false;byteValue((int)(length>>>8));byteValue((int)length);utfCount=0;emit(value,this);
            if(utfCount!=length)throw new IllegalStateException("Native record text changed");
        }
        String finish(){
            flush();live();byte[] hash=digest.digest();live();if(hash.length!=32)throw new IllegalArgumentException("Unexpected SHA length");
            char[] hex=new char[64];String digits="0123456789abcdef";
            for(int i=0;i<32;i++){int value=hash[i]&255;hex[2*i]=digits.charAt(value>>>4);hex[2*i+1]=digits.charAt(value&15);}
            String result=new String(hex);live();return result;
        }
        @Override public void close(){if(!closed){closed=true;owner.close();}}
    }
    private static void emit(Object value,Output out)throws UTFDataFormatException{
        if(value==null){out.text("null");return;}
        if(value instanceof String s){out.text(s);return;}
        if(value instanceof Enum<?> e){out.text(e.name());return;}
        if(value instanceof Double d){out.real(d);return;}
        if(value instanceof OptionalDouble optional){
            if(optional.isEmpty())out.text("OptionalDouble.empty");
            else {out.text("OptionalDouble[");out.real(optional.getAsDouble());out.character(']');}return;
        }
        if(value instanceof List<?> list){
            out.character('[');for(int i=0;i<list.size();i++){if(i>0)out.text(", ");emit(list.get(i),out);}out.character(']');return;
        }
        if(value instanceof MetricPoint p){out.text("MetricPoint[xMeters=");out.real(p.xMeters());out.text(", yMeters=");out.real(p.yMeters());
        }else if(value instanceof ProbabilisticProfile p){
            out.text("ProbabilisticProfile[profileIndex=");out.integer(p.profileIndex());out.text(", chainageMeters=");out.real(p.chainageMeters());
            out.text(", anchor=");emit(p.anchor(),out);out.text(", normalUnit=");emit(p.normalUnit(),out);
            out.text(", minimumOffsetMeters=");out.real(p.minimumOffsetMeters());out.text(", maximumOffsetMeters=");out.real(p.maximumOffsetMeters());
            out.text(", sourcePitchMeters=");out.real(p.sourcePitchMeters());out.text(", nativePitchKnown=");out.flag(p.nativePitchKnown());
            out.text(", noiseFloor=");out.real(p.noiseFloor());out.text(", samples=");emit(p.samples(),out);out.text(", modes=");emit(p.modes(),out);
            out.text(", censoredModes=");emit(p.censoredModes(),out);out.text(", exactAnchorOffsetMeters=");emit(p.exactAnchorOffsetMeters(),out);
            out.text(", orientationSupport=");emit(p.orientationSupport(),out);
        }else if(value instanceof ProbabilisticProfile.Sample s){
            out.text("Sample[offsetMeters=");out.real(s.offsetMeters());out.text(", intensity=");out.real(s.intensity());out.text(", valid=");out.flag(s.valid());
        }else if(value instanceof ProbabilisticProfile.Mode m){
            out.text("Mode[id=");out.text(m.id());out.text(", evidenceLineage=");out.text(m.evidenceLineage());
            out.text(", coreMinimumMeters=");out.real(m.coreMinimumMeters());out.text(", coreMaximumMeters=");out.real(m.coreMaximumMeters());
            out.text(", localizationSigmaMeters=");out.real(m.localizationSigmaMeters());out.text(", existenceConfidence=");out.real(m.existenceConfidence());
            out.text(", localizationConfidence=");out.real(m.localizationConfidence());out.text(", peakOffsetsMeters=");emit(m.peakOffsetsMeters(),out);
            out.text(", nestedCenterOffsetsMeters=");emit(m.nestedCenterOffsetsMeters(),out);out.text(", groupedParent=");out.flag(m.groupedParent());
            out.text(", orientationSupport=");emit(m.orientationSupport(),out);out.text(", scalarAmplitudeReliability=");out.real(m.scalarAmplitudeReliability());
            out.text(", branchCoherence=");out.real(m.branchCoherence());out.text(", positionalReliability=");out.real(m.positionalReliability());
            out.text(", leftDirectSpanMeters=");out.real(m.leftDirectSpanMeters());out.text(", rightDirectSpanMeters=");out.real(m.rightDirectSpanMeters());
        }else if(value instanceof ProbabilisticProfile.CensoredMode m){
            out.text("CensoredMode[id=");out.text(m.id());out.text(", evidenceLineage=");out.text(m.evidenceLineage());out.text(", side=");emit(m.side(),out);
            out.text(", observedBoundaryMeters=");out.real(m.observedBoundaryMeters());out.text(", existenceConfidence=");out.real(m.existenceConfidence());
            out.text(", gradientSupported=");out.flag(m.gradientSupported());out.text(", inwardExtentMeasured=");out.flag(m.inwardExtentMeasured());
            out.text(", inwardSupportOffsetMeters=");out.real(m.inwardSupportOffsetMeters());out.text(", shoulderThreshold=");out.real(m.shoulderThreshold());
            out.text(", directionalReliability=");out.real(m.directionalReliability());
        }else if(value instanceof ImageOrientationSupport s){
            out.text("ImageOrientationSupport[status=");emit(s.status(),out);out.text(", modes=");emit(s.modes(),out);out.text(", certainty=");out.real(s.certainty());
        }else if(value instanceof ImageOrientationSupport.AngularMode m){
            out.text("AngularMode[startRadians=");out.real(m.startRadians());out.text(", endRadians=");out.real(m.endRadians());
            out.text(", peakBearingRadians=");out.real(m.peakBearingRadians());out.text(", response=");out.real(m.response());
        }else if(value instanceof NestedCenterReceipt r){
            out.text("NestedCenterReceipt[stage=");emit(r.stage(),out);out.text(", available=");out.flag(r.available());out.text(", intervalMeasured=");out.flag(r.intervalMeasured());
            out.text(", winnerSampleIndex=");out.integer(r.winnerSampleIndex());out.text(", firstCoreSampleIndex=");out.integer(r.firstCoreSampleIndex());
            out.text(", lastCoreSampleIndex=");out.integer(r.lastCoreSampleIndex());out.text(", localMaximum=");out.real(r.localMaximum());
            out.text(", threshold=");out.real(r.threshold());out.text(", centerOffsetMeters=");out.real(r.centerOffsetMeters());
        }else if(value instanceof BIdentificationFacts.RawReceipt r){
            out.text("RawReceipt[firstPeakIndex=");out.integer(r.firstPeakIndex());out.text(", lastPeakIndex=");out.integer(r.lastPeakIndex());out.text(", winnerIndex=");out.integer(r.winnerIndex());
        }else if(value instanceof BIdentificationFacts.FlankReceipt r){
            out.text("FlankReceipt[leftIndex=");out.integer(r.leftIndex());out.text(", rightIndex=");out.integer(r.rightIndex());out.text(", complete=");out.flag(r.complete());
        }else if(value instanceof BIdentificationFacts.StageReceipt r){
            out.text("StageReceipt[original=");emit(r.original(),out);out.text(", firstOffsetMeters=");out.real(r.firstOffsetMeters());out.text(", lastOffsetMeters=");out.real(r.lastOffsetMeters());
        }else if(value instanceof BIdentificationFacts.CorrelationReceipt r){
            out.text("CorrelationReceipt[fieldLineage=");out.text(r.fieldLineage());out.text(", transformId=");out.text(r.transformId());out.text(", nativePitchKnown=");out.flag(r.nativePitchKnown());
        }else if(value instanceof LateralStateCell c){
            out.text("LateralStateCell[offsetMeters=");out.real(c.offsetMeters());out.text(", quadratureWidthMeters=");out.real(c.quadratureWidthMeters());
            out.text(", exactAnchor=");out.flag(c.exactAnchor());out.text(", mandatory=");out.flag(c.mandatory());out.text(", branchLabel=");out.text(c.branchLabel());
        }else if(value instanceof ObservationComponent c){
            out.text("ObservationComponent[id=");out.text(c.id());out.text(", evidenceLineage=");out.text(c.evidenceLineage());out.text(", kind=");emit(c.kind(),out);
            out.text(", priorWeight=");out.real(c.priorWeight());out.text(", groupedParent=");out.flag(c.groupedParent());
        }else throw new IllegalArgumentException("Unsupported legacy record");
        out.character(']');
    }
}
