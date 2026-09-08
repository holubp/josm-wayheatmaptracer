package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

/** Checked failure for malformed, unsafe, oversized, or inconsistent diagnostic archives. */
public class Format15ArchiveException extends java.io.IOException {
    private static final long serialVersionUID = 1L;
    /** Creates an archive error with a safe diagnostic message. */
    public Format15ArchiveException(String message) {
        super(message);
    }

    /** Creates an archive error without retaining arbitrary remote exception text. */
    public Format15ArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
