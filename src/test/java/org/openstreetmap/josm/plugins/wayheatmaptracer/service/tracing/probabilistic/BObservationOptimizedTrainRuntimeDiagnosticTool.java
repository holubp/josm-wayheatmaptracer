package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.Path;
import java.util.*;

/** Explicit optimized diagnostic launch; independent pins and approval are both required. */
public final class BObservationOptimizedTrainRuntimeDiagnosticTool {
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Use --output NEWDIR --pins-sha256 EXTERNAL_LITERAL");
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) {
            if(!Set.of("--output","--pins-sha256").contains(args[i])||args[i+1].isBlank()
                    ||options.put(args[i],args[i+1])!=null)throw new IllegalArgumentException("Fixed diagnostic arguments required");
        }
        BObservationIdentificationTool.runOptimizedTrainRuntimeDiagnostic(Path.of(options.get("--output")),options.get("--pins-sha256"));
    }
    private BObservationOptimizedTrainRuntimeDiagnosticTool() { }
}
