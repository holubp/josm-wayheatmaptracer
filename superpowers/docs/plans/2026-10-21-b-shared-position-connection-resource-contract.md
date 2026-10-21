# Shared-feature Task0 resource/arithmetic contract — REVIEW CANDIDATE

Plan: `2026-10-21-b-shared-position-connection-implementation-plan.md`, original
approved SHA256 `8d2696decb532b860fed4cd4315771c4e5be28123ec68eff5a516d1ad6555974`;
reviewed proxy-unit clarification SHA256
`04713a960da0cb2f81b6d8d780d0e594d1e46c3ab8a38c147d587eb218245ac4`.
Latest reviewed public/internal magnitude clarification plan SHA256
`b4b1697a08036dec0d3706655e05dc26882a9743ceb0d62db652d8fc6b398568`.
Spec: `2026-10-21-b-shared-position-connection-numerical-design.md`, SHA256
`ba6f4eca9614849beaf585dee5ae6b6864d5ded4c4353bce263cae4ac033a6ad`.
Source baseline: `ead91cf120082950944f00821e412a28cb101317`.

This document distinguishes checked algebra from existing-source facts and from
prospective implementation obligations. This is a **Task0 proof-contract candidate
requiring independent review**. A successful worksheet command or matching digest
is not execution eligibility or proof of an
unimplemented Java helper. No capture, profiles, inference or empirical evaluation
was used to derive these numbers. Existing source/authorities/caps are unchanged.

## 1. Existing-source facts and independently pinned bytes

Paths below start at `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/`.

| Source | Existing-source fact | SHA256 |
| --- | --- | --- |
| `service/tracing/AttemptMemoryLedger.java:20–26,71–94` | production root256MiB, object/array header16, alignment8, reference8; explicit experiment root remains64MiB | `94f8d0cdf4cc914ee295e82160764a5f330e6321af9bd2a1637a9b4f61a85b6b` |
| `model/ScalarEvidenceField.java:81–120` | finite/outside/support checks; fixed4 valid vertices; original subtractive bilinear grouping; no missing-data renormalization | `136c84e5e504145863c5f407cbe27915f2507b0f0287213fc8d79a7dc3be0b1f` |
| `model/MetricRegion.java:144–207,233–295,484–519` | event bound2+2e, endpoints first, bounded growth and owned sort, original epsilon predicates/midpoint tests, finally-close scratch | `02820875cbf59e1792c9a16c73666255410973f7574ac70e93b39d965355f697` |
| `service/tracing/probabilistic/ProbabilisticInference.java:2137–2140` | deepArrayBytes(r,c,w)=A(r,8)+r*A(c,w) | `cf44e2099bd2b3f233b4aefa5e391598d73fcf1194c3966929ebd6f4cce7d344` |

The verifier authenticates the first3 actual source files before deriving a
report. Its formulas mirror the stated fourth fact but do not load/execute Java.
The ledger expressly excludes recursive owner bookkeeping from payload charges;
none of these numbers is a whole-JVM-heap claim. Future classes/leases/arrays are
not present, so their eventual field inventories remain an explicit source audit.

## 2. Exact rational arithmetic pins (proved independently)

For response `[0,1/2,1]`, gain4, L0=1:

| Cell widths | A | H | q0 / qm | Density p | Weighted density sum |
| --- | --- | --- | --- | --- | --- |
| `[1,1,1]` |3/2|6|1/7,6/7|`[1/21,1/3,13/21]`|1|
| `[1/10,1/10,1/10]` |3/20|3/5|5/8,3/8|`[25/12,10/3,55/12]`|1|

The latter densities exceed1 and legitimately give negative unary logarithms.
No density/probability floor is inserted. For background1/50, nuisance1/25,
impulse2/25, excess is1/50. The separable B3 centre coefficient is(2/4)^2=1/4,
so excess-before-B3 gives1/200. Filtering the raw plane first gives7/200,
which is below b+N=3/50 and therefore falsely gives0 after excess subtraction.
The verifier/test distinguish these two orders; this pin is not a rendered test.

For D(u,v)=u+v+2uv, the diagonal is2t+2t². Simpson's t-integral is5/3,
so the metric chord integral is(5/3)*sqrt2. The rational coefficient is exact;
this does not prove a binary64 sqrt/hypot/log or accumulated error envelope.
For0<=r<=epsilon, A<=epsilon*W, H<=gain*epsilon*W/L0 and qm<=H. Context
weights in[0,1] preserve a nonnegative upper envelope. These are real-arithmetic
weak-signal statements; no extractor-rank/MAP/noisy-image theorem follows.

## 3. Exact-integer operation DAG bit-length derivation

Finite binary64 represented on the common2^-1074 scale has integer magnitude
strictly below2^2098. The following ceilings use `bits(a*b)<=bits(a)+bits(b)`
and `bits(a±b)<=max(bits(a),bits(b))+1`; sign is separate. Source matrix and
endpoint doubles must first be independently finite. The determinant must be
nonzero. Indices<=191401 require at most18 bits.
Every public API checks operand and conservative result absolute-magnitude bits
before allocation. Java signed `bitLength()` is insufficient: -2^16384 has
bitLength16384 but absolute magnitude16385, i.e.513 limbs. Addition/subtraction
use max(magnitude bits)+1, multiplication uses their sum, nonnegative left shift
uses magnitude bits+shift (zero stays zero), and division bounds quotient by the
dividend and remainder by the divisor. Refuse an unprovable or >16384 result
before the call, never after it. Internal audited JDK temporaries may exceed
512 limbs within the separate1024-limb slot bound.

| Expression | Magnitude bits at most | Reason |
| --- | --- | --- |
| encoded operand |2098|largest finite binary64 scaled by2^1074|
| coordinate-origin difference |2099|one subtraction|
| shared inverse determinant |4197|difference of two2098×2098 products|
| inverse numerator |4198|sum of2098×2099 products|
| endpoint numerator difference |4199|one subtraction|
| face event numerator |4216|max(4198,4197+18)+1|
| face event denominator |4199|endpoint difference|
| fresh midpoint numerator |8416|4216+4199+1|
| fresh midpoint denominator |8399|2*4199+1 (factor2 included)|
| source fractional coordinate/cell subtraction |12617|max(4198+8399,4199+8416)+1, then cell-index subtraction|
| conversion numerator, additional1074-bit shift |13691|12617+1074|

Thus the stated DAG's largest listed magnitude13691 is below512*32=16384.
Cross-comparing face fractions uses at most4216+4199=8415 bits; sign and
zero/one comparisons of the final coordinate use its existing common denominator,
not a second arbitrary12617×12617 crossproduct. Such a crossproduct would need
25234 bits and is forbidden by this DAG. This distinction must be enforced in
the future helper; a generic rational library does not establish it.

Each interval uses fresh original-endpoint expressions. No cumulative rational
sum/GCD or unbounded decimal precision is allowed. Exact clipping precedes int
floor casts. Equal corner events are coalesced by crossproducts. Interior grid
faces belong to the lower-index cell; outer faces belong to their interior cell.
Source ScalarEvidenceField retains its own original sampling-cell ownership;
this chord ownership rule does not alter or selectively bypass its masks.
Signed-zero stored endpoint bits remain separately retained; equality of their
real coordinates does not authorize replacing their original representation.

**Not established by the bit table:** all live immutable/mutable JDK work arrays,
operation complexity, the prescribed conversion implementation's actual call
count, or the absence of an unlisted expression. These are not consequences of
13691<16384 and cannot be labelled existing-source certified before audit.

## 4. Source-derived count dimensions and conditional U

Use n,M,N=W*H,N0,C0,S,K,E_local,T,Q,P as in the approved plan. Let
R=Q+4E_local and Z=P*(W+H+4). Conservative unknown footprints count in Q;
each R location needs9 real validity/value reads, including outside-component
zero taps. Thus stencil_reads=9R, not9n or9M. No M*N tensor is retained.
Bounds checked separately are n<=81,M<=8192,samples<=401n,S<=96n,
N,N0<=191401,C0<=190404,P<=(n-1)*96² and the existing8M guards.

The closed prospective DAG has these per-body scalar/proxy allowances. Every
block has a fixed instruction-slot ceiling, even when an early refusal executes
fewer slots; there are no data-dependent loops inside a block. Loop multiplicity
is outside the block and enters U. Future Java bodies must prove conformance
against these slots, not merely accept a declared total. This is a pre-code
operation contract, not a statement that those future sources already exist.

| Prospective body | Proposed allowance | Current proof status |
| --- | --- | --- |
| metadata/source binding |256 per T,N0,C0,S,n,M,N,K slot|fixed slot block below; source bilinear has no data loop|
| response |1024 per R, including9 V-plane reads/masks|9 fixed64-slot taps +448 fixed response slots|
| C neighbourhood |128 per candidate, at most81N|fixed integer-offset block; no secondary search|
| D |96N|two fixed log1p API calls and bounded outward enclosure block|
| pair setup |512P|fixed table/admission setup, region work separately charged|
| scalar event/Simpson |4096Z|fixed entry/mid/exit/error blocks; exact API work separate|
| region admission |128P*A_region|opaque proxy, not observed comparisons/JVM operations|
| exact API multiplicity |256(M+R+P)+512Z|fixed call-site ceilings below; no hidden recursive rational reducer|

Primitive slots count arithmetic, finite tests, predicates, index operations and
array reads/writes separately; StrictMath calls and exact calls also have raw API
counters. Counter bookkeeping itself is excluded to avoid recursive counting.
No block measures JVM instructions or CPU time. Each bounded block polls before
entry/after exit; response additionally polls between fixed taps, and4096-slot
event work is divided into<=1024-slot subblocks. No cancellation-free4096 loop.

| Block | Closed scalar slot partition (sum is the stated ceiling) |
| --- | --- |
| metadata256 |32 reads/writes +32 index/length +64 predicate/finite +64 scalar/transform +32 masks/identity +32 control|
| response1024 |9*64 taps +128 coordinates/support +32 B3 +64 tents/products +96 finite/underflow +96 outward error +32 control|
| context128 |16 index +16 read/mask +8 distance +8 weight +8 max/predicate +32 enclosure +8 control +32 explicit checks|
| D96 |32 products/log-call/control slots +32 finite/order +32 outward residual|
| pair512 |128 identity/layout +128 support/status +128 scalar setup +128 checkpoints/checked counts|
| event4096 |1024 event/coordinate controls +512 three D evaluators +256 length/normalization +128 Simpson/sum +1536 propagated enclosures +640 finite/mask/control|

For a bilinear D evaluation at one point: two complements, four nonnegative
weight products, four value products and three additions are13 nominal scalar
operations. Each elementary interval multiplication needs four endpoint products,
min/max comparisons and two outward neighbours, at most28 scalar/index slots;
13*28*3=1092<1536 error slots across entry/mid/exit. The remaining event blocks
cover length and accumulated addition enclosures. A future implementation that
adds another evaluator must either fit an explicit unused slot or fail this DAG
audit before use, not silently increase U. The original source V bilinear API is
evaluated once per analysis vertex; response taps read the frozen V plane.

Exact API call blocks are capped at256 for each mode/response/pair setup and512
per event. A permissible source-conforming split is mode(64 encode +64 join/bound
+128 footprint), response(64 encode +128 projection/comparison +64 ownership),
pair(64 original endpoints +128 inverse/clip +64 comparisons), event(128 event
crossproducts +128 entry/exit coordinates +128 fresh midpoint coordinates
+128 ratio conversion). No exact loop depends on numerical exponent magnitude;
bitlength/shift determines the bounded conversion directly. Public exact calls
are charged before entry and polled before/after; discarded/rejected calls count.

For e original region edges, A_region=(2e+2)*(128*(e+1)+ceilLog2(2e+2)+64).
The source proves event count<=2e+2, at most2e+3 endpoint/midpoint membership
queries, and one intersection test per edge. Its onSegment+ray-crossing body
fits128 registered scalar/API slots per edge (coordinate/index reads, original
min/max bounds, cross/distance predicate, ray arithmetic and control). The
intersection body also fits128 per edge. Thus geometric work is bounded by
128e*(2e+3)+128e=256e²+512e, below128*(2e+2)*(e+1), with256 slots remaining.
BoundedEventList growth/copy is geometric: backing slots<4B, reference copies
<4B, boxed insertion B, fixed controls<32B for B=2e+2, below64B; the original
B*ceilLog2(B) sort proxy is separate. This proves A_region as a registered
source/control proxy, not a comparison, hardware or StrictMath-body count.
Null region means e=0; a present MetricRegion has at least3 edges by its actual
constructor. No constructor validation is rerun inside this admission allowance.
Mandatory cancellation remains the existing API probe plus before/after exact
calls and at most1024 counted new scalar/index operations between polls.

For κ=(2*512+2)^2=1,052,676, the checked proposed U is:

```
1024 +256*(T+N0+C0+S+n+M+N+K) +1024R +128*81N +96N
 +512P +4096Z +128P*A_region
 +(1+κ)*(256*(M+R+P)+512Z)
```

All additions/multiplications, including intermediate totals, must fit signed
Java long or refuse. This formula is evaluated as a **conditional proposal**;
there is no global old G constant and no cap increase. The verifier rejects
substituting old BUILD_UNITS or a different allocation/call-count claim.

### Reviewed unit correction: registered weighted limb-pair proxy

The original plan called κ a BIT-operation block while its input was a32-bit WORD count.
512×512 word products can represent262,144 products, each involving up to
32×32 bit products:268,435,456>1,052,676. This is a dimensional/census
counterexample to treating κ as a universal bit-complexity proof; it is not a
claim that OpenJDK actually executes schoolbook multiplication for that size.
The controller/reviewer resolved this dimensional defect narrowly: κ is a
**registered weighted quadratic LIMB-PAIR PROXY charge per exact API**, not a
bit/word/JVM primitive theorem. Raw API counts remain separate. U is the sum of
the closed scalar DAG/proxy blocks; no hardware-operation or elapsed-time claim
follows. Solver score units and caps are untouched. The original counterexample
is retained to prevent a later report from relabelling this proxy as bit work.
Karatsuba/Toom/Knuth paths and transient arrays still require a separate pinned
JDK source audit; the proxy correction does not discharge that hard gate.

Official immutable-version source anchors for that audit are
[OpenJDK17 BigInteger](https://github.com/openjdk/jdk17u/blob/jdk-17.0.12%2B7/src/java.base/share/classes/java/math/BigInteger.java)
and [MutableBigInteger](https://github.com/openjdk/jdk17u/blob/jdk-17.0.12%2B7/src/java.base/share/classes/java/math/MutableBigInteger.java).
The bounded scratch audit uses the actual retrieved immutable17.0.12+7 sources
(the earlier17+35 web cache miss is not a source proof). BigInteger SHA256
`50c292cec45578f911a309690f990036a946ee09768ed30e4d54fb18a18a95ee`;
MutableBigInteger SHA256
`a9ad677a26ca32d7d39ce4e80197141c5af1b9a8aafa86511b005cc0b843b4b4`.
Missing local src.zip is not a mathematical blocker. These are read-only temp
references, not repository fixtures or a modified JDK.

## 5. Owned layout and source-bound exact-call lifetime contract

A(n,w)=align8(16+n*w), O(f)=align8(16+f); checked before each allocation.
The executable formula reproduces the plan layouts, using conservative global
component/state maxima for local row scratch. It reports source, descriptors,
planes, coordinates, table, exact, row, region and1MiB publication independently.
Omitting source/table/report/exact is a refused claim, not a zero-sized phase.

The plan's exact scratch formula gives
512*(O(64)+A(1024,4))+A(128,8)+4096=2,151,440 bytes.
This funds512 object/array pairs with up to1024 int slots each. The independent
pinned-JDK audit is retained at the PRIMARY observation ledger's
`cross-stream-result-audit/shared-position-jdk17-scratch-review-20261021.md`, SHA256
`490c113363d6b0cf8f5d4f27a5f43b5682ecb0e2f9b0a28da2040d6fa61036e8`.
It proves376 deliberately overlapping library slots:200 multiply/square,
160 nested division and16 wrapper/conversion/reference-array slots. Public
operands/result<=512 magnitude limbs give internal multiplication<=872 limbs
and division normalization<=946, both below1024. It includes serial recursion,
normalization/carry/strip replacements and the fixed initial class phase below
128 slots. This is live retained payload, not total allocations before GC,
JVM/RSS/native-stack or hardware-work assurance. It does not disable intrinsics.

The caller is a fixed slot machine, not a retained expression graph. Its exact
reference array is128 slots; eight fixed wrapper result/alias references add8.
Caller classes must have precisely these maximum simultaneously live bindings:

| Caller live category | Paired slots | Included bindings |
| --- | --- | --- |
| grid/encoding constants |16|affine coefficients, origin, determinant, index constants,0/1|
| canonical edge |16|four input coordinates, origin differences, inverse endpoints, deltas|
| clipping |16|two clipped t fractions and rectangle event comparison temporaries|
| face events |16|current/next x/y event fractions, step/face numerators|
| current coordinates |16|one entry/mid/exit evaluation's common denominator, projections, cell remainders|
| ratio conversion |16|scaled operands, exponent/shift metadata, quotient/remainder, twice-remainder/tie guards|
| expression intermediates |32|at most two operands/results per fixed call, saved crossproducts and branch values|
| fixed output/aliases |8|two quotient/remainder outputs and conservatively duplicated aliases/reference wrappers|
| total |136|128 array bindings+8 fixed references|

Each event evaluates entry, midpoint and exit sequentially: converted results
are primitive doubles, so the preceding point's exact references are cleared
before the next. No mode/cell/pair loop retains an exact result beyond its one
primitive output; descriptors/tables hold only primitive data and borrowed
original identities. No collection, memo, callback or recursion exists in the
caller. The fixed straight-line DAG reserves32 expression slots; after each
node, a value remains only in its named category or is discarded. Every store
to the136-slot capacity is checked before retaining a new value; all slots are
cleared on exit/failure. This is an implementable static lifetime contract to
verify against future Java source, not a measured future implementation peak.
One exact API is active at a time. Therefore caller136+library376=512 slots.
The initialization phase is sequential and fits this same bound, not an extra
unfunded phase. Any new allowlisted API/body/live binding requires proof review.

The allowlist is valueOf(long), add/subtract, multiply (including identity square),
divideAndRemainder with nonzero divisor, nonnegative shifts, abs/negate, signum,
compareTo, bitLength, testBit and small quotient longValue. General decimal/string
conversion, GCD/modPow, arbitrary parallel arithmetic and unbounded caches are
excluded. The quotient used for rounded binary64 conversion is<=54 bits.
Each opaque call polls immediately before/after; finite admitted operand lengths
bound loops/call count, not wall time or an interrupt inside JDK arithmetic.
Bind to audited OpenJDK17.0.12+7 paths or a separately reviewed source-equivalent
runtime; unsupported runtime is typed capability refusal before construction.

Existing MetricRegion bounds require live facade/iterator, old+new backing lists
during growth, boxed events and opaque sort scratch. Its owner finally-closes.
The conservative wrapper also reserves one interpolated MetricPoint and three
polygon/list iterators, separately from the API's existing facade/sort charges.
The verifier conservatively sums the plan payloads simultaneously with actual
declared already-live original bytes; it gives no unproved early-release credit.
These reservations do not borrow hidden capacity. Future source must expose its
actual wrappers/arrays/leases and stay within the prescribed layout; an omitted
wrapper is not excused by a large aggregate charge with no lifetime argument.

Numerical phases must eventually release source/descriptors/V/F/C after local
rows/D are frozen, D/exact/coordinate construction scratch before inference,
and hold the1MiB publication owner through atomic output commit. Root0 is an
actual host observation after close. The current worksheet creates no owners,
so it supplies no measured peak/root0 or solver/work receipt.

## 6. Numerical enclosure contract with explicit refusal

J/error are dimensionless; unknown length/error are metres. With L0=1m the
proposed acceptance gates are Jerror<=2^-32*max(1,length/L0) and unknown-error
<=2^-32*max(L0,length). They are runtime acceptance predicates, not a guarantee
every finite input can pass. Any negative deficit, nonfinite
intermediate or positive product underflow is refusal; log-rounding coalescence
may instead be valid D=0 with its computed residual enclosure. No algebraic
rewrite/clamp, physical tolerance change or outcome-tuned precision is permitted.

Immutable17.0.12+7 StrictMath source SHA256
`1a52f5cdb3cf7d902e0b1e1ba4733435a434fb4c5c8a50d18e5f1c6ad98c7661`
documents correctly rounded sqrt at289. Math SHA256
`ca85b54e20ee35896b675fef59f201dbc2c56c263f9c2a89cfa10285090bb1d1`
documents hypot within1ulp at2154 and log1p within1ulp at2231, with delegation
to StrictMath at2164/2239. These bound exact-real API results, not exact bit
equality with a mathematical transcendental. The capability must bind an audited
JDK implementation/version; unsupported runtime is unavailable before feature
construction. No network/runtime source recovery inside an attempt.

Treat actual authoritative V binary64 samples as exact input values. For each
new elementary +,-,*,/ operation, interval endpoints enclose exact real results:
evaluate endpoint candidates in the frozen grouping and step outward; for a
product use all4 endpoint products, then outward min/max. Known positive exact
product rounding to0 is numerical refusal. For sqrt, one adjacent representable
each side encloses its correct-rounding error. For hypot/log1p use two adjacent
representables each side: at a binade transition the local spacing can double,
so one neighbour alone is not a universal1ulp enclosure. Nonfinite endpoints
are refusal; divisions require a denominator interval strictly on one side of0.

The D expression/order is unchanged. C>=F and monotone log1p justify intersecting
its real-error enclosure with[0,+infinity], but nominal D is never clamped.
C>F may coalesce to nominal0 within the residual enclosure. Carry intervals
through nonnegative bilinear weights, length and fixed Simpson grouping; sum
in canonical increasing t. Exact cell/event identities, not interval epsilons,
own geometry. At the end, compare the outward error bound to the stated J/length
gates. If the error is too large, refuse the whole feature; no precision retry,
fallback, forced floor or promise of success. This establishes a sound error
policy without pretending that all finite/exotic inputs have achievable bounds.

## 7. Executable use and completion boundary

Run `python3 -m pytest scripts/tests/test_b_shared_resource_contract.py -q`
and repeat with `python3 -O -m pytest ... -q`. Tests use independent Fractions,
exact count/type/overflow boundaries and controlled claim mutations. They do
not certify future source or an actual cohort. The CLI takes one closed flat
counts JSON; reports say PROOF_CANDIDATE_REVIEW_REQUIRED/entryEligible=false.
`require_entry` always refuses, including a caller-forged eligible flag.

The pre-code contract now includes closed DAG, fixed caller lifetimes, pinned
JDK transient audit, integer/FP policies and simultaneous payload equations.
Independent review must accept these before Task1. Future implementation
DAG/layout/checkpoint conformance remains a mandatory source/test audit; no
existing or future numerical outcome is inferred from this contract. Numerical
acceptance can refuse unachievable enclosures rather than promise every input
fits. No source/capture/solver implementation, CI or empirical permission follows
from the worksheet. Original300/360s ceilings,
64MiB/128M/96state/8M units and all authorities remain unchanged.
