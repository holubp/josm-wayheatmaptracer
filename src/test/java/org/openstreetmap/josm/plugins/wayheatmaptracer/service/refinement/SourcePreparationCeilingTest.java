package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;

/** A declared preparation ceiling is factual and bound to the actual operation kind. */
class SourcePreparationCeilingTest {
    private static Map<GeometryWorkContext.Kind,Long> counts(long visits) {
        var values=new EnumMap<GeometryWorkContext.Kind,Long>(GeometryWorkContext.Kind.class);
        for(var kind:GeometryWorkContext.Kind.values())values.put(kind,kind==GeometryWorkContext.Kind.COPY?visits:0L);
        return values;
    }
    private static RefinementAttemptDiagnostics.SourcePreparationWork work(long visits,OptionalLong ceiling) throws Exception {
        var ctor=RefinementAttemptDiagnostics.SourcePreparationWork.class.getConstructor(RefinementAttemptDiagnostics.Cause.class,
            long.class,Map.class,Optional.class,Optional.class,Optional.class,OptionalLong.class);
        try{return ctor.newInstance(RefinementAttemptDiagnostics.Cause.NONE,visits,counts(visits),Optional.empty(),Optional.empty(),Optional.empty(),ceiling);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw failure;}
    }
    private static OperationTicket ticket(StageKind kind) {
        var stage=new StageId(UUID.randomUUID());var choice=new CandidateId("synthetic-parent");
        return new OperationTicket(new SessionId(UUID.randomUUID()),1,stage,choice,
            kind==StageKind.REFINE_A?TrackerMode.CORRIDOR_AWARE:TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE,kind,
            kind==StageKind.SMOOTH?Optional.of(new SmoothingInput(stage,choice,stage,choice,"Medium")):Optional.empty());
    }
    private static RefinementAttemptDiagnostics diagnostic(OperationTicket ticket,RefinementAttemptDiagnostics.SourcePreparationWork work) {
        var binding=new RefinementAttemptDiagnostics.Binding(ticket,ticket.parent(),"synthetic-source","network","settings","frozen","parent");
        return new RefinementAttemptDiagnostics(binding,Optional.empty(),RefinementAttemptDiagnostics.Publication.NO_CHILD,
            RefinementAttemptDiagnostics.Phase.SOURCE_PREPARATION,RefinementAttemptDiagnostics.Cause.NONE,0,true,Optional.of(work));
    }
    @Test void effectiveCeilingAllowsOnlyActualRefinementAndOldMissingStaysEightMillion() throws Exception {
        var larger=work(9_000_000,OptionalLong.of(64_000_000));
        assertNotNull(diagnostic(ticket(StageKind.REFINE_A),larger));
        assertNotNull(diagnostic(ticket(StageKind.REFINE_B),larger));
        assertThrows(IllegalArgumentException.class,()->diagnostic(ticket(StageKind.SMOOTH),larger));
        assertThrows(IllegalArgumentException.class,()->work(8_000_001,OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class,()->work(64_000_001,OptionalLong.of(64_000_000)));
        assertThrows(IllegalArgumentException.class,()->work(0,OptionalLong.of(64_000_001)));
        assertThrows(IllegalArgumentException.class,()->work(258,OptionalLong.of(257)));
        assertNotNull(diagnostic(ticket(StageKind.SMOOTH),work(257,OptionalLong.of(257))));
        var legacy=new RefinementAttemptDiagnostics.SourcePreparationWork(RefinementAttemptDiagnostics.Cause.NONE,0,counts(0),Optional.empty(),Optional.empty());
        var accessor=legacy.getClass().getMethod("maximumVisits");
        assertEquals(OptionalLong.empty(),accessor.invoke(legacy),"Unavailable older fields must not become measured zero or a new64M grant");
    }
    @Test void captureWorkUsesItsOwnActualTicketKind() {
        String hash="a".repeat(64);
        java.util.function.Function<OperationTicket,RefinementAttemptDiagnostics.SourceFacts> facts=actual->new RefinementAttemptDiagnostics.SourceFacts(actual,hash,
            RefinementAttemptDiagnostics.SourceKind.VISIBLE,RefinementAttemptDiagnostics.Coverage.RECAPTURED,hash,hash,hash,
            RefinementAttemptDiagnostics.InputFrameRole.CAPTURED_INPUT,100,100,100,100,0,OptionalLong.of(9_000_000));
        assertNotNull(facts.apply(ticket(StageKind.REFINE_A)));
        assertNotNull(facts.apply(ticket(StageKind.REFINE_B)));
        assertThrows(IllegalArgumentException.class,()->facts.apply(ticket(StageKind.SMOOTH)));
    }
}
