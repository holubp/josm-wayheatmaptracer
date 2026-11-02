# Accepted-mode threshold receipts and connector minimum

Status: metadata component implemented experimentally and independently reviewed after its accounting fix. Native defaults retain exact measured work/memory parity. Connector admission and continuous branch certification remain unimplemented. Applies only to the experimental connector certificate. Native extraction, geometry, costs and ranking remain unchanged.

## Actual arithmetic

In `LocalScalarProfileExtractor.extractInternal`, lines 459–486 compute `localBackground=max(noiseFloor,peak.leftMinimum(),peak.rightMinimum())`, `prominence=peak.value()-localBackground`, then `threshold=localBackground+localModeShoulderFraction*prominence`. This raw scalar shoulder threshold controls `containingInterval` and the flank brackets. It is the support cutoff proposed below.

`addNestedCenter`, lines 675–680, independently computes `localMaximum=values[peak(values,search)]` and `threshold=localBackground+coreFraction*max(0,localMaximum-localBackground)`. Raw/B3/B5 stages can have different maxima and thresholds. Capture the RAW invocation's actual computed value; never reconstruct it from `RawPeak.value`, `Mode.coreMinimumMeters`, or the filtered stages. The Mode core fields describe the final center-set envelope, not this raw intensity threshold.

## Minimal additive API

Add nested immutable records:

- `RawCoreThreshold(int winnerSampleIndex, double winnerValue, double threshold)`.
- `AcceptedModeThresholdReceipt(Mode mode, RawPeak originPeak, double localBackground, double shoulderThreshold, Optional<RawCoreThreshold> rawCore)`.

Validate nonnull references/Optional, finite scalar values in [0,1], and nonnegative winner index. Do not introduce threshold-order constraints or recompute formulas in constructors. A threshold receipt alone certifies neither position nor two-sided support.

Do NOT change DetailedResult, its fields/constructors, DetailedCollector's fields/layout, or any existing Result/Mode/FlankReceipt/RawCoreReceipt. Default extract/extractDetailed and context-veto entry points retain their original arithmetic, work counts, allocation charges, object layouts and peak retained bytes.

Add the separate immutable wrapper `AcceptedThresholdExtraction(DetailedResult result, List<AcceptedModeThresholdReceipt> acceptedReceipts)` with nonnull validation and List.copyOf. Its supplied receipts uniquely match result.baseline().modes() and result.rawPeaks() by exact object identity, not record equality; reject duplicate/mismatched ownership. The wrapper adds no measurement authority.

Add ONLY this opt-in public method initially:

`AcceptedThresholdExtraction extractDetailedWithAcceptedThresholds(List<Sample> samples, double sourcePitchMeters, EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner, CancellationProbe cancellation, LongConsumer charge)`.

It has the same inputs as the existing full bounded extractDetailed overload. Keep every old public signature and default execution path. A separate optional AcceptedThresholdCollector owns its charged list/probes and wrapper; add NO field to DetailedCollector. Thread a nullable optional collector through private overloads. Existing methods pass null; the off path allocates no additional state and performs no new charged operations. Preserve context-veto scope and semantics.

Only with that collector present, pass a charged scratch probe to the existing RAW addNestedCenter call. Immediately after its existing threshold calculation, record the already computed peakIndex/localMaximum/threshold; do not repeat formulas or alter scans, arithmetic, tie order or center acceptance. B3/B5 receive no probe. Raw early return means Optional.empty. At the accepted `else { modes.add(measured); ... }` branch only, freeze the receipt using the exact measured/detailedPeak objects, existing localBackground/shoulder threshold and captured raw probe. RMS_ONLY/censored candidates produce none. RawCoreReceipt absence remains independent; numeric capture never fabricates its bracket/center provenance.

Charge opt-in scratch, receipt/wrapper/list allocation, copies and work to the existing owner/cancellation scope; transfer/release on the same success/failure boundaries. Preserve existing extraction callers. NativeConnectorEvaluator explicitly invokes the new method. Bind returned probes to their ordered connector/context, actual sample window, heading, native pitch and parameter version; never reuse under another transverse query. N source classifications remain unchanged.

## Exact admission predicate

Use accepted RAW SHOULDER thresholds for the scalar-support envelope. Do not require raw-core intensity everywhere: that would tighten the existing center-set/localization-uncertainty rule. Export raw-core values for provenance only. Separately require the already specified complete/unique mode, measured center-distance/uncertainty, flanks, authority and mutual branch-continuation gates.

Let ordered connector probes be `t_0=0<...<t_m=1`, including native-cell events and the existing maximum half-native-pitch spacing. Each selected accepted mode must have exactly one threshold receipt. Missing/ambiguous receipt is unavailable. For each closed probe slab define the explicit conservative admission envelope `S_k=max(shoulderThreshold_k,shoulderThreshold_(k+1))`. This is a derived policy bound, not a claim that intermediate thresholds were measured or interpolated.

For every slab intersected with every native interpolation cell, require complete strict supercover support and `min I(r(t)) >= S_k`. Within one cell `I(r(t))` is quadratic; evaluate both endpoints and any interior stationary point. The reviewed BilinearSegmentIntegral already performs this calculation: evaluate each slab's actual subsegment and compare its complete minimum to S_k; it traverses all cell pieces. Charge every repeated query/visit. No new raster, integration approximation, epsilon relaxation, global minimum-threshold comparison or endpoint/mean-only acceptance. Equality follows native >= semantics. Zero-length arcs use the transported-direction probe and actual point value with the same threshold predicate; no physical coverage is created.

## Tests and remaining obligation

First baseline the old full extractDetailed and context-veto paths: assert identical Result/Mode values, reported work, allocations and peak retained bytes after the change. Golden native work identities must remain unchanged, not regenerated. Opt-in and default extraction must have bit-exact underlying DetailedResult values; only opt-in pays receipt overhead. Test accepted nonzero-background shoulder/core literals, a raw winner differing from the origin peak, absence on unchanged old APIs, suppressed/censored exclusion, identity mismatch, null/range validation, immutable lists, allocation/work charging and cancellation. For background .2 and raw peak/winner1, expected shoulder .776 and raw core .9360000000000002 (actual binary64 arithmetic) under defaults; first confirm the full fixture actually reaches acceptance.

Connector tests: local slab thresholds versus an incorrectly global bound; a bilinear interior dip with bright endpoints/mean; cell/probe-boundary equality; invalid supercover corner; absent receipt; and center-within-sigma with intensity below core but above shoulder. Retain opposite-arc and strong/weak strand controls.

The conservative slab maximum can reject a genuinely fading ridge. Its preservation/usable-geometry effect must be measured; do not lower thresholds ad hoc. Finite transverse receipts plus this exact scalar inequality do not themselves prove continuous branch identity between probes: the separate branch-certificate obligation remains. No connector admission or experiment-quality success follows from adding these receipts.
