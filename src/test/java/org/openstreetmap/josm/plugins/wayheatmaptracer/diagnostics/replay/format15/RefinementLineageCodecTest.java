package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;

class RefinementLineageCodecTest {
    @Test void preparationCeilingRoundTripsWithoutGrantingOldArchivesANewBudget() throws Exception {
        var root=root();var key=new RefinementLineageCodec.CandidateKey(root.id(),root.selected());
        var smoothing=new OperationTicket(session,1,root.id(),root.selected(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE,StageKind.SMOOTH,
            Optional.of(new SmoothingInput(root.id(),root.selected(),root.id(),root.selected(),"Medium")));
        var base=publicationDocument(List.of(root),root,smoothing,Optional.empty(),RefinementAttemptDiagnostics.Publication.FAILED,
            RefinementAttemptDiagnostics.Phase.SOURCE_PREPARATION,RefinementAttemptDiagnostics.Cause.FAILED,key);
        var counts=new EnumMap<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long>(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.class);
        for(var kind:org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values())counts.put(kind,kind.name().equals("COPY")?9_000_000L:0L);
        var constructor=RefinementAttemptDiagnostics.SourcePreparationWork.class.getConstructor(RefinementAttemptDiagnostics.Cause.class,
            long.class,Map.class,Optional.class,Optional.class,Optional.class,OptionalLong.class);
        var work=constructor.newInstance(RefinementAttemptDiagnostics.Cause.FAILED,9_000_000L,counts,Optional.empty(),Optional.empty(),Optional.empty(),OptionalLong.of(64_000_000));
        var old=base.latestPublication().orElseThrow().diagnostic();
        var diagnostic=new RefinementAttemptDiagnostics(old.binding(),old.pipeline(),old.publication(),old.phase(),old.cause(),0,true,Optional.of(work));
        var document=new RefinementLineageCodec.Document(base.binding(),base.originalOsm(),base.stages(),base.latestAttempt(),
            Optional.of(new RefinementLineageCodec.LatestPublication(diagnostic,key)));
        var artifact=RefinementLineageCodec.artifact(document);
        var decoded=RefinementLineageCodec.read(artifact).latestPublication().orElseThrow().diagnostic().sourcePreparation().orElseThrow();
        assertEquals(9_000_000L,decoded.visits());
        assertEquals(StageKind.SMOOTH,RefinementLineageCodec.read(artifact).latestPublication().orElseThrow().diagnostic().binding().ticket().kind());
        assertEquals(OptionalLong.of(64_000_000),decoded.getClass().getMethod("maximumVisits").invoke(decoded));
        String json=new String(artifact.bytes(),StandardCharsets.UTF_8);
        assertTrue(json.contains("\"maximumVisits\":64000000"));
        String missing=json.replace(",\"maximumVisits\":64000000","");
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.read(Format15Artifact.text(RefinementLineageCodec.ARTIFACT,missing)));
        String oldValid=missing.replace("\"visits\":9000000","\"visits\":7000000").replace("\"COPY\":9000000","\"COPY\":7000000");
        var oldWork=RefinementLineageCodec.read(Format15Artifact.text(RefinementLineageCodec.ARTIFACT,oldValid)).latestPublication().orElseThrow().diagnostic().sourcePreparation().orElseThrow();
        assertEquals(OptionalLong.empty(),oldWork.getClass().getMethod("maximumVisits").invoke(oldWork));
        String overflow=json.replace("\"maximumVisits\":64000000","\"maximumVisits\":8000000");
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.read(Format15Artifact.text(RefinementLineageCodec.ARTIFACT,overflow)));
    }
    static final String SOURCE = "a".repeat(64), SETTINGS = "b".repeat(64);
    final SessionId session = new SessionId(UUID.randomUUID());
    final CandidateId blue = new CandidateId("blue"), purple = new CandidateId("purple");
    final Format15Artifact geometry = Format15Artifact.text("geometry/synthetic.csv", "synthetic\n");
    final Format15Artifact originalGeometry = Format15Artifact.text("original-geometry.json", "synthetic\n");
    static final String ORIGINAL_OSM_IDENTITY = "synthetic-original-osm";
    Candidate candidate(String id, Optional<RefinementOperationReceipt> receipt) {
        var points = List.of(new EastNorth(1,2), new EastNorth(3,4));
        var result = new AlignmentResult(null,null,List.of(),points,points,List.of(),null,null);
        var payload = RefinementPayload.capture(result, 1_000_000);
        if (receipt.isPresent()) payload = RefinementPayload.captureNative(result,payload,
            new RefinementPayload.NativeReview(null,null,receipt,List.of(),OptionalInt.empty()),1_000_000);
        return new Candidate(new CandidateId(id),payload,List.of(),"identity-"+id,"native-"+id,"blue");
    }
    Stage root() { return Stage.root(List.of(candidate("blue",Optional.empty()), candidate("purple",Optional.empty())), purple); }
    OperationTicket ticket(Stage root, int sequence) {
        return new OperationTicket(session,sequence,root.id(),root.selected(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
    }
    RefinementOperationReceipt receipt(OperationTicket ticket, Outcome outcome, Optional<TraceWorkUsage> work) {
        var attempt = new RefinementOperationReceipt.Attempt("blue","blue",RefinementPipeline.AttemptKind.INTERVAL,
            new OccurrenceRange(1,3),"native-result",Optional.of(outcome),OptionalInt.of(1),work,
            Optional.of(SOURCE),Optional.of(SETTINGS),Optional.of("sampler-v1"),Optional.of(TraceHypothesisSet.Status.COMPLETE),List.of("native-blue"));
        return new RefinementOperationReceipt(ticket,"parent-identity",SOURCE,outcome,List.of(attempt),work,false);
    }
    Map<RefinementLineageCodec.CandidateKey,Format15Artifact> refs(List<Stage> stages) {
        var refs = new LinkedHashMap<RefinementLineageCodec.CandidateKey,Format15Artifact>();
        for (var stage:stages) for(var c:stage.candidates()) refs.put(new RefinementLineageCodec.CandidateKey(stage.id(),c.id()),geometry);
        return refs;
    }
    RefinementLineageCodec.Document capture(List<Stage> stages, Stage current, Optional<RefinementOperationReceipt> last) {
        return RefinementLineageCodec.capture("synthetic-build",SOURCE,SETTINGS,AlignmentMode.PRECISE_SHAPE,session,ORIGINAL_OSM_IDENTITY,originalGeometry,stages,current.id(),last,refs(stages));
    }
    Format15Bundle bundle() { return new Format15Bundle("synthetic-build",SOURCE,SETTINGS,Map.of(geometry.name(),geometry,originalGeometry.name(),originalGeometry)); }

    @Test void completePipelineReceiptWithFailedPresentationMustRemainFactual() {
        var root=root();var actual=receipt(ticket(root,1),Outcome.COMPLETE,Optional.of(new TraceWorkUsage(9,17,2,1)));
        var old=capture(List.of(root),root,Optional.empty());
        var binding=new RefinementLineageCodec.Binding("synthetic-build",Format15Safety.sha256("runtime-source"),SETTINGS,AlignmentMode.PRECISE_SHAPE,session,root.id(),root.id(),root.selected());
        var original=new RefinementAttemptDiagnostics.Binding(actual.ticket(),root.id(),"runtime-source",actual.originalNetworkHash(),SETTINGS,"frozen-proof",actual.parentIdentity());
        var diagnostic=new RefinementAttemptDiagnostics(original,Optional.of(actual),RefinementAttemptDiagnostics.Publication.FAILED,RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED,0);
        var publication=new RefinementLineageCodec.LatestPublication(diagnostic,new RefinementLineageCodec.CandidateKey(root.id(),root.selected()));
        var doc=assertDoesNotThrow(()->new RefinementLineageCodec.Document(binding,new RefinementLineageCodec.OriginalOsm(actual.originalNetworkHash(),old.originalOsm().geometry()),old.stages(),Optional.of(actual),Optional.of(publication)),"Later presentation failure must not force rewriting COMPLETE native outcome");
        var decoded=RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc));
        assertEquals(Outcome.COMPLETE,decoded.latestAttempt().orElseThrow().outcome());assertEquals(publication,decoded.latestPublication().orElseThrow());
    }

    RefinementLineageCodec.Document publicationDocument(List<Stage> stages,Stage current,OperationTicket ticket,
            Optional<RefinementOperationReceipt> receipt,RefinementAttemptDiagnostics.Publication publication,
            RefinementAttemptDiagnostics.Phase phase,RefinementAttemptDiagnostics.Cause cause,RefinementLineageCodec.CandidateKey result) {
        var base=capture(stages,current,Optional.empty());
        var header=new RefinementLineageCodec.Binding("synthetic-build",Format15Safety.sha256("runtime-source"),SETTINGS,AlignmentMode.PRECISE_SHAPE,session,stages.get(0).id(),current.id(),current.selected());
        var binding=new RefinementAttemptDiagnostics.Binding(ticket,stages.get(0).id(),"runtime-source",SOURCE,SETTINGS,"frozen-proof",receipt.map(RefinementOperationReceipt::parentIdentity).orElse("parent-identity"));
        var diagnostic=new RefinementAttemptDiagnostics(binding,receipt,publication,phase,cause,0);
        return new RefinementLineageCodec.Document(header,new RefinementLineageCodec.OriginalOsm(SOURCE,base.originalOsm().geometry()),base.stages(),receipt,
            Optional.of(new RefinementLineageCodec.LatestPublication(diagnostic,result)));
    }
    @Test void refusalCompletenessIsAdditiveAndOldMissingFieldMeansCompletedObservation() {
        var root=root();var key=new RefinementLineageCodec.CandidateKey(root.id(),root.selected());
        var old=publicationDocument(List.of(root),root,ticket(root,1),Optional.empty(),RefinementAttemptDiagnostics.Publication.CANCELLED,RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.CANCELLED,key);
        assertTrue(old.latestPublication().orElseThrow().diagnostic().materializationComplete());
        var complete=old.latestPublication().orElseThrow().diagnostic();var incomplete=new RefinementAttemptDiagnostics(complete.binding(),Optional.empty(),complete.publication(),complete.phase(),complete.cause(),0,false);
        var doc=new RefinementLineageCodec.Document(old.binding(),old.originalOsm(),old.stages(),old.latestAttempt(),Optional.of(new RefinementLineageCodec.LatestPublication(incomplete,key)));
        String json=new String(RefinementLineageCodec.artifact(doc).bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(json.contains("\"refusalsComplete\":false"));assertFalse(RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc)).latestPublication().orElseThrow().diagnostic().materializationComplete());
        String legacy=new String(RefinementLineageCodec.artifact(old).bytes(),java.nio.charset.StandardCharsets.UTF_8).replace(",\"refusalsComplete\":true","");assertTrue(RefinementLineageCodec.read(Format15Artifact.text(RefinementLineageCodec.ARTIFACT,legacy)).latestPublication().orElseThrow().diagnostic().materializationComplete());
    }
    @Test void prePipelineFailureHasPublicationButNoInventedReceiptOrWork() {
        var root=root();var doc=publicationDocument(List.of(root),root,ticket(root,1),Optional.empty(),RefinementAttemptDiagnostics.Publication.FAILED,
            RefinementAttemptDiagnostics.Phase.ACQUISITION,RefinementAttemptDiagnostics.Cause.FAILED,new RefinementLineageCodec.CandidateKey(root.id(),root.selected()));
        var decoded=RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc));assertEquals(doc,decoded);
        assertTrue(decoded.latestAttempt().isEmpty());assertTrue(decoded.latestPublication().orElseThrow().diagnostic().pipeline().isEmpty());
        var old=capture(List.of(root),root,Optional.empty());assertTrue(RefinementLineageCodec.read(RefinementLineageCodec.artifact(old)).latestPublication().isEmpty());
    }
    @Test void publishedAttemptMustMatchItsRetainedCandidateOwnedReceiptExactly() {
        var root=root();var ticket=ticket(root,1);var receipt=receipt(ticket,Outcome.COMPLETE,Optional.of(new TraceWorkUsage(9,17,2,1)));
        var child=Stage.child(ticket,List.of(candidate("blue",Optional.of(receipt))),blue);
        var doc=publicationDocument(List.of(root,child),child,ticket,Optional.of(receipt),RefinementAttemptDiagnostics.Publication.PUBLISHED,
            RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.NONE,new RefinementLineageCodec.CandidateKey(child.id(),blue));
        assertEquals(doc,RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc)));
        var actual=doc.latestPublication().orElseThrow();var d=actual.diagnostic();
        var incomplete=new RefinementAttemptDiagnostics(d.binding(),d.pipeline(),d.publication(),d.phase(),d.cause(),d.materializationRefusals(),false);
        assertThrows(IllegalArgumentException.class,()->new RefinementLineageCodec.Document(doc.binding(),doc.originalOsm(),doc.stages(),doc.latestAttempt(),Optional.of(new RefinementLineageCodec.LatestPublication(incomplete,actual.resultingSelection()))));
        var other=receipt(ticket,Outcome.COMPLETE,Optional.of(new TraceWorkUsage(10,18,2,1)));
        assertThrows(IllegalArgumentException.class,()->publicationDocument(List.of(root,child),child,ticket,Optional.of(other),RefinementAttemptDiagnostics.Publication.PUBLISHED,
            RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.NONE,new RefinementLineageCodec.CandidateKey(child.id(),blue)));
        assertThrows(IllegalArgumentException.class,()->capture(List.of(root,child),child,Optional.of(receipt)),"Old unqualified duplicate receipt remains refused");
    }
    @Test void publishedMetadataCannotCallAnOlderRetainedOperationLatest() {
        var root=root();var first=ticket(root,1);var receipt1=receipt(first,Outcome.COMPLETE,Optional.empty());
        var a=Stage.child(first,List.of(candidate("blue",Optional.of(receipt1))),blue);
        var second=ticket(a,2);var receipt2=receipt(second,Outcome.COMPLETE,Optional.empty());var b=Stage.child(second,List.of(candidate("purple",Optional.of(receipt2))),purple);
        assertThrows(IllegalArgumentException.class,()->publicationDocument(List.of(root,a,b),a,first,Optional.of(receipt1),RefinementAttemptDiagnostics.Publication.PUBLISHED,
            RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.NONE,new RefinementLineageCodec.CandidateKey(a.id(),blue)),"Historical current selection cannot relabel its older operation as latest");
    }
    @Test void publicationCannotRelabelWrongRootSourceSettingsOrResultSelection() {
        var root=root();var receipt=receipt(ticket(root,1),Outcome.COMPLETE,Optional.empty());
        var valid=publicationDocument(List.of(root),root,receipt.ticket(),Optional.of(receipt),RefinementAttemptDiagnostics.Publication.FAILED,
            RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED,new RefinementLineageCodec.CandidateKey(root.id(),root.selected()));
        var b=valid.binding();var p=valid.latestPublication();
        assertAll(
            ()->assertThrows(IllegalArgumentException.class,()->new RefinementLineageCodec.Document(new RefinementLineageCodec.Binding(b.buildIdentity(),SOURCE,b.parameterHash(),b.mode(),b.session(),b.root(),b.current(),b.currentChoice()),valid.originalOsm(),valid.stages(),valid.latestAttempt(),p)),
            ()->assertThrows(IllegalArgumentException.class,()->new RefinementLineageCodec.Document(new RefinementLineageCodec.Binding(b.buildIdentity(),b.sourceIdentityHash(),SOURCE,b.mode(),b.session(),b.root(),b.current(),b.currentChoice()),valid.originalOsm(),valid.stages(),valid.latestAttempt(),p)),
            ()->assertThrows(IllegalArgumentException.class,()->new RefinementLineageCodec.Document(b,new RefinementLineageCodec.OriginalOsm("wrong-original",valid.originalOsm().geometry()),valid.stages(),valid.latestAttempt(),p)),
            ()->assertThrows(IllegalArgumentException.class,()->publicationDocument(List.of(root),root,receipt.ticket(),Optional.of(receipt),RefinementAttemptDiagnostics.Publication.FAILED,
                RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED,new RefinementLineageCodec.CandidateKey(root.id(),blue))),
            ()->assertThrows(IllegalArgumentException.class,()->capture(List.of(root),root,Optional.of(receipt)),"COMPLETE without matching publication metadata remains refused"));
    }

    @Test void originalOsmMustBeBoundEvenForRootOnlyHistoryWithoutReceipt() {
        var root=root(); var doc=capture(List.of(root),root,Optional.empty());
        var replaced=Format15Artifact.text("original-geometry.json","replaced-original");
        var missing=new Format15Bundle("synthetic-build",SOURCE,SETTINGS,Map.of(geometry.name(),geometry));
        var changed=new Format15Bundle("synthetic-build",SOURCE,SETTINGS,Map.of(geometry.name(),geometry,replaced.name(),replaced));
        var lineage=RefinementLineageCodec.artifact(doc);
        var readMissing=new Format15Bundle("synthetic-build",SOURCE,SETTINGS,Map.of(geometry.name(),geometry,lineage.name(),lineage));
        var readChanged=new Format15Bundle("synthetic-build",SOURCE,SETTINGS,Map.of(geometry.name(),geometry,replaced.name(),replaced,lineage.name(),lineage));
        assertAll(
            ()->assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.attach(missing,doc)),
            ()->assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.attach(changed,doc)),
            ()->assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.readBound(readMissing,root.id(),purple)),
            ()->assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.readBound(readChanged,root.id(),purple)));
        var roundTrip=RefinementLineageCodec.readBound(RefinementLineageCodec.attach(bundle(),doc),root.id(),purple).orElseThrow();
        assertEquals(ORIGINAL_OSM_IDENTITY,roundTrip.originalOsm().immutableIdentity());
        assertEquals(originalGeometry.name(),roundTrip.originalOsm().geometry().artifact());
        assertEquals(originalGeometry.sha256(),roundTrip.originalOsm().geometry().sha256());
        assertEquals(geometry.sha256(),roundTrip.originalOsm().geometry().sha256(),"Equal bytes are allowed across distinct semantic identities");
        assertTrue(roundTrip.latestAttempt().isEmpty());
        assertTrue(roundTrip.stages().get(0).candidates().stream().allMatch(c->c.operation().isEmpty()));
    }
    @Test void unchangedOriginalAndLegacyCandidateMayShareAnActualArtifact() {
        var root=root();
        var doc=RefinementLineageCodec.capture("synthetic-build",SOURCE,SETTINGS,AlignmentMode.PRECISE_SHAPE,session,
            ORIGINAL_OSM_IDENTITY,geometry,List.of(root),root.id(),Optional.empty(),refs(List.of(root)));
        var shared=new Format15Bundle("synthetic-build",SOURCE,SETTINGS,Map.of(geometry.name(),geometry));
        assertEquals(doc,RefinementLineageCodec.readBound(RefinementLineageCodec.attach(shared,doc),root.id(),purple).orElseThrow());
        assertEquals(doc.originalOsm().geometry(),doc.stages().get(0).candidates().get(0).geometry());
    }
    @Test void modelHistoryAndCandidateOwnedReceiptsRoundTripAndAttach() {
        var root = root(); var ticket = ticket(root,1);
        var receipt = receipt(ticket,Outcome.COMPLETE,Optional.of(new TraceWorkUsage(9,17,2,1)));
        // The same candidate ID in distinct stages must retain distinct geometry ownership.
        var child = Stage.child(ticket,List.of(candidate("blue",Optional.of(receipt))),blue);
        var doc = capture(List.of(root,child),child,Optional.empty());
        assertEquals(doc,RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc)));
        assertTrue(doc.stages().get(0).candidates().get(0).operation().isEmpty());
        assertEquals(receipt,doc.stages().get(1).candidates().get(0).operation().orElseThrow());
        var attached = RefinementLineageCodec.attach(bundle(),doc);
        assertEquals(doc,RefinementLineageCodec.readBound(attached,child.id(),blue).orElseThrow());
        assertTrue(RefinementLineageCodec.readBound(bundle(),root.id(),purple).isEmpty());
    }
    @Test void undoDifferentRootChoiceKeepsForwardHistoryAndNoChildFailureWorkUnavailable() {
        var root=root(); var first=ticket(root,1);
        var child=Stage.child(first,List.of(candidate("blue",Optional.of(receipt(first,Outcome.PARTIAL,Optional.empty())))),blue);
        var changed=root.withSelected(blue);
        var failure=receipt(ticket(changed,2),Outcome.CANCELLED,Optional.empty());
        var doc=capture(List.of(changed,child),changed,Optional.of(failure));
        var decoded=RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc));
        assertEquals(doc,decoded);
        assertEquals(purple,decoded.stages().get(1).parentChoice().orElseThrow());
        assertTrue(decoded.latestAttempt().orElseThrow().actualWork().isEmpty());
        assertTrue(decoded.latestAttempt().orElseThrow().attempts().get(0).actualWork().isEmpty());
        assertEquals(2,decoded.stages().size());
    }
    @Test void rejectsDanglingDuplicateStagesAndCandidateReferences() {
        var root=root(); var doc=capture(List.of(root),root,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->capture(List.of(root,root),root,Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.capture("synthetic-build",SOURCE,SETTINGS,AlignmentMode.PRECISE_SHAPE,session,ORIGINAL_OSM_IDENTITY,originalGeometry,
            List.of(root),root.id(),Optional.empty(),Map.of()));
        var other=root();
        assertThrows(IllegalArgumentException.class,()->capture(List.of(root,other),root,Optional.empty()));
        var invalid=RefinementLineageCodec.artifact(doc);
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.read(Format15Artifact.text(invalid.name(),
            new String(invalid.bytes(),StandardCharsets.UTF_8).replace("\"selected\":\"purple\"","\"selected\":\"missing\""))));
    }
    @Test void rejectsWrongReceiptSessionParentAndInventedRootReceipt() {
        var root=root(); var other=root(); var wrong=ticket(other,1);
        var child=Stage.child(ticket(root,1),List.of(candidate("blue",Optional.of(receipt(wrong,Outcome.COMPLETE,Optional.empty())))),blue);
        assertThrows(IllegalArgumentException.class,()->capture(List.of(root,child),child,Optional.empty()));
        var rootReceipt=Stage.root(List.of(candidate("blue",Optional.of(receipt(ticket(root,1),Outcome.COMPLETE,Optional.empty())))),blue);
        assertThrows(IllegalArgumentException.class,()->capture(List.of(rootReceipt),rootReceipt,Optional.empty()));
    }
    @Test void validatesActualArtifactsHeadersAndSelection() {
        var root=root(); var doc=capture(List.of(root),root,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.attach(new Format15Bundle("other",SOURCE,SETTINGS,Map.of(geometry.name(),geometry,originalGeometry.name(),originalGeometry)),doc));
        var tampered=Format15Artifact.text(geometry.name(),"tampered");
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.attach(new Format15Bundle("synthetic-build",SOURCE,SETTINGS,Map.of(tampered.name(),tampered,originalGeometry.name(),originalGeometry)),doc));
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.readBound(RefinementLineageCodec.attach(bundle(),doc),root.id(),blue));
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.attach(new Format15Bundle("synthetic-build",SETTINGS,SOURCE,Map.of(geometry.name(),geometry,originalGeometry.name(),originalGeometry)),doc));
    }
    @Test void actualSessionSmoothingReplacementPreservesDiscardedInvocationOwner() throws Exception {
        // Reuse the existing synthetic authority fixture without widening its production API.
        var fixture=Class.forName("org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementParentFixtures");
        var factory=fixture.getDeclaredMethod("authority",AlignmentMode.class); factory.setAccessible(true);
        var authority=(RefinementRootAuthority)factory.invoke(null,AlignmentMode.PRECISE_SHAPE);
        javax.swing.SwingUtilities.invokeAndWait(()->{
            var root=root();
            var history=new RefinementSession(root,authority.effectiveConfig(),authority.sourceIdentity(),authority,RefinementLimits.defaults());
            var medium=history.beginSmoothing("Medium");
            var first=Stage.child(medium,List.of(candidate("blue",Optional.empty())),blue);
            history.reserve(medium,1,history.availablePayloadBytes()); assertTrue(history.publish(medium,first));
            var strong=history.beginSmoothing("Strong");
            var replacement=Stage.child(strong,List.of(candidate("blue",Optional.of(receipt(strong,Outcome.COMPLETE,Optional.empty())))),blue);
            history.reserve(strong,1,history.availablePayloadBytes()); assertTrue(history.publish(strong,replacement));
            assertEquals(2,history.history().size());
            assertFalse(history.history().stream().anyMatch(stage->stage.id().equals(first.id())));
            var doc=RefinementLineageCodec.capture("synthetic-build",SOURCE,SETTINGS,AlignmentMode.PRECISE_SHAPE,strong.session(),ORIGINAL_OSM_IDENTITY,originalGeometry,history.history(),
                replacement.id(),Optional.empty(),refs(history.history()));
            assertEquals(first.id(),doc.stages().get(1).smoothingInput().orElseThrow().owner());
            assertEquals(doc,RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc)));
        });
    }
    @Test void actualPublishedSmoothingReplacementKeepsFactualDiscardedInvocationOwner() throws Exception {
        // Reuse the existing synthetic authority fixture without widening its production API.
        var fixture=Class.forName("org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementParentFixtures");
        var factory=fixture.getDeclaredMethod("authority",AlignmentMode.class); factory.setAccessible(true);
        var authority=(RefinementRootAuthority)factory.invoke(null,AlignmentMode.PRECISE_SHAPE);
        javax.swing.SwingUtilities.invokeAndWait(()->{
            var root=root();
            var history=new RefinementSession(root,authority.effectiveConfig(),authority.sourceIdentity(),authority,RefinementLimits.defaults());
            var medium=history.beginSmoothing("Medium");
            var first=Stage.child(medium,List.of(candidate("blue",Optional.empty())),blue);
            history.reserve(medium,1,history.availablePayloadBytes()); assertTrue(history.publish(medium,first));
            var strong=history.beginSmoothing("Strong");
            var replacement=Stage.child(strong,List.of(candidate("blue",Optional.of(receipt(strong,Outcome.COMPLETE,Optional.empty())))),blue);
            history.reserve(strong,1,history.availablePayloadBytes()); assertTrue(history.publish(strong,replacement));
            assertEquals(2,history.history().size());
            assertFalse(history.history().stream().anyMatch(stage->stage.id().equals(first.id())));
            var actual=replacement.candidates().get(0).payload().nativeReview().orElseThrow().operation().orElseThrow();
            var binding=new RefinementAttemptDiagnostics.Binding(strong,root.id(),"runtime-source",SOURCE,SETTINGS,"frozen-proof",actual.parentIdentity());
            var diagnostic=new RefinementAttemptDiagnostics(binding,Optional.of(actual),RefinementAttemptDiagnostics.Publication.PUBLISHED,
                RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.NONE,0);
            var published=new RefinementLineageCodec.LatestPublication(diagnostic,new RefinementLineageCodec.CandidateKey(replacement.id(),blue));
            var doc=RefinementLineageCodec.capture("synthetic-build",Format15Safety.sha256("runtime-source"),SETTINGS,AlignmentMode.PRECISE_SHAPE,strong.session(),SOURCE,originalGeometry,history.history(),
                replacement.id(),Optional.of(actual),refs(history.history()),Optional.of(published));
            assertEquals(first.id(),doc.latestAttempt().orElseThrow().ticket().parent());
            assertEquals(doc,RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc)));
            var failed=new RefinementAttemptDiagnostics(binding,Optional.of(actual),RefinementAttemptDiagnostics.Publication.FAILED,
                RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED,0);
            assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.capture("synthetic-build",Format15Safety.sha256("runtime-source"),SETTINGS,AlignmentMode.PRECISE_SHAPE,strong.session(),SOURCE,originalGeometry,history.history(),
                replacement.id(),Optional.of(actual),refs(history.history()),Optional.of(new RefinementLineageCodec.LatestPublication(failed,new RefinementLineageCodec.CandidateKey(first.id(),blue)))));
            history.close();
        });
    }
    @Test void unavailableAttemptsPreserveExplicitZeroCallsAndUnavailableWork() {
        var root=root();
        var attempts=new ArrayList<RefinementOperationReceipt.Attempt>();
        for(var kind:List.of(RefinementPipeline.AttemptKind.UNAVAILABLE,RefinementPipeline.AttemptKind.UNATTEMPTED,RefinementPipeline.AttemptKind.FROZEN))
            attempts.add(new RefinementOperationReceipt.Attempt("gray","gray",kind,new OccurrenceRange(0,3),"not-attempted",
                Optional.empty(),OptionalInt.of(0),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),List.of()));
        var receipt=new RefinementOperationReceipt(ticket(root,1),"parent-identity",SOURCE,Outcome.NO_USABLE_RESULT,attempts,Optional.empty(),false);
        var doc=capture(List.of(root),root,Optional.of(receipt));
        var result=RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc)).latestAttempt().orElseThrow();
        assertEquals(receipt,result);
        assertEquals(0,result.attempts().get(0).nativeCalls().orElseThrow());
        assertTrue(result.actualWork().isEmpty());
        assertTrue(result.attempts().stream().allMatch(a->a.actualWork().isEmpty() && a.outcome().isEmpty()));
    }
    @Test void checksumArchiveRoundTripDoesNotAddReplayCapability(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var root=root(); var doc=capture(List.of(root),root,Optional.empty());
        var attached=RefinementLineageCodec.attach(bundle(),doc); var file=directory.resolve("lineage.zip");
        Format15BundleWriter.write(attached,file); var archive=Format15ArchiveReader.read(file);
        assertEquals(attached.artifact(RefinementLineageCodec.ARTIFACT).sha256(),archive.artifact(RefinementLineageCodec.ARTIFACT).orElseThrow().sha256());
        var restored=new Format15Bundle(archive.buildIdentity(),archive.sourceIdentityHash(),archive.parameterHash(),archive.artifacts());
        assertEquals(doc,RefinementLineageCodec.readBound(restored,root.id(),purple).orElseThrow());
        assertFalse(archive.capability().supports(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FULL_EDIT_PLAN));
    }
    @Test void rejectsStageCandidateAndAttemptCountBoundsAndParentTampering() {
        var root=root(); var doc=capture(List.of(root),root,Optional.empty());
        var tooMany=new ArrayList<Candidate>(); for(int i=0;i<33;i++) tooMany.add(candidate("c"+i,Optional.empty()));
        var crowded=Stage.root(tooMany,tooMany.get(0).id());
        assertThrows(IllegalArgumentException.class,()->capture(List.of(crowded),crowded,Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->capture(Collections.nCopies(9,root),root,Optional.empty()));
        var first=ticket(root,1); var child=Stage.child(first,List.of(candidate("blue",Optional.empty())),blue);
        var childDoc=capture(List.of(root,child),child,Optional.empty());
        var text=new String(RefinementLineageCodec.artifact(childDoc).bytes(),StandardCharsets.UTF_8);
        for(var bad:List.of(text.replace("\"parentChoice\":\"purple\"","\"parentChoice\":\"missing\""),
                text.replace("\"parent\":\""+root.id().value(),"\"parent\":\""+child.id().value())))
            assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.read(Format15Artifact.text(RefinementLineageCodec.ARTIFACT,bad)));
        var sample=receipt(first,Outcome.FAILED,Optional.empty()).attempts().get(0);
        var oversized=new RefinementOperationReceipt(first,"parent-identity",SOURCE,Outcome.FAILED,Collections.nCopies(513,sample),Optional.empty(),false);
        assertThrows(IllegalArgumentException.class,()->capture(List.of(root),root,Optional.of(oversized)));
        // DTO construction and returned lists are immutable too.
        assertThrows(UnsupportedOperationException.class,()->doc.stages().clear());
        assertThrows(UnsupportedOperationException.class,()->doc.stages().get(0).candidates().clear());
    }
    @Test void resourceLimitedChildAndAbsentReplacementOwnerAreFactualButSelfOwnerIsInvalid() {
        var root=root(); var first=ticket(root,1);
        var limited=receipt(first,Outcome.RESOURCE_LIMIT,Optional.of(new TraceWorkUsage(9,17,2,1)));
        var child=Stage.child(first,List.of(candidate("blue",Optional.of(limited))),blue);
        var doc=capture(List.of(root,child),child,Optional.empty());
        assertEquals(doc,RefinementLineageCodec.read(RefinementLineageCodec.artifact(doc)));
        var input=new SmoothingInput(child.id(),blue,root.id(),purple,"Strong");
        var self=new Stage(child.id(),Optional.of(root.id()),Optional.of(purple),StageKind.SMOOTH,TrackerMode.LEGACY_V02,
            List.of(candidate("blue",Optional.empty())),blue,Optional.of(input));
        assertThrows(IllegalArgumentException.class,()->capture(List.of(root,self),self,Optional.empty()));
    }
    @Test void rejectsMalformedPrivateAndOversizedInput() {
        var root=root(); var doc=capture(List.of(root),root,Optional.empty());
        var text=new String(RefinementLineageCodec.artifact(doc).bytes(),StandardCharsets.UTF_8);
        for(var bad: List.of(text.replace("\"schema\":1","\"schema\":2"),text.replace("synthetic-build","/private/archive"),
                text.replace("synthetic-build","https://host/?signature=secret"),text.replace("\"schema\":1","\"schema\":1,\"schema\":1"),
                text.replace("\"schema\":1","\"schema\":1,\"coordinates\":[1,2]"),"[".repeat(1000),text.replace("identity-blue","x".repeat(1025)),text.replace("identity-blue","\\ud800")))
            assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.read(Format15Artifact.text(RefinementLineageCodec.ARTIFACT,bad)));
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.read(Format15Artifact.binary(RefinementLineageCodec.ARTIFACT,new byte[RefinementLineageCodec.MAX_BYTES+1])));
        assertThrows(IllegalArgumentException.class,()->RefinementLineageCodec.read(Format15Artifact.binary(RefinementLineageCodec.ARTIFACT,new byte[]{(byte)0xc3,0x28})));
        assertFalse(text.contains("latitude")); assertFalse(text.contains("payload"));
    }
}
