# Area-presence observation experiment: prefit evidence

This records prerequisites for the approved feasibility amendment, not a fitted
model, HOLDOUT result, production promotion or release. The original OBS-01–20
requirements and later production/private/integration gates remain required.

## Frozen inputs

- Family: `area-presence-core-position-v2`, unchanged 32-configuration grid.
- Manifest: `2026-10-18-b-observation-feasibility-manifest.json`, SHA-256
  `8fb47902e402bded101cd239d52b1f67655616f12ba6d0d7a35918b203a0aa77`.
- Exact 27-member source aggregate:
  `cf7b048bf8483f888cb497e1a795935a262aeb601b09cd432adb4a5c612436b4`.
- External reviewed-source registry:
  `61246d9c65582c990ad52ed581a8de2c13b3a4671c4a3e70a0b1fd38289e17a6`.
  The registry is outside the source inventory; the consuming Git revision is
  checked and recorded externally, avoiding a recursive containing-commit hash.
- Catalog: `debec23907badfaa467fac91cb0da5f3d8771e357ea1faa6f618d60c852d7557`.
  Public index: `97b134e7c983b698679258f244ee8431696bb34257615ce27675e19c21f6b092`,
  19,960 bytes; 468 metadata rows including retired diagnostic rows.
- Renderer: `native-observations-production-resample-v6-strict-math`.

Actual source, manifest, canonical public index, catalog and sealed metadata are
validated before evaluation. The seal rechecks registry identity and all actual
bindings. A supplied current source hash cannot replace external registration.
Only the public index is packaged in the standalone tool runtime. No sealed
payload, seed or private capture is packaged or uploaded.

## Behavioral evidence and review

- TRAIN scheduling and real evaluator share production reconstruction, binding,
  MAP, cache and coupled-control assembly. Encountered source/cache failures abort
  the experiment; genuine quality failures reject configurations. Lower geometry
  skipped after the four-feasible cutoff remains untested.
- Fresh SELECT is inaccessible through EXTRACT and every subset route. Repeated
  score/pipeline unit checks use fixed nonqualifying dummy scenes. The dummy path
  cannot choose/freeze a model or grant evaluation authority.
- Pure registry and fresh-boundary checks: 9 GREEN, archive SHA-256
  `0ac86ebd1d6bea26a989608ffe1bbca66a898f7e45544effea9586b195cd5946`.
- Prefreeze focused checks: 132 GREEN across 11 suites, archive SHA-256
  `1c2cc627469b29739ef1a2cf3384df2fa54ffb2eacf9df342f9dbe1b4aafce42`.
- Actual disposable-checkout source/index/registry refusals and kernel neighbours:
  33 GREEN across five suites, archive SHA-256
  `c49986fd21d52fb39bcfa0ac41c0b1ec60d9c86c77a6dab3d1677e02d764a25b`.
  Each refusal test first requires successful clean CATALOG registration, preventing
  missing runtime classes from masquerading as a refusal. An initial subprocess
  classpath failure was corrected; it was not model failure evidence.
- Generic sealed producer: 30 Python tests GREEN. CI boundary checks: six GREEN.
- Final consolidated affected run: 194 tests across 16 suites GREEN, no failures,
  errors or skipped tests; `observationIdentifyClasses` also succeeded (1m53s).
  Archive SHA-256 `0410d087e8291ace098e505c262112b144591ad026a749ea2d80a624b7d57b20`.
  Combined producer/workflow rerun: 36 Python tests GREEN (1.35s).
- Independent Sol source/test reviews closed the original TRAIN metadata defects,
  three runner/access findings and registry/freeze concerns. No Astra was used.

## Remaining prerequisites and gates

The complete affected-suite archive is retained. Cross-host original-TRAIN renderer
parity must still be retained before the one frozen public FIT_SELECT execution. No fresh
SELECT, sealed HOLDOUT or private case was materialized for this prefit packet.
Fitting retains the existing watchdog, memory, disk, catalog and attempt caps.
Its result must be preserved even on failure. A score/fitting pass alone cannot
complete the goal: real production candidate execution, unchanged regressions,
one qualified sealed HOLDOUT evaluation, private acceptance, reproducible baseline
comparison and final integration/review remain required.
