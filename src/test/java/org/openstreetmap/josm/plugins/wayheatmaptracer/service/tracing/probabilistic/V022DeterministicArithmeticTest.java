package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ImageOrientationDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Exact backend admission plus independently grounded numerical/graph controls. */
class V022DeterministicArithmeticTest {
    // Java 17 StrictMath specifies fdlibm 5.3 for these functions. Math permits
    // platform implementations. Correctly rounded sqrt and basic operations
    // deliberately do not appear here.
    private static final Set<String> FDLIBM = Set.of("sin", "cos", "tan", "asin", "acos",
        "atan", "exp", "log", "log10", "cbrt", "atan2", "pow", "sinh", "cosh",
        "tanh", "hypot", "expm1", "log1p");

    @Test
    void bInferenceCannotLinkPlatformVariableTranscendentals() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : List.of(EvidenceModelParameters.class,
                ProbabilisticInference.class, ProbabilisticObservationModel.class,
                ProbabilisticProfileFactory.class, ProbabilisticProfile.class, InferenceProfile.class,
                LongitudinalModeReliability.class, MetricPoint.class,
                ImageOrientationSupport.class, ImageOrientationDescriptor.class,
                ImageCostField.class, FinalGeometryEvaluator.class, ModernTracePipeline.class)) {
            inspect(type, violations);
        }
        assertTrue(violations.isEmpty(), () -> "B inference links platform-variable operations: " + violations);
    }

    @Test
    void numericalPolicyIsPartOfDefaultParameterIdentity() {
        assertTrue(EvidenceModelParameters.defaults().version().contains("java17-fdlibm53-v1"));
        assertTrue(EvidenceModelParameters.withoutShapeTerms().version().contains("java17-fdlibm53-v1"));
    }

    @Test
    void referenceBackendMatchesIndependentAnalyticControlsExactly() {
        // Hex doubles independently rounded from 100-digit Decimal exp/log/sqrt
        // and the analytic pi/4 and 3-4-5 triangle. These are public, synthetic
        // operands, not a host-Math comparison or a private capture expectation.
        bits(0x1.78b56362cef38p-2, StrictMath.exp(-1.0));
        bits(0x1.62e42fefa39efp-1, StrictMath.log(2.0));
        bits(0x1.921fb54442d18p-1, StrictMath.atan2(1.0, 1.0));
        bits(5.0, StrictMath.hypot(3.0, 4.0));
        bits(0.0, StrictMath.sin(0.0));
        bits(1.0, StrictMath.cos(0.0));
        bits(0x1.6a09e667f3bcdp0, Math.sqrt(2.0));
        bits(0x1.0c152382d7365p-1, EvidenceModelParameters.defaults().turnScaleRadians());
    }

    @Test
    void referenceExpReproducesPublishedFdlibmIncludingUnderflowAndNonNearestRounding() {
        // exp(1) demonstrates why a correctly-rounded Decimal oracle cannot
        // substitute for the specified fdlibm algorithm. No tolerance is used.
        bits(0x1.5bf0a8b14576ap1, Fdlibm53ExpReference.exp(1.0));
        for (double exponent : new double[] {Double.NEGATIVE_INFINITY, -800.0,
                -745.0, -744.0, -710.0, -700.0, -541.375, -143.25, -20.0,
                -1.0, -0.5, -0.1, -0.0, 0.0, 0.1, 0.5, 1.0, 20.0,
                700.0, 710.0, Double.POSITIVE_INFINITY, Double.NaN}) {
            bits(Fdlibm53ExpReference.exp(exponent), StrictMath.exp(exponent));
        }
    }

    @Test
    void productionPosteriorUsesReferenceExpForPublicLowProbabilityAlternatives() {
        var profile = V022ProbabilisticInferenceTest.profile(0,
            new double[] {-2, -1, 0, 1, 2}, new double[] {1, 1, 1, 1, 1},
            new double[] {0.0, 1.0, 20.0, 143.25, 541.375},
            new String[] {"a", "b", "c", "d", "e"});
        var result = new ProbabilisticInference().solve(List.of(profile),
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());
        for (var path : result.rawPaths()) {
            bits(Fdlibm53ExpReference.exp(-path.energy() - result.logPartition()),
                path.conditionalPosteriorMass());
        }
    }

    @Test
    void oneUlpEnergyDifferenceIsPreservedAndNeverRoundedToATie() {
        var profile = V022ProbabilisticInferenceTest.profile(0, new double[] {-1, 1},
            new double[] {1, 1}, new double[] {Math.nextUp(1.0), 1.0},
            new String[] {"left", "right"});
        var result = new ProbabilisticInference().solve(List.of(profile),
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());
        assertArrayEquals(new int[] {1}, result.mapPath().orElseThrow().stateIndices());
        bits(1.0, result.rawPaths().get(0).energy());
        bits(Math.nextUp(1.0), result.rawPaths().get(1).energy());
        assertEquals(2, result.rawPaths().size());
    }

    @Test
    void fullUniformGraphRetainsAllStatesTransitionsGeometryAndLexicalTies() {
        List<InferenceProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            profiles.add(V022ProbabilisticInferenceTest.profile(index,
                new double[] {-1, 0, 1}, new double[] {1, 1, 1}, new double[] {0, 0, 0},
                new String[] {"left", "center", "right"}));
        }
        var result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());
        // 3 x 3 pairs at each of two stages; 3^3 transitions in each direction.
        assertEquals(18, result.evaluatedPairVisits());
        assertEquals(54, result.evaluatedTransitions());
        assertEquals(27, result.rawPaths().size());
        int ordinal = 0;
        for (int first = 0; first < 3; first++) {
            for (int second = 0; second < 3; second++) {
                for (int third = 0; third < 3; third++) {
                    var path = result.rawPaths().get(ordinal++);
                    assertArrayEquals(new int[] {first, second, third}, path.stateIndices());
                    assertEquals(List.of(new MetricPoint(0, first - 1),
                        new MetricPoint(1, second - 1), new MetricPoint(2, third - 1)), path.points());
                    bits(0.0, path.energy());
                }
            }
        }
    }

    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual),
            () -> Double.toHexString(expected) + " != " + Double.toHexString(actual));
    }

    /** Reads linked method owners from classfiles; no source-text or host backend assumptions. */
    private static void inspect(Class<?> type, List<String> violations) throws IOException {
        try (var resource = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (resource == null) throw new IOException("Missing classfile for " + type.getName());
            var input = new DataInputStream(resource);
            if (input.readInt() != 0xcafebabe) throw new IOException("Invalid classfile");
            input.readUnsignedShort(); input.readUnsignedShort();
            Object[] pool = new Object[input.readUnsignedShort()];
            List<int[]> methods = new ArrayList<>();
            for (int index = 1; index < pool.length; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[index] = input.readUTF();
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> { input.readLong(); index++; }
                    case 7, 8, 16, 19, 20 -> pool[index] = input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        int[] pair = {input.readUnsignedShort(), input.readUnsignedShort()};
                        pool[index] = pair;
                        if (tag == 10 || tag == 11) methods.add(pair);
                    }
                    case 15 -> { input.readUnsignedByte(); input.readUnsignedShort(); }
                    default -> throw new IOException("Unknown constant-pool tag " + tag);
                }
            }
            for (int[] method : methods) {
                String owner = (String) pool[(Integer) pool[method[0]]];
                String name = (String) pool[((int[]) pool[method[1]])[0]];
                if (owner.equals("java/lang/Math") && FDLIBM.contains(name)) {
                    violations.add(type.getSimpleName() + "." + name);
                }
                if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath"))
                        && (name.equals("toRadians") || name.equals("toDegrees"))) {
                    violations.add(type.getSimpleName() + "." + name);
                }
                if (owner.equals("java/util/stream/DoubleStream")
                        && (name.equals("sum") || name.equals("average"))) {
                    violations.add(type.getSimpleName() + ".DoubleStream." + name);
                }
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) inspect(nested, violations);
    }
}
