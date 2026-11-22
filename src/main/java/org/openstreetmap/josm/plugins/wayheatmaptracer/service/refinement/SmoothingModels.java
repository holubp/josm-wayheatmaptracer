package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket;

/** Immutable P1 input values. No solver, qualification or changed native basis is implied. */
public final class SmoothingModels {
    private SmoothingModels(){}
    public enum Preset { MEDIUM_1PX, STRONG_2PX }
    public enum Status { REQUESTED, PARTIALLY_REQUESTED, UNCHANGED, REFUSED_GEOMETRY,
        REFUSED_RESOURCE, UNAVAILABLE_SOURCE, NONCONVERGED }
    /** An empty inputHash explicitly means the original input-byte hash is unavailable.
     * imageIdentity is an opaque acquired-image ownership identity, never a content hash.
     */
    public record Frame(String inputHash,String imageIdentity,String palette,String mappingIdentity,String intensityMode,
            LocalMetricFrame coordinateFrame,SupportedInputRasterTransform originalInputTransform,
            int inputWidth,int inputHeight,String inputCoordinateConvention,Optional<VerifiedOriginalAffine> originalAffine) {
        public Frame {
            Objects.requireNonNull(inputHash);Objects.requireNonNull(originalAffine);
            if(imageIdentity==null||imageIdentity.isBlank()||palette==null||palette.isBlank()||mappingIdentity==null||mappingIdentity.isBlank()
                    ||intensityMode==null||intensityMode.isBlank()||coordinateFrame==null||!coordinateFrame.hasCompleteNumericalIdentity()
                    ||originalInputTransform==null||inputWidth<2||inputHeight<2||inputCoordinateConvention==null||inputCoordinateConvention.isBlank())
                throw new IllegalArgumentException("Original reference frame is unavailable");
            if(originalInputTransform instanceof SupportedInputRasterTransform.LocalMetricAffine affine&&!affine.coordinateFrame().equals(coordinateFrame))
                throw new IllegalArgumentException("Original affine belongs to another metric frame");
            if(originalAffine.isPresent()&&!(originalInputTransform instanceof SupportedInputRasterTransform.LocalMetricAffine))
                throw new IllegalArgumentException("A Mercator input has no factual global metric affine");
            if(originalAffine.isPresent()&&!originalAffine.get().equals(verifiedOriginalAffine((SupportedInputRasterTransform.LocalMetricAffine)originalInputTransform)))
                throw new IllegalArgumentException("Affine proof differs from the exact original input transform");
        }
    }
    public record VerifiedOriginalAffine(RasterMetricTransform transform,String proofIdentity) {
        public VerifiedOriginalAffine {Objects.requireNonNull(transform);if(proofIdentity==null||proofIdentity.isBlank())throw new IllegalArgumentException("Factual affine proof required");}
    }
    static VerifiedOriginalAffine verifiedOriginalAffine(SupportedInputRasterTransform.LocalMetricAffine a) {
        double determinant=a.rasterXPerEastMeter()*a.rasterYPerNorthMeter()-a.rasterXPerNorthMeter()*a.rasterYPerEastMeter();
        double x=a.rasterCenterAtMetricOrigin().x(),y=a.rasterCenterAtMetricOrigin().y();
        var origin=new MetricPoint((-a.rasterYPerNorthMeter()*x+a.rasterXPerNorthMeter()*y)/determinant,
            (a.rasterYPerEastMeter()*x-a.rasterXPerEastMeter()*y)/determinant);
        return new VerifiedOriginalAffine(RasterMetricTransform.metricGrid(origin,a.rasterYPerNorthMeter()/determinant,
            -a.rasterYPerEastMeter()/determinant,-a.rasterXPerNorthMeter()/determinant,a.rasterXPerEastMeter()/determinant),a.parameterIdentity());
    }
    /** Factual accepted Gaussian units; distinct from the raw original-input frame. */
    public record BaselineReference(String protocol,String evidenceHash,String fieldName,String mappingIdentity,
            RasterMetricTransform transform,int width,int height) {
        public BaselineReference {
            if(!"captured-scalar-reference-v1".equals(protocol)||evidenceHash==null||!evidenceHash.matches("[0-9a-f]{64}")
                    ||fieldName==null||fieldName.isBlank()||mappingIdentity==null||mappingIdentity.isBlank()||transform==null||width<2||height<2)
                throw new IllegalArgumentException("Factual scalar reference required");
        }
        public String units(){return "baseline-capture-reference-units";}
    }
    public record Parent(OperationTicket ticket,ParentTraceReference reference,Frame frame,Optional<FinalControlBasis> nativeBasis,
            FinalControlBasis smoothingBasis,String representationIdentity,List<FinalRoutePointId> pointIds,ImageCostField image,EvidenceSnapshot evidence,NativeFacts nativeFacts,BaselineReference baselineReference,
            Optional<RefinementRootAuthority.OriginalEvidenceHashProof> originalEvidenceHashProof) {
        public Parent(OperationTicket ticket,ParentTraceReference reference,Frame frame,Optional<FinalControlBasis> nativeBasis,
                FinalControlBasis smoothingBasis,String representationIdentity,List<FinalRoutePointId> pointIds,ImageCostField image,EvidenceSnapshot evidence,NativeFacts nativeFacts,BaselineReference baselineReference) {
            this(ticket,reference,frame,nativeBasis,smoothingBasis,representationIdentity,pointIds,image,evidence,nativeFacts,baselineReference,Optional.empty());
        }
        public Parent {
            Objects.requireNonNull(ticket);Objects.requireNonNull(reference);Objects.requireNonNull(frame);Objects.requireNonNull(nativeBasis);
            Objects.requireNonNull(smoothingBasis);Objects.requireNonNull(image);Objects.requireNonNull(evidence);Objects.requireNonNull(nativeFacts);pointIds=List.copyOf(pointIds);
            Objects.requireNonNull(baselineReference);Objects.requireNonNull(originalEvidenceHashProof);
            var scalar=evidence.fields().get(nativeFacts.source().fieldName());
            if(!baselineReference.transform().equals(evidence.transform())||!baselineReference.fieldName().equals(nativeFacts.source().fieldName())
                    ||baselineReference.width()!=scalar.width()||baselineReference.height()!=scalar.height()||!baselineReference.mappingIdentity().equals(frame.mappingIdentity()))
                throw new IllegalArgumentException("Baseline reference differs from selected scalar evidence");
            if(representationIdentity==null||representationIdentity.isBlank()||pointIds.size()!=reference.points().size()
                    ||!smoothingBasis.initialCoordinates().equals(reference.points())||smoothingBasis.size()!=pointIds.size()
                    ||new HashSet<>(pointIds).size()!=pointIds.size())throw new IllegalArgumentException("Smoothing final-slot representation differs from exact parent");
            if(nativeFacts.source().evidence()!=evidence||!nativeFacts.source().source().palette().equals(frame.palette())
                    ||!nativeFacts.source().source().mapping().equals(frame.mappingIdentity()))throw new IllegalArgumentException("Inherited facts belong to another selected source");
            if(!nativeFacts.source().hasFactualInputHash()||!nativeFacts.source().inputByteHash().equals(frame.inputHash()))throw new IllegalArgumentException("Original input hash is unavailable or mismatched");
        }
    }
    /** Actual inherited observations and disposition, never a smoothing approval or manufactured branch proof. */
    public record NativeFacts(RetainedSmoothingSource source,CandidateEvidence evidence,CandidateGeometryCleanup geometryCleanup,
            List<String> safetyWarnings,List<JunctionSafetyFinding> junctionFindings,boolean parentApplicable,List<DetectorAttempt> detectorAttempts,
            Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result> quality,
            Optional<ValidationReport.Disposition> nativeDisposition,List<RefinementPipeline.SourceSpan> sourceSpans,
            Optional<ValidationReport> nativeValidation) {
        /** Historical facts have no separately retained native plan finding codes. */
        public NativeFacts(RetainedSmoothingSource source,CandidateEvidence evidence,CandidateGeometryCleanup geometryCleanup,
                List<String> safetyWarnings,List<JunctionSafetyFinding> junctionFindings,boolean parentApplicable,List<DetectorAttempt> detectorAttempts,
                Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result> quality,
                Optional<ValidationReport.Disposition> nativeDisposition,List<RefinementPipeline.SourceSpan> sourceSpans) {
            this(source,evidence,geometryCleanup,safetyWarnings,junctionFindings,parentApplicable,detectorAttempts,quality,nativeDisposition,sourceSpans,Optional.empty());
        }
        public NativeFacts {
            Objects.requireNonNull(source);Objects.requireNonNull(evidence);Objects.requireNonNull(geometryCleanup);
            Objects.requireNonNull(quality);Objects.requireNonNull(nativeDisposition);Objects.requireNonNull(nativeValidation);
            if(nativeValidation.isPresent()&&!nativeDisposition.equals(Optional.of(nativeValidation.get().disposition())))
                throw new IllegalArgumentException("Native validation disposition differs from captured plan");
            safetyWarnings=List.copyOf(safetyWarnings);junctionFindings=List.copyOf(junctionFindings);detectorAttempts=List.copyOf(detectorAttempts);sourceSpans=List.copyOf(sourceSpans);
        }
    }
    public record Limits(long visits,long scratchBytes,long deadlineNanos) {
        public Limits {if(visits<0||scratchBytes<0)throw new IllegalArgumentException("Negative smoothing limits");}
    }
}
