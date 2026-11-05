# Legacy Fast Smoother Experiment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans; implement the checked steps without delegation.

**Goal:** Test one cheap constraint on existing smoothing-induced darkening; do not attempt to repair an unrepresentable raw fold.

**Architecture:** A named package-private experiment reuses existing seeds, DP and anchoring once, replacing only the four-pass smoothing update. Public/default tracking and its four-argument smoother remain untouched.

**Tech Stack:** Java17/JUnit5; existing raster fixtures and cached native replay.

**Spec:** Independent `build/private-drasov-resume-20261105/legacy-fast-heuristic-review.md`; approved Drasov design and latest fast-heuristic user direction.

**Code reference:** d1ba6e5d11f4f00c2bcd212d1c23c28438cccab8 ordinary methods. **Implementation parent:** current controller e8d69ba2ef2d8faf1f0d79681a6844e27a7ca2af, a separate documentation branch, not a later d1ba code baseline. Verify its ordinary track/smoother bodies against d1ba; preserve removed experimental APIs and existing edits. Never restore the whole d1ba tracker. Default/named tests and timing use this same implementation parent.

## Short design: COHERENT_NATIVE_SUPPORT_V1

Future production edit: `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/RidgeTracker.java` only. Add package-private `trackCoherentSmoothingExperiment(List<CrossSectionProfile>,double,CancellationProbe)`, returning immutable `CoherentSmoothingExperimentResult(List<CenterlineCandidate> candidates, CoherentSmoothingCounters counters)`; its constructor uses List.copyOf. `CoherentSmoothingCounters` has six nonnegative primitive longs: orderedSamplesVisited, peakComparisons, certificateRows, bracketComparisons, trials, changedUpdates. Counting stays invocation-local. Reuse `bestPathForSeed`, `anchoredPath`, projection/evidence/deduplication once per seed. Call a private experimental `smoothOffsets(..., EvidenceBank, Counters, CancellationProbe)`; no optional solver, retained tracker state, preference, UI/default, sampling, cleanup or second tracking pass.

For each profile, validate immutable `intensitySamples` once: at least2 samples, strictly increasing finite offsets, finite raw/B3/B5 values in[0,1], every insideRaster=true and anchorWithinRaster=true. Cache eligibility and maximum adjacent offset gap h. This is **retained sampling pitch**, not ground metres/native pitch or the Legacy normalization argument. Invalid/unordered/empty rows receive no protection. Use direct lower-bound binary bracketing; interpolate each channel within its valid bracket; S(x)=min(raw,B3,B5). Exact sample hits use that sample. No per-trial sorting, copying, allocation or ProfileIntensityInterpolator.

Per seed freeze anchored offsets o. Before forming P=anchor+normal*o, require finite raster anchor/normal components and |hypot(nx,ny)−1|≤1e−9; never renormalize geometry. Cache S(o), selected native peak and P. Offsets, h, H and P distances are sampled-raster pixels; factual chainage is metres used only in dimensionless t, never inferred from raster distance or Legacy normalization. A row requires exactly one nonsynthetic peak within h/2 of o, S(o)≥0.25, and no other peak with intensity≥0.8 times that peak. Unavailable/ambiguous evidence preserves the old update.

For i with five rows i−2..i+2, require all five eligible and factual chainages finite/strictly increasing; compatibility constructors' constant-zero chainages receive no certificate. Let H=max(h) over that window. For j=i−1,i,i+1, let t=(s[j]−s[j−1])/(s[j+1]−s[j−1]); require distance(P[j],(1−t)P[j−1]+tP[j+1])≤2H. This is local coherence, never source-forward ordering or a global turn cutoff.

Within each existing pass compute the old proposal y unchanged. Protect only certified i with |current−o|≤h/2 and available S(current)≥0.5*S(o). Try exactly alpha=1,1/2,1/4, in that order, at z=current+alpha*(y−current); accept the first with |z−o|≤h/2 and S(z)≥0.5*S(o). The frozen reference prevents four successive halvings from eroding support. If all three valid trials fail, retain current. Any unavailable lookup disables protection and uses y. Everything else uses y. Four passes only; no new moving average.

Constants are frozen before private evidence: the public nine-pixel sine bend has sub-two-sample local residual; the isolated10px stray has a much larger residual. Raw/B3/B5 agreement excludes unsupported single-channel evidence. If this distinction fails, reject the experiment rather than tune exceptions on Drasov.

## Checks and experiment steps

- [ ] Add `RidgeTrackerCoherentSmoothingExperimentTest.java`. Intended RED: nine analytic profiles, j=0..8, anchors(4j,−o), normals(0,1), o alternating0/8, calibrated one-raster-pixel-per-metre source chainage j*sqrt(80); independent straight ridge y=0. Integer offsets−25..25 have raw1 only at o, B3/B5 from fixed kernels; native peaks intensity/scaleAgreement1. Assert old alias update enters darkness; experiment retains S≥0.5*S(o) at indices2..6. Test raw/anchored parity through focused reflection: compute their shared output once against frozen expected offsets, feed those same immutable lists directly to both smoother variants, and verify inputs unchanged. Named-entry integration uses the unique-peak oracle; no second track inside either entry or raw/anchored timing payload. Label analytic, not a real-sampler proof.
- [ ] Snapshot existing `RidgeTrackerRasterSupportTest` images unchanged:128²,9*sin((x−16)*pi/96), amplitude.95/sigma3 gives apex≥7; straight trace.72/sigma1 interrupted |x−64|≤8 plus stray.98 at(64,74) gives near-gap |offset|≤4. Require the stray certificate false. Run RED, implement minimally, run GREEN; do not manufacture RED from compilation.
- [ ] Test exact bracket hits, invalid/duplicate/reversed samples, missing/ambiguous evidence and backoff order. Run existing tracker/profile-integrity/raster/PhysicalPathContinuity controls, including opposite150° arc, switchback/hairpin, strong/weak parallel ridge, invalid support and collinear insertion. Preserve fixed/shared/tagged anchors, raw diagnostics and Move node identities. No command changes.
- [ ] Count ordered samples visited, peak comparisons, certificate rows, bracket comparisons, trials and changed updates. With seeds b≤3: order visits=sumS once; support lookups≤17bN; trials≤12bN; O(N) scratch, no per-trial allocation. Cancellation at profile/pass boundaries.
- [ ] Only after public gates, perform one bounded existing cached Drasov native replay; report raw/anchored/final-preview support, topology and profile124. No full solver, acquisition, new weights or private coefficient tuning. If no certificate/improved usable output, stop.
- [ ] Predeclare timing:2 warm-up matched pairs,3 interleaved measured pairs; tracker batches100 calls, cached capture→final-preview one call/pair, same host/JDK/settings. Reject either median ratio>1.10; reject consistent(all3) ratio>1.02. Noisy/inconclusive results do not pass. The10% ceiling is a proposed experiment rejection gate, **not user acceptance of slowdown**.
- [ ] Independent affected review before any adoption. Partial smoothing success is not raw-fold repair, Move qualification, practical performance or completed Drasov quality. Keep default Off; no release/adoption from this packet.
