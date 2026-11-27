package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;

/** Pure purpose-specific source policy. Planning never changes settings or original edit authority. */
public final class RefinementSourcePlanner {
    public static final List<String> BASE_PALETTES=List.of("hot","blue","bluered","purple","gray");
    private RefinementSourcePlanner() { }
    /** Detached credential-free source choices; no live layer/config references. */
    public record Settings(boolean managed,String activity,String selectedPalette,int inferenceZoom,int validationZoom,
            ManagedTileGeneration generation,IntensitySamplingMode intensity,List<String> alternatives,boolean aggregate) {
        public Settings {
            Objects.requireNonNull(generation);Objects.requireNonNull(intensity);Objects.requireNonNull(alternatives);
            var known=org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService.alternativeDetectorMappings();
            if(alternatives.size()>known.size()||!known.containsAll(alternatives)||new HashSet<>(alternatives).size()!=alternatives.size())
                throw new IllegalArgumentException("Unknown or duplicate refinement detector mapping");
            alternatives=List.copyOf(alternatives);
            if(activity==null||activity.isBlank()||!BASE_PALETTES.contains(selectedPalette)
                    ||inferenceZoom<10||inferenceZoom>16||validationZoom<10||validationZoom>inferenceZoom)
                throw new IllegalArgumentException("Invalid frozen refinement source policy");
        }
        /** Validates against the immutable root source policy before any new acquisition. */
        public void requireRootPolicy(RefinementRootAuthority root) {
            var config=root.effectiveConfig().heatmap();
            var frozen=org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentTileSourcePlan.from(config);
            var mode=config.intensitySamplingMode()==null?IntensitySamplingMode.COLOR_MAPPING:config.intensitySamplingMode();
            if(!activity.equals(config.activity())||!selectedPalette.equals(frozen.selectedColor())
                    ||!generation.equals(new ManagedTileGeneration(Math.max(0L,config.cacheBuster())))||intensity!=mode
                    ||inferenceZoom!=frozen.requiredZooms().stream().mapToInt(Integer::intValue).max().orElseThrow()
                    ||validationZoom!=frozen.requiredZooms().stream().mapToInt(Integer::intValue).min().orElseThrow()
                    ||aggregate!=config.aggregateAllColorSchemes()||!config.multiColorDetection()&&!alternatives.isEmpty())
                throw new IllegalArgumentException("Refinement source policy differs from frozen root settings");
        }
        /** Capture layer/config policy on the EDT before off-thread transport and pixel copies. */
        public static Settings capture(boolean managed,ManagedHeatmapConfig config,List<String> alternatives) {
            if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Source policy capture requires EDT");
            var ordinary=org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentTileSourcePlan.from(config);
            int inference=ordinary.requiredZooms().stream().mapToInt(Integer::intValue).max().orElseThrow();
            int validation=ordinary.requiredZooms().stream().mapToInt(Integer::intValue).min().orElseThrow();
            return new Settings(managed,config.activity(),ordinary.selectedColor(),inference,validation,
                new ManagedTileGeneration(Math.max(0L,config.cacheBuster())),config.intensitySamplingMode()==null?IntensitySamplingMode.COLOR_MAPPING:config.intensitySamplingMode(),
                config.multiColorDetection()?alternatives:List.of(),config.aggregateAllColorSchemes());
        }
    }
    public record SourceRequest(String palette,String mapping,boolean aggregate,String unavailableReason) {
        public SourceRequest {Objects.requireNonNull(palette);Objects.requireNonNull(mapping);Objects.requireNonNull(unavailableReason);}
    }
    public record Plan(OperationTicket ticket,Settings settings,ParentTraceReference parent,List<String> acquiredPalettes,
            List<SourceRequest> requests,boolean aggregateRequested) {
        public Plan {acquiredPalettes=List.copyOf(acquiredPalettes);requests=List.copyOf(requests);}
        public String operationIdentity(){return ticket.session().value()+":"+ticket.sequence();}
    }
    public static Plan plan(OperationTicket ticket,Settings settings,ParentTraceReference parent) {
        Objects.requireNonNull(ticket);Objects.requireNonNull(settings);Objects.requireNonNull(parent);
        if(!ticket.parent().equals(parent.parentStage())||!ticket.choice().equals(parent.parentChoice())
                ||ticket.mode()!=parent.root().request().geometryMode())throw new IllegalArgumentException("Source plan must pin exact selected parent");
        var selected=parent.selectedSource();
        if(!BASE_PALETTES.contains(selected.palette()))throw new IllegalArgumentException("Selected detector palette is unavailable");
        String unavailable="";
        if(ticket.kind()==StageKind.SMOOTH)unavailable="existing-parent-evidence-required";
        else if(selected.aggregate()&&!settings.intensity().usesColorMapping())unavailable="direct-aggregate-unavailable";
        else if(selected.aggregate()&&!settings.managed())unavailable="visible-aggregate-unavailable";
        else if(!settings.managed()&&!settings.intensity().usesColorMapping())unavailable="visible-direct-intensity-unsupported";
        else if(!settings.managed()&&!selected.palette().equals(settings.selectedPalette()))unavailable="visible-palette-unavailable";
        var request=new SourceRequest(selected.palette(),selected.mapping(),selected.aggregate(),unavailable);
        var acquired=new LinkedHashSet<String>();
        if(unavailable.isEmpty()&&settings.managed()) {
            acquired.add(selected.palette());
            if(selected.aggregate())acquired.addAll(BASE_PALETTES);
        }
        return new Plan(ticket,settings,parent,List.copyOf(acquired),List.of(request),selected.aggregate()&&unavailable.isEmpty());
    }
}
