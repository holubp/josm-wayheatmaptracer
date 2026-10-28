package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Read-only exploratory consumer; never a qualified run or production Apply. */
final class BSharedFeasibilityComparison {
    static final String EVIDENCE_CLASS="EXPLORATORY_QUALITY_ONLY_V1";
    record Settings(int gain,double eta){
        Settings{if(!(gain==1||gain==4||gain==16||gain==64)||!(eta==0||eta==.25||eta==1||eta==4))
            throw new IllegalArgumentException("Fixed feasibility family required");}
        int etaIndex(){return eta==0?0:eta==.25?1:eta==1?2:3;}
    }
    record Request(String caseId,Settings settings,Path output){
        Request{if(settings==null||output==null)throw new IllegalArgumentException("Complete feasibility request required");
            BSharedPositionComparison.requireAttempt(caseId,settings.gain(),settings.etaIndex());}
    }
    private BSharedFeasibilityComparison(){}
    static Map<String,Object> executeTiny(CancellationProbe cancellation){
        long start=System.nanoTime();
        CancellationProbe probe=()->cancellation.cancelled()||System.nanoTime()-start>=300_000_000_000L;
        var work=BPhysicalWork.createExploratory(probe);var root=work.owner();
        var receipt=new LinkedHashMap<String,Object>();
        try(var source=BSharedFeasibilityInputs.tiny(work);
            var table=BPhysicalPairTable.buildExploratory(work,source,EvidenceModelParameters.defaults(),1)){
            var result=table.solveStoredRaw1(new TraceBudgets(96,8_000_000,128_000_000,1,1),2);
            receipt.put("evidenceClass",EVIDENCE_CLASS);receipt.put("solverInvocations",table.solverInvocations());
            receipt.put("routeReturned",result.result().mapPath().isPresent());
            receipt.put("runtimeQualified",false);receipt.put("sharedRowsUsed",table.sharedRowsPrepared()>0);
            receipt.put("qualifiedProspectiveEnvelopes","UNAVAILABLE");
        }finally{work.close();}
        receipt.put("trackedBytesAfterClose",root.currentBytes());return receipt;
    }
    /** One fresh attempt; mandatory receipts never treat a refusal as a quality pass. */
    static Map<String,Object> execute(Request request,LongSupplier clock,CancellationProbe cancellation)throws Exception{
        if(!"true".equals(System.getenv("CI"))||!"true".equals(System.getenv("V022_RUN_SHARED_FEASIBILITY")))
            throw new IllegalStateException("Original numerical execution is CI-only");
        long start=clock.getAsLong();
        CancellationProbe probe=()->cancellation.cancelled()||clock.getAsLong()-start>=300_000_000_000L;
        Files.createDirectory(request.output()); //An existing namespace cannot be retried or overwritten.
        var receipt=new LinkedHashMap<String,Object>();
        receipt.put("evidenceClass",EVIDENCE_CLASS);receipt.put("caseId",request.caseId());
        receipt.put("gain",request.settings().gain());receipt.put("eta",request.settings().eta());
        receipt.put("runtimeQualified",false);receipt.put("totalPluginMemoryQualified",false);
        receipt.put("qualifiedProspectiveEnvelopes","UNAVAILABLE");
        receipt.put("javaRuntime",System.getProperty("java.runtime.version"));
        receipt.put("javaVendor",System.getProperty("java.vendor"));
        receipt.put("sourceRevision",System.getenv("GITHUB_SHA"));
        var work=BPhysicalWork.createExploratory(probe);var root=work.owner();
        BPhysicalPairTable observedTable=null;String phase="CAPTURE";
        try(var source=BSharedFeasibilityInputs.capture(request.caseId(),work)){
            receipt.put("snapshotHash",source.snapshot().canonicalHash());
            receipt.put("sourceIdentity",source.snapshot().sourceIdentity());
            receipt.put("sourceTransformIdentity",source.snapshot().resampling().sourceTransformIdentity());
            receipt.put("original",coordinates(source.original()));
            phase="SHARED_TABLE";
            try(var table=BPhysicalPairTable.buildExploratory(work,source,EvidenceModelParameters.defaults(),request.settings().gain())){
                observedTable=table;phase="SOLVER";
                var solved=table.solveStoredRaw1(new TraceBudgets(96,8_000_000,128_000_000,1,1),request.settings().etaIndex());
                var result=solved.result();receipt.put("solverStatus",result.status().name());
                receipt.put("sharedRowsPrepared",table.sharedRowsPrepared());
                if(result.mapPath().isPresent()){
                    var path=result.mapPath().orElseThrow().points();phase="POST_RETURN_QUALITY";
                    var quality=BNativePointAcceptance.evaluate(request.caseId(),source.snapshot(),source.original(),source.original(),
                        path,probe,root);
                    probe.checkpoint();
                    receipt.put("quality",quality);receipt.put("returned",coordinates(path));
                    receipt.put("reference",BSharedFeasibilityTool.reference(request.caseId()));
                    receipt.put("nativePitchMeters",1);
                    receipt.put("status","MODEL_RETURNED_ROUTE");
                }else receipt.put("status","NO_RETURNED_ROUTE");
            }
        }catch(RuntimeException primary){
            receipt.remove("returned");receipt.remove("quality");
            receipt.put("status","INCONCLUSIVE");receipt.put("failurePhase",phase);
            receipt.put("failureClass",primary.getClass().getSimpleName());
            if(primary instanceof BPhysicalPairTable.EdgeCertificateFailure edge)
                receipt.put("edgeCertificateFailure",edge.diagnostics());
            if(primary instanceof BPhysicalPairTable.JointBudgetFailure budget)
                receipt.put("jointBudgetFailure",budget.diagnostics());
            receipt.put("failureFrames",java.util.Arrays.stream(primary.getStackTrace()).limit(12)
                .map(f->f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber()).toList());
        }finally{work.close();}
        receipt.put("solverInvocations",observedTable==null?0:observedTable.solverInvocations());receipt.put("trackedBytesAfterClose",root.currentBytes());
        receipt.put("elapsedNanos",clock.getAsLong()-start);
        BIdentificationReceipt.publish(request.output().resolve("receipt.json"),receipt);
        return receipt;
    }
    private static List<List<Double>> coordinates(List<MetricPoint> points){
        return points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList();
    }
}
