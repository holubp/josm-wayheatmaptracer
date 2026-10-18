package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRasterGrid;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Lazy analytic scene renderer. Holdout values are created only after EvaluationAccess permits them. */
final class BIdentificationScenes {
    private static final double MIN_X = -32.0;
    private static final double MAX_X = 152.0;
    private static final double MIN_Y = -2.0;
    private static final double MAX_Y = 62.0;
    private static final double[] SELECT_FLECK_CENTERS = {13.0, 41.0, 74.0, 106.0};

    private BIdentificationScenes() { }

    static BIdentificationCatalog.Scene render(BIdentificationCatalog.CaseKey key,
            BIdentificationCatalog.EvaluationAccess access) {
        if (access == null) throw new IllegalArgumentException("Evaluation access is required");
        BIdentificationCatalog.CaseDefinition definition = BIdentificationCatalog.definition(key, access);
        if (key.split() == BIdentificationCatalog.Split.HOLDOUT) {
            throw new SecurityException("HOLDOUT values are unavailable before qualified Task5 access");
        }
        if (key.split() == BIdentificationCatalog.Split.INELIGIBLE_DIAGNOSTIC) {
            throw new SecurityException("Retired SELECT diagnostics cannot be materialized");
        }
        access.beforeMaterialization(key);

        double nativePitch = definition.sourcePitchMeters();
        double rasterPitch = nativePitch / definition.oversampling();
        double samplePhaseMeters = definition.phase() * nativePitch;
        if ((definition.key().split() == BIdentificationCatalog.Split.TRAIN
                || definition.key().split() == BIdentificationCatalog.Split.REGRESSION)
                && definition.contrastId().equals("resolved-short-apex-vs-stair-step-alias")
                && definition.side().equals("right")) {
            samplePhaseMeters=((definition.phase()+.5)%1)*nativePitch;
        }
        int width = (int) StrictMath.ceil((MAX_X - MIN_X) / nativePitch) + 2;
        int height = (int) StrictMath.ceil((MAX_Y - MIN_Y) / nativePitch) + 2;
        double[] values = new double[Math.multiplyExact(width, height)];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
                new MetricPoint(MIN_X - samplePhaseMeters,
                        MAX_Y + samplePhaseMeters),
                nativePitch, 0.0, 0.0, -nativePitch);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                MetricPoint point = transform.pixelCenterToMetric(x, y);
                valid[index] = isValid(definition, point);
                values[index] = intensity(definition, point);
            }
        }
        boolean paletteContrast = definition.contrastId().equals("palette-semantic-vs-rendered-source");
        boolean freshSelect = definition.key().split() == BIdentificationCatalog.Split.SELECT;
        String palette = paletteContrast && !freshSelect ? "blue"
                : paletteContrast ? "synthetic-scalar-capability-proxy" : "synthetic-native-scalar";
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                paletteContrast && definition.side().equals("left") && !freshSelect
                        ? EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING
                        : EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, palette,
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        List<MetricPoint> source = new ArrayList<>(List.of(
                new MetricPoint(0.0, 30.0), new MetricPoint(120.0, 30.0)));
        if (definition.reversed()) java.util.Collections.reverse(source);
        var nativeOrigin=transform.pixelCenterToMetric(0,0);
        var inputTransform=SupportedInputRasterTransform.localMetricAffine(frame,
                new RasterPoint(-nativeOrigin.xMeters()/nativePitch,nativeOrigin.yMeters()/nativePitch),
                1/nativePitch,0,0,-1/nativePitch);
        var outputGrid=new MetricRasterGrid(frame,nativeOrigin,1,0,0,-1,rasterPitch,
                (width-1)*definition.oversampling()+1,(height-1)*definition.oversampling()+1);
        // Finer output samples reuse native observations through the live capture producer.
        EvidenceSnapshot evidence=new RasterEvidenceCapture().captureWithMetricScalarSource(
                "b-identification-"+key.id(),new java.awt.image.BufferedImage(width,height,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB),valid,values,
                source.stream().map(frame::toGeographic).toList(),source,inputTransform,outputGrid,
                paletteContrast && definition.side().equals("right")
                        ? EvidenceResolution.renderedOnly(freshSelect ? rasterPitch : nativePitch)
                        : EvidenceResolution.nativeSource(nativePitch,nativePitch),
                25,"analytic-native:"+nativeHash(values,valid),
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,CancellationProbe.NONE);
        return new BIdentificationCatalog.Scene(evidence, source, truth(definition, access));
    }

    private static String nativeHash(double[] values,boolean[] valid) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var buffer=java.nio.ByteBuffer.allocate(9);
            for(int i=0;i<values.length;i++) {
                buffer.clear();buffer.putDouble(values[i]);buffer.put((byte)(valid[i]?1:0));
                digest.update(buffer.array());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static BIdentificationCatalog.Truth truth(BIdentificationCatalog.CaseDefinition definition,
            BIdentificationCatalog.EvaluationAccess access) {
        String contrast = definition.contrastId();
        String side = definition.side();
        if (contrast.equals("blank-uniform-invalid-censored")) {
            if (side.equals("invalid")) return new BIdentificationCatalog.Truth(List.of(
                    new BIdentificationCatalog.LabelRegion(MetricRegion.rectangle(48,5,72,55),
                            BIdentificationCatalog.StructureLabel.UNKNOWN,BIdentificationCatalog.RasterLabel.INVALID,
                            BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE,List.of()),
                    // Deliberately leave transition buffers unlabeled: complete-cell
                    // resampling can invalidate cells beyond the native missing interval.
                    new BIdentificationCatalog.LabelRegion(MetricRegion.rectangle(0,5,40,55),
                            BIdentificationCatalog.StructureLabel.NO_STRUCTURE,BIdentificationCatalog.RasterLabel.VALID,
                            BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE,List.of()),
                    new BIdentificationCatalog.LabelRegion(MetricRegion.rectangle(80,5,120,55),
                            BIdentificationCatalog.StructureLabel.NO_STRUCTURE,BIdentificationCatalog.RasterLabel.VALID,
                            BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE,List.of())));
            var structure = side.equals("left") || side.equals("right")
                    ? BIdentificationCatalog.StructureLabel.NO_STRUCTURE
                    : BIdentificationCatalog.StructureLabel.UNKNOWN;
            var raster = side.equals("invalid") ? BIdentificationCatalog.RasterLabel.INVALID
                    : side.equals("censored") ? BIdentificationCatalog.RasterLabel.CENSORED
                    : BIdentificationCatalog.RasterLabel.VALID;
            return new BIdentificationCatalog.Truth(List.of(new BIdentificationCatalog.LabelRegion(MetricRegion.rectangle(0, 0, 120, 60),
                    structure, raster, BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE, List.of())));
        }
        if (definition.key().split() == BIdentificationCatalog.Split.SELECT
                && contrast.equals("weak-coherent-curve-vs-disconnected-flecks") && side.equals("right")) {
            return new BIdentificationCatalog.Truth(List.of(new BIdentificationCatalog.LabelRegion(
                    MetricRegion.rectangle(0, 0, 120, 60), BIdentificationCatalog.StructureLabel.UNKNOWN,
                    BIdentificationCatalog.RasterLabel.VALID,
                    BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE, List.of())));
        }
        List<List<MetricPoint>> curves = new ArrayList<>();
        if (definition.key().split() != BIdentificationCatalog.Split.SELECT
                && contrast.equals("weak-coherent-curve-vs-disconnected-flecks") && side.equals("right")) {
            curves.add(curve(definition, 0, 21.0, 27.0));
            curves.add(curve(definition, 0, 57.0, 63.0));
            curves.add(curve(definition, 0, 93.0, 99.0));
        } else if (contrast.equals("sparse-crossing-noisy-strands-vs-persistent-parallel")) {
            int branchCount = definition.key().split() == BIdentificationCatalog.Split.SELECT
                    ? (side.equals("left") ? 3 : 2) : (side.equals("left") ? 3 : 2);
            for (int branch = 0; branch < branchCount; branch++) curves.add(curve(definition, branch, 0.0, 120.0));
        } else {
            curves.add(curve(definition, 0, 0.0, 120.0));
        }
        List<List<MetricPoint>> polygons = curves.stream().map(curve -> tubePolygon(curve, 1.5)).toList();
        MetricRegion region = new MetricRegion(polygons);
        BIdentificationCatalog.AssociationLabel association = contrast.equals(
                "weak-coherent-curve-vs-disconnected-flecks") && side.equals("right")
                        && definition.key().split() != BIdentificationCatalog.Split.SELECT
                ? BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE
                : curves.size() > 1
                        ? BIdentificationCatalog.AssociationLabel.AMBIGUOUS
                                : BIdentificationCatalog.AssociationLabel.UNIQUE;
        return new BIdentificationCatalog.Truth(List.of(new BIdentificationCatalog.LabelRegion(region, BIdentificationCatalog.StructureLabel.OBSERVED_STRUCTURE,
                BIdentificationCatalog.RasterLabel.VALID, association, curves)));
    }

    private static List<MetricPoint> curve(BIdentificationCatalog.CaseDefinition definition,
            int branch, double startX, double endX) {
        List<MetricPoint> result = new ArrayList<>();
        int steps = Math.max(1, (int) StrictMath.ceil((endX - startX) / 1.0));
        for (int index = 0; index <= steps; index++) {
            double x = startX + (endX - startX) * index / steps;
            result.add(new MetricPoint(x, centerY(definition, x, branch)));
        }
        return List.copyOf(result);
    }

    private static List<MetricPoint> tubePolygon(List<MetricPoint> curve, double halfWidth) {
        List<MetricPoint> polygon = new ArrayList<>();
        for (MetricPoint point : curve) polygon.add(new MetricPoint(point.xMeters(), point.yMeters() - halfWidth));
        for (int index = curve.size() - 1; index >= 0; index--) {
            MetricPoint point = curve.get(index);
            polygon.add(new MetricPoint(point.xMeters(), point.yMeters() + halfWidth));
        }
        return polygon;
    }

    private static double centerY(BIdentificationCatalog.CaseDefinition definition, double x) {
        return centerY(definition, x, 0);
    }

    private static double centerY(BIdentificationCatalog.CaseDefinition definition, double x, int branch) {
        String contrast = definition.contrastId();
        String side = definition.side();
        if (definition.key().split() == BIdentificationCatalog.Split.SELECT) {
            double f = BIdentificationCatalog.selectCurve(x);
            return switch (contrast) {
                case "weak-coherent-curve-vs-disconnected-flecks", "concentrated-band-vs-diffuse-band",
                     "multiple-physical-pitches-vs-reversed-source-order", "palette-semantic-vs-rendered-source" -> f;
                case "sparse-crossing-noisy-strands-vs-persistent-parallel" -> {
                    double t = x / 120.0;
                    if (side.equals("left")) yield f + switch (branch) {
                        case 0 -> -2.0 + 4.0 * t;
                        case 1 -> 1.4 - 0.7 * t;
                        default -> 2.0 - 4.0 * t;
                    };
                    yield f + (branch == 0 ? -4.0 : 4.0);
                }
                case "resolved-short-apex-vs-stair-step-alias" -> {
                    if (!side.equals("left")) yield f;
                    double u = (x - 60.0) / 6.0;
                    double c = Math.abs(u) < 1.0 ? (1.0 - u * u) * (1.0 - u * u) * (1.0 - u * u) : 0.0;
                    yield f + 4.5 * c;
                }
                case "displaced-strong-corridor-vs-nearby-faint-strand" -> f + 14.0;
                default -> throw new IllegalArgumentException("Unregistered fresh SELECT truth curve");
            };
        }
        double splitShift = splitShift(definition.key().split()) + splitShape(definition.key().split(), x);
        return switch (contrast) {
            case "weak-coherent-curve-vs-disconnected-flecks" ->
                    30.0 + splitShift + 2.0 * StrictMath.sin(2.0 * Math.PI * x / 80.0);
            case "concentrated-band-vs-diffuse-band" -> 30.0 + splitShift;
            case "sparse-crossing-noisy-strands-vs-persistent-parallel" -> {
                if (side.equals("left") && branch == 2) {
                    yield 30.0 + splitShift + 4.0 * StrictMath.sin(2 * Math.PI * x / 48.0);
                }
                yield (branch == 0 ? 26.0 : 34.0) + splitShift;
            }
            case "resolved-short-apex-vs-stair-step-alias" -> {
                double halfLength=definition.variantId().startsWith("six-metre-apex")?3:6;
                yield 30.0 + splitShift + 3.0 * Math.max(0.0, 1.0 - Math.abs(x - 60.0) / halfLength);
            }
            case "displaced-strong-corridor-vs-nearby-faint-strand" ->
                    (side.equals("left") ? 39.0 : 27.0) + splitShift;
            case "multiple-physical-pitches-vs-reversed-source-order" ->
                    30.0 + splitShift + 0.04 * (x - 60.0);
            case "palette-semantic-vs-rendered-source" -> 30.0 + splitShift;
            default -> 30.0 + splitShift;
        };
    }

    private static double intensity(BIdentificationCatalog.CaseDefinition definition, MetricPoint point) {
        String contrast = definition.contrastId();
        String side = definition.side();
        double x = point.xMeters();
        double y = point.yMeters();
        if (definition.key().split() == BIdentificationCatalog.Split.SELECT) {
            double f = BIdentificationCatalog.selectCurve(x);
            switch (contrast) {
                case "weak-coherent-curve-vs-disconnected-flecks": {
                    double transverse = gaussian(y, f, 0.9);
                    if (side.equals("left")) return clamp(0.02 + 0.08 * transverse);
                    double window = 0.0;
                    for (double center : SELECT_FLECK_CENTERS) {
                        double delta = (x - center) / 1.6;
                        if (Math.abs(x - center) < 1.6) {
                            double base = 1.0 - delta * delta;
                            window = Math.max(window, base * base * base);
                        }
                    }
                    return clamp(0.02 + 0.08 * transverse * window);
                }
                case "concentrated-band-vs-diffuse-band":
                    return clamp(0.02 + (side.equals("left") ? 0.16 : 0.032)
                            * gaussian(y, f, side.equals("left") ? 0.65 : 3.25));
                case "sparse-crossing-noisy-strands-vs-persistent-parallel": {
                    if (side.equals("left")) {
                        double t = x / 120.0;
                        return clamp(0.02 + 0.035 * (gaussian(y, f - 2.0 + 4.0 * t, 0.28)
                                + gaussian(y, f + 1.4 - 0.7 * t, 0.28)
                                + gaussian(y, f + 2.0 - 4.0 * t, 0.28)));
                    }
                    return clamp(0.02 + 0.44 * gaussian(y, f - 4.0, 0.8)
                            + 0.44 * gaussian(y, f + 4.0, 0.8));
                }
                case "resolved-short-apex-vs-stair-step-alias": {
                    if (side.equals("left")) {
                        double u = (x - 60.0) / 6.0;
                        double c = Math.abs(u) < 1.0 ? (1.0 - u * u) * (1.0 - u * u) * (1.0 - u * u) : 0.0;
                        return clamp(0.02 + 0.30 * gaussian(y, f + 4.5 * c, 0.7));
                    }
                    double snapped = definition.sourcePitchMeters()
                            * StrictMath.floor(f / definition.sourcePitchMeters() + 0.5);
                    return clamp(0.02 + 0.14 * gaussian(y, snapped, 0.7));
                }
                case "displaced-strong-corridor-vs-nearby-faint-strand":
                    return clamp(0.02 + 0.70 * gaussian(y, f + 14.0, 1.1)
                            + (side.equals("right") ? 0.035 * gaussian(y, 30.0, 0.7) : 0.0));
                case "multiple-physical-pitches-vs-reversed-source-order",
                     "palette-semantic-vs-rendered-source":
                    return clamp(0.02 + 0.16 * gaussian(y, f, 1.05));
                default: throw new IllegalArgumentException("Unregistered fresh SELECT scene");
            }
        }
        if (contrast.equals("blank-uniform-invalid-censored")) {
            if (side.equals("right")) return 0.80;
            if (side.equals("censored")) return 0.02 + 0.65 * gaussian(y, 5.4, 0.8);
            return 0.02;
        }
        if (contrast.equals("weak-coherent-curve-vs-disconnected-flecks")) {
            double cross = gaussian(y, centerY(definition, x), 0.9);
            if (side.equals("left")) return clamp(0.02 + 0.24 * cross);
            double longitudinal = 0.0;
            for (double fleck : new double[] {24, 60, 96}) longitudinal += gaussian(x, fleck, 3.0);
            return clamp(0.02 + 0.24 * cross * Math.min(1.0, longitudinal));
        }
        if (contrast.equals("concentrated-band-vs-diffuse-band")) {
            double sigma = side.equals("left") ? 0.8 : 3.2;
            double amplitude = 0.16 / (sigma * StrictMath.sqrt(2.0 * Math.PI));
            return clamp(0.02 + amplitude * gaussian(y, centerY(definition, x), sigma));
        }
        if (contrast.equals("sparse-crossing-noisy-strands-vs-persistent-parallel")) {
            double splitShift = splitShift(definition.key().split()) + splitShape(definition.key().split(), x);
            if (side.equals("right")) return clamp(0.02
                    + 0.44 * gaussian(y, 26 + splitShift, 0.8)
                    + 0.44 * gaussian(y, 34 + splitShift, 0.8));
            double crossing = 0.25 * gaussian(y, 30 + splitShift
                    + 4 * StrictMath.sin(2 * Math.PI * x / 48.0), 0.8);
            double stable = 0.13 * (gaussian(y, 26 + splitShift, 0.8)
                    + gaussian(y, 34 + splitShift, 0.8));
            double sparseNoise = (StrictMath.round(x) % 19 == 0)
                    ? 0.16 * gaussian(y, 20 + (StrictMath.round(x) % 11), 0.5) : 0;
            return clamp(0.02 + crossing + stable + sparseNoise);
        }
        if (contrast.equals("resolved-short-apex-vs-stair-step-alias")) {
            double amplitude=definition.variantId().startsWith("six-metre-apex")?.80:.14;
            return clamp(0.02 + amplitude * gaussian(y, centerY(definition, x), 0.75));
        }
        if (contrast.equals("displaced-strong-corridor-vs-nearby-faint-strand")) {
            double shift = splitShift(definition.key().split()) + splitShape(definition.key().split(), x);
            double strongY = (side.equals("left") ? 39 : 27) + shift;
            double faintY = (side.equals("left") ? 27 : 34) + shift;
            return clamp(0.02 + (side.equals("left") ? 0.70 : 0.55) * gaussian(y, strongY, 1.1)
                    + (side.equals("left") ? 0.09 : 0.06) * gaussian(y, faintY, 0.9));
        }
        if (contrast.equals("multiple-physical-pitches-vs-reversed-source-order")) {
            return clamp(0.02 + 0.3 * gaussian(y, centerY(definition, x), 1.0));
        }
        if (contrast.equals("palette-semantic-vs-rendered-source")) {
            double target = clamp(0.02 + 0.65 * gaussian(y, centerY(definition, x), 1.2)
                    + 0.2 * gaussian(y, centerY(definition, x) + 3, 2.2));
            int blue = (int) StrictMath.round(target * 255.0);
            if (side.equals("left")) {
                return clamp(RenderedHeatmapSampler.colorIntensity(0, 0, blue, "blue"));
            }
            return (0.0722 * blue) / 255.0;
        }
        return 0.02;
    }

    private static boolean isValid(BIdentificationCatalog.CaseDefinition definition, MetricPoint point) {
        return !(definition.contrastId().equals("blank-uniform-invalid-censored")
                && definition.side().equals("invalid") && point.xMeters() >= 48 && point.xMeters() <= 72);
    }

    private static double splitShift(BIdentificationCatalog.Split split) {
        return switch (split) {
            case TRAIN, SELECT, INELIGIBLE_DIAGNOSTIC, HOLDOUT, REGRESSION -> 0.0;
        };
    }

    private static double splitShape(BIdentificationCatalog.Split split, double x) {
        return switch (split) {
            case TRAIN -> 0.8 * StrictMath.sin(2.0 * Math.PI * x / 120.0);
            case SELECT -> BIdentificationCatalog.selectCurve(x) - 30.0;
            case INELIGIBLE_DIAGNOSTIC, HOLDOUT -> 0.0;
            case REGRESSION -> 0.0;
        };
    }

    private static double gaussian(double value, double center, double sigma) {
        double z = (value - center) / sigma;
        return StrictMath.exp(-0.5 * z * z);
    }

    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
}
