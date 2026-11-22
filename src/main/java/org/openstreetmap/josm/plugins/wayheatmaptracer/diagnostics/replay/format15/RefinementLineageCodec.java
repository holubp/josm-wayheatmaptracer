package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;

/**
 * Coordinate-free, additive schema-1 history. Structural consistency and artifact hashes
 * do not establish physical authority, frozen native input availability, or chain replay.
 * Invocation owners of replacement smoothing stages may no longer be retained.
 */
public final class RefinementLineageCodec {
    public static final String ARTIFACT = "refinement-lineage.json";
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 512, MAX_STRING = 1024;
    private RefinementLineageCodec() { }

    /** Candidate IDs are local to their owning stage. */
    public record CandidateKey(StageId stage, CandidateId candidate) {
        public CandidateKey { Objects.requireNonNull(stage); Objects.requireNonNull(candidate); }
    }
    /** Reference to already supplied geometry bytes, never a newly inferred geometry. */
    public record GeometryRef(String artifact, String sha256) {
        public GeometryRef {
            Format15Safety.name(artifact,false); text(artifact);
            if (artifact.equals(ARTIFACT)) throw invalid();
            Format15Safety.requiredHash(sha256,"geometry sha256");
        }
    }
    /** Explicit original-OSM identity and geometry, independent of every generated stage candidate.
     * Equal geometry bytes or shared artifacts are permitted; factual authority remains the caller's obligation.
     */
    public record OriginalOsm(String immutableIdentity, GeometryRef geometry) {
        public OriginalOsm { identity(immutableIdentity); Objects.requireNonNull(geometry); }
    }
    /** Header and exact current selection, independent of retained forward history. */
    public record Binding(String buildIdentity, String sourceIdentityHash, String parameterHash,
            AlignmentMode mode, SessionId session, StageId root, StageId current, CandidateId currentChoice) {
        public Binding {
            identity(buildIdentity); Format15Safety.requiredHash(sourceIdentityHash,"sourceIdentityHash");
            Format15Safety.requiredHash(parameterHash,"parameterHash");
            Objects.requireNonNull(mode); Objects.requireNonNull(session); Objects.requireNonNull(root);
            Objects.requireNonNull(current); Objects.requireNonNull(currentChoice); text(currentChoice.value());
        }
    }
    /** Successful factual operation summaries are copied only from candidate-owned payloads. */
    public record SmoothingPublication(OperationTicket ticket,GeometryRef receipt,String parentIdentity) {
        public SmoothingPublication {Objects.requireNonNull(ticket);Objects.requireNonNull(receipt);identity(parentIdentity);
            if(ticket.kind()!=StageKind.SMOOTH||ticket.smoothingInput().isEmpty())throw invalid();}
    }
    public record CandidateEntry(CandidateId id, String immutableIdentity, String nativeCandidateId,
            String sourcePalette, GeometryRef geometry, Optional<RefinementOperationReceipt> operation,Optional<SmoothingPublication> smoothingPublication) {
        public CandidateEntry(CandidateId id,String immutableIdentity,String nativeCandidateId,String sourcePalette,GeometryRef geometry,Optional<RefinementOperationReceipt> operation){
            this(id,immutableIdentity,nativeCandidateId,sourcePalette,geometry,operation,Optional.empty());
        }
        public CandidateEntry {
            Objects.requireNonNull(id); text(id.value()); identity(immutableIdentity); identity(nativeCandidateId);
            text(sourcePalette); Objects.requireNonNull(geometry); Objects.requireNonNull(operation);
            Objects.requireNonNull(smoothingPublication);if(operation.isPresent()&&smoothingPublication.isPresent())throw invalid();
        }
    }
    /** Retained stage metadata without payloads, coordinates, images, or mutable handles. */
    public record StageEntry(StageId id, Optional<StageId> parent, Optional<CandidateId> parentChoice,
            StageKind kind, TrackerMode engine, CandidateId selected, Optional<SmoothingInput> smoothingInput,
            List<CandidateEntry> candidates) {
        public StageEntry {
            Objects.requireNonNull(id); Objects.requireNonNull(parent); Objects.requireNonNull(parentChoice);
            Objects.requireNonNull(kind); Objects.requireNonNull(engine); Objects.requireNonNull(selected);
            Objects.requireNonNull(smoothingInput); candidates=List.copyOf(candidates);
            if (candidates.isEmpty() || candidates.size()>32 || parent.isPresent()!=parentChoice.isPresent()
                    || kind.root()!=parent.isEmpty()
                    || (kind==StageKind.SMOOTH)!=smoothingInput.isPresent()
                    || kind==StageKind.ROOT_LEGACY && engine!=TrackerMode.LEGACY_V02
                    || kind==StageKind.ROOT_NATIVE && !Set.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC).contains(engine)
                    || kind==StageKind.REFINE_A && engine!=TrackerMode.CORRIDOR_AWARE
                    || kind==StageKind.REFINE_B && engine!=TrackerMode.PROBABILISTIC) throw invalid();
            var ids=new HashSet<CandidateId>();
            for(var c:candidates) if(!ids.add(c.id())) throw invalid();
            if(!ids.contains(selected)) throw invalid();
            if(smoothingInput.isPresent()) {
                var input=smoothingInput.get();
                text(input.choice().value()); text(input.unsmoothedChoice().value());
                if(input.owner().equals(id)) throw invalid();
                if(!parent.orElseThrow().equals(input.unsmoothedParent())
                        || !parentChoice.orElseThrow().equals(input.unsmoothedChoice())) throw invalid();
            }
        }
    }
    /** An independent latest unsuccessful attempt need not have produced a child or native work. */
    public record LatestPublication(RefinementAttemptDiagnostics diagnostic,CandidateKey resultingSelection) {
        public LatestPublication {Objects.requireNonNull(diagnostic);Objects.requireNonNull(resultingSelection);}
    }
    public record Document(Binding binding, OriginalOsm originalOsm, List<StageEntry> stages, Optional<RefinementOperationReceipt> latestAttempt,
            Optional<LatestPublication> latestPublication) {
        public Document(Binding binding,OriginalOsm originalOsm,List<StageEntry> stages,Optional<RefinementOperationReceipt> latestAttempt){this(binding,originalOsm,stages,latestAttempt,Optional.empty());}
        public Document {
            Objects.requireNonNull(binding); Objects.requireNonNull(originalOsm); stages=List.copyOf(stages); Objects.requireNonNull(latestAttempt);Objects.requireNonNull(latestPublication);
            validate(binding,originalOsm,stages,latestAttempt,latestPublication);
        }
    }

    /** Snapshots only model metadata; callers must explicitly supply the original-OSM identity/artifact
     * and actual candidate-specific geometry artifacts. No identity is inferred from an operation receipt.
     */
    public static Document capture(String build, String sourceHash, String settingsHash, AlignmentMode mode,
            SessionId session, String originalOsmIdentity, Format15Artifact originalGeometry, List<Stage> history, StageId current,
            Optional<RefinementOperationReceipt> latest, Map<CandidateKey,Format15Artifact> geometry) {
        return capture(build,sourceHash,settingsHash,mode,session,originalOsmIdentity,originalGeometry,history,current,latest,geometry,Optional.empty());
    }
    public static Document capture(String build,String sourceHash,String settingsHash,AlignmentMode mode,
            SessionId session,String originalOsmIdentity,Format15Artifact originalGeometry,List<Stage> history,StageId current,
            Optional<RefinementOperationReceipt> latest,Map<CandidateKey,Format15Artifact> geometry,Optional<LatestPublication> publication) {
        return capture(build,sourceHash,settingsHash,mode,session,originalOsmIdentity,originalGeometry,history,current,latest,geometry,publication,Map.of());
    }
    public static Document capture(String build,String sourceHash,String settingsHash,AlignmentMode mode,
            SessionId session,String originalOsmIdentity,Format15Artifact originalGeometry,List<Stage> history,StageId current,
            Optional<RefinementOperationReceipt> latest,Map<CandidateKey,Format15Artifact> geometry,Optional<LatestPublication> publication,
            Map<CandidateKey,SmoothingPublication> smoothingPublications) {
        if(history==null || history.isEmpty() || history.size()>8 || geometry==null || originalGeometry==null) throw invalid();
        var originalOsm=new OriginalOsm(originalOsmIdentity,new GeometryRef(originalGeometry.name(),originalGeometry.sha256()));
        var entries=new ArrayList<StageEntry>(); var expected=new HashSet<CandidateKey>();
        for(var stage:history) {
            var candidates=new ArrayList<CandidateEntry>();
            for(var candidate:stage.candidates()) {
                var key=new CandidateKey(stage.id(),candidate.id()); expected.add(key);
                var artifact=geometry.get(key); if(artifact==null) throw invalid();
                candidates.add(new CandidateEntry(candidate.id(),candidate.immutableIdentity(),candidate.nativeCandidateId(),
                    candidate.sourcePalette(),new GeometryRef(artifact.name(),artifact.sha256()),
                    candidate.payload().nativeReview().flatMap(review->review.operation()),Optional.ofNullable(smoothingPublications.get(key))));
            }
            entries.add(new StageEntry(stage.id(),stage.parent(),stage.parentChoice(),stage.kind(),stage.engine(),
                stage.selected(),stage.smoothingInput(),candidates));
        }
        if(!expected.equals(geometry.keySet())) throw invalid();
        var selected=history.stream().filter(s->s.id().equals(current)).findFirst().orElseThrow(RefinementLineageCodec::invalid).selected();
        return new Document(new Binding(build,sourceHash,settingsHash,mode,session,history.get(0).id(),current,selected),originalOsm,entries,latest,publication);
    }

    /** Encodes a deterministic JSON artifact with an explicit schema and bounded content. */
    public static Format15Artifact artifact(Document document) {
        String encoded=json(documentMap(document));
        byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw invalid();
        return Format15Artifact.binary(ARTIFACT,bytes);
    }
    /** Decodes strict UTF-8 and strict fields; unknown fields, duplicate keys and deep JSON are rejected. */
    public static Document read(Format15Artifact artifact) {
        if(!ARTIFACT.equals(artifact.name()) || artifact.sizeBytes()>MAX_BYTES) throw invalid();
        try {
            String content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(artifact.bytes())).toString();
            checkNesting(content);
            Object parsed=new Format15ArchiveReader.JsonParser(content).parse();
            var root=parsed instanceof Map<?,?> values&&values.containsKey("latestPublication")
                ?fields(parsed,"schema","binding","originalOsm","stages","latestAttempt","latestPublication")
                :fields(parsed,"schema","binding","originalOsm","stages","latestAttempt");
            if(number(root.get("schema"))!=1) throw invalid();
            var b=fields(root.get("binding"),"build","source","settings","mode","session","root","current","currentChoice");
            var binding=new Binding(string(b.get("build")),string(b.get("source")),string(b.get("settings")),
                enumeration(AlignmentMode.class,b.get("mode")),session(b.get("session")),stage(b.get("root")),
                stage(b.get("current")),candidate(b.get("currentChoice")));
            var original=fields(root.get("originalOsm"),"identity","geometry");
            var originalRef=fields(original.get("geometry"),"artifact","sha256");
            var originalOsm=new OriginalOsm(string(original.get("identity")),
                new GeometryRef(string(originalRef.get("artifact")),string(originalRef.get("sha256"))));
            var entries=new ArrayList<StageEntry>();
            for(var item:array(root.get("stages"),8)) {
                var s=fields(item,"id","parent","parentChoice","kind","engine","selected","smoothingInput","candidates");
                var cs=new ArrayList<CandidateEntry>();
                for(var ci:array(s.get("candidates"),32)) {
                    var c=ci instanceof Map<?,?> values&&values.containsKey("smoothingPublication")
                        ?fields(ci,"id","identity","nativeId","palette","geometry","operation","smoothingPublication")
                        :fields(ci,"id","identity","nativeId","palette","geometry","operation");
                    var g=fields(c.get("geometry"),"artifact","sha256");
                    cs.add(new CandidateEntry(candidate(c.get("id")),string(c.get("identity")),string(c.get("nativeId")),
                        string(c.get("palette")),new GeometryRef(string(g.get("artifact")),string(g.get("sha256"))),
                        optionalReceipt(c.get("operation")),readSmoothingPublication(c.get("smoothingPublication"))));
                }
                entries.add(new StageEntry(stage(s.get("id")),optionalStage(s.get("parent")),optionalCandidate(s.get("parentChoice")),
                    enumeration(StageKind.class,s.get("kind")),enumeration(TrackerMode.class,s.get("engine")),
                    candidate(s.get("selected")),smoothing(s.get("smoothingInput")),cs));
            }
            var latest=optionalReceipt(root.get("latestAttempt"));
            return new Document(binding,originalOsm,entries,latest,readPublication(root.get("latestPublication"),latest));
        } catch (java.nio.charset.CharacterCodingException | RuntimeException error) {
            throw new IllegalArgumentException("Invalid refinement lineage",error);
        }
    }
    /** Validates against supplied immutable bundle bytes and its existing header before attaching. */
    public static Format15Bundle attach(Format15Bundle bundle, Document document) {
        validateBinding(document,bundle,document.binding().current(),document.binding().currentChoice());
        var artifacts=new LinkedHashMap<>(bundle.artifacts());
        if(artifacts.containsKey(ARTIFACT)) throw invalid();
        var artifact=artifact(document); artifacts.put(ARTIFACT,artifact);
        return new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),artifacts);
    }
    /** Old bundles remain unavailable; present lineage must match the actual bundle and requested selection. */
    public static Optional<Document> readBound(Format15Bundle bundle, StageId current, CandidateId choice) {
        var artifact=bundle.artifacts().get(ARTIFACT); if(artifact==null) return Optional.empty();
        var document=read(artifact); validateBinding(document,bundle,current,choice); return Optional.of(document);
    }
    /** Hash consistency is not a substitute for independent source/physical authority checks. */
    public static void validateBinding(Document document, Format15Bundle bundle, StageId current, CandidateId choice) {
        var b=document.binding();
        if(!b.buildIdentity().equals(bundle.buildIdentity()) || !b.sourceIdentityHash().equals(bundle.sourceIdentityHash())
                || !b.parameterHash().equals(bundle.parameterHash()) || !b.current().equals(current)
                || !b.currentChoice().equals(choice)) throw invalid();
        validateGeometry(document.originalOsm().geometry(),bundle);
        for(var s:document.stages()) for(var c:s.candidates()) {
            validateGeometry(c.geometry(),bundle);
            c.smoothingPublication().ifPresent(publication->validateSmoothingArtifact(s,c,publication,bundle));
        }
    }
    private static void validateGeometry(GeometryRef reference,Format15Bundle bundle) {
        var actual=bundle.artifacts().get(reference.artifact());
        if(actual==null || !actual.sha256().equals(reference.sha256())) throw invalid();
    }
    private static void validateSmoothingArtifact(StageEntry stage,CandidateEntry candidate,SmoothingPublication publication,Format15Bundle bundle) {
        validateGeometry(publication.receipt(),bundle);
        var artifact=bundle.artifacts().get(publication.receipt().artifact());
        if(artifact.sizeBytes()>MAX_BYTES)throw invalid();
        try {
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(artifact.bytes())).toString();checkNesting(text);
            Object decoded=new Format15ArchiveReader.JsonParser(text).parse();
            if(!(decoded instanceof Map<?,?> receipt))throw invalid();
            var ticket=publication.ticket();var preset=ticket.smoothingInput().orElseThrow().preset();
            if(number(receipt.get("schema"))!=1||!stage.id().equals(stage(receipt.get("stage")))||!candidate.id().equals(candidate(receipt.get("choice")))
                    ||!candidate.geometry().artifact().equals(string(receipt.get("geometryArtifact")))||!candidate.geometry().sha256().equals(string(receipt.get("geometrySha256")))
                    ||!ticket.equals(readTicket(receipt.get("operationTicket")))||!publication.parentIdentity().equals(string(receipt.get("parentReferenceIdentity")))
                    ||!ticket.mode().name().equals(string(receipt.get("mode")))||!"existing-gaussian-capture-reference-v1".equals(string(receipt.get("method")))
                    ||!"CHANGED".equals(string(receipt.get("outcome")))||number(receipt.get("nativeCalls"))!=0
                    ||!Objects.equals(preset.equals("Medium")?"MEDIUM_1PX":preset.equals("Strong")?"STRONG_2PX":null,string(receipt.get("strength")))
                    ||number(receipt.get("workVisits"))<0||number(receipt.get("proposalWorkVisits"))<0
                    ||number(receipt.get("proposalWorkVisits"))>number(receipt.get("workVisits"))||number(receipt.get("guardTrials"))<0)throw invalid();
            Format15Safety.requiredHash(string(receipt.get("inputHash")),"Gaussian input hash");
            Format15Safety.requiredHash(string(receipt.get("referenceEvidenceHash")),"Gaussian reference hash");
            Format15Safety.requiredHash(string(receipt.get("referenceTransformIdentity")),"Gaussian frame hash");
            if(!(receipt.get("acceptedScale") instanceof Number scale)||!Double.isFinite(scale.doubleValue())||scale.doubleValue()<=0||scale.doubleValue()>1
                    ||number(receipt.get("referenceWidth"))<=0||number(receipt.get("referenceHeight"))<=0)throw invalid();
            String protocol=string(receipt.get("inputHashProtocol"));
            if(!Set.of(org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_SET_PROTOCOL).contains(protocol))throw invalid();
            identity(string(receipt.get("mappingIdentity")));identity(string(receipt.get("referenceField")));
        }catch(java.nio.charset.CharacterCodingException malformed){throw invalid();}
    }
    public static String operationTicketJson(OperationTicket ticket){return json(ticketMap(ticket));}
    private static Map<String,Object> smoothingPublicationMap(SmoothingPublication publication){
        return map("ticket",ticketMap(publication.ticket()),"receipt",map("artifact",publication.receipt().artifact(),"sha256",publication.receipt().sha256()),"parentIdentity",publication.parentIdentity());
    }
    private static Optional<SmoothingPublication> readSmoothingPublication(Object value){
        if(value==null)return Optional.empty();var publication=fields(value,"ticket","receipt","parentIdentity");var receipt=fields(publication.get("receipt"),"artifact","sha256");
        return Optional.of(new SmoothingPublication(readTicket(publication.get("ticket")),new GeometryRef(string(receipt.get("artifact")),string(receipt.get("sha256"))),string(publication.get("parentIdentity"))));
    }

    private static void validate(Binding b,OriginalOsm original,List<StageEntry> stages,Optional<RefinementOperationReceipt> latest,Optional<LatestPublication> publication) {
        if(stages.isEmpty() || stages.size()>8 || !stages.get(0).id().equals(b.root())) throw invalid();
        var retained=new LinkedHashMap<StageId,StageEntry>();
        var allIds=new HashSet<StageId>(); for(var s:stages) allIds.add(s.id());
        var sequences=new HashMap<Long,RefinementOperationReceipt>(); long lastSequence=0; int totalAttemptCopies=0;
        for(int index=0;index<stages.size();index++) {
            var s=stages.get(index);
            if(retained.containsKey(s.id()) || (index==0)!=s.kind().root()) throw invalid();
            if(index>0) {
                // Retained history is linear, including smoothing replacement's unsmoothed input.
                if(!s.parent().orElseThrow().equals(stages.get(index-1).id())) throw invalid();
                requireChoice(retained,s.parent().orElseThrow(),s.parentChoice().orElseThrow());
                if(s.smoothingInput().isPresent()) {
                    var input=s.smoothingInput().get();
                    if(s.engine()!=retained.get(s.parent().orElseThrow()).engine()
                            || allIds.contains(input.owner()) && !retained.containsKey(input.owner())) throw invalid();
                    if(retained.containsKey(input.owner())) requireChoice(retained,input.owner(),input.choice());
                }
            }
            long stageSequence=0;
            for(var c:s.candidates())if(c.smoothingPublication().isPresent()) {
                if(index==0||s.kind()!=StageKind.SMOOTH)throw invalid();
                var t=c.smoothingPublication().orElseThrow().ticket();var input=t.smoothingInput().orElseThrow();
                if(!t.session().equals(b.session())||t.mode()!=b.mode()||t.engine()!=s.engine()||!s.smoothingInput().equals(t.smoothingInput())
                        ||!s.parent().orElseThrow().equals(input.unsmoothedParent())||!s.parentChoice().orElseThrow().equals(input.unsmoothedChoice())
                        ||stageSequence!=0&&stageSequence!=t.sequence())throw invalid();
                stageSequence=t.sequence();
            }
            for(var c:s.candidates()) if(c.operation().isPresent()) {
                if(index==0) throw invalid();
                var r=c.operation().get(); validateReceipt(r,b,retained);
                totalAttemptCopies+=r.attempts().size(); if(totalAttemptCopies>4096) throw invalid();
                var t=r.ticket();
                var input=t.smoothingInput().map(SmoothingInput::unsmoothedParent).orElse(t.parent());
                var choice=t.smoothingInput().map(SmoothingInput::unsmoothedChoice).orElse(t.choice());
                if(!s.parent().orElseThrow().equals(input) || !s.parentChoice().orElseThrow().equals(choice)
                        || s.kind()!=t.kind() || s.engine()!=t.engine() || !s.smoothingInput().equals(t.smoothingInput())
                        || r.outcome()!=Outcome.COMPLETE && r.outcome()!=Outcome.PARTIAL && r.outcome()!=Outcome.RESOURCE_LIMIT
                        || stageSequence!=0 && stageSequence!=t.sequence()) throw invalid();
                stageSequence=t.sequence();
                var previous=sequences.putIfAbsent(t.sequence(),r);
                if(previous!=null && !previous.equals(r)) throw invalid();
            }
            if(stageSequence!=0) {
                if(stageSequence<=lastSequence) throw invalid(); lastSequence=stageSequence;
            }
            retained.put(s.id(),s);
        }
        requireChoice(retained,b.current(),b.currentChoice());
        if(!retained.get(b.current()).selected().equals(b.currentChoice())) throw invalid();
        if(publication.isPresent()) {
            validatePublication(b,original,retained,sequences,lastSequence,latest,publication.get());
        } else if(latest.isPresent()) {
            var r=latest.get(); validateReceipt(r,b,retained);
            requireChoice(retained,r.ticket().parent(),r.ticket().choice());
            if(r.outcome()==Outcome.COMPLETE || r.outcome()==Outcome.PARTIAL
                    || sequences.containsKey(r.ticket().sequence()) || r.ticket().sequence()<=lastSequence) throw invalid();
        }
    }
    private static void validatePublication(Binding b,OriginalOsm original,Map<StageId,StageEntry> retained,
            Map<Long,RefinementOperationReceipt> sequences,long lastSequence,Optional<RefinementOperationReceipt> latest,LatestPublication state) {
        var diagnostic=state.diagnostic();var binding=diagnostic.binding();var ticket=binding.ticket();
        if(!ticket.session().equals(b.session())||ticket.mode()!=b.mode()||!binding.rootStage().equals(b.root())
                ||!Format15Safety.sha256(binding.rootSourceIdentity()).equals(b.sourceIdentityHash())
                ||!binding.rootParameterHash().equals(b.parameterHash())||!binding.originalNetworkHash().equals(original.immutableIdentity())
                ||!diagnostic.pipeline().equals(latest))throw invalid();
        identity(binding.rootSourceIdentity());identity(binding.frozenGeometryIdentity());identity(binding.parentIdentity());
        Format15Safety.requiredHash(binding.originalNetworkHash(),"original network hash");
        if(diagnostic.publication()==RefinementAttemptDiagnostics.Publication.PUBLISHED&&ticket.smoothingInput().isPresent()) {
            var input=ticket.smoothingInput().get();
            requireChoice(retained,input.unsmoothedParent(),input.unsmoothedChoice());
            if(retained.containsKey(input.owner()))requireChoice(retained,input.owner(),input.choice());
        } else requireChoice(retained,ticket.parent(),ticket.choice());
        requireChoice(retained,state.resultingSelection().stage(),state.resultingSelection().candidate());
        for(var receipt:sequences.values())if(!receipt.originalNetworkHash().equals(binding.originalNetworkHash()))throw invalid();
        latest.ifPresent(receipt->{validateReceipt(receipt,b,retained);if(!receipt.ticket().equals(ticket)||!receipt.parentIdentity().equals(binding.parentIdentity())
            ||!receipt.originalNetworkHash().equals(binding.originalNetworkHash()))throw invalid();});
        var disposition=diagnostic.publication();var cause=diagnostic.cause();
        if(disposition==RefinementAttemptDiagnostics.Publication.PUBLISHED) {
            StageId newest=null;for(var retainedStage:retained.keySet())newest=retainedStage;
            if(ticket.kind()==StageKind.SMOOTH) {
                if(!diagnostic.materializationComplete()||latest.isPresent()||cause!=RefinementAttemptDiagnostics.Cause.NONE
                        ||diagnostic.phase()!=RefinementAttemptDiagnostics.Phase.PUBLICATION||ticket.sequence()!=lastSequence
                        ||!state.resultingSelection().stage().equals(newest))throw invalid();
                var stage=retained.get(state.resultingSelection().stage());
                var selected=stage.candidates().stream().filter(c->c.id().equals(state.resultingSelection().candidate())).findFirst().orElseThrow(RefinementLineageCodec::invalid);
                var actual=selected.smoothingPublication().orElseThrow(RefinementLineageCodec::invalid);
                if(!actual.ticket().equals(ticket)||!actual.parentIdentity().equals(binding.parentIdentity()))throw invalid();
                return;
            }
            if(!diagnostic.materializationComplete()||latest.isEmpty()||cause!=RefinementAttemptDiagnostics.Cause.NONE||diagnostic.phase()!=RefinementAttemptDiagnostics.Phase.PUBLICATION
                    ||ticket.sequence()!=lastSequence||!state.resultingSelection().stage().equals(newest)||!latest.get().equals(sequences.get(ticket.sequence())))throw invalid();
            var stage=retained.get(state.resultingSelection().stage());
            if(stage.kind()!=ticket.kind()||stage.engine()!=ticket.engine()||!stage.parent().equals(Optional.of(ticket.smoothingInput().map(SmoothingInput::unsmoothedParent).orElse(ticket.parent())))
                    ||!stage.parentChoice().equals(Optional.of(ticket.smoothingInput().map(SmoothingInput::unsmoothedChoice).orElse(ticket.choice())))
                    ||stage.candidates().stream().filter(c->c.id().equals(state.resultingSelection().candidate())).noneMatch(c->c.operation().equals(latest)))throw invalid();
        } else {
            if(!state.resultingSelection().equals(new CandidateKey(ticket.parent(),ticket.choice()))||sequences.containsKey(ticket.sequence())||ticket.sequence()<=lastSequence)throw invalid();
            if(disposition==RefinementAttemptDiagnostics.Publication.CANCELLED&&cause!=RefinementAttemptDiagnostics.Cause.CANCELLED
                    ||disposition==RefinementAttemptDiagnostics.Publication.STALE&&cause!=RefinementAttemptDiagnostics.Cause.STALE
                    ||cause==RefinementAttemptDiagnostics.Cause.NONE)throw invalid();
        }
    }
    private static void requireChoice(Map<StageId,StageEntry> retained,StageId id,CandidateId choice) {
        var stage=retained.get(id);
        if(stage==null || stage.candidates().stream().noneMatch(c->c.id().equals(choice))) throw invalid();
    }
    private static void validateReceipt(RefinementOperationReceipt r,Binding b,Map<StageId,StageEntry> retained) {
        var t=r.ticket();
        if(!t.session().equals(b.session()) || t.mode()!=b.mode() || r.attempts().size()>MAX_ATTEMPTS) throw invalid();
        text(t.choice().value()); identity(r.parentIdentity()); Format15Safety.requiredHash(r.originalNetworkHash(),"original network hash");
        Objects.requireNonNull(r.outcome()); Objects.requireNonNull(r.actualWork());
        if(t.smoothingInput().isPresent()) {
            var input=t.smoothingInput().get();
            requireChoice(retained,input.unsmoothedParent(),input.unsmoothedChoice());
            // The invocation owner of a replaced smoothing stage is legitimately no longer retained.
            if(retained.containsKey(input.owner())) requireChoice(retained,input.owner(),input.choice());
            text(input.choice().value()); text(input.unsmoothedChoice().value());
        } else requireChoice(retained,t.parent(),t.choice());
        for(var a:r.attempts()) {
            text(a.palette()); text(a.mapping()); text(a.reason()); Objects.requireNonNull(a.kind());
            Objects.requireNonNull(a.range()); Objects.requireNonNull(a.outcome()); Objects.requireNonNull(a.nativeCalls());
            Objects.requireNonNull(a.actualWork()); Objects.requireNonNull(a.nativeStatus());
            if(a.nativeCalls().isPresent() && a.nativeCalls().getAsInt()<0 || a.nativeCandidateIds().size()>32) throw invalid();
            a.evidenceHash().ifPresent(h->Format15Safety.requiredHash(h,"evidence hash"));
            a.parameterHash().ifPresent(h->Format15Safety.requiredHash(h,"parameter hash"));
            a.samplerId().ifPresent(RefinementLineageCodec::text);
            var ids=new HashSet<String>(); for(var id:a.nativeCandidateIds()) {text(id); if(!ids.add(id)) throw invalid();}
            if((a.kind()==RefinementPipeline.AttemptKind.UNAVAILABLE || a.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED
                    || a.kind()==RefinementPipeline.AttemptKind.FROZEN) && (a.outcome().isPresent() || a.nativeCalls().isPresent() && a.nativeCalls().getAsInt()!=0
                    || a.actualWork().isPresent() || a.evidenceHash().isPresent() || a.parameterHash().isPresent()
                    || a.samplerId().isPresent() || a.nativeStatus().isPresent() || !a.nativeCandidateIds().isEmpty())) throw invalid();
        }
    }

    private static Map<String,Object> documentMap(Document d) {
        var b=d.binding(); var stages=new ArrayList<Object>();
        for(var s:d.stages()) {
            var cs=new ArrayList<Object>();
            for(var c:s.candidates()) cs.add(map("id",c.id().value(),"identity",c.immutableIdentity(),"nativeId",c.nativeCandidateId(),
                "palette",c.sourcePalette(),"geometry",map("artifact",c.geometry().artifact(),"sha256",c.geometry().sha256()),
                "operation",c.operation().map(RefinementLineageCodec::receiptMap).orElse(null),
                "smoothingPublication",c.smoothingPublication().map(RefinementLineageCodec::smoothingPublicationMap).orElse(null)));
            stages.add(map("id",s.id().value().toString(),"parent",s.parent().map(v->v.value().toString()).orElse(null),
                "parentChoice",s.parentChoice().map(CandidateId::value).orElse(null),"kind",s.kind().name(),"engine",s.engine().name(),
                "selected",s.selected().value(),"smoothingInput",smoothingMap(s.smoothingInput()),"candidates",cs));
        }
        var result=map("schema",1,"originalOsm",map("identity",d.originalOsm().immutableIdentity(),
            "geometry",map("artifact",d.originalOsm().geometry().artifact(),"sha256",d.originalOsm().geometry().sha256())),"binding",map("build",b.buildIdentity(),"source",b.sourceIdentityHash(),"settings",b.parameterHash(),
            "mode",b.mode().name(),"session",b.session().value().toString(),"root",b.root().value().toString(),
            "current",b.current().value().toString(),"currentChoice",b.currentChoice().value()),"stages",stages,
            "latestAttempt",d.latestAttempt().map(RefinementLineageCodec::receiptMap).orElse(null));
        d.latestPublication().ifPresent(state->result.put("latestPublication",publicationMap(state)));return result;
    }
    private static Map<String,Object> publicationMap(LatestPublication state) {
        var d=state.diagnostic();var b=d.binding();
        var result=map("ticket",ticketMap(b.ticket()),"root",b.rootStage().value().toString(),"sourceIdentity",b.rootSourceIdentity(),
            "originalNetworkHash",b.originalNetworkHash(),"rootParameters",b.rootParameterHash(),"frozenGeometry",b.frozenGeometryIdentity(),"parentIdentity",b.parentIdentity(),
            "publication",d.publication().name(),"phase",d.phase().name(),"cause",d.cause().name(),"refusals",d.materializationRefusals(),"refusalsComplete",d.materializationComplete(),
            "resultStage",state.resultingSelection().stage().value().toString(),"resultChoice",state.resultingSelection().candidate().value());
        d.sourcePreparation().ifPresent(work->result.put("sourcePreparation",sourcePreparationMap(work)));return result;
    }
    private static Map<String,Object> sourcePreparationMap(RefinementAttemptDiagnostics.SourcePreparationWork work) {
        var counters=new LinkedHashMap<String,Object>();for(var kind:org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values())counters.put(kind.name(),work.counters().get(kind));
        Object source=work.failedSource().map(s->map("palette",s.palette(),"mapping",s.mapping(),"aggregate",s.aggregate(),"unavailable",s.unavailableReason())).orElse(null);
        Object nativeWork=work.nativeWork().map(n->map("outcome",n.outcome().name(),"work",workMap(n.actualWork()),"calls",n.nativeCalls().isPresent()?n.nativeCalls().getAsInt():null)).orElse(null);
        var result=map("phase","SOURCE_PREPARATION","cause",work.cause().name(),"visits",work.visits(),"counters",counters,"failedSource",source,"nativeWork",nativeWork);
        work.sourceFacts().ifPresent(f->result.put("sourceFacts",map("ticket",ticketMap(f.ticket()),"sourceIdentityHash",f.sourceIdentityHash(),"sourceKind",f.sourceKind().name(),"coverage",f.coverage().name(),
            "planHash",f.planHash(),"frameHash",f.frameHash(),"inputHash",f.inputHash(),"frameRole",f.frameRole().name(),"frameWidth",f.frameWidth(),"frameHeight",f.frameHeight(),
            "outputGridWidth",f.outputGridWidth(),"outputGridHeight",f.outputGridHeight(),"sourceRevision",f.sourceRevision(),"captureAuxiliaryVisits",f.captureAuxiliaryVisits().isPresent()?f.captureAuxiliaryVisits().getAsLong():null)));
        return result;
    }
    private static Optional<RefinementAttemptDiagnostics.SourcePreparationWork> readSourcePreparation(Object value) {
        if(value==null)return Optional.empty();
        var keys=new ArrayList<>(List.of("phase","cause","visits","counters","failedSource","nativeWork"));if(value instanceof Map<?,?> m&&m.containsKey("sourceFacts"))keys.add("sourceFacts");
        var p=fields(value,keys.toArray(String[]::new));if(!"SOURCE_PREPARATION".equals(p.get("phase")))throw invalid();
        var kinds=org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values();var names=Arrays.stream(kinds).map(Enum::name).toArray(String[]::new);var raw=fields(p.get("counters"),names);
        var counters=new EnumMap<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long>(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.class);for(var kind:kinds)counters.put(kind,number(raw.get(kind.name())));
        Optional<RefinementSourcePlanner.SourceRequest> source=Optional.empty();if(p.get("failedSource")!=null){var v=fields(p.get("failedSource"),"palette","mapping","aggregate","unavailable");if(!(v.get("aggregate") instanceof Boolean))throw invalid();source=Optional.of(new RefinementSourcePlanner.SourceRequest(string(v.get("palette")),string(v.get("mapping")),(Boolean)v.get("aggregate"),string(v.get("unavailable"))));}
        Optional<RefinementAttemptDiagnostics.NativeWork> nativeWork=Optional.empty();if(p.get("nativeWork")!=null){var v=fields(p.get("nativeWork"),"outcome","work","calls");nativeWork=Optional.of(new RefinementAttemptDiagnostics.NativeWork(enumeration(Outcome.class,v.get("outcome")),work(v.get("work")),v.get("calls")==null?OptionalInt.empty():OptionalInt.of(integer(v.get("calls")))));}
        return Optional.of(new RefinementAttemptDiagnostics.SourcePreparationWork(enumeration(RefinementAttemptDiagnostics.Cause.class,p.get("cause")),number(p.get("visits")),counters,source,nativeWork,readSourceFacts(p.get("sourceFacts"))));
    }
    private static Optional<RefinementAttemptDiagnostics.SourceFacts> readSourceFacts(Object value){
        if(value==null)return Optional.empty();
        var p=fields(value,"ticket","sourceIdentityHash","sourceKind","coverage","planHash","frameHash","inputHash","frameRole","frameWidth","frameHeight","outputGridWidth","outputGridHeight","sourceRevision","captureAuxiliaryVisits");
        RefinementAttemptDiagnostics.SourceKind kind;RefinementAttemptDiagnostics.Coverage coverage;RefinementAttemptDiagnostics.InputFrameRole role;
        try{kind=RefinementAttemptDiagnostics.SourceKind.valueOf(string(p.get("sourceKind")));coverage=RefinementAttemptDiagnostics.Coverage.valueOf(string(p.get("coverage")));role=RefinementAttemptDiagnostics.InputFrameRole.valueOf(string(p.get("frameRole")));}
        catch(IllegalArgumentException unknown){return Optional.empty();}
        return Optional.of(new RefinementAttemptDiagnostics.SourceFacts(readTicket(p.get("ticket")),string(p.get("sourceIdentityHash")),kind,coverage,string(p.get("planHash")),string(p.get("frameHash")),string(p.get("inputHash")),role,
            integer(p.get("frameWidth")),integer(p.get("frameHeight")),integer(p.get("outputGridWidth")),integer(p.get("outputGridHeight")),number(p.get("sourceRevision")),p.get("captureAuxiliaryVisits")==null?OptionalLong.empty():OptionalLong.of(number(p.get("captureAuxiliaryVisits")))));
    }
    private static Optional<LatestPublication> readPublication(Object value,Optional<RefinementOperationReceipt> receipt) {
        if(value==null)return Optional.empty();
        boolean hasCompleteness=value instanceof Map<?,?> m && m.containsKey("refusalsComplete");
        var keys=new ArrayList<>(List.of("ticket","root","sourceIdentity","originalNetworkHash","rootParameters","frozenGeometry","parentIdentity","publication","phase","cause","refusals","resultStage","resultChoice"));
        if(hasCompleteness)keys.add("refusalsComplete");
        if(value instanceof Map<?,?> values&&values.containsKey("sourcePreparation"))keys.add("sourcePreparation");
        var p=fields(value,keys.toArray(String[]::new));
        if(hasCompleteness && !(p.get("refusalsComplete") instanceof Boolean))throw invalid();
        var binding=new RefinementAttemptDiagnostics.Binding(readTicket(p.get("ticket")),stage(p.get("root")),string(p.get("sourceIdentity")),string(p.get("originalNetworkHash")),
            string(p.get("rootParameters")),string(p.get("frozenGeometry")),string(p.get("parentIdentity")));
        var diagnostic=new RefinementAttemptDiagnostics(binding,receipt,enumeration(RefinementAttemptDiagnostics.Publication.class,p.get("publication")),
            enumeration(RefinementAttemptDiagnostics.Phase.class,p.get("phase")),enumeration(RefinementAttemptDiagnostics.Cause.class,p.get("cause")),integer(p.get("refusals")),!hasCompleteness || (Boolean)p.get("refusalsComplete"),readSourcePreparation(p.get("sourcePreparation")));
        return Optional.of(new LatestPublication(diagnostic,new CandidateKey(stage(p.get("resultStage")),candidate(p.get("resultChoice")))));
    }
    private static Object smoothingMap(Optional<SmoothingInput> input) {
        return input.map(s->map("owner",s.owner().value().toString(),"choice",s.choice().value(),"unsmoothedParent",s.unsmoothedParent().value().toString(),
            "unsmoothedChoice",s.unsmoothedChoice().value(),"preset",s.preset())).orElse(null);
    }
    private static Map<String,Object> receiptMap(RefinementOperationReceipt r) {
        var t=r.ticket(); var attempts=new ArrayList<Object>();
        for(var a:r.attempts()) attempts.add(map("palette",a.palette(),"mapping",a.mapping(),"kind",a.kind().name(),
            "first",a.range().firstIndex(),"last",a.range().lastIndex(),"reason",a.reason(),"outcome",a.outcome().map(Enum::name).orElse(null),
            "nativeCalls",a.nativeCalls().isPresent()?a.nativeCalls().getAsInt():null,"work",workMap(a.actualWork()),
            "evidence",a.evidenceHash().orElse(null),"parameters",a.parameterHash().orElse(null),"sampler",a.samplerId().orElse(null),
            "nativeStatus",a.nativeStatus().map(Enum::name).orElse(null),"nativeCandidates",a.nativeCandidateIds()));
        return map("ticket",ticketMap(t),"parentIdentity",r.parentIdentity(),"originalNetworkHash",r.originalNetworkHash(),
            "outcome",r.outcome().name(),"attempts",attempts,"work",workMap(r.actualWork()),"truncated",r.truncated());
    }
    private static Map<String,Object> ticketMap(OperationTicket t) {
        return map("session",t.session().value().toString(),"sequence",t.sequence(),"parent",t.parent().value().toString(),"choice",t.choice().value(),
            "engine",t.engine().name(),"mode",t.mode().name(),"kind",t.kind().name(),"smoothingInput",smoothingMap(t.smoothingInput()));
    }
    private static OperationTicket readTicket(Object value) {
        var t=fields(value,"session","sequence","parent","choice","engine","mode","kind","smoothingInput");
        return new OperationTicket(session(t.get("session")),number(t.get("sequence")),stage(t.get("parent")),candidate(t.get("choice")),
            enumeration(TrackerMode.class,t.get("engine")),enumeration(AlignmentMode.class,t.get("mode")),enumeration(StageKind.class,t.get("kind")),smoothing(t.get("smoothingInput")));
    }
    private static Object workMap(Optional<TraceWorkUsage> work) {
        return work.map(w->List.of(w.pairVisits(),w.transitions(),(long)w.rawAlternatives(),(long)w.distinctAlternatives())).orElse(null);
    }
    private static Optional<RefinementOperationReceipt> optionalReceipt(Object value) {
        if(value==null) return Optional.empty();
        var r=fields(value,"ticket","parentIdentity","originalNetworkHash","outcome","attempts","work","truncated");
        var ticket=readTicket(r.get("ticket"));
        var attempts=new ArrayList<RefinementOperationReceipt.Attempt>();
        for(var item:array(r.get("attempts"),MAX_ATTEMPTS)) {
            var a=fields(item,"palette","mapping","kind","first","last","reason","outcome","nativeCalls","work", "evidence","parameters","sampler","nativeStatus","nativeCandidates");
            var ids=new ArrayList<String>(); for(var id:array(a.get("nativeCandidates"),32)) ids.add(string(id));
            attempts.add(new RefinementOperationReceipt.Attempt(string(a.get("palette")),string(a.get("mapping")),
                enumeration(RefinementPipeline.AttemptKind.class,a.get("kind")),new OccurrenceRange(integer(a.get("first")),integer(a.get("last"))),
                string(a.get("reason")),optionalEnum(Outcome.class,a.get("outcome")),a.get("nativeCalls")==null?OptionalInt.empty():OptionalInt.of(integer(a.get("nativeCalls"))),
                work(a.get("work")),optionalString(a.get("evidence")),optionalString(a.get("parameters")),optionalString(a.get("sampler")),
                optionalEnum(TraceHypothesisSet.Status.class,a.get("nativeStatus")),ids));
        }
        if(!(r.get("truncated") instanceof Boolean truncated)) throw invalid();
        return Optional.of(new RefinementOperationReceipt(ticket,string(r.get("parentIdentity")),string(r.get("originalNetworkHash")),
            enumeration(Outcome.class,r.get("outcome")),attempts,work(r.get("work")),truncated));
    }
    private static Optional<SmoothingInput> smoothing(Object value) {
        if(value==null) return Optional.empty();
        var s=fields(value,"owner","choice","unsmoothedParent","unsmoothedChoice","preset");
        return Optional.of(new SmoothingInput(stage(s.get("owner")),candidate(s.get("choice")),stage(s.get("unsmoothedParent")),
            candidate(s.get("unsmoothedChoice")),string(s.get("preset"))));
    }
    private static Optional<TraceWorkUsage> work(Object value) {
        if(value==null) return Optional.empty(); var a=array(value,4); if(a.size()!=4) throw invalid();
        return Optional.of(new TraceWorkUsage(number(a.get(0)),number(a.get(1)),integer(a.get(2)),integer(a.get(3))));
    }
    private static Map<String,Object> map(Object... pairs) {
        var result=new LinkedHashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> fields(Object value,String... keys) {
        if(!(value instanceof Map<?,?> m) || !m.keySet().equals(Set.of(keys))) throw invalid();
        return (Map<String,Object>)m;
    }
    private static List<?> array(Object value,int max) {
        if(!(value instanceof List<?> list) || list.size()>max) throw invalid(); return list;
    }
    private static String string(Object value) { if(!(value instanceof String s)) throw invalid(); text(s); return s; }
    private static void identity(String value) {
        text(value); if(value.isBlank()) throw invalid();
    }
    private static void text(String value) {
        if(value==null || value.length()>MAX_STRING || value.chars().anyMatch(c->c<32 || c==127)) throw invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if(Character.isLowSurrogate(c)) throw invalid();
        }
        Format15Safety.requireSafeExportedMetadata(value);
        // Metadata is coordinate-free identity/reason text; URLs and credential syntax are never needed.
        var lower=value.toLowerCase(Locale.ROOT);
        if(value.contains("://") || lower.contains("cookie=") || lower.contains("token=") || lower.contains("password=")) throw invalid();
    }
    private static long number(Object value) { if(!(value instanceof Long n)) throw invalid(); return n; }
    private static int integer(Object value) { long n=number(value); if(n<0 || n>Integer.MAX_VALUE) throw invalid(); return (int)n; }
    private static StageId stage(Object value) { var s=string(value); var id=UUID.fromString(s); if(!id.toString().equals(s)) throw invalid(); return new StageId(id); }
    private static SessionId session(Object value) { return new SessionId(stage(value).value()); }
    private static CandidateId candidate(Object value) { return new CandidateId(string(value)); }
    private static Optional<StageId> optionalStage(Object value) { return value==null?Optional.empty():Optional.of(stage(value)); }
    private static Optional<CandidateId> optionalCandidate(Object value) { return value==null?Optional.empty():Optional.of(candidate(value)); }
    private static Optional<String> optionalString(Object value) { return value==null?Optional.empty():Optional.of(string(value)); }
    private static <E extends Enum<E>> E enumeration(Class<E> type,Object value) { return Enum.valueOf(type,string(value)); }
    private static <E extends Enum<E>> Optional<E> optionalEnum(Class<E> type,Object value) { return value==null?Optional.empty():Optional.of(enumeration(type,value)); }
    private static String json(Object value) {
        var out=new StringBuilder(); appendJson(out,value); return out.toString();
    }
    private static void appendJson(StringBuilder out,Object value) {
        if(value==null) out.append("null");
        else if(value instanceof String s) out.append(ScalarReplayExpectation.quote(s));
        else if(value instanceof Number || value instanceof Boolean) out.append(value);
        else if(value instanceof Map<?,?> m) {
            out.append('{'); boolean first=true;
            for(var e:m.entrySet()) {
                if(!first) out.append(','); first=false;
                out.append(ScalarReplayExpectation.quote((String)e.getKey())).append(':'); appendJson(out,e.getValue());
            }
            out.append('}');
        } else if(value instanceof List<?> a) {
            out.append('['); boolean first=true;
            for(var v:a) {if(!first) out.append(','); first=false; appendJson(out,v);}
            out.append(']');
        } else throw invalid();
        if(out.length()>MAX_BYTES) throw invalid();
    }
    private static void checkNesting(String content) {
        int depth=0; boolean quoted=false,escaped=false;
        for(int i=0;i<content.length();i++) {
            char c=content.charAt(i);
            if(quoted) { if(escaped) escaped=false; else if(c=='\\') escaped=true; else if(c=='"') quoted=false; }
            else if(c=='"') quoted=true;
            else if(c=='{' || c=='[') { if(++depth>24) throw invalid(); }
            else if(c=='}' || c==']') { if(--depth<0) throw invalid(); }
        }
        if(depth!=0 || quoted) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid refinement lineage"); }
}
