"""Bounded network-free parsing and non-sensitive signatures for OSM XML."""

from __future__ import annotations

import gzip
import hashlib
import io
import math
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass


@dataclass(frozen=True)
class OsmLimits:
    """Resource limits for one plain or gzip-compressed OSM document."""

    max_compressed_bytes: int = 64 * 1024**2
    max_uncompressed_bytes: int = 256 * 1024**2
    max_compression_ratio: float = 200.0
    max_elements: int = 2_000_000
    max_depth: int = 64
    max_attributes_per_element: int = 64
    max_attribute_bytes: int = 16 * 1024
    max_text_bytes: int = 1024 * 1024


class OsmError(ValueError):
    """Typed parse rejection whose message contains no source XML."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class WaySignature:
    """Coordinate-free identity and coarse geometry metadata for one OSM way."""

    way_id: int
    node_count: int
    length_bucket_meters: int
    endpoint_degree_class: str
    endpoint_tag_presence: str
    protected_node_id_hash: str
    geometry_hash: str

    def to_manifest(self) -> dict[str, object]:
        """Serialize only non-sensitive fields suitable for a canonical manifest."""

        return {
            "wayId": self.way_id,
            "nodeCount": self.node_count,
            "lengthBucketMeters": self.length_bucket_meters,
            "endpointDegreeClass": self.endpoint_degree_class,
            "endpointTagPresence": self.endpoint_tag_presence,
            "protectedNodeIdHash": self.protected_node_id_hash,
            "geometryHash": self.geometry_hash,
        }


@dataclass(frozen=True)
class WayRecord:
    """Immutable local way geometry used for matching but never directly serialized."""

    way_id: int
    node_refs: tuple[int, ...]
    coordinates: tuple[tuple[float, float] | None, ...]
    tags: tuple[tuple[str, str], ...]
    signature: WaySignature


@dataclass(frozen=True)
class OsmDocument:
    """Immutable parsed OSM summary plus local-only way records."""

    root_name: str
    format_name: str
    node_count: int
    way_count: int
    relation_count: int
    ways: tuple[WayRecord, ...]

    @property
    def way_signatures(self) -> tuple[WaySignature, ...]:
        """Return coordinate-free signatures in deterministic document order."""

        return tuple(way.signature for way in self.ways)


_PROHIBITED_DTD = re.compile(br"<!\s*(?:DOCTYPE|ENTITY)\b", re.I)
_EXTERNAL_SCHEMA = "{http://www.w3.org/2001/XMLSchema-instance}schemaLocation"
_NO_NAMESPACE_SCHEMA = "noNamespaceSchemaLocation"
_XINCLUDE_NAMESPACE = "{http://www.w3.org/2001/XInclude}"


def parse_osm_bytes(
    source: bytes,
    *,
    source_name: str = "input.osm",
    limits: OsmLimits | None = None,
) -> OsmDocument:
    """Parse bounded OSM or gzip OSM bytes with all external XML features rejected."""

    active = limits or OsmLimits()
    if len(source) > active.max_compressed_bytes:
        raise OsmError("COMPRESSED_SIZE", "OSM input exceeds compressed-size limit")
    gzip_named = source_name.lower().endswith(".gz")
    gzip_magic = source.startswith(b"\x1f\x8b")
    if gzip_named and not gzip_magic:
        raise OsmError("GZIP_MAGIC", "gzip-suffixed OSM input has no gzip magic")
    xml = _bounded_gzip(source, active) if gzip_magic else source
    if len(xml) > active.max_uncompressed_bytes:
        raise OsmError("UNCOMPRESSED_SIZE", "OSM input exceeds uncompressed-size limit")
    if gzip_magic and len(xml) / max(1, len(source)) > active.max_compression_ratio:
        raise OsmError("COMPRESSION_RATIO", "OSM gzip compression ratio exceeds limit")
    if _PROHIBITED_DTD.search(xml):
        raise OsmError("DTD", "DTD and entity declarations are prohibited")
    return _parse_xml(xml, active)


def _bounded_gzip(source: bytes, limits: OsmLimits) -> bytes:
    """Decompress one gzip stream while enforcing its output bound."""

    output = io.BytesIO()
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(source), mode="rb") as handle:
            while True:
                chunk = handle.read(min(1024 * 1024, limits.max_uncompressed_bytes + 1))
                if not chunk:
                    break
                if output.tell() + len(chunk) > limits.max_uncompressed_bytes:
                    raise OsmError("UNCOMPRESSED_SIZE", "OSM input exceeds uncompressed-size limit")
                output.write(chunk)
    except OsmError:
        raise
    except (EOFError, OSError) as error:
        raise OsmError("MALFORMED_GZIP", "malformed gzip OSM input") from error
    return output.getvalue()


def _parse_xml(xml: bytes, limits: OsmLimits) -> OsmDocument:
    """Build an immutable OSM document using bounded pull-parser events."""

    parser = ET.XMLPullParser(events=("start", "end"))
    nodes: dict[int, tuple[float, float, list[tuple[str, str]]]] = {}
    ways_raw: list[tuple[int, list[int], list[tuple[str, str]]]] = []
    relation_count = 0
    current_node: tuple[int, float, float, list[tuple[str, str]]] | None = None
    current_way: tuple[int, list[int], list[tuple[str, str]]] | None = None
    depth = 0
    element_count = 0
    root_name = ""
    try:
        for offset in range(0, len(xml), 64 * 1024):
            parser.feed(xml[offset:offset + 64 * 1024])
            for event, element in parser.read_events():
                local = _local_name(element.tag)
                if event == "start":
                    depth += 1
                    element_count += 1
                    if element_count > limits.max_elements:
                        raise OsmError("ELEMENT_LIMIT", "OSM element-count limit exceeded")
                    if depth > limits.max_depth:
                        raise OsmError("DEPTH_LIMIT", "OSM nesting-depth limit exceeded")
                    _validate_element(element, limits)
                    if not root_name:
                        root_name = local
                        if root_name != "osm":
                            raise OsmError("INVALID_OSM", "XML root is not osm")
                    if element.tag.startswith(_XINCLUDE_NAMESPACE):
                        raise OsmError("XINCLUDE", "XInclude is prohibited")
                    if local == "node":
                        node_id = _integer(element.attrib.get("id"))
                        lat = _coordinate(element.attrib.get("lat"), -90.0, 90.0)
                        lon = _coordinate(element.attrib.get("lon"), -180.0, 180.0)
                        current_node = (node_id, lat, lon, [])
                    elif local == "way":
                        current_way = (_integer(element.attrib.get("id")), [], [])
                    elif local == "relation":
                        relation_count += 1
                    elif local == "nd" and current_way is not None:
                        current_way[1].append(_integer(element.attrib.get("ref")))
                    elif local == "tag":
                        tag = (element.attrib.get("k", ""), element.attrib.get("v", ""))
                        if current_way is not None:
                            current_way[2].append(tag)
                        elif current_node is not None:
                            current_node[3].append(tag)
                else:
                    _validate_text(element, limits)
                    if local == "node" and current_node is not None:
                        node_id, lat, lon, tags = current_node
                        nodes[node_id] = (lat, lon, tags)
                        current_node = None
                    elif local == "way" and current_way is not None:
                        ways_raw.append(current_way)
                        current_way = None
                    depth -= 1
                    element.clear()
        parser.close()
    except OsmError:
        raise
    except (ET.ParseError, TypeError, ValueError, OverflowError) as error:
        raise OsmError("INVALID_OSM", "malformed OSM XML or invalid primitive value") from error
    if depth != 0 or not root_name:
        raise OsmError("INVALID_OSM", "incomplete OSM XML")
    return _document(root_name, nodes, ways_raw, relation_count)


def _validate_element(element: ET.Element, limits: OsmLimits) -> None:
    """Reject oversized attributes and external-schema declarations."""

    if len(element.attrib) > limits.max_attributes_per_element:
        raise OsmError("ATTRIBUTE_LIMIT", "OSM attribute-count limit exceeded")
    total = sum(len(str(key).encode("utf-8")) + len(str(value).encode("utf-8"))
                for key, value in element.attrib.items())
    if total > limits.max_attribute_bytes:
        raise OsmError("ATTRIBUTE_LIMIT", "OSM attribute-size limit exceeded")
    for key in element.attrib:
        if key == _EXTERNAL_SCHEMA or _local_name(key) in {"schemaLocation", _NO_NAMESPACE_SCHEMA}:
            raise OsmError("EXTERNAL_SCHEMA", "external schema declarations are prohibited")


def _validate_text(element: ET.Element, limits: OsmLimits) -> None:
    """Reject oversized element text or tail content."""

    size = len((element.text or "").encode("utf-8")) + len((element.tail or "").encode("utf-8"))
    if size > limits.max_text_bytes:
        raise OsmError("TEXT_LIMIT", "OSM text-size limit exceeded")


def _integer(value: str | None) -> int:
    """Parse one required signed 64-bit OSM identifier."""

    if value is None:
        raise ValueError("missing integer")
    result = int(value)
    if result < -(2**63) or result > 2**63 - 1:
        raise ValueError("integer outside signed 64-bit range")
    return result


def _coordinate(value: str | None, minimum: float, maximum: float) -> float:
    """Parse one finite coordinate constrained to its geographic range."""

    try:
        coordinate = float(value) if value is not None else math.nan
    except ValueError as error:
        raise OsmError("INVALID_COORDINATE", "invalid OSM coordinate") from error
    if not math.isfinite(coordinate) or coordinate < minimum or coordinate > maximum:
        raise OsmError("INVALID_COORDINATE", "invalid OSM coordinate")
    return coordinate


def _document(root_name, nodes, ways_raw, relation_count) -> OsmDocument:
    """Resolve parsed primitives and compute coordinate-free way signatures."""

    degree: dict[int, int] = {}
    for _, references, _ in ways_raw:
        for reference in set(references):
            degree[reference] = degree.get(reference, 0) + 1
    ways = []
    for way_id, references, tags in ways_raw:
        coordinates = tuple((nodes[ref][0], nodes[ref][1]) if ref in nodes else None
                            for ref in references)
        signature = _signature(way_id, references, coordinates, nodes, degree)
        ways.append(WayRecord(way_id, tuple(references), coordinates, tuple(sorted(tags)), signature))
    return OsmDocument(root_name, "OSM_XML", len(nodes), len(ways), relation_count, tuple(ways))


def _signature(way_id, references, coordinates, nodes, degree) -> WaySignature:
    """Compute one direction-independent local geometry signature."""

    length = sum(_distance(left, right) for left, right in zip(coordinates, coordinates[1:])
                 if left is not None and right is not None)
    bucket = int(round(length / 5.0) * 5)
    endpoints = [references[0], references[-1]] if references else []
    endpoint_degrees = ":".join(str(min(9, degree.get(ref, 0))) for ref in endpoints) or "none"
    endpoint_tags = ":".join("1" if ref in nodes and nodes[ref][2] else "0" for ref in endpoints) or "none"
    protected = [
        ref for index, ref in enumerate(references)
        if index in {0, len(references) - 1}
        or degree.get(ref, 0) > 1
        or (ref in nodes and nodes[ref][2])
    ]
    geometry_values = ["missing" if coordinate is None else f"{coordinate[0]:.7f},{coordinate[1]:.7f}"
                       for coordinate in coordinates]
    return WaySignature(way_id, len(references), bucket, endpoint_degrees, endpoint_tags,
                        _direction_hash([str(value) for value in protected]),
                        _direction_hash(geometry_values))


def _direction_hash(values: list[str]) -> str:
    """Hash an ordered sequence identically in either travel direction."""

    forward = "|".join(values)
    reverse = "|".join(reversed(values))
    return hashlib.sha256(min(forward, reverse).encode("ascii", "strict")).hexdigest()


def _distance(left: tuple[float, float], right: tuple[float, float]) -> float:
    """Return haversine ground distance in metres."""

    lat1, lon1 = map(math.radians, left)
    lat2, lon2 = map(math.radians, right)
    delta_lat = lat2 - lat1
    delta_lon = lon2 - lon1
    value = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return 6_371_008.8 * 2 * math.asin(min(1.0, math.sqrt(value)))


def _local_name(name: str) -> str:
    """Strip an XML namespace from an expanded element or attribute name."""

    return name.rsplit("}", 1)[-1]
