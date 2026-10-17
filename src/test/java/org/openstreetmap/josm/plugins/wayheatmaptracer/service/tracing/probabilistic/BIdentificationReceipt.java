package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ObservationManifestJson;

/** Bounded canonical receipts for the explicit offline experiment, never model inputs. */
final class BIdentificationReceipt {
    static final String PACKAGE = "org/openstreetmap/josm/plugins/wayheatmaptracer/";
    static final List<String> SOURCE_FILES = List.of(
            "service/evidence/LocalScalarProfileExtractor.java",
            "service/tracing/probabilistic/ProbabilisticObservationModel.java",
            "service/tracing/probabilistic/ProbabilisticProfileFactory.java",
            "service/tracing/probabilistic/ProbabilisticStateBuilder.java",
            "service/tracing/probabilistic/ProbabilisticLatticeAdmission.java",
            "service/tracing/probabilistic/ProbabilisticContinuationProposals.java",
            "service/tracing/probabilistic/ProbabilisticInference.java",
            "service/tracing/probabilistic/InferenceProfile.java",
            "service/tracing/probabilistic/ProbabilisticTraceEngine.java",
            "service/tracing/probabilistic/EvidenceModelParameters.java",
            "service/tracing/probabilistic/BIdentificationFacts.java",
            "service/tracing/probabilistic/BIdentificationFactsExtractor.java",
            "service/tracing/probabilistic/BObservationKernel.java",
            "service/tracing/probabilistic/BIdentifiedObservationKernel.java",
            "service/tracing/probabilistic/BObservationMixture.java",
            "service/tracing/probabilistic/BObservationAdapter.java",
            "service/tracing/probabilistic/BObservationGeometry.java",
            "service/tracing/probabilistic/BIdentificationCatalog.java",
            "service/tracing/probabilistic/BIdentificationScenes.java",
            "service/tracing/probabilistic/BIdentificationScore.java",
            "service/tracing/probabilistic/BIdentificationAcceptance.java",
            "service/tracing/probabilistic/BIdentificationPairCache.java",
            "service/tracing/probabilistic/BIdentificationManifest.java",
            "service/tracing/probabilistic/BIdentificationReceipt.java",
            "service/tracing/probabilistic/BObservationIdentificationTool.java");

    static String sourceHash() throws Exception {
        Map<String,Object> inventory = new TreeMap<>();
        for(String relative:SOURCE_FILES) {
            inventory.put(relative,sourceMemberHash(relative));
        }
        return hash(canonical(inventory).getBytes(StandardCharsets.UTF_8));
    }
    static String sourceMemberHash(String relative) throws Exception {
        if(!SOURCE_FILES.contains(relative))throw new IllegalArgumentException("Unknown source inventory member");
        Path main=Path.of("src/main/java",PACKAGE+relative);
        Path test=Path.of("src/test/java",PACKAGE+relative);
        if(Files.exists(main)&&Files.exists(test))throw new IllegalArgumentException("Duplicate production/test implementation: "+relative);
        Path source=Files.exists(main)?main:test;
        if(Files.isSymbolicLink(source)||Files.size(source)>1_048_576)throw new IllegalArgumentException("Unsafe source inventory member");
        return hash(Files.readAllBytes(source));
    }

    /** Streaming fingerprint of actual frozen observations and admitted cells, not model scores. */
    static String factsHash(List<BIdentificationFacts.ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe cancellation) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))) {
            out.writeUTF(BIdentificationFacts.VERSION);out.writeInt(facts.size());
            for(int i=0;i<facts.size();i++) {
                cancellation.checkpoint();var profile=facts.get(i).baseline();
                out.writeUTF(profile.toString());
                for(var mode:facts.get(i).modes()) {
                    cancellation.checkpoint();out.writeUTF(mode.lineage());
                    for(var fact:mode.values()) {
                        cancellation.checkpoint();out.writeUTF(fact.feature().name());out.writeUTF(fact.unit().name());
                        out.writeUTF(fact.availability().name());out.writeBoolean(fact.value().isPresent());
                        if(fact.value().isPresent())out.writeDouble(fact.value().getAsDouble());
                    }
                    for(var receipt:mode.provenance()) {cancellation.checkpoint();out.writeUTF(receipt.toString());}
                }
                for(var cell:lattices.get(i).cells()) {cancellation.checkpoint();out.writeUTF(cell.toString());}
                for(var component:lattices.get(i).components()) {cancellation.checkpoint();out.writeUTF(component.toString());}
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static String canonical(Object value) {
        StringBuilder result=new StringBuilder();append(result,value,0);
        if(result.length()>65_536)throw new IllegalArgumentException("Receipt size limit");
        return result.toString();
    }
    private static void append(StringBuilder out,Object value,int depth) {
        if(depth>16)throw new IllegalArgumentException("Receipt nesting limit");
        if(value instanceof Map<?,?> map) {
            out.append('{');boolean comma=false;
            for(String key:map.keySet().stream().map(k->{if(!(k instanceof String s))throw new IllegalArgumentException("String keys required");return s;}).sorted().toList()) {
                if(comma)out.append(',');comma=true;append(out,key,depth+1);out.append(':');append(out,map.get(key),depth+1);
            }out.append('}');
        } else if(value instanceof Collection<?> list) {
            out.append('[');boolean comma=false;
            for(Object item:list){if(comma)out.append(',');comma=true;append(out,item,depth+1);}out.append(']');
        } else if(value instanceof String text) {
            out.append('"');
            for(int i=0;i<text.length();i++) {char c=text.charAt(i);
                if(c=='"'||c=='\\')out.append('\\').append(c);
                else if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
            }out.append('"');
        } else if(value instanceof Number number) {
            if(!Double.isFinite(number.doubleValue()))throw new IllegalArgumentException("Nonfinite receipt number");out.append(number);
        } else if(value instanceof Boolean)out.append(value);
        else if(value==null)out.append("null");
        else throw new IllegalArgumentException("Unsupported receipt value");
    }
    static Map<String,Object> read(Path file) throws Exception {
        return parse(boundedBytes(file));
    }
    static Map<String,Object> parse(byte[] bytes) throws Exception {
        if(bytes==null||bytes.length>65_536)throw new IllegalArgumentException("Receipt byte limit");
        return ObservationManifestJson.parse(bytes);
    }
    static byte[] readVerifiedBytes(Path file,String expectedHash,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe cancellation,Runnable afterHash) throws Exception {
        if(expectedHash==null||!expectedHash.matches("[0-9a-f]{64}")||cancellation==null||afterHash==null)
            throw new IllegalArgumentException("Verified receipt controls required");
        cancellation.checkpoint();byte[] bytes=boundedBytes(file);cancellation.checkpoint();
        if(!hash(bytes).equals(expectedHash))throw new IllegalArgumentException("Verified receipt bytes changed");
        afterHash.run();cancellation.checkpoint();return bytes;
    }
    private static byte[] boundedBytes(Path file) throws Exception {
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(65_537);
            if(bytes.length>65_536)throw new IllegalArgumentException("Receipt byte limit");
            return bytes;
        }
    }
    /** New-file atomic publication; interrupted work cannot resemble a selected candidate. */
    static void publish(Path destination,Map<String,Object> fields) throws Exception {
        byte[] bytes=(canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>65_536)throw new IllegalArgumentException("Receipt byte limit");
        Files.createDirectories(destination.toAbsolutePath().getParent());
        if(Files.exists(destination))throw new FileAlreadyExistsException(destination.toString());
        Path temporary=destination.resolveSibling(destination.getFileName()+".partial");
        Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW);
        try {Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);}
        finally {Files.deleteIfExists(temporary);}
    }
    private BIdentificationReceipt() { }
}
