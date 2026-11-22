package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.function.LongSupplier;

/** Small source authority: expensive preparation precedes the dataset lock. */
public interface PreparedCommandSource extends LongSupplier {
    default Runnable prepareExecution(boolean redo){getAsLong();return ()->getAsLong();}
    default void withSource(Runnable transaction){getAsLong();transaction.run();}
}
