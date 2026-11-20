# G004 B source pin amendment

The G006 release freeze continues to pin the exact RC15 B source set and all 58 Legacy source files. G004 adds one reviewed exception for the B entry point `src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticTraceEngine.java` because the approved adapter requires actual B entry and owned-memory behavior.

For that path only, the RC15 digest `1dd73d6872266b40b69243ad728db12275010d9407d9fce322d4c97596cb896e` is superseded by the reviewed G004 digest `ecf57b8bd8ad06f89afa304111837c9832d1f51c519b1cc8909611e938efc28e`. The source change was reviewed in commit `4c847a4efbe31dfbbd3a3e3de3ed314e4fdae608`. The named overlay is fixed in the guard; it does not accept a digest computed from the current file.

This contract amendment preserves the RC15 B mathematics, source set, other B file hashes, all 58 Legacy hashes, and the existing numerical, work, plan, speed, repetition, production-observer, and cancellation gates. The approved Java review and tests are separate evidence; passing the source pin verifies bytes only and is not behavior proof.

The amendment responds to the public CI Python failure `scripts/tests/test_g006_release_source_freeze.py::ReleasedSourceFreezeTest.test_b_source_set_and_bytes_match_rc15`: the approved G004 entry point changed, while the freeze still demanded its RC15 bytes. This is a one-path reconciliation, not a general relaxation of the release freeze.
