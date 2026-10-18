package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.Path;
import java.util.*;

/** Separate fixed-path prospective protocol; never aliases old registration or HOLDOUT. */
public final class BProspectiveObservationIdentificationTool {
    public static void main(String[] args) throws Exception {
        var options=new HashMap<String,String>();
        if(args.length!=6&&args.length!=8)throw new IllegalArgumentException("Explicit prospective launch arguments required");
        for(int i=0;i<args.length;i+=2) {
            if(!Set.of("--phase","--output","--binding","--pins-sha256").contains(args[i])||args[i+1].isBlank()
                    ||options.put(args[i],args[i+1])!=null)throw new IllegalArgumentException("Fixed prospective arguments required");
        }
        if(!options.keySet().containsAll(Set.of("--phase","--output","--pins-sha256")))
            throw new IllegalArgumentException("Independent pins, phase and output required");
        var phase=BIdentificationCatalog.Phase.valueOf(options.get("--phase"));
        if(phase==BIdentificationCatalog.Phase.HOLDOUT)throw new SecurityException("No prospective HOLDOUT authority");
        if((phase==BIdentificationCatalog.Phase.CATALOG)==options.containsKey("--binding"))
            throw new IllegalArgumentException("Only EXTRACT/FIT_SELECT consumes a CATALOG binding");
        BObservationIdentificationTool.runProspective(phase,Path.of(options.get("--output")),
                options.containsKey("--binding")?Path.of(options.get("--binding")):null,
                BObservationIdentificationTool.Controls.defaults(),options.get("--pins-sha256"));
    }
    private BProspectiveObservationIdentificationTool() { }
}
