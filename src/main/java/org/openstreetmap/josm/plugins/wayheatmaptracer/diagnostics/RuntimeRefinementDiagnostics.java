package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.util.*;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Encodes factual retained review state; it never creates native inputs or replay capability. */
public final class RuntimeRefinementDiagnostics {
    private RuntimeRefinementDiagnostics() { }
    public record Applied(RefinementLineageCodec.CandidateKey selection,String identity,List<GeographicPoint> geometry) {
        public Applied {Objects.requireNonNull(selection);Objects.requireNonNull(identity);geometry=List.copyOf(geometry);if(geometry.size()<2)throw new IllegalArgumentException("Observed applied geometry unavailable");}
    }
    public static Format15Bundle capture(RefinementRootAuthority authority,List<Stage> history,Stage current,SessionId session,
            String initialChoice,Optional<RefinementAttemptDiagnostics> latest,Optional<RefinementLineageCodec.CandidateKey> operationResult,
            RefinementAttemptDiagnostics.Availability availability,String state,Optional<RefinementLineageCodec.CandidateKey> reviewed,
            Optional<Applied> applied,Optional<Format15Artifact> rootContext) {
        Objects.requireNonNull(authority);Objects.requireNonNull(availability);
        if(!Set.of("preview-open","review-confirmed","review-confirmation-failed","cancelled","applied","apply-failed").contains(state))throw new IllegalArgumentException("Fixed review state required");
        if(latest.isPresent()!=operationResult.isPresent())throw new IllegalArgumentException("Attempt publication selection is unavailable");
        var artifacts=new LinkedHashMap<String,Format15Artifact>();
        var geometry=new LinkedHashMap<RefinementLineageCodec.CandidateKey,Format15Artifact>();
        var original=Format15Artifact.text("original-osm-geographic.csv",originalGeometry(authority));artifacts.put(original.name(),original);
        for(int s=0;s<history.size();s++) {
            var stage=history.get(s);
            for(int c=0;c<stage.candidates().size();c++) {
                var candidate=stage.candidates().get(c);
                var artifact=Format15Artifact.text("geometry/stage-"+s+"-choice-"+c+".csv",projectedGeometry(authority.projectionCode(),candidate.payload().finalPreviewGeometry()));
                geometry.put(new RefinementLineageCodec.CandidateKey(stage.id(),candidate.id()),artifact);artifacts.put(artifact.name(),artifact);
                var nativeReview=candidate.payload().nativeReview();
                if(nativeReview.isPresent()) {
                    var review=nativeReview.get();
                    var quality=Format15Artifact.text("quality/stage-"+s+"-choice-"+c+".txt",
                        "planIdentity="+candidate.immutableIdentity()+"\ndisposition="+(review.plan()==null?"unavailable":review.plan().validation().disposition().name())
                        +"\nfindings="+(review.quality()==null?"unavailable":review.quality().findings())+"\n");
                    artifacts.put(quality.name(),quality);
                }
            }
        }
        var currentKey=new RefinementLineageCodec.CandidateKey(current.id(),current.selected());
        if(!history.contains(current)||!geometry.containsKey(currentKey))throw new IllegalArgumentException("Current retained selection differs");
        var originalRoot=history.get(0);
        if(originalRoot.candidates().stream().noneMatch(c->c.id().value().equals(initialChoice)))throw new IllegalArgumentException("Initial Legacy choice is unavailable");
        reviewed.ifPresent(key->{if(!geometry.containsKey(key))throw new IllegalArgumentException("Reviewed selection is no longer retained");});
        if(applied.isPresent()) {
            var actual=applied.get();var selected=current.candidates().stream().filter(c->c.id().equals(current.selected())).findFirst().orElseThrow();
            if(!state.equals("applied")||!actual.selection().equals(currentKey)||!actual.identity().equals(selected.immutableIdentity()))throw new IllegalArgumentException("Applied selection differs from exact stored preview");
            var artifact=Format15Artifact.text("applied-geographic.csv",geographicGeometry(actual.geometry()));artifacts.put(artifact.name(),artifact);
        } else if(state.equals("applied"))throw new IllegalArgumentException("Applied geometry was not observed");
        rootContext.ifPresent(a->{if(!a.name().equals("legacy-root-diagnostics.zip"))throw new IllegalArgumentException("Legacy context identity differs");artifacts.put(a.name(),a);});
        var currentGeometry=geometry.get(currentKey);
        String status="{\"state\":"+quote(state)+",\"mode\":"+quote(authority.request().geometryMode().name())
            +",\"initialLegacyChoice\":"+quote(initialChoice)+",\"currentStage\":"+quote(current.id().value().toString())
            +",\"currentChoice\":"+quote(current.selected().value())+",\"currentGeometry\":"+quote(currentGeometry.name())
            +",\"currentGeometrySha256\":"+quote(currentGeometry.sha256())+",\"latestAttemptAvailability\":"+quote(availability.name())
            +",\"reviewed\":"+reviewed.map(RuntimeRefinementDiagnostics::keyJson).orElse("null")
            +",\"applied\":"+applied.map(a->keyJson(a.selection())).orElse("null")
            +",\"appliedGeometryScope\":"+quote(applied.isPresent()?"observed-full-selected-way":"unavailable")
            +",\"legacyRootContext\":"+quote(rootContext.isPresent()?"available-root-only":"unavailable")
            +",\"nativeFrozenInputs\":\"unavailable\",\"chainReplay\":\"unavailable\"}\n";
        var statusArtifact=Format15Artifact.text("review-state.json",status);artifacts.put(statusArtifact.name(),statusArtifact);
        String source=sha256(authority.sourceIdentity());var settings=frozenSettings(authority);String parameters=settings.sha256();artifacts.put(settings.name(),settings);
        var base=new Format15Bundle("runtime-stepwise-refinement-v1",source,parameters,artifacts);
        var publication=latest.map(d->new RefinementLineageCodec.LatestPublication(d,operationResult.orElseThrow()));
        var document=RefinementLineageCodec.capture(base.buildIdentity(),source,parameters,authority.request().geometryMode(),session,
            authority.network().canonicalHash(),original,history,current.id(),latest.flatMap(RefinementAttemptDiagnostics::pipeline),geometry,publication);
        return RefinementLineageCodec.attach(base,document);
    }
    /** Opaque binding only: optional artifact privacy validation must never gate native refinement. */
    public static String frozenSettingsIdentity(RefinementRootAuthority authority){return sha256(frozenSettingsJson(authority));}
    /** Hashes only the factual frozen descriptor and complete captured redacted settings, never preferences. */
    public static Format15Artifact frozenSettings(RefinementRootAuthority authority) {
        String json=frozenSettingsJson(authority);String lower=json.toLowerCase(java.util.Locale.ROOT);
        if(java.util.regex.Pattern.compile("(?:^|[^a-z0-9_])(?:signature|policy|key-pair-id|x-amz-credential)=").matcher(lower).find())
            throw new IllegalArgumentException("Signed configuration text is not exportable");
        return Format15Artifact.text("frozen-root-settings.json",json);
    }
    private static String frozenSettingsJson(RefinementRootAuthority authority) {
        var config=authority.effectiveConfig();
        String json="{\"schema\":1,\"authorityRequestRole\":\"original-movement-authority-not-consumed-legacy-execution\",\"authorityParameterDescriptor\":"+quote(authority.request().parameterHash())
            +",\"authoritySamplerDescriptor\":"+quote(authority.request().samplerId())
            +",\"effectiveGeometryMode\":"+quote(authority.request().geometryMode().name())
            +",\"originalSettingsIdentity\":"+quote(authority.request().settingsHash())
            +",\"originalMovementAuthority\":"+quote(authority.request().permissions().canonicalIdentity())
            +",\"capturedHeatmap\":"+config.heatmap().toRedactedJson()+",\"effectiveHeatmap\":"+config.effectiveHeatmap().toRedactedJson()
            +",\"cleanup\":"+config.cleanup().toRedactedJson()+",\"searchHalfWidthMetersOverride\":"
            +(config.searchHalfWidthMetersOverride().isPresent()?Double.toString(config.searchHalfWidthMetersOverride().getAsDouble()):"null")+"}\n";
        return json;
    }
    private static String originalGeometry(RefinementRootAuthority authority) {
        var way=(DetachedWay)authority.network().primitives().get(authority.request().selectedWayKey());
        StringBuilder csv=new StringBuilder("wayNamespace,wayIdentity,occurrence,nodeIdentity,selected,latitudeDegrees,longitudeDegrees\n");
        for(int i=0;i<way.nodeKeys().size();i++) {
            var key=way.nodeKeys().get(i);var node=(DetachedNode)authority.network().primitives().get(key);var p=node.coordinate();
            csv.append(way.key().identityKind().name()).append(',').append(way.key().id()).append(',').append(i).append(',').append(key.id()).append(',').append(i>=authority.request().selectedRange().firstIndex()&&i<=authority.request().selectedRange().lastIndex())
                .append(',').append(p.latitudeDegrees()).append(',').append(p.longitudeDegrees()).append('\n');
        }
        return csv.toString();
    }
    private static String projectedGeometry(String projection,List<EastNorth> points) {
        StringBuilder csv=new StringBuilder("projectionCode,occurrence,east,north\n");
        for(int i=0;i<points.size();i++){var p=points.get(i);if(!Double.isFinite(p.east())||!Double.isFinite(p.north()))throw new IllegalArgumentException("Nonfinite retained geometry");csv.append(projection).append(',').append(i).append(',').append(p.east()).append(',').append(p.north()).append('\n');}
        return csv.toString();
    }
    private static String geographicGeometry(List<GeographicPoint> points) {
        StringBuilder csv=new StringBuilder("occurrence,latitudeDegrees,longitudeDegrees\n");
        for(int i=0;i<points.size();i++){var p=points.get(i);csv.append(i).append(',').append(p.latitudeDegrees()).append(',').append(p.longitudeDegrees()).append('\n');}
        return csv.toString();
    }
    static String sha256(String text) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable",unavailable);}
    }
    private static String keyJson(RefinementLineageCodec.CandidateKey key){return "{\"stage\":"+quote(key.stage().value().toString())+",\"choice\":"+quote(key.candidate().value())+"}";}
    private static String quote(String value) {
        StringBuilder out=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"'||c=='\\')out.append('\\');if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();
    }
}
