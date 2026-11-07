package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;

/** Synthetic actual production fixture; no private capture or prerecorded B result. */
public final class B06HybridFixture {
    public record Actual(FrozenReplayInput frozen,LiveBPreviewService.LegacyBComputation computation) { }
    public static Actual run(boolean compact,boolean fixedMiddle,boolean noNative) {
        return run(compact,fixedMiddle,noNative,null,true);
    }
    public static Actual runWithRemainingBudget(TraceBudgets remaining) {
        return run(true,false,false,remaining,true);
    }
    public static Actual historicalWithoutNative() {return run(false,false,true,null,false);}
    private static Actual run(boolean compact,boolean fixedMiddle,boolean noNative,TraceBudgets explicitRemaining,boolean recordedParent) {
        var original=LegacyBProductionRunnerTest.fixture(fixedMiddle,false);var old=original.input();
        var parent=recordedParent?V022RecordedBChildExecutionTest.parent(old.request(),V022RecordedBChildExecutionTest.newParent(compact)):old.request();
        if(compact) {
            var r=parent;
            // A genuinely wider synthetic child graph; the default cap/K and production policy are unchanged.
            parent=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),RecoveryPermissions.disabled(8),r.budgets(),
                    r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),
                    r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        }
        var input=new LegacyBProductionRunner.Input(parent,old.network(),old.evidence(),old.fieldName(),old.originalProjected(),old.originalMetric(),
                old.projectionCode(),old.occurrences(),old.frozenRanges(),old.simplify(),old.projectedTolerance());
        var ledger=AttemptMemoryLedger.production();
        Actual actual;
        try(var owner=ledger.rootOwner()) {
            var runner=new LegacyBProductionRunner();var deadline=LegacyBPipeline.Deadline.production();
            var discovery=runner.discoverVisible(input,original.visible(),CancellationProbe.NONE,owner,deadline);
            if(discovery.value().seeds().isEmpty())throw new AssertionError("Fixture needs genuine Legacy discovery");
            var remaining=explicitRemaining!=null?explicitRemaining:compact?new TraceBudgets(96,8_000_000,1_000_000,32,8):parent.budgets();
            var executed=runner.refine(discovery,List.of(discovery.value().seeds().get(0).id()),remaining,!noNative,
                    LegacyBPipeline.Reason.WORK_BUDGET,CancellationProbe.NONE,owner,deadline);
            var computation=new LiveBPreviewService.LegacyBComputation(input,executed.value(),original.visible(),null);
            var source=FrozenLegacyBInput.capture(computation);
            var frozen=new FrozenReplayInput(parent,input.evidence(),input.network(),new ModernTracePipeline.Options(input.fieldName(),
                    GeometryCleanupConfig.disabled(),"selected-visible",0),parent.permissions().ordinaryRadiusMeters(),source);
            actual=new Actual(frozen,computation);
        }
        if(ledger.currentBytes()!=0)throw new AssertionError("Fixture leaked native ownership");
        return actual;
    }
    private B06HybridFixture() { }
}
