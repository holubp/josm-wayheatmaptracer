# Recorded B capture and section replay

New visible and managed Experimental B captures record `INVENTORY_PREP_V4`.
Its unchanged width-based admission chooses one full cached-V4 inference when
the conservative score/pair/state bounds fit, otherwise the existing conditional
compact inference. The frozen parameter hash selects the same policy in ordinary
replay. Historical exact and V1/V2/V3/V4 hashes remain executable with their
existing semantics; an unknown hash does not acquire a new policy.

New Legacy → B captures use a separate recognized parent identity. The guarded
reference factory still owns the exact child request, source polyline, section
authority and remaining allocation. The B engine validates that authority before
resolving the new policy from immutable `Reference.originalRequest`. It never
rewrites the opaque child hash. Historical and unknown parent hashes retain the
exact backend. No Legacy algorithm, wrapper, source sampler or numerical model
changes are part of this integration.

## Actual run companions

New interval exports serialize the actual ordered `IntervalTraceBatch` requests,
including a single trimmed interval and unequal remaining budgets. New sequential
Hybrid exports serialize the actual ordered section outcomes and executed
`SectionExecution` references/results. They do not infer a full-way request from
a section result. Each executed entry binds its request hash, parameter hash,
requested chainage count, budgets, status, native usage, admission witness and
available native work receipt. Skipped sections preserve their factual reason
and do not manufacture a B run. A chainage count without a witness is explicitly
a requested count, not an observed state census.

`b-run-admissions.bin` is an additive bounded diagnostic artifact (schema 1,
at most 4096 entries and 8 MiB). The work vector and this companion are assembled
before the schema-3 numerical declaration binds both hashes and the frozen
input. Ordinary replay validates the declaration, child/cumulative budgets and
witnesses, then compares the ordered companion with actual reconstructed native
runs. Rebinding checksums alone cannot validate a swapped or foreign request.
Existing numerical declaration schemas and old result constructors stay readable.
Both new Hybrid parent bindings and inventory-policy interval payloads require
schema 3; a historical declaration cannot remove that requirement. Historical
standalone V4 schema-2 captures remain executable.

Artifact serialization bounds are independent of runtime attempt-memory leases;
they neither measure the entire heap nor establish owner cleanup. Native execution
retains its existing checked budgets, cancellation and owner boundaries.
Replay companion construction has a separate owner/probe path. It reserves
bounded preflight bookkeeping and a checked conservative bound for actual entry,
UTF, work-JSON, request-hash scratch and simultaneously live buffer/artifact
copies before allocating them. The returned artifact keeps its charge through
the caller's remaining owner lifetime. Requests and serialization checkpoint at
entries and bounded chunks; metadata denial and cancellation retain their typed
failure and do not rewrite existing native work. The verified input digest is
reused from the frozen artifact/index rather than re-encoding source imagery.
The replay caller translates only a ledger denial from that owned companion
call, after its existing owner closes, to `TraceMemoryLimitException` with
stage `b-run-admissions` and the actual completed batch pair/score counts.
This is metadata capacity refusal, not a solver failure or successful empty run.
Cancellation and other-stage exceptions retain their original type/identity.
The package-private test overload creates the owner from its supplied ledger;
its metadata probe factory runs only at the new-parent boundary after native
work. Production uses `NONE`; there is no engine selector or global hook.

## Incomplete attempts

Early and partial cancellations retain actual available usage and diagnostics.
Missing native admission/work evidence produces `execution-unattested` and blocks
strict result replay; it does not block reading the frozen diagnostic archive.
There is no invented zero solver receipt after actual work. A zero-native-section
Hybrid attempt records the actual empty work vector and explicit skipped sections.
Conditional compact uncertainty remains review-required through Hybrid assembly;
section posterior values never become a full-way posterior.

This source integration does not qualify private high-case inference, wide-model
quality, repeated latency, GUI cancellation, or the final integrated release.
Those gates and critical independent source review remain separate.
