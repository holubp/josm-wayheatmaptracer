package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StravaCookieParserTest {
    @Test
    void parsesSemicolonSeparatedCookieHeader() {
        StravaCookieValues values = StravaCookieParser.parse(
            "CloudFront-Key-Pair-Id=key-123; "
                + "CloudFront-Policy=policy.abc; "
                + "CloudFront-Signature=sig~value__; "
                + "_strava_idcf=jwt.token.value"
        );

        assertEquals("key-123", values.keyPairId());
        assertEquals("policy.abc", values.policy());
        assertEquals("sig~value__", values.signature());
        assertEquals("jwt.token.value", values.sessionToken());
    }

    @Test
    void rejectsMissingRequiredCookie() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> StravaCookieParser.parse("CloudFront-Key-Pair-Id=key-123"));

        assertEquals("Cookie header is missing CloudFront-Policy.", ex.getMessage());
    }
}
