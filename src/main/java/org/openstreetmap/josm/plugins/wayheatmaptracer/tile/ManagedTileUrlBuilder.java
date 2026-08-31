package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** Builds generation-consistent managed tile URLs without accepting unsafe path input. */
public final class ManagedTileUrlBuilder {
    private static final URI DEFAULT_ORIGIN = URI.create("https://content-a.strava.com");
    private final URI origin;
    private final boolean allowLoopbackHttp;

    /** Creates the production HTTPS Strava URL builder. */
    public ManagedTileUrlBuilder() {
        this(DEFAULT_ORIGIN, false);
    }

    ManagedTileUrlBuilder(URI origin, boolean allowLoopbackHttp) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.allowLoopbackHttp = allowLoopbackHttp;
        validateOrigin(origin, allowLoopbackHttp);
    }

    /**
     * Builds a credential-free URL with a numeric cache generation query.
     *
     * @param address validated tile address
     * @param generation numeric cache generation
     * @return safe request URI
     */
    public URI build(ManagedTileAddress address, ManagedTileGeneration generation) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(generation, "generation");
        String path = "/identified/globalheat/" + address.activity() + '/' + address.color() + '/'
            + address.zoom() + '/' + address.x() + '/' + address.y() + ".png";
        try {
            return new URI(origin.getScheme(), null, origin.getHost(), origin.getPort(), path,
                "whtr-cache=" + generation.value(), null);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Managed tile URL could not be constructed safely");
        }
    }

    /**
     * Builds the JOSM TMS template with the same safe generation query policy.
     *
     * @param activity managed activity
     * @param color managed palette
     * @param generation numeric cache generation
     * @return JOSM TMS template
     */
    public String buildJosmTemplate(String activity, String color, ManagedTileGeneration generation) {
        ManagedTileAddress validated = new ManagedTileAddress(activity, color, 0, 0, 0);
        return "tms[15]:https://content-a.strava.com/identified/globalheat/" + validated.activity() + '/'
            + validated.color() + "/{zoom}/{x}/{y}.png?whtr-cache=" + generation.value();
    }

    private static void validateOrigin(URI origin, boolean allowLoopbackHttp) {
        boolean https = "https".equalsIgnoreCase(origin.getScheme());
        boolean loopback = allowLoopbackHttp && "http".equalsIgnoreCase(origin.getScheme())
            && ("127.0.0.1".equals(origin.getHost()) || "localhost".equalsIgnoreCase(origin.getHost()));
        if ((!https && !loopback) || origin.getHost() == null || origin.getUserInfo() != null
            || origin.getQuery() != null || origin.getFragment() != null) {
            throw new IllegalArgumentException("Managed tile origin must be safe HTTPS");
        }
    }
}
