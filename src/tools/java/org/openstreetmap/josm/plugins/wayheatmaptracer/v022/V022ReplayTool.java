package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ProductionReplayCommand;

/** Offline-only entry point for strict bounded v0.22 production replay. */
public final class V022ReplayTool {
    private V022ReplayTool() {
    }

    /** Runs the production replay command and preserves its strict exit status. */
    public static void main(String[] arguments) throws Exception {
        int status = ProductionReplayCommand.run(arguments);
        if (status != 0) {
            System.exit(status);
        }
    }
}
