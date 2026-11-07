package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BenchmarkDecisionComparatorTest {
    @Test void v4ContractBindsExplicitScopeAndRejectsOtherPolicies() {
        var contract = BenchmarkComparisonContract.parse("b-inventory-v4-numerical-readonly-v1");
        var rc6 = BenchmarkComparisonContract.RC6_B_PARAMETERS;
        var v4 = "68d3b706f28674e60cb45646be640e4d83d1c14200c93987b4c0a42dcceb5fda";
        var scopes = BenchmarkComparisonContract.ComparisonScope.values();
        for (var scope : scopes) {
            String first = scope == BenchmarkComparisonContract.ComparisonScope.CANDIDATE_REPEAT ? v4 : rc6;
            String second = scope == BenchmarkComparisonContract.ComparisonScope.BASELINE_REPEAT ? rc6 : v4;
            assertDoesNotThrow(() -> BenchmarkComparisonContract.requireApprovedParameters(contract, scope, first, second));
            assertThrows(IllegalStateException.class, () -> BenchmarkComparisonContract.requireApprovedParameters(
                    contract, scope, first, "7b3b1d6108998233eae015e4bcf398376a9d7a37f39393a085178f39ae97aeb6"));
        }
        assertThrows(IllegalStateException.class, () -> BenchmarkComparisonContract.requireApprovedParameters(rc6, v4));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkComparisonContract.requireApprovedParameters(contract, null, rc6, v4));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkComparisonContract.ComparisonScope.parse("same-build"));
    }

    @Test
    void scalarComparisonRejectsOneUlpAndSignedZero() {
        assertDoesNotThrow(() -> BenchmarkDecisionComparatorMain.requireRawValuesEqual(
                new double[] {1.0, -0.0}, new double[] {1.0, -0.0}, "hot"));
        assertThrows(IllegalStateException.class,
                () -> BenchmarkDecisionComparatorMain.requireRawValuesEqual(
                        new double[] {1.0}, new double[] {Math.nextUp(1.0)}, "hot"));
        assertThrows(IllegalStateException.class,
                () -> BenchmarkDecisionComparatorMain.requireRawValuesEqual(
                        new double[] {0.0}, new double[] {-0.0}, "hot"));
    }
}
