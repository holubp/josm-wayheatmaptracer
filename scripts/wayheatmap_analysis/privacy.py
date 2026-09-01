"""Privacy-safe labels and findings for private calibration inventories."""

from __future__ import annotations

import hashlib
import re


_RULES = {
    "AUTHORIZATION": re.compile(r"\bAuthorization\b|\bBearer\s+", re.I),
    "COOKIE": re.compile(r"\bCookie\b", re.I),
    "CLOUDFRONT": re.compile(r"CloudFront-(?:Key-Pair-Id|Policy|Signature)", re.I),
    "SIGNED_URL": re.compile(r"X-Amz-(?:Credential|Signature)|signed_?url", re.I),
    "STRAVA_IDCF": re.compile(r"_strava_idcf", re.I),
    "TOKEN": re.compile(r"(?:access|refresh)_token", re.I),
}
_COORDINATE = re.compile(r"(?<![A-Za-z0-9])[-+]?\d{1,3}\.\d{4,}(?![A-Za-z0-9])")
_SAFE_SCALAR = re.compile(r"^[A-Za-z0-9._/#:+-]{0,160}$")


def findings(
    value: str,
    *,
    identity: str = "",
    include_coordinates: bool = False,
) -> list[dict[str, object]]:
    """Return deterministic secret findings, optionally including label coordinates."""

    member_hash = hashlib.sha256((identity or value).encode("utf-8", "replace")).hexdigest()
    result = []
    for rule, pattern in sorted(_RULES.items()):
        count = len(pattern.findall(value))
        if count:
            result.append({"rule": rule, "severity": "high", "memberHash": member_hash,
                           "count": count})
    if include_coordinates and _COORDINATE.search(value):
        result.append({"rule": "COORDINATE", "severity": "high", "memberHash": member_hash,
                       "count": len(_COORDINATE.findall(value))})
    return result


def safe_label(value: str, digest: str) -> str:
    """Return a relative label or a stable hash marker when its text is sensitive."""

    if findings(value, include_coordinates=True):
        return f"<redacted-{digest[:12]}>"
    return value.replace("\\", "/")


def safe_scalar(value: object) -> str:
    """Keep only short identifier-like metadata that contains no secret markers."""

    text = "" if value is None else str(value)
    if not _SAFE_SCALAR.fullmatch(text) or findings(text):
        return ""
    return text
