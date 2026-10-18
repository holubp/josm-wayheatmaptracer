"""Behavioral contract for the sealed replacement HOLDOUT producer.

Every recipe in this module is explicitly dummy test data. These fixtures can
exercise the generic serializer, but can never qualify or authorize evaluation.
"""

from __future__ import annotations

import hashlib
import hmac
import importlib.util
import json
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "generate-observation-holdout.py"
SPEC = importlib.util.spec_from_file_location("observation_holdout_generator", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)

DUMMY_SEED = bytes(range(32))
DUMMY_MARKER = "NONQUALIFYING-DUMMY-FIXTURE-ONLY"
CONTRASTS = (
    "concentrated-band-vs-diffuse-band",
    "displaced-strong-corridor-vs-nearby-faint-strand",
    "multiple-physical-pitches-vs-reversed-source-order",
    "palette-semantic-vs-rendered-source",
    "resolved-short-apex-vs-stair-step-alias",
    "sparse-crossing-noisy-strands-vs-persistent-parallel",
    "weak-coherent-curve-vs-disconnected-flecks",
)
GENERATOR_HASH = "a" * 64
PROTOCOL_HASH = "b" * 64
PHASES = (0.0, 0.25, 0.5, 0.75, 0.25, 0.75, 0.0, 0.5)
PITCHES = (0.5, 0.5, 1.0, 1.0, 1.75, 1.75, 0.5, 1.75)
OVERSAMPLING = (1, 2, 1, 2, 1, 2, 2, 1)


class ScriptedStream:
    def __init__(self, values):
        self.values = iter(values)
        self.bytes_read = 0

    def read(self, count):
        result = bytes(next(self.values) for _ in range(count))
        self.bytes_read += count
        return result


def test_hmac_byte_stream_uses_the_fixed_domain_counter_and_dummy_seed():
    stream = generator.HmacByteStream(DUMMY_SEED)
    domain = b"b-observation-holdout-v2"
    expected = b"".join(
        hmac.new(DUMMY_SEED, domain + counter.to_bytes(8, "big"), hashlib.sha256).digest()
        for counter in range(2)
    )
    assert stream.read(len(expected)) == expected


def test_hmac_stream_enforces_one_bounded_invocation_byte_budget():
    stream = generator.HmacByteStream(DUMMY_SEED, max_bytes=3)
    assert len(stream.read(3)) == 3
    with pytest.raises(generator.GenerationError):
        stream.read(1)


def test_unbiased_offset_mapping_has_equal_preimage_counts_and_rejects_tail_bytes():
    mapped = [generator.offset_from_byte(value) for value in range(256)]
    assert mapped[222:] == [None] * 34
    physical_offsets = {index / 4 for index in range(-16, 21)}
    assert set(mapped[:222]) == physical_offsets
    assert {value / 4: mapped[:222].count(value / 4) for value in range(-16, 21)} == {
        value / 4: 6 for value in range(-16, 21)
    }
    stream = ScriptedStream((222, 255, 221))
    assert generator.draw_offset(stream) == 5.0
    assert stream.bytes_read == 3


def test_offset_rejection_stops_at_the_configured_stream_budget():
    stream = ScriptedStream((222, 255))
    with pytest.raises(generator.GenerationError):
        generator.draw_offset(stream, max_bytes=2)
    assert stream.bytes_read == 2


def test_quintic_spline_has_exact_endpoints_c2_joins_and_analytic_bounds():
    dummy_offsets = (1.0, -2.0, 3.0, -1.0, 4.0)
    assert generator.spline_value(dummy_offsets, 0.0) == 30.0
    assert generator.spline_value(dummy_offsets, 120.0) == 30.0
    assert generator.spline_value(dummy_offsets, -1.0) == 30.0
    assert generator.spline_value(dummy_offsets, 121.0) == 30.0
    for knot_index, offset in enumerate((0.0, *dummy_offsets, 0.0)):
        x = knot_index * 20.0
        assert generator.spline_value(dummy_offsets, x) == 30.0 + offset
        first, second = generator.spline_derivatives(dummy_offsets, x)
        assert first == 0.0
        assert second == 0.0
    values = [generator.spline_value(dummy_offsets, step / 4) for step in range(0, 481)]
    assert min(values) >= 28.0
    assert max(values) <= 34.0


def test_all_registered_dummy_templates_have_truthful_branch_and_observation_labels():
    offsets = (-2.0, -1.25, 1.25, 3.0, 0.0)
    for contrast in generator.CONTRASTS:
        for side in generator.SIDES:
            recipe = generator._recipe_record(contrast, offsets, "v2", side)
            assert recipe["renderer"] == "strictmath-scalar-v2"
            assert recipe["baseCurve"]["interiorOffsetsMeters"] == list(offsets)
            assert recipe["truth"]["association"] in ("UNIQUE", "AMBIGUOUS", "NOT_APPLICABLE")

    for side, expected_branches in (
        ("left", ("F+(-2+4t)", "F+(1.4-0.7t)", "F+(2-4t)")),
        ("right", ("F-4", "F+4")),
    ):
        recipe = generator._recipe_record(
            "sparse-crossing-noisy-strands-vs-persistent-parallel", offsets, "v2", side
        )
        assert recipe["truth"]["association"] == "AMBIGUOUS"
        assert recipe["truth"]["location"] == "OBSERVED_STRUCTURE"
        assert recipe["truth"]["curve"]["branches"] == list(expected_branches)
        assert recipe["field"]["centerOffsetsFromF"] == (
            [-2.0, 1.4, 2.0] if side == "left" else [-4.0, 4.0]
        )

    alias = generator._recipe_record(
        "resolved-short-apex-vs-stair-step-alias", offsets, "v2", "right"
    )
    assert alias["field"]["center"] == "pitch*floor(F/pitch+0.5)"
    assert alias["truth"]["association"] == "UNIQUE"
    assert alias["truth"]["curve"] == {"base": "F"}


def test_geometry_validity_and_reverse_canonicalization_use_only_dummy_tuples():
    valid = (-2.0, -1.25, 1.25, 3.0, 0.0)
    reverse = tuple(reversed(valid))
    assert generator.valid_geometry(valid)
    assert generator.canonical_geometry(valid) == generator.canonical_geometry(reverse)
    assert not generator.valid_geometry((1.0, -1.0, 0.5, 0.0, 0.0))
    assert not generator.valid_geometry((3.0, 0.0, 0.0, 0.0, 0.0))
    assert not generator.valid_geometry((0.0, 0.0, 0.0, 0.0, 0.0))


def test_distinct_group_draw_rejects_duplicate_and_reversed_aliases_within_proposal_bound():
    first = (-2.0, -1.25, 1.25, 3.0, 0.0)
    second = (-3.0, 1.5, -1.0, 2.0, 0.25)
    third = (3.0, -2.0, 1.0, -1.25, 0.5)
    proposals = iter((first, tuple(reversed(first)), second, third,
                      (-1.0, 2.0, -3.0, 1.25, 4.0),
                      (1.0, -2.0, 3.0, -1.25, 0.5),
                      (-4.0, 1.0, -1.25, 2.5, 3.0),
                      (2.0, -3.5, 1.25, -1.0, 4.5),
                      (-1.5, 3.0, -2.25, 1.0, 4.0)))
    groups = generator.draw_distinct_groups(lambda: next(proposals), group_count=7,
                                             max_proposals_per_group=64)
    assert len(groups) == 7
    keys = [generator.canonical_geometry(group) for group in groups]
    assert len(set(keys)) == 7


def test_group_proposal_exhaustion_is_bounded_and_fails_closed():
    valid = (-2.0, -1.25, 1.25, 3.0, 0.0)
    calls = 0

    def repeat_alias():
        nonlocal calls
        calls += 1
        return valid

    with pytest.raises(generator.GenerationError):
        generator.draw_distinct_groups(repeat_alias, group_count=2, max_proposals_per_group=4)
    assert calls == 5


def _dummy_package_inputs():
    dummy_offsets = (
        (-2.0, -1.25, 1.25, 3.0, 0.0),
        (-3.0, 1.5, -1.0, 2.0, 0.25),
        (3.0, -2.0, 1.0, -1.25, 0.5),
        (-1.0, 2.0, -3.0, 1.25, 4.0),
        (1.0, -2.0, 3.0, -1.25, 0.5),
        (-4.0, 1.0, -1.25, 2.5, 3.0),
        (2.0, -3.5, 1.25, -1.0, 4.5),
    )
    groups = generator._group_metadata()
    variants = {
        f"v{index}": {
            "phase": PHASES[index],
            "sourcePitchMeters": PITCHES[index],
            "oversampling": OVERSAMPLING[index],
        }
        for index in range(8)
    }
    recipes = []
    for group_index in range(7):
        for variant_index in range(8):
            for side in ("left", "right"):
                recipe = generator._recipe_record(
                    CONTRASTS[group_index], dummy_offsets[group_index], f"v{variant_index}", side
                )
                recipe["dummyMarker"] = DUMMY_MARKER
                recipes.append({
                    "group": f"g{group_index}",
                    "variant": f"v{variant_index}",
                    "side": side,
                    "recipe": recipe,
                })
    payload = {
        "schema": 1,
        "version": generator.PROTOCOL_VERSION,
        "generationId": generator.GENERATION_ID,
        "generatorHash": GENERATOR_HASH,
        "protocolHash": PROTOCOL_HASH,
        "renderer": "strictmath-scalar-v2",
        "dummyMarker": DUMMY_MARKER,
        "groups": {
            f"g{index}": {
                "contrast": CONTRASTS[index],
                "interiorOffsetsMeters": list(dummy_offsets[index]),
            }
            for index in range(7)
        },
        "recipes": recipes,
    }
    payload_bytes = generator.canonical_json_bytes(payload)
    index_bytes = generator.build_public_index(
        groups, variants, recipes, payload_bytes=payload_bytes,
        generator_hash=GENERATOR_HASH, protocol_hash=PROTOCOL_HASH,
    )
    private = {
        "schema": 1,
        "version": generator.PROTOCOL_VERSION,
        "generationId": generator.GENERATION_ID,
        "masterSeedHex": DUMMY_SEED.hex(),
        "streamDomain": generator.STREAM_DOMAIN.decode("ascii"),
        "streamBytesRead": 0,
        "streamBlocksRead": 0,
        "proposalLimitPerGroup": generator.MAX_PROPOSALS_PER_GROUP,
        "generatorHash": GENERATOR_HASH,
        "protocolHash": PROTOCOL_HASH,
        "dummyMarker": DUMMY_MARKER,
    }
    private_bytes = generator.canonical_json_bytes(private)
    return payload_bytes, private_bytes, index_bytes


def test_dummy_index_is_compact_canonical_deterministic_and_redacts_concrete_recipes():
    payload, _, index_bytes = _dummy_package_inputs()
    index = json.loads(index_bytes)
    assert index_bytes.endswith(b"\n")
    assert generator.canonical_json_bytes(index) == index_bytes
    assert index["split"] == "HOLDOUT"
    assert len(index["groups"]) == 7
    assert len(index["variants"]) == 8
    assert len(index["entries"]) == 112
    assert set(index["entries"][0]) == {"id", "group", "variant", "side", "recipeHash"}
    assert len({entry["id"] for entry in index["entries"]}) == 112
    assert len({entry["recipeHash"] for entry in index["entries"]}) == 112
    assert all(entry["id"].startswith("b-holdout-v2/g") for entry in index["entries"])
    assert all(set(entry) == {"id", "group", "variant", "side", "recipeHash"}
               for entry in index["entries"])
    assert DUMMY_MARKER.encode() not in index_bytes
    assert b"dummyCoordinates" not in index_bytes
    assert b"dummyLabel" not in index_bytes
    assert DUMMY_SEED.hex().encode() not in index_bytes
    assert hashlib.sha256(payload).hexdigest() == index["payload"]["sha256"]
    assert len(index_bytes) <= generator.MAX_INDEX_BYTES
    assert generator.validate_public_index(index_bytes)
    assert index_bytes == _dummy_package_inputs()[2]


def test_public_index_rejects_unknown_keys_duplicate_ids_and_oversize_bytes():
    _, _, index_bytes = _dummy_package_inputs()
    index = json.loads(index_bytes)
    index["unregistered"] = True
    with pytest.raises(generator.GenerationError):
        generator.validate_public_index(generator.canonical_json_bytes(index))

    index = json.loads(_dummy_package_inputs()[2])
    index["entries"][1]["id"] = index["entries"][0]["id"]
    with pytest.raises(generator.GenerationError):
        generator.validate_public_index(generator.canonical_json_bytes(index))

    with pytest.raises(generator.GenerationError):
        generator.validate_public_index(b"x" * (generator.MAX_INDEX_BYTES + 1))


def test_package_publication_rejects_malformed_payload_schema_and_private_identity(tmp_path):
    payload, private, index_bytes = _dummy_package_inputs()
    index = json.loads(index_bytes)
    malformed_payload = json.loads(payload)
    malformed_payload["schema"] = 99
    malformed_bytes = generator.canonical_json_bytes(malformed_payload)
    index["payload"] = {
        "sha256": hashlib.sha256(malformed_bytes).hexdigest(), "byteCount": len(malformed_bytes),
    }
    scope = tmp_path / "malformed-payload"
    scope.mkdir()
    with pytest.raises(generator.GenerationError):
        generator.publish_package(
            scope, malformed_bytes, private, generator.canonical_json_bytes(index)
        )
    assert not (scope / "index.json").exists()

    payload, private, index_bytes = _dummy_package_inputs()
    mismatched_private = json.loads(private)
    mismatched_private["generatorHash"] = "c" * 64
    scope = tmp_path / "mismatched-private"
    scope.mkdir()
    with pytest.raises(generator.GenerationError):
        generator.publish_package(
            scope, payload, generator.canonical_json_bytes(mismatched_private), index_bytes
        )
    assert not (scope / "index.json").exists()


def test_package_publication_binds_each_payload_recipe_to_its_index_hash(tmp_path):
    payload, private, index_bytes = _dummy_package_inputs()
    payload_record = json.loads(payload)
    payload_record["recipes"][0]["recipe"]["background"] = 0.03
    changed_payload = generator.canonical_json_bytes(payload_record)
    index = json.loads(index_bytes)
    index["payload"] = {
        "sha256": hashlib.sha256(changed_payload).hexdigest(), "byteCount": len(changed_payload),
    }
    scope = tmp_path / "recipe-index-mismatch"
    scope.mkdir()
    with pytest.raises(generator.GenerationError):
        generator.publish_package(
            scope, changed_payload, private, generator.canonical_json_bytes(index)
        )
    assert not (scope / "index.json").exists()


def test_common_git_directory_maps_a_worktree_to_one_exact_primary_scope(tmp_path):
    primary = tmp_path / "primary"
    common_git = primary / ".git"
    worktree = tmp_path / "worktree"
    common_git.mkdir(parents=True)
    worktree.mkdir()
    assert generator.primary_repository_from_common_git_dir(common_git) == primary.resolve()
    assert generator.GENERATION_ID == "sealed-observation-v2-generation-v2"
    assert generator.generation_scope(primary) == (
        primary / "build" / "sealed-observation-v2" / "generation-v2"
    )
    with pytest.raises(generator.GenerationError):
        generator.primary_repository_from_common_git_dir(common_git, is_bare=True)


def test_existing_fixed_scope_refuses_before_entropy_and_preserves_prior_claim(tmp_path):
    primary = tmp_path / "primary"
    scope = primary / "build" / "sealed-observation-v2" / "generation-v2"
    scope.mkdir(parents=True)
    marker = scope / "prior-attempt.txt"
    marker.write_text("preserve")
    calls = 0

    def entropy(size):
        nonlocal calls
        calls += 1
        return b"x" * size

    with pytest.raises(generator.GenerationError):
        generator.claim_generation_scope(primary, entropy_source=entropy)
    assert calls == 0
    assert marker.read_text() == "preserve"


def test_symlinked_or_escaped_fixed_scope_refuses_before_entropy(tmp_path):
    primary = tmp_path / "primary"
    outside = tmp_path / "outside"
    primary.mkdir()
    outside.mkdir()
    (outside / "keep.txt").write_text("preserve")
    (primary / "build").symlink_to(outside, target_is_directory=True)
    entropy_calls = []
    with pytest.raises(generator.GenerationError):
        generator.claim_generation_scope(
            primary, entropy_source=lambda size: entropy_calls.append(size) or DUMMY_SEED
        )
    assert entropy_calls == []
    assert (outside / "keep.txt").read_text() == "preserve"


def test_protocol_and_generator_hashes_bind_the_actual_fixed_source_bytes():
    generator_hash, protocol_hash = generator._repository_hashes(ROOT)
    source_path = ROOT / "scripts" / "generate-observation-holdout.py"
    protocol_path = ROOT / "superpowers" / "docs" / "plans" / "2026-10-18-b-sealed-holdout-generator-contract.md"
    assert generator_hash == hashlib.sha256(source_path.read_bytes()).hexdigest()
    assert protocol_hash == hashlib.sha256(protocol_path.read_bytes()).hexdigest()
    with pytest.raises(generator.GenerationError):
        generator._repository_hashes(ROOT.parent)


def test_cli_refuses_seed_or_path_arguments_and_ci_without_entropy_or_scope(monkeypatch, tmp_path):
    entropy_calls = []
    monkeypatch.setattr(generator, "draw_master_seed", lambda: entropy_calls.append(True))
    with pytest.raises(generator.GenerationError):
        generator.main(["--seed", "dummy"])
    monkeypatch.setenv("CI", "true")
    with pytest.raises(generator.GenerationError):
        generator.main([])
    assert entropy_calls == []
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize("entrypoint", ("runner-default", "cli-default"))
def test_real_default_entropy_callback_claims_first_and_reads_exactly_once(
    monkeypatch, tmp_path, entrypoint,
):
    primary = tmp_path / "dummy-primary"
    (primary / ".git").mkdir(parents=True)
    failed_v1 = primary / "build" / "sealed-observation-v2" / "generation-v1"
    failed_v1.mkdir(parents=True)
    prior_failure = b'{"cause":"MASTER_SEED_UNAVAILABLE","status":"FAILED"}\n'
    (failed_v1 / "failure.json").write_bytes(prior_failure)
    scope = generator.generation_scope(primary)
    assert scope == primary / "build" / "sealed-observation-v2" / "generation-v2"
    entropy_calls = []
    construction_calls = []

    class StopBeforeDummyRecipeConstruction(Exception):
        pass

    def dummy_os_entropy(size):
        assert scope.is_dir()
        assert list(scope.iterdir()) == []
        entropy_calls.append(size)
        return DUMMY_SEED

    def stop_before_construction(seed, *, generator_hash, protocol_hash):
        construction_calls.append((seed, generator_hash, protocol_hash))
        raise StopBeforeDummyRecipeConstruction()

    # Keep the actual draw_master_seed callback, including the runner's bound
    # default. Only the OS boundary is mocked; no random data or recipes exist.
    monkeypatch.setattr(generator.os, "urandom", dummy_os_entropy)
    monkeypatch.setattr(generator, "_repository_hashes", lambda root: (GENERATOR_HASH, PROTOCOL_HASH))
    monkeypatch.setattr(generator, "build_generation_documents", stop_before_construction)
    monkeypatch.setattr(generator, "_git_primary_repository", lambda cwd: primary)
    monkeypatch.delenv("CI", raising=False)
    with pytest.raises(StopBeforeDummyRecipeConstruction):
        if entrypoint == "runner-default":
            generator.run_production_once(primary=primary)
        else:
            generator.main([])
    assert entropy_calls == [32]
    assert construction_calls == [(DUMMY_SEED, GENERATOR_HASH, PROTOCOL_HASH)]
    assert {path.name for path in scope.iterdir()} == {"failure.json"}
    assert json.loads((scope / "failure.json").read_bytes())["cause"] == "GENERATION_FAILED"
    failure_bytes = (scope / "failure.json").read_bytes()
    with pytest.raises(generator.GenerationError, match="GENERATION_SCOPE_ALREADY_CLAIMED"):
        if entrypoint == "runner-default":
            generator.run_production_once(primary=primary)
        else:
            generator.main([])
    assert entropy_calls == [32]
    assert construction_calls == [(DUMMY_SEED, GENERATOR_HASH, PROTOCOL_HASH)]
    assert (scope / "failure.json").read_bytes() == failure_bytes
    assert {p.name for p in failed_v1.iterdir()} == {"failure.json"}
    assert (failed_v1 / "failure.json").read_bytes() == prior_failure
    assert {p.name for p in scope.parent.iterdir()} == {"generation-v1", "generation-v2"}


def test_old_zero_argument_callback_binding_never_enters_os_body(monkeypatch, tmp_path):
    calls = []
    monkeypatch.setattr(generator.os, "urandom", lambda count: calls.append(count) or DUMMY_SEED)

    # The exact old callback signature/body, using only the mocked OS boundary.
    def old_draw_master_seed():
        return generator.os.urandom(32)

    primary = tmp_path / "dummy-old-binding"
    primary.mkdir()
    with pytest.raises(generator.GenerationError, match="MASTER_SEED_UNAVAILABLE") as caught:
        generator.claim_generation_scope(primary, entropy_source=old_draw_master_seed)
    assert isinstance(caught.value.__cause__, TypeError)
    assert calls == []
    assert {p.name for p in generator.generation_scope(primary).iterdir()} == {"failure.json"}


def test_v2_entropy_failure_is_terminal_without_second_os_request(monkeypatch, tmp_path):
    primary = tmp_path / "dummy-os-failure"
    (primary / ".git").mkdir(parents=True)
    calls = []

    def unavailable_dummy_entropy(size):
        calls.append(size)
        raise OSError("dummy entropy refusal")

    monkeypatch.setattr(generator.os, "urandom", unavailable_dummy_entropy)
    monkeypatch.setattr(generator, "_repository_hashes", lambda root: (GENERATOR_HASH, PROTOCOL_HASH))
    with pytest.raises(generator.GenerationError, match="MASTER_SEED_UNAVAILABLE"):
        generator.run_production_once(primary=primary)
    scope = generator.generation_scope(primary)
    assert scope == primary / "build" / "sealed-observation-v2" / "generation-v2"
    failure_bytes = (scope / "failure.json").read_bytes()
    with pytest.raises(generator.GenerationError, match="GENERATION_SCOPE_ALREADY_CLAIMED"):
        generator.run_production_once(primary=primary)
    assert calls == [32]
    assert {p.name for p in scope.iterdir()} == {"failure.json"}
    assert (scope / "failure.json").read_bytes() == failure_bytes
    assert {p.name for p in scope.parent.iterdir()} == {"generation-v2"}


@pytest.mark.parametrize("size", (-1, 0, 31, 33))
def test_default_entropy_callback_refuses_non_master_seed_sizes_without_os_read(monkeypatch, size):
    calls = []
    monkeypatch.setattr(generator.os, "urandom", lambda count: calls.append(count) or DUMMY_SEED)
    with pytest.raises(generator.GenerationError, match="INVALID_MASTER_SEED_SIZE"):
        generator.draw_master_seed(size)
    assert calls == []


def test_dummy_package_hashes_publish_index_last_and_failure_never_mints_success(tmp_path):
    payload, private, index = _dummy_package_inputs()
    scope = tmp_path / "claimed-success"
    scope.mkdir()
    result = generator.publish_package(scope, payload, private, index)
    assert result["payloadSHA256"] == hashlib.sha256(payload).hexdigest()
    assert result["privateSHA256"] == hashlib.sha256(private).hexdigest()
    assert result["indexSHA256"] == hashlib.sha256(index).hexdigest()
    assert (scope / "payload.json").read_bytes() == payload
    assert (scope / "generation-private.json").read_bytes() == private
    assert (scope / "index.json").read_bytes() == index

    failed_primary = tmp_path / "primary-failure"
    broken_scope = generator.generation_scope(failed_primary)
    broken_scope.mkdir(parents=True)
    broken = json.loads(index)
    broken["payload"]["sha256"] = "0" * 64
    with pytest.raises(generator.GenerationError):
        generator.publish_package(broken_scope, payload, private, generator.canonical_json_bytes(broken))
    assert not (broken_scope / "index.json").exists()
    failure = (broken_scope / "failure.json").read_text()
    assert DUMMY_SEED.hex() not in failure
    assert DUMMY_MARKER not in failure
    assert json.loads(failure)["status"] == "FAILED"
    with pytest.raises(generator.GenerationError):
        generator.claim_generation_scope(failed_primary, entropy_source=lambda size: b"z" * size)


@pytest.mark.parametrize(
    ("which", "limit_name"),
    (("payload", "MAX_PAYLOAD_BYTES"), ("private", "MAX_PRIVATE_BYTES"), ("index", "MAX_INDEX_BYTES")),
)
def test_output_size_bounds_refuse_before_success_publication(tmp_path, which, limit_name):
    payload, private, index = _dummy_package_inputs()
    oversized = b"x" * (getattr(generator, limit_name) + 1)
    if which == "payload":
        payload = oversized
    elif which == "private":
        private = oversized
    else:
        index = oversized
    scope = tmp_path / f"oversized-{which}"
    scope.mkdir()
    with pytest.raises(generator.GenerationError):
        generator.publish_package(scope, payload, private, index)
    assert not (scope / "index.json").exists()
