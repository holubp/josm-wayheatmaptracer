package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;

/** Bounded immutable diagnostic serialization, separate from runtime attempt-memory ownership. */
final class Format15BRunAdmissionReceipt {
    static final String ARTIFACT="b-run-admissions.bin";
    static final int MAX_BYTES=8*1024*1024,MAX_ENTRIES=4096;
    /** profiles is the frozen requested chainage count; native census is unavailable without witness. */
    record Entry(String id,String reason,String requestHash,String parameterHash,int profiles,
            TraceBudgets budgets,TraceWorkUsage usage,TraceHypothesisSet.Status status,
            String witness,Optional<ExecutionWorkReceipt> work) {
        boolean executed(){return requestHash!=null;}
    }
    record Decoded(String sourceHash,String inputHash,String parameterHash,String kind,List<Entry> entries) {
        Decoded {entries=List.copyOf(entries);}
    }
    private Format15BRunAdmissionReceipt() { }
    private static final class BoundedBytes extends ByteArrayOutputStream {
        BoundedBytes(){super(8192);}
        @Override public synchronized void write(int value){if(count==MAX_BYTES)throw new IllegalArgumentException("b-run-serialization-budget");super.write(value);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){if(length>MAX_BYTES-count)throw new IllegalArgumentException("b-run-serialization-budget");super.write(bytes,offset,length);}
    }
    static boolean intervalPolicy(TraceRequest request){return request.engine()==TrackerMode.PROBABILISTIC&&BPolicy.recorded(request)==BPolicy.INVENTORY_PREP_V4;}
    static boolean referencePolicy(TraceRequest request){return request.engine()==TrackerMode.LEGACY_THEN_B&&BReferencePolicyBinding.isNewParentHash(request.parameterHash());}
    static String requestHash(TraceRequest request) {
        try {var digest=MessageDigest.getInstance("SHA-256");try(var data=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){FrozenReplayCodec.writeRequestOnly(data,request);}return HexFormat.of().formatHex(digest.digest());}
        catch(IOException|NoSuchAlgorithmException failure){throw new IllegalArgumentException("b-run-request-binding-invalid",failure);}
    }
    private static Entry executed(String id,String reason,TraceRequest request,TraceHypothesisSet inference,TraceWorkUsage usage) {
        String witness=inference.explanation().startsWith(DenseBAdmission.VERSION+"|")?DenseBAdmission.witnessFromExplanation(inference.explanation()):null;
        return new Entry(id,reason,requestHash(request),request.parameterHash(),request.profileChainage().cumulativeGroundMeters().size(),
                request.budgets(),usage,inference.status(),witness,inference.workAccounting());
    }
    static Format15Artifact intervals(String sourceHash,FrozenReplayInput input,IntervalTraceBatch batch) {
        if(!intervalPolicy(input.request())||!batch.fullRequest().equals(input.request())||!batch.evidence().equals(input.evidence())||!batch.network().equals(input.network()))
            throw new IllegalArgumentException("b-run-interval-owner-mismatch");
        var entries=new ArrayList<Entry>();int ordinal=0;
        for(var run:batch.runs())entries.add(executed("interval-"+ordinal++,"EXECUTED",run.request(),run.result().inference(),run.usage()));
        return encode(new Decoded(sourceHash,input.canonicalHash(),input.request().parameterHash(),"INTERVAL",entries));
    }
    static Format15Artifact references(String sourceHash,FrozenReplayInput input,LegacyBProductionRunner.Result actual) {
        if(!referencePolicy(input.request()))throw new IllegalArgumentException("b-run-parent-policy-mismatch");
        var entries=new ArrayList<Entry>();
        for(var outcome:actual.batch().sections()) {
            if(outcome.execution().isEmpty()){entries.add(new Entry(outcome.section().id(),outcome.reason().name(),null,null,0,null,null,null,null,Optional.empty()));continue;}
            var execution=outcome.execution().orElseThrow();var reference=execution.reference().orElseThrow(()->new IllegalArgumentException("b-run-reference-unavailable"));
            if(!reference.originalRequest().equals(input.request())||!reference.originalEvidence().equals(input.evidence())||BReferencePolicyBinding.recordedPolicy(reference)!=BPolicy.INVENTORY_PREP_V4)
                throw new IllegalArgumentException("b-run-reference-owner-mismatch");
            entries.add(executed(outcome.section().id(),outcome.reason().name(),reference.request(),execution.run().result(),execution.run().usage()));
        }
        return encode(new Decoded(sourceHash,input.canonicalHash(),input.request().parameterHash(),"REFERENCE",entries));
    }
    static Format15Artifact references(Format15Bundle bundle,FrozenReplayInput input,LiveBPreviewService.LegacyBComputation actual) {
        if(actual.input().request()!=input.request()||actual.input().evidence()!=input.evidence()||actual.input().network()!=input.network())throw new IllegalArgumentException("b-run-reference-owner-mismatch");
        return references(bundle.sourceIdentityHash(),input,actual.result());
    }
    private static void text(DataOutputStream out,String value)throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>2*1024*1024)throw new IllegalArgumentException("b-run-text-budget");
        out.writeInt(bytes.length);out.write(bytes);
    }
    private static String text(DataInputStream in)throws IOException {
        int n=in.readInt();if(n<0||n>2*1024*1024||n>in.available())throw new IllegalArgumentException("b-run-text-budget");
        return new String(in.readNBytes(n),StandardCharsets.UTF_8);
    }
    private static void budget(DataOutputStream out,TraceBudgets b)throws IOException {out.writeInt(b.maximumStatesPerProfile());out.writeLong(b.maximumPairVisits());out.writeLong(b.maximumTransitions());out.writeInt(b.maximumRawAlternatives());out.writeInt(b.maximumDistinctAlternatives());}
    private static TraceBudgets budget(DataInputStream in)throws IOException {return new TraceBudgets(in.readInt(),in.readLong(),in.readLong(),in.readInt(),in.readInt());}
    static Format15Artifact encode(Decoded value) {
        if(value.entries().size()>MAX_ENTRIES)throw new IllegalArgumentException("b-run-entry-budget");
        try {var bytes=new BoundedBytes();try(var data=new DataOutputStream(bytes)) {
            data.writeInt(1);text(data,value.sourceHash());text(data,value.inputHash());text(data,value.parameterHash());text(data,value.kind());data.writeInt(value.entries().size());
            for(var entry:value.entries()) {
                text(data,entry.id());text(data,entry.reason());data.writeBoolean(entry.executed());if(!entry.executed())continue;
                text(data,entry.requestHash());text(data,entry.parameterHash());data.writeInt(entry.profiles());budget(data,entry.budgets());
                data.writeLong(entry.usage().pairVisits());data.writeLong(entry.usage().transitions());data.writeInt(entry.usage().rawAlternatives());data.writeInt(entry.usage().distinctAlternatives());text(data,entry.status().name());
                data.writeBoolean(entry.witness()!=null);if(entry.witness()!=null)text(data,entry.witness());
                text(data,new String(Format15ExecutionWorkCodec.artifact(List.of(entry.work())).bytes(),StandardCharsets.UTF_8));
            }
        }return Format15Artifact.binary(ARTIFACT,bytes.toByteArray());}catch(IOException failure){throw new IllegalArgumentException("b-run-serialization-budget",failure);}
    }
    static Decoded read(Format15Artifact artifact) {
        if(!ARTIFACT.equals(artifact.name())||artifact.sizeBytes()>MAX_BYTES)throw new IllegalArgumentException("b-run-receipt-budget");
        try(var in=new DataInputStream(new ByteArrayInputStream(artifact.bytes()))) {
            if(in.readInt()!=1)throw new IllegalArgumentException("b-run-schema");String source=text(in),input=text(in),parameter=text(in),kind=text(in);
            Format15Safety.requiredHash(source,"source");Format15Safety.requiredHash(input,"input");Format15Safety.requiredHash(parameter,"parameter");
            if(!kind.equals("INTERVAL")&&!kind.equals("REFERENCE"))throw new IllegalArgumentException("b-run-kind");
            int n=in.readInt();if(n<0||n>MAX_ENTRIES)throw new IllegalArgumentException("b-run-entry-budget");var entries=new ArrayList<Entry>();
            for(int i=0;i<n;i++) {
                String id=text(in),reason=text(in);if(id.isBlank()||id.length()>1024||reason.isBlank()||reason.length()>128)throw new IllegalArgumentException("b-run-entry-identity");
                if(!in.readBoolean()){entries.add(new Entry(id,reason,null,null,0,null,null,null,null,Optional.empty()));continue;}
                String request=text(in),policy=text(in);Format15Safety.requiredHash(request,"request");Format15Safety.requiredHash(policy,"policy");int count=in.readInt();if(count<1)throw new IllegalArgumentException("b-run-row-count");
                var limits=budget(in);var usage=new TraceWorkUsage(in.readLong(),in.readLong(),in.readInt(),in.readInt());var status=TraceHypothesisSet.Status.valueOf(text(in));String witness=in.readBoolean()?text(in):null;
                var work=Format15ExecutionWorkCodec.read(Format15Artifact.text(Format15ExecutionWorkCodec.ARTIFACT,text(in)));if(work.size()!=1)throw new IllegalArgumentException("b-run-work-count");
                entries.add(new Entry(id,reason,request,policy,count,limits,usage,status,witness,work.get(0)));
            }
            if(in.available()!=0)throw new IllegalArgumentException("b-run-trailing-data");return new Decoded(source,input,parameter,kind,entries);
        }catch(IOException failure){throw new IllegalArgumentException("b-run-receipt-invalid",failure);}
    }
    static List<Optional<ExecutionWorkReceipt>> work(Decoded value){return value.entries().stream().filter(Entry::executed).map(Entry::work).toList();}
    static String workName(Decoded value){return value.kind().equals("REFERENCE")?LegacyBReplayWork.ARTIFACT:Format15ExecutionWorkCodec.ARTIFACT;}
    static Format15Artifact workArtifact(Decoded value){
        var work=work(value);
        return work.isEmpty()?Format15Artifact.text(Format15ExecutionWorkCodec.ARTIFACT,"{\"schema\":1,\"runs\":[]}\n"):Format15ExecutionWorkCodec.artifact(work);
    }
    static boolean attested(Decoded value,TraceRequest root) {
        long pairs=0,scores=0;boolean attested=true;
        for(var e:value.entries()) {
            if(!e.executed())continue;
            var b=e.budgets();var usage=e.usage();
            if(b.maximumStatesPerProfile()!=root.budgets().maximumStatesPerProfile()||b.maximumPairVisits()>root.budgets().maximumPairVisits()
                    ||b.maximumTransitions()>root.budgets().maximumTransitions()||b.maximumRawAlternatives()>root.budgets().maximumRawAlternatives()||b.maximumDistinctAlternatives()>root.budgets().maximumDistinctAlternatives()
                    ||usage.pairVisits()>b.maximumPairVisits()||usage.transitions()>b.maximumTransitions()||usage.rawAlternatives()>b.maximumRawAlternatives()||usage.distinctAlternatives()>b.maximumDistinctAlternatives())
                throw new IllegalArgumentException("b-run-work-budget");
            pairs=Math.addExact(pairs,usage.pairVisits());scores=Math.addExact(scores,usage.transitions());
            if(e.witness()==null||e.work().isEmpty()||e.status()==TraceHypothesisSet.Status.CANCELLED){attested=false;continue;}
            var bounds=DenseBAdmission.validateWitness(e.witness(),b,e.profiles());var nativeWork=e.work().orElseThrow();
            String expected=bounds.admitted()?BPolicy.INVENTORY_FULL_VERSION:BPolicy.INVENTORY_COMPACT_VERSION;
            if(nativeWork.bStages().size()!=1||nativeWork.otherEngineTransitions()!=0||!nativeWork.hasPolicy(expected)||nativeWork.computedTransitions()!=usage.transitions()
                    ||bounds.admitted()&&(nativeWork.computedTransitions()>bounds.scoreUpper()||nativeWork.bStages().get(0).refusedScoreAttempts()!=0))
                throw new IllegalArgumentException("b-run-native-work-mismatch");
        }
        if(pairs>root.budgets().maximumPairVisits()||scores>root.budgets().maximumTransitions())throw new IllegalArgumentException("b-run-cumulative-work-budget");
        return attested;
    }
    static Decoded validate(Format15Archive archive,FrozenReplayInput input,String inputHash) {
        try {
            var value=read(archive.artifact(ARTIFACT).orElseThrow());
            if(!value.sourceHash().equals(archive.sourceIdentityHash())||!value.inputHash().equals(inputHash)||!value.parameterHash().equals(input.request().parameterHash())
                    ||!(value.kind().equals("INTERVAL")?intervalPolicy(input.request()):referencePolicy(input.request())))throw new IllegalArgumentException("binding");
            var expectedWork=workArtifact(value);if(!archive.artifact(workName(value)).orElseThrow().sha256().equals(expectedWork.sha256()))throw new IllegalArgumentException("work-binding");
            if(!attested(value,input.request()))throw new ReplayMismatchException("b-run-result-replay-unavailable");
            if(value.kind().equals("INTERVAL")) {
                var payload=FrozenIntervalReplayCodec.decode(archive.artifact(FrozenIntervalReplayCodec.ARTIFACT).orElseThrow().bytes());
                if(payload.runs().size()!=value.entries().size())throw new IllegalArgumentException("interval-count");
                for(int i=0;i<payload.runs().size();i++){var request=payload.runs().get(i).request();var e=value.entries().get(i);if(!e.id().equals("interval-"+i)||!e.executed()||!e.requestHash().equals(requestHash(request))||!e.parameterHash().equals(request.parameterHash())||e.profiles()!=request.profileChainage().cumulativeGroundMeters().size()||!e.budgets().equals(request.budgets()))throw new IllegalArgumentException("interval-request-binding");}
            }
            return value;
        }catch(ReplayMismatchException failure){throw failure;}catch(RuntimeException failure){throw new ReplayMismatchException("b-run-admission-receipt-invalid");}
    }
    static void validateActualIntervals(Format15Archive archive,FrozenReplayInput input,IntervalTraceBatch batch) {
        if(archive.artifact(ARTIFACT).isPresent()&&!intervals(archive.sourceIdentityHash(),input,batch).sha256().equals(archive.artifact(ARTIFACT).orElseThrow().sha256()))
            throw new ReplayMismatchException("b-run-actual-interval-mismatch");
    }
    static void validateActualReference(Format15Archive archive,Format15ReplayRunner.Result result) {
        if(archive.artifact(ARTIFACT).isPresent()&&(result.bRunAdmissions()==null||!result.bRunAdmissions().sha256().equals(archive.artifact(ARTIFACT).orElseThrow().sha256())))
            throw new ReplayMismatchException("b-run-actual-reference-mismatch");
    }
}
