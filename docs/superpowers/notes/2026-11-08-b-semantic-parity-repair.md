# B semantic parity repair evidence

Approved plan: drasov-a-corridor-fix/docs/superpowers/plans/2026-11-08-b-semantic-parity-repair.md.
Base026b9513; isolated branch codex/b-semantic-parity-repair-20261108.
Scope is test comparator, its tests and this note only. Production/Legacy/defaults,
old private artifacts and all baseline pins remain immutable.

Ruling: extract the existing byte-equality predicates into named test-only
comparison boundaries before fixing them, so RED demonstrates actual rejection
of order-only diagnostic maps and valid distinct native bindings rather than a
missing Java symbol. No accepted comparison behavior changes in this extraction.

Evidence is retained in build/private-drasov-b-resource-20261106/semantic-parity-repair.
Public RED/GREEN and required component/fingerprint/budgeted neighbors are recorded below.
Saved private postprocessing is deferred until independent review; no private
engine, sampler, replay or capture is authorized here. Existing full public CI
evidence and separate qualification holds are not relabelled by this repair.

Task1 implementation: diagnostic maps compare complete exact key/value spellings
with duplicate rejection; every surrounding quality/topology character remains
exact. Existing byte comparator is retained for callers requiring byte identity.
Each native component container is decoded through FinalReplayExpectation's own
factual archive/input binding and FinalOutputComponentsCodec. Complete component
set/digests, final root, quality summary, raw/final artifacts, actual native work
and all baseline pins remain checked. The cross-policy preservation path is
deliberately restricted to the approved compact ORIGINAL_REFINEMENT case. It
requires the exact V5 retention-version to V6 budget-version substitution and
one canonical, receipt-bound additive witness; same-policy explanation/work
changes remain rejected. No binary header offsets or generic field stripping.

RED: five tests, exactly two intended assertion failures, recorded in red.log
and red-xml/. First implementation rejected a valid provenance trailer because
it assumed an unowned reason token; source inspection showed the normative
refinements=1/provenance envelope, which replaced that assumption without
relaxing whole-explanation comparison. The retained green-attempt1 log/XML
records that failure. Expanded GREEN: eight tests, zero failures/errors/skips,
including ten independently self-consistent native mutations (one-bit raw/final
coordinates, assignment, branch, support, provenance, quality, finding,
diagnostics, work and explanation) and forged/missing archive evidence.

Public command, under Java17 and the shared JVM flock:

```
JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk \
flock /data/data/com.termux/files/usr/tmp/josm-drasov-gradle.lock \
sh ./gradlew --no-daemon --max-workers=1 \
-Pjosm19555HostJar=/data/data/com.termux/files/usr/tmp/v022-official-josm19555-host/JOSM-Linux-java11-19555.jar \
test --tests '*V022BudgetedFrontierParityTest'
```

Neighbor command uses the same prefix and additionally selects
V022FinalOutputCompanionTest, V022OutputCompanionBudgetTest,
V022BudgetedFrontierReplayTest, V022ProductionReplayTest and
V022BenchmarkFinalOutputComparisonTest. Terminal result will be appended below.
Private comparison and independent review remain pending; no private numerical
work or old-artifact overwrite occurred. The separate public search prototype
has not started.

Final neighbor run: BUILD SUCCESSFUL in3m6s; six suites/123 tests, zero failures,
errors or skips. Raw XML retained in neighbors-green-xml/ and aggregate in
test-summary.json. The run includes all eight current parity tests and115
neighbor tests. Source code was unchanged between expanded GREEN and this run;
only this evidence note was updated. git diff --check passes. Scope remains
exactly the two test files and this note. Task1 is ready for independent review;
Task2 saved private postprocessing is still pending that review and parent action.
