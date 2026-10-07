# Exact bounded B solver: warmed replay measurements

Measured on the current Termux/Android host, using Java/Gradle and the production
`Format15ReplayRunner` at `FINAL_GEOMETRY`. This is computation replay, not a
whole ordinary-action capture-to-preview benchmark or user-device runtime claim.

Baseline source snapshot: `b380bec011bb7a9eb35f15434c30e17f46ea8f91`.
Optimized source: worker `bdc9e13784eb9eebe54d93b94cae49ea2206b21f`, integrated
as `82ea716`; `ProbabilisticInference.java` SHA-256
`dd68fdfad6159ff97efb8c626a08058a4840f433bc07b08cbc124b668022f635`.
Each case ran once for warm-up and three measured repetitions in the same JVM;
baseline and optimized runs were sequential, without competing Gradle suites.

| Capture suffix | Baseline total median | Optimized total median | Total speedup | Baseline K-best median | Optimized K-best median | K-best speedup |
|---|---:|---:|---:|---:|---:|---:|
| `1791322700867` | 116790.344539 ms | 36935.204622 ms | 3.162× | 97999 ms | 17160 ms | 5.711× |
| `1791322862816` | 135289.001198 ms | 41129.529776 ms | 3.289× | 112679 ms | 18585 ms | 6.063× |

All measured scalar and final fingerprints are identical across versions and
repetitions and match the previously checked archive final fingerprints.
The optimized physical counters report 96329424 extension descriptors versus
6141808 ancestry records for the first case, and 103525232 versus 6604272 for
the second. These are 15.68× and 15.68× respectively. Historical source allocated
ancestry on every extension; historical binaries did not export these counters,
so this allocation comparison is source-derived, not a fabricated old counter.

The private harness and logs remain in ignored `build/v022/rc6` directories of
`v022-kbest-baseline` and `v022-usable-kbest` under the Termux temporary root.
Command in each worktree:

```sh
sh ./gradlew --no-daemon -I build/v022/rc6/kbest-bench.init.gradle \
  runKBestFrozenBenchmark \
  --args='/storage/emulated/0/GitHub/josm-slide/last-alignment-diagnostics-1791322700867.zip /storage/emulated/0/GitHub/josm-slide/last-alignment-diagnostics-1791322862816.zip' \
  --console=plain
```

Baseline log records `BUILD SUCCESSFUL in 17m 42s`; optimized command reports
explicit exit 0 and `BUILD SUCCESSFUL in 5m 46s`. Independent source review and
51 focused tests cover numerical ordering, retained-prefix equivalence,
cancellation, logical budget order and memory-owner accounting.

Task 4's solver/replay objective is met. Its whole ordinary-action cached-source
latency, acquisition/finalization breakdown, cold/network latency and cancellation
response checks remain open. Neither this result nor matching replay fingerprints
certifies alternative-search completeness or full edit-plan replay.
