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
    public record CandidateEntry(CandidateId id, String immutableIdentity, String nativeCandidateId,
            String sourcePalette, GeometryRef geometry, Optional<RefinementOperationReceipt> operation) {
        public CandidateEntry {
            Objects.requireNonNull(id); text(id.value()); identity(immutableIdentity); identity(nativeCandidateId);
            text(sourcePalette); Objects.requireNonNull(geometry); Objects.requireNonNull(operation);
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
                    || (kind==StageKind.ROOT_LEGACY)!=parent.isEmpty()
                    || (kind==StageKind.SMOOTH)!=smoothingInput.isPresent()
                    || kind==StageKind.ROOT_LEGACY && engine!=TrackerMode.LEGACY_V02
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
    public record Document(Binding binding, OriginalOsm originalOsm, List<StageEntry> stages, Optional<RefinementOperationReceipt> latestAttempt) {
        public Document {
            Objects.requireNonNull(binding); Objects.requireNonNull(originalOsm); stages=List.copyOf(stages); Objects.requireNonNull(latestAttempt);
            validate(binding,stages,latestAttempt);
        }
    }

    /** Snapshots only model metadata; callers must explicitly supply the original-OSM identity/artifact
     * and actual candidate-specific geometry artifacts. No identity is inferred from an operation receipt.
     */
    public static Document capture(String build, String sourceHash, String settingsHash, AlignmentMode mode,
            SessionId session, String originalOsmIdentity, Format15Artifact originalGeometry, List<Stage> history, StageId current,
            Optional<RefinementOperationReceipt> latest, Map<CandidateKey,Format15Artifact> geometry) {
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
                    candidate.payload().nativeReview().flatMap(review->review.operation())));
            }
            entries.add(new StageEntry(stage.id(),stage.parent(),stage.parentChoice(),stage.kind(),stage.engine(),
                stage.selected(),stage.smoothingInput(),candidates));
        }
        if(!expected.equals(geometry.keySet())) throw invalid();
        var selected=history.stream().filter(s->s.id().equals(current)).findFirst().orElseThrow(RefinementLineageCodec::invalid).selected();
        return new Document(new Binding(build,sourceHash,settingsHash,mode,session,history.get(0).id(),current,selected),originalOsm,entries,latest);
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
            var root=fields(new Format15ArchiveReader.JsonParser(content).parse(),"schema","binding","originalOsm","stages","latestAttempt");
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
                    var c=fields(ci,"id","identity","nativeId","palette","geometry","operation");
                    var g=fields(c.get("geometry"),"artifact","sha256");
                    cs.add(new CandidateEntry(candidate(c.get("id")),string(c.get("identity")),string(c.get("nativeId")),
                        string(c.get("palette")),new GeometryRef(string(g.get("artifact")),string(g.get("sha256"))),
                        optionalReceipt(c.get("operation"))));
                }
                entries.add(new StageEntry(stage(s.get("id")),optionalStage(s.get("parent")),optionalCandidate(s.get("parentChoice")),
                    enumeration(StageKind.class,s.get("kind")),enumeration(TrackerMode.class,s.get("engine")),
                    candidate(s.get("selected")),smoothing(s.get("smoothingInput")),cs));
            }
            return new Document(binding,originalOsm,entries,optionalReceipt(root.get("latestAttempt")));
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
        for(var s:document.stages()) for(var c:s.candidates()) validateGeometry(c.geometry(),bundle);
    }
    private static void validateGeometry(GeometryRef reference,Format15Bundle bundle) {
        var actual=bundle.artifacts().get(reference.artifact());
        if(actual==null || !actual.sha256().equals(reference.sha256())) throw invalid();
    }

    private static void validate(Binding b,List<StageEntry> stages,Optional<RefinementOperationReceipt> latest) {
        if(stages.isEmpty() || stages.size()>8 || !stages.get(0).id().equals(b.root())) throw invalid();
        var retained=new LinkedHashMap<StageId,StageEntry>();
        var allIds=new HashSet<StageId>(); for(var s:stages) allIds.add(s.id());
        var sequences=new HashMap<Long,RefinementOperationReceipt>(); long lastSequence=0; int totalAttemptCopies=0;
        for(int index=0;index<stages.size();index++) {
            var s=stages.get(index);
            if(retained.containsKey(s.id()) || (index==0)!=(s.kind()==StageKind.ROOT_LEGACY)) throw invalid();
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
        if(latest.isPresent()) {
            var r=latest.get(); validateReceipt(r,b,retained);
            requireChoice(retained,r.ticket().parent(),r.ticket().choice());
            if(r.outcome()==Outcome.COMPLETE || r.outcome()==Outcome.PARTIAL
                    || sequences.containsKey(r.ticket().sequence()) || r.ticket().sequence()<=lastSequence) throw invalid();
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
                "operation",c.operation().map(RefinementLineageCodec::receiptMap).orElse(null)));
            stages.add(map("id",s.id().value().toString(),"parent",s.parent().map(v->v.value().toString()).orElse(null),
                "parentChoice",s.parentChoice().map(CandidateId::value).orElse(null),"kind",s.kind().name(),"engine",s.engine().name(),
                "selected",s.selected().value(),"smoothingInput",smoothingMap(s.smoothingInput()),"candidates",cs));
        }
        return map("schema",1,"originalOsm",map("identity",d.originalOsm().immutableIdentity(),
            "geometry",map("artifact",d.originalOsm().geometry().artifact(),"sha256",d.originalOsm().geometry().sha256())),"binding",map("build",b.buildIdentity(),"source",b.sourceIdentityHash(),"settings",b.parameterHash(),
            "mode",b.mode().name(),"session",b.session().value().toString(),"root",b.root().value().toString(),
            "current",b.current().value().toString(),"currentChoice",b.currentChoice().value()),"stages",stages,
            "latestAttempt",d.latestAttempt().map(RefinementLineageCodec::receiptMap).orElse(null));
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
        return map("ticket",map("session",t.session().value().toString(),"sequence",t.sequence(),"parent",t.parent().value().toString(),
            "choice",t.choice().value(),"engine",t.engine().name(),"mode",t.mode().name(),"kind",t.kind().name(),
            "smoothingInput",smoothingMap(t.smoothingInput())),"parentIdentity",r.parentIdentity(),"originalNetworkHash",r.originalNetworkHash(),
            "outcome",r.outcome().name(),"attempts",attempts,"work",workMap(r.actualWork()),"truncated",r.truncated());
    }
    private static Object workMap(Optional<TraceWorkUsage> work) {
        return work.map(w->List.of(w.pairVisits(),w.transitions(),(long)w.rawAlternatives(),(long)w.distinctAlternatives())).orElse(null);
    }
    private static Optional<RefinementOperationReceipt> optionalReceipt(Object value) {
        if(value==null) return Optional.empty();
        var r=fields(value,"ticket","parentIdentity","originalNetworkHash","outcome","attempts","work","truncated");
        var t=fields(r.get("ticket"),"session","sequence","parent","choice","engine","mode","kind","smoothingInput");
        var ticket=new OperationTicket(session(t.get("session")),number(t.get("sequence")),stage(t.get("parent")),candidate(t.get("choice")),
            enumeration(TrackerMode.class,t.get("engine")),enumeration(AlignmentMode.class,t.get("mode")),
            enumeration(StageKind.class,t.get("kind")),smoothing(t.get("smoothingInput")));
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
