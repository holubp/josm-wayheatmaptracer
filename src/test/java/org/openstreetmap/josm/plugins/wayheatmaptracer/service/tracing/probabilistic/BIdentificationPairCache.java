package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import java.nio.charset.StandardCharsets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationAcceptance.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Detached typed pair summaries only; cached values never feed a model or target scorer. */
final class BIdentificationPairCache {
    interface Publisher {void publish(String name,Map<String,Object> fields) throws Exception;}
    record Chunk(String file,String sha256) { }
    record Handle(CaseDefinition definition,SourceKey source,String factsHash,String modelHash,List<Chunk> chunks) {
        Handle {chunks=List.copyOf(chunks);}
    }
    record Index(CaseDefinition definition,SourceKey source,String factsHash,String modelHash,String file,String sha256,int profiles) { }
    static Index index(String prefix,Handle handle,Publisher publisher,CancellationProbe cancellation) throws Exception {
        cancellation.checkpoint();String file=prefix+"-index.json";
        var fields=Map.<String,Object>of("chunks",handle.chunks().stream().map(c->Map.of("file",c.file(),"sha256",c.sha256())).toList());
        byte[] bytes=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>65_536)throw new IllegalArgumentException("Pair index bound");
        publisher.publish(file,fields);return new Index(handle.definition(),handle.source(),handle.factsHash(),handle.modelHash(),file,BIdentificationReceipt.hash(bytes),handle.chunks().size());
    }
    static Handle readIndex(Path directory,Index index,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) throws Exception {
        return readIndex(directory,index,cancellation,owner,()->{});
    }
    static Handle readIndex(Path directory,Index index,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner,Runnable afterHash) throws Exception {
        cancellation.checkpoint();
        if(owner==null||!index.file().matches("[a-zA-Z0-9-]{1,80}-index\\.json")||!sha(index.sha256())||index.profiles()<1||index.profiles()>4096)
            throw new IllegalArgumentException("Bounded owned pair index required");
        try(var transaction=owner.child("pair-index-read");var scratch=transaction.child("pair-index-json")) {
            scratch.reserve(2L*1024*1024);var file=directory.resolve(index.file());
            byte[] bytes=BIdentificationReceipt.readVerifiedBytes(file,index.sha256(),cancellation,afterHash);
            try(var payload=transaction.reserve(8192L+16L*bytes.length)) {
            var fields=BIdentificationReceipt.parse(bytes);if(!fields.keySet().equals(java.util.Set.of("chunks")))throw new IllegalArgumentException("Invalid pair index");
            var raw=list(fields.get("chunks"));if(raw.size()!=index.profiles())throw new IllegalArgumentException("Pair index count changed");
            var chunks=new ArrayList<Chunk>();
            for(var item:raw){cancellation.checkpoint();var m=map(item);if(!m.keySet().equals(java.util.Set.of("file","sha256"))
                    ||!(m.get("file") instanceof String name)||!(m.get("sha256") instanceof String hash))throw new IllegalArgumentException("Invalid pair index member");chunks.add(new Chunk(name,hash));}
            var handle=new Handle(index.definition(),index.source(),index.factsHash(),index.modelHash(),chunks);
            payload.adopt(handle);cancellation.checkpoint();scratch.close();transaction.transferTo(owner);return handle;
            }
        }
    }
    static Handle write(Path directory,String prefix,SceneSummary summary,String factsHash,String modelHash,
            Publisher publisher,CancellationProbe cancellation) throws Exception {
        cancellation.checkpoint();
        if(directory==null||prefix==null||!prefix.matches("[a-zA-Z0-9-]{1,80}")||summary==null
                ||summary.profiles().isEmpty()||summary.profiles().size()>4096||publisher==null
                ||!sha(factsHash)||!sha(modelHash))throw new IllegalArgumentException("Bounded typed pair-cache inputs required");
        var chunks=new ArrayList<Chunk>();
        // Caller owns >=1MiB scratch before entering: one <=64KiB encoded profile plus its map/string copies.
        for(int i=0;i<summary.profiles().size();i++) {
            cancellation.checkpoint();var profile=summary.profiles().get(i);long bound=2048;
            if(profile.modes().size()>96)throw new IllegalArgumentException("Pair cache mode bound");
            for(var mode:profile.modes()) {
                cancellation.checkpoint();bound=Math.addExact(bound,768L+12L*mode.lineage().length());
            }
            if(bound>65_000)throw new IllegalArgumentException("Pair cache profile encoding bound");
            String name=prefix+"-p%04d.json".formatted(i);
            var fields=Map.<String,Object>of("schema",1,"case",summary.definition().key().id(),"source",source(summary.source()),
                    "factsHash",factsHash,"modelHash",modelHash,"index",i,"profile",profile(profile,cancellation));
            byte[] encoded=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
            if(encoded.length>65_536)throw new IllegalArgumentException("Pair cache chunk bound");
            cancellation.checkpoint();publisher.publish(name,fields);chunks.add(new Chunk(name,BIdentificationReceipt.hash(encoded)));
        }
        cancellation.checkpoint();return new Handle(summary.definition(),summary.source(),factsHash,modelHash,chunks);
    }
    static SceneSummary read(Path directory,Handle handle,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) throws Exception {
        return read(directory,handle,cancellation,owner,()->{});
    }
    static SceneSummary read(Path directory,Handle handle,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner,Runnable afterHash) throws Exception {
        cancellation.checkpoint();
        if(directory==null||handle==null||owner==null||handle.chunks().isEmpty()||handle.chunks().size()>4096
                ||!sha(handle.factsHash())||!sha(handle.modelHash()))throw new IllegalArgumentException("Owned bounded pair-cache read required");
        try(var transaction=owner.child("pair-cache-read");var scratch=transaction.child("json-scratch")) {
            // Before ANY file bytes, decoded strings, JSON parse graph or simultaneous immutable copy.
            scratch.reserve(2L*1024*1024);
            try(var wrapper=transaction.reserve(4096L+32L*handle.chunks().size())) {
            var profiles=new ArrayList<ProfileSummary>();int index=0;
            for(var chunk:handle.chunks()) {
                cancellation.checkpoint();
                if(!chunk.file().matches("[a-zA-Z0-9-]{1,80}-p[0-9]{4}\\.json")||!sha(chunk.sha256()))
                    throw new IllegalArgumentException("Unsafe pair cache member");
                Path file=directory.resolve(chunk.file());
                byte[] bytes=BIdentificationReceipt.readVerifiedBytes(file,chunk.sha256(),cancellation,afterHash);
                long retained=Math.addExact(8192L,16L*bytes.length);
                try(var payload=transaction.reserve(retained)) {
                    var fields=BIdentificationReceipt.parse(bytes);cancellation.checkpoint();
                    if(!fields.keySet().equals(java.util.Set.of("schema","case","source","factsHash","modelHash","index","profile"))
                            ||number(fields.get("schema"))!=1||number(fields.get("index"))!=index++
                            ||!handle.definition().key().id().equals(fields.get("case"))
                            ||!handle.factsHash().equals(fields.get("factsHash"))||!handle.modelHash().equals(fields.get("modelHash"))
                            ||!BIdentificationReceipt.canonical(source(handle.source())).equals(BIdentificationReceipt.canonical(fields.get("source"))))
                        throw new IllegalArgumentException("Pair cache binding changed");
                    var profile=decode(map(fields.get("profile")),cancellation);payload.adopt(profile);profiles.add(profile);
                }
            }
            var summary=new SceneSummary(handle.definition(),handle.source(),profiles);cancellation.checkpoint();
            wrapper.adopt(summary);scratch.close();transaction.transferTo(owner);return summary;
            }
        }
    }
    private static boolean sha(String text){return text!=null&&text.matches("[0-9a-f]{64}");}
    private static Map<String,Object> source(SourceKey key) {
        return Map.of("group",key.sourceGroup(),"nativeHash",key.nativeHash(),"frame",key.nativeFrame(),"capability",key.capability().name());
    }
    private static Map<String,Object> profile(ProfileSummary p,CancellationProbe cancellation) {
        var modes=new ArrayList<Map<String,Object>>();
        for(var mode:p.modes()) {cancellation.checkpoint();modes.add(Map.of("lineage",mode.lineage(),"core",interval(mode.core()),
                "shoulder",interval(mode.shoulder()),"mass",mode.mass(),"rho",mode.rhoMeters().isPresent()?mode.rhoMeters().getAsDouble():"UNAVAILABLE",
                "quantiles",mode.quantiles().isPresent()?List.of(mode.quantiles().get().q25Meters(),mode.quantiles().get().medianMeters(),mode.quantiles().get().q75Meters()):List.of()));}
        return Map.of("chainage",p.chainageMeters(),"anchor",List.of(p.anchor().xMeters(),p.anchor().yMeters()),
                "normal",List.of(p.canonicalNormal().xMeters(),p.canonicalNormal().yMeters()),"exactAnchor",p.exactAnchor(),"modes",modes);
    }
    private static List<Double> interval(Optional<Interval> interval) {
        return interval.isEmpty()?List.of():List.of(interval.get().minimumMeters(),interval.get().maximumMeters());
    }
    private static ProfileSummary decode(Map<?,?> p,CancellationProbe cancellation) {
        if(!p.keySet().equals(java.util.Set.of("chainage","anchor","normal","exactAnchor","modes"))||!(p.get("exactAnchor") instanceof Boolean))
            throw new IllegalArgumentException("Invalid cached profile");
        var raw=list(p.get("modes"));if(raw.size()>96)throw new IllegalArgumentException("Cached mode bound");
        var modes=new ArrayList<ModeSummary>();
        for(var item:raw) {
            cancellation.checkpoint();var m=map(item);
            if(!m.keySet().equals(java.util.Set.of("lineage","core","shoulder","mass","rho","quantiles"))||!(m.get("lineage") instanceof String lineage))
                throw new IllegalArgumentException("Invalid cached mode");
            var q=list(m.get("quantiles"));if(q.size()!=0&&q.size()!=3)throw new IllegalArgumentException("Invalid cached quantiles");
            Object rho=m.get("rho");OptionalDouble resolution;
            if(rho instanceof Number)resolution=OptionalDouble.of(number(rho));
            else if("UNAVAILABLE".equals(rho))resolution=OptionalDouble.empty();else throw new IllegalArgumentException("Invalid cached resolution");
            modes.add(new ModeSummary(lineage,decodeInterval(m.get("core")),decodeInterval(m.get("shoulder")),number(m.get("mass")),resolution,
                    q.isEmpty()?Optional.empty():Optional.of(new Quantiles(number(q.get(0)),number(q.get(1)),number(q.get(2))))));
        }
        return new ProfileSummary(number(p.get("chainage")),point(p.get("anchor")),point(p.get("normal")),(Boolean)p.get("exactAnchor"),modes);
    }
    private static Optional<Interval> decodeInterval(Object value) {
        var values=list(value);if(values.isEmpty())return Optional.empty();if(values.size()!=2)throw new IllegalArgumentException("Invalid cached interval");
        return Optional.of(new Interval(number(values.get(0)),number(values.get(1))));
    }
    private static MetricPoint point(Object value) {var values=list(value);if(values.size()!=2)throw new IllegalArgumentException("Invalid cached point");return new MetricPoint(number(values.get(0)),number(values.get(1)));}
    private static double number(Object value) {if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Finite cached number required");return n.doubleValue();}
    private static List<?> list(Object value) {if(!(value instanceof List<?> list))throw new IllegalArgumentException("Cached list required");return list;}
    private static Map<?,?> map(Object value) {if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Cached object required");return map;}
    private BIdentificationPairCache() { }
}
