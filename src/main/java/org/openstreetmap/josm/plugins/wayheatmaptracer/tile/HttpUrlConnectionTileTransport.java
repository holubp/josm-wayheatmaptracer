package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Privacy-auditable bounded {@link HttpURLConnection} transport for managed tiles. */
public final class HttpUrlConnectionTileTransport implements ManagedTileTransport {
    private final ManagedTileUrlBuilder urlBuilder;
    private final TileReliabilityPolicy policy;

    /**
     * Creates the production transport with HTTPS-only URLs.
     *
     * @param policy transport limits and timeouts
     */
    public HttpUrlConnectionTileTransport(TileReliabilityPolicy policy) {
        this(new ManagedTileUrlBuilder(), policy);
    }

    HttpUrlConnectionTileTransport(ManagedTileUrlBuilder urlBuilder, TileReliabilityPolicy policy) {
        this.urlBuilder = urlBuilder;
        this.policy = policy;
    }

    @Override
    public TransportResponse execute(TileRequest request, CredentialSnapshot credentials) {
        long started = System.nanoTime();
        HttpURLConnection connection = null;
        boolean connected = false;
        try {
            if (request.cancellation().isCancelled()) {
                return response(TileFetchStatus.CANCELLED, -1, "", null, null, started, "cancelled");
            }
            URI uri = urlBuilder.build(request.address(), request.generation());
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(boundedTimeout(request, policy.connectTimeoutMillis()));
            connection.setReadTimeout(boundedTimeout(request, policy.readTimeoutMillis()));
            connection.setRequestProperty("Cookie", credentials.cookieHeader());
            connection.setRequestProperty("User-Agent", "JOSM WayHeatmapTracer");
            connection.connect();
            connected = true;
            if (request.cancellation().isCancelled()) {
                return response(TileFetchStatus.CANCELLED, -1, "", null, null, started, "cancelled");
            }
            connection.setReadTimeout(boundedTimeout(request, policy.readTimeoutMillis()));
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                return response(TileFetchStatus.UNSAFE_REDIRECT, status, connection.getContentType(), null,
                    null, started, "redirect-blocked");
            }
            if (status == 401 || status == 403) {
                return response(TileFetchStatus.AUTH_FAILURE, status, connection.getContentType(), null,
                    null, started, "http-auth");
            }
            if (status == 429) {
                Instant retry = parseRetryAfter(connection.getHeaderField("Retry-After"), Instant.now(), policy);
                return response(TileFetchStatus.RATE_LIMITED, status, connection.getContentType(), null,
                    retry, started, "http-rate-limit");
            }
            if (status == 204 || status == 404) {
                return response(TileFetchStatus.NO_TILE, status, connection.getContentType(), null,
                    null, started, "http-no-tile");
            }
            if (status >= 500) {
                return response(TileFetchStatus.HTTP_SERVER_ERROR, status, connection.getContentType(), null,
                    null, started, "http-server");
            }
            if (status < 200 || status >= 300) {
                return response(TileFetchStatus.HTTP_CLIENT_ERROR, status, connection.getContentType(), null,
                    null, started, "http-client");
            }
            long declared = connection.getContentLengthLong();
            if (declared > policy.maximumBodyBytes()) {
                return response(TileFetchStatus.BODY_TOO_LARGE, status, connection.getContentType(), null,
                    null, started, "body-too-large");
            }
            byte[] body;
            try (InputStream input = connection.getInputStream()) {
                body = readBounded(input, policy.maximumBodyBytes());
            }
            if (body == null) {
                return response(TileFetchStatus.BODY_TOO_LARGE, status, connection.getContentType(), null,
                    null, started, "body-too-large");
            }
            return response(TileFetchStatus.SUCCESS_NETWORK, status, connection.getContentType(), body,
                null, started, "");
        } catch (SocketTimeoutException ex) {
            return response(connected ? TileFetchStatus.READ_TIMEOUT : TileFetchStatus.CONNECT_TIMEOUT,
                -1, "", null, null, started, connected ? "read-timeout" : "connect-timeout");
        } catch (IOException | RuntimeException ex) {
            return response(TileFetchStatus.NETWORK_ERROR, -1, "", null, null, started, "network");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static Instant parseRetryAfter(String value, Instant now, TileReliabilityPolicy policy) {
        Instant fallback = now.plus(policy.rateLimitFallback());
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Instant parsed;
        try {
            long seconds = Long.parseLong(value.trim());
            parsed = now.plusSeconds(Math.max(0L, seconds));
        } catch (NumberFormatException ex) {
            try {
                parsed = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException invalid) {
                return fallback;
            }
        }
        Instant maximum = now.plus(policy.rateLimitMaximum());
        return parsed.isAfter(maximum) ? maximum : parsed;
    }

    private int boundedTimeout(TileRequest request, int configuredMillis) {
        long remaining = Duration.between(Instant.now(), request.deadline()).toMillis();
        return (int) Math.max(1L, Math.min(configuredMillis, remaining));
    }

    private byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) {
                return null;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private TransportResponse response(TileFetchStatus status, int httpStatus, String contentType, byte[] body,
        Instant retry, long started, String reason) {
        return new TransportResponse(status, httpStatus, contentType, body, retry,
            Duration.ofNanos(System.nanoTime() - started), reason);
    }
}
