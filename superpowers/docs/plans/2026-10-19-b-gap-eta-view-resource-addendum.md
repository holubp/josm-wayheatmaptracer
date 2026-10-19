# Cached eta-view resource addendum — Task4

The approved finite family has seven positive eta values. A scene builds its
gap table once and reuses it across those settings; eta0 bypasses the table and
potential completely. Cache the seven immutable potential views at construction,
so selecting an eta and reading a transition allocate nothing.

The Task2/3 implementation's 512 B table-wrapper allowance includes one 32 B view.
Replace that allowance with 784 B: `512 + 8 - 32 + 7*32 + A(7,8)`. The new
table-instance reference adds 8 B and the reference array is 72 B under the same
existing caller-declared accounting convention. The complete wrapper census is
`112 + 16 + 168 + 128 + 64 + 224 + 72 = 784` bytes. This is an explicit additional
272 B, not spare capacity or exact JVM telemetry.

Task4 must include this delta before allocation in table preflight, retained
ownership, independent small-payload census, and any affected public bound.
Construction work must count initialization of the seven views. Selecting and
using a cached view preserves actual lookup counts and rejects closed tables.
Failure releases the complete attempt; no smaller graph, fallback or cap change.

For N profiles and P adjacent-state pairs, explicitly add
`64 + 2*P + 7*(2 + 3*N) + 8*N` to the existing reserved work upper bound before table
claim/allocation. This covers fixed view setup, finite-value checks and seven
positive-setting profile-binding scans, plus the bounded dimension-count pass
that derives P. Charge that pass as well. Check the unchanged BUILD_UNITS cap;
do not presume spare units in an old block. A tuple that no longer fits refuses
atomically. Qualification still requires all actual registered cases to finish
under the unchanged cap, rather than treating a loose worst-case bound as success.

The original accepted Task0 proof/contract and source-contract fixture remain
byte-identical. This integration addendum requires the scoped independent Task4
review and inclusion in the new Task7 source inventory before empirical launch.
The 64 MiB experiment cap, original solver guards and all qualification gates remain.
