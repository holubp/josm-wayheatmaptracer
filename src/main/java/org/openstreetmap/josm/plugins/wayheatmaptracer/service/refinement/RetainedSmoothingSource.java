package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Factual selected scalar source; its original image frame is distinct from the output grid.
 * Backing arrays remain charged once by the acquisition's grouped evidence identity.
 */
public final class RetainedSmoothingSource {
    /** Factual original Legacy applicability, separate from an old cached wrapper's compatibility flags. */
    public record LegacyDisposition(String nativeCandidateId,boolean applicable) {
        public LegacyDisposition {if(nativeCandidateId==null||nativeCandidateId.isBlank())throw new IllegalArgumentException("Original Legacy candidate identity required");}
    }
    private final RefinementSourcePlanner.SourceRequest source;
    private final IntensitySamplingMode intensityMode;
    private final String fieldName;
    private final EvidenceSnapshot evidence;
    private final SupportedInputRasterTransform referenceTransform;
    private final int referenceWidth, referenceHeight;
    private final long backingBytes;
    private final String imageOwnershipIdentity;
    private final String inputByteHash;
    private final String inputHashProtocol;
    private final OptionalLong generation;
    private final Optional<VisibleSourceEpoch.Receipt> visibleReceipt;
    private final Optional<LegacyDisposition> legacyDisposition;
    private final Runnable currentness;
    private final Map<Object,Long> charges;

    // Only actual acquisition writers can bind an existing grouped evidence charge.
    RetainedSmoothingSource(RefinementSourcePlanner.SourceRequest source, IntensitySamplingMode intensityMode,
            String fieldName, EvidenceSnapshot evidence, SupportedInputRasterTransform referenceTransform,
            int referenceWidth, int referenceHeight, long backingBytes, Runnable currentness) {
        this(source,intensityMode,fieldName,evidence,referenceTransform,referenceWidth,referenceHeight,backingBytes,currentness,
            evidence.sourceIdentity()+"/input-ownership/"+referenceTransform.parameterIdentity()+"/"+referenceWidth+"x"+referenceHeight);
    }
    RetainedSmoothingSource(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensityMode,
            String fieldName,EvidenceSnapshot evidence,SupportedInputRasterTransform referenceTransform,
            int referenceWidth,int referenceHeight,long backingBytes,Runnable currentness,String imageOwnershipIdentity) {
        this(source,intensityMode,fieldName,evidence,referenceTransform,referenceWidth,referenceHeight,backingBytes,currentness,imageOwnershipIdentity,"");
    }
    RetainedSmoothingSource(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensityMode,
            String fieldName,EvidenceSnapshot evidence,SupportedInputRasterTransform referenceTransform,
            int referenceWidth,int referenceHeight,long backingBytes,Runnable currentness,String imageOwnershipIdentity,String inputByteHash) {
        this(source,intensityMode,fieldName,evidence,referenceTransform,referenceWidth,referenceHeight,backingBytes,currentness,imageOwnershipIdentity,inputByteHash,OptionalLong.empty(),Optional.empty());
    }
    RetainedSmoothingSource(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensityMode,
            String fieldName,EvidenceSnapshot evidence,SupportedInputRasterTransform referenceTransform,
            int referenceWidth,int referenceHeight,long backingBytes,Runnable currentness,String imageOwnershipIdentity,String inputByteHash,
            OptionalLong generation,Optional<VisibleSourceEpoch.Receipt> visibleReceipt) {
        this(source,intensityMode,fieldName,evidence,referenceTransform,referenceWidth,referenceHeight,backingBytes,currentness,imageOwnershipIdentity,inputByteHash,generation,visibleReceipt,Optional.empty());
    }
    RetainedSmoothingSource(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensityMode,
            String fieldName,EvidenceSnapshot evidence,SupportedInputRasterTransform referenceTransform,
            int referenceWidth,int referenceHeight,long backingBytes,Runnable currentness,String imageOwnershipIdentity,String inputByteHash,
            OptionalLong generation,Optional<VisibleSourceEpoch.Receipt> visibleReceipt,Optional<LegacyDisposition> legacyDisposition) {
        this(source,intensityMode,fieldName,evidence,referenceTransform,referenceWidth,referenceHeight,backingBytes,currentness,imageOwnershipIdentity,inputByteHash,generation,visibleReceipt,legacyDisposition,"");
    }
    RetainedSmoothingSource(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensityMode,
            String fieldName,EvidenceSnapshot evidence,SupportedInputRasterTransform referenceTransform,
            int referenceWidth,int referenceHeight,long backingBytes,Runnable currentness,String imageOwnershipIdentity,String inputByteHash,
            OptionalLong generation,Optional<VisibleSourceEpoch.Receipt> visibleReceipt,Optional<LegacyDisposition> legacyDisposition,String inputHashProtocol) {
        this.source=Objects.requireNonNull(source);this.intensityMode=Objects.requireNonNull(intensityMode);
        this.fieldName=Objects.requireNonNull(fieldName);this.evidence=Objects.requireNonNull(evidence);
        this.referenceTransform=Objects.requireNonNull(referenceTransform);
        this.currentness=Objects.requireNonNull(currentness);
        this.imageOwnershipIdentity=Objects.requireNonNull(imageOwnershipIdentity);
        this.inputByteHash=Objects.requireNonNull(inputByteHash);
        this.inputHashProtocol=Objects.requireNonNull(inputHashProtocol);
        this.generation=Objects.requireNonNull(generation);this.visibleReceipt=Objects.requireNonNull(visibleReceipt);
        this.legacyDisposition=Objects.requireNonNull(legacyDisposition);
        if(generation.isPresent()&&generation.getAsLong()<0)throw new IllegalArgumentException("Negative retained generation");
        if(!source.unavailableReason().isEmpty()||!evidence.fields().containsKey(fieldName)
                ||referenceWidth<=0||referenceHeight<=0||backingBytes<=0
                ||imageOwnershipIdentity.isBlank()||referenceWidth!=evidence.resampling().inputWidth()||referenceHeight!=evidence.resampling().inputHeight()
                ||!referenceTransform.parameterIdentity().equals(evidence.resampling().sourceTransformIdentity()))
            throw new IllegalArgumentException("Retained source does not bind the factual input raster");
        var lineage=evidence.fields().get(fieldName).lineage();
        if(generation.isPresent()&&lineage.acquisitionKind()!=EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE
                ||visibleReceipt.isPresent()&&lineage.acquisitionKind()!=EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER)
            throw new IllegalArgumentException("Retained epoch kind differs from actual acquisition");
        String mapping=intensityMode.usesColorMapping()?source.mapping():source.palette()+"/"+intensityMode.detectorName();
        if(!lineage.sourcePalette().equals(mapping)||lineage.completeAggregate()!=source.aggregate()
                ||!referenceTransform.supports(lineage.acquisitionKind()))
            throw new IllegalArgumentException("Retained scalar mapping differs from selected source");
        this.referenceWidth=referenceWidth;this.referenceHeight=referenceHeight;this.backingBytes=backingBytes;
        var inventory=new IdentityHashMap<Object,Long>();
        inventory.put(evidence,backingBytes);
        // Includes this descriptor, source request, transform, currentness token, strings and container.
        long chars=(long)referenceTransform.parameterIdentity().length()+source.palette().length()
            +source.mapping().length()+fieldName.length()+imageOwnershipIdentity.length()+inputByteHash.length()+inputHashProtocol.length()+legacyDisposition.map(d->d.nativeCandidateId().length()).orElse(0);
        inventory.put(this,Math.addExact(4096L,Math.multiplyExact(chars,8L)));
        charges=Collections.unmodifiableMap(inventory);
    }
    public RefinementSourcePlanner.SourceRequest source(){return source;}
    public IntensitySamplingMode intensityMode(){return intensityMode;}
    public String fieldName(){return fieldName;}
    public EvidenceSnapshot evidence(){return evidence;}
    public SupportedInputRasterTransform referenceTransform(){return referenceTransform;}
    public int referenceWidth(){return referenceWidth;}
    public int referenceHeight(){return referenceHeight;}
    public long backingBytes(){return backingBytes;}
    /** Opaque identity of the acquired image/frame; no assertion of an original byte hash. */
    public String imageOwnershipIdentity(){return imageOwnershipIdentity;}
    /** Empty means no original input-byte hash was produced; it never contains an output-grid hash. */
    public String inputByteHash(){return inputByteHash;}
    public String inputHashProtocol(){return inputHashProtocol;}
    public boolean hasFactualInputHash(){return inputByteHash.matches("[0-9a-f]{64}")&&inputHashProtocol.equals(source.aggregate()?
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_SET_PROTOCOL:
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL);}
    public OptionalLong generation(){return generation;}
    public Optional<VisibleSourceEpoch.Receipt> visibleReceipt(){return visibleReceipt;}
    public Optional<LegacyDisposition> legacyDisposition(){return legacyDisposition;}
    public RasterMetricTransform outputTransform(){return evidence.transform();}
    public EvidenceResolution outputResolution(){return evidence.resolution();}
    public EvidenceFieldLineage.AcquisitionKind acquisitionKind(){return evidence.fields().get(fieldName).lineage().acquisitionKind();}
    public void requireCurrent(){currentness.run();}
    Map<Object,Long> charges(){return charges;}
    long descriptorBytes(){return charges.get(this);}
    /** Same-ledger co-ownership, without copying or charging the scalar/mask arrays twice. */
    public void retainInto(AttemptMemoryLedger.Owner destination) {
        if(destination.retainIfAbsent(evidence).bytes()!=backingBytes)throw new IllegalArgumentException("Retained evidence group byte bound differs from its actual lease");
        destination.retainIfAbsent(this);
    }
    /** Joins a real admitted original-source conversion to its already-accounted evidence identity. */
    public static RetainedSmoothingSource fromOriginalConversion(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensity,
            String field,EvidenceSnapshot evidence,SupportedInputRasterTransform transform,int width,int height,long backingBytes,
            Runnable currentness,String imageIdentity,String inputByteHash,OptionalLong generation,Optional<VisibleSourceEpoch.Receipt> visibleReceipt,
            Optional<LegacyDisposition> legacyDisposition,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        return fromOriginalConversion(source,intensity,field,evidence,transform,width,height,backingBytes,currentness,imageIdentity,inputByteHash,generation,visibleReceipt,legacyDisposition,"",work);
    }
    public static RetainedSmoothingSource fromOriginalConversion(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensity,
            String field,EvidenceSnapshot evidence,SupportedInputRasterTransform transform,int width,int height,long backingBytes,
            Runnable currentness,String imageIdentity,String inputByteHash,OptionalLong generation,Optional<VisibleSourceEpoch.Receipt> visibleReceipt,
            Optional<LegacyDisposition> legacyDisposition,String inputHashProtocol,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        return fromOriginalConversion(source,intensity,field,evidence,transform,width,height,backingBytes,currentness,imageIdentity,inputByteHash,generation,visibleReceipt,legacyDisposition,inputHashProtocol,work,work.owner());
    }
    public static RetainedSmoothingSource fromOriginalConversion(RefinementSourcePlanner.SourceRequest source,IntensitySamplingMode intensity,
            String field,EvidenceSnapshot evidence,SupportedInputRasterTransform transform,int width,int height,long backingBytes,
            Runnable currentness,String imageIdentity,String inputByteHash,OptionalLong generation,Optional<VisibleSourceEpoch.Receipt> visibleReceipt,
            Optional<LegacyDisposition> legacyDisposition,String inputHashProtocol,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work,AttemptMemoryLedger.Owner owner) {
        if(owner.retainIfAbsent(evidence).bytes()!=backingBytes)throw new IllegalArgumentException("Original evidence bound differs from producer admission");
        long chars=(long)transform.parameterIdentity().length()+source.palette().length()+source.mapping().length()+field.length()+imageIdentity.length()+inputByteHash.length()+inputHashProtocol.length()+legacyDisposition.map(d->d.nativeCandidateId().length()).orElse(0);
        try(var admitted=work.reserveOn(owner,"original-source-descriptor",Math.addExact(4096L,Math.multiplyExact(chars,8L)))) {
            var result=new RetainedSmoothingSource(source,intensity,field,evidence,transform,width,height,backingBytes,currentness,imageIdentity,inputByteHash,generation,visibleReceipt,legacyDisposition,inputHashProtocol);
            admitted.adopt(result);return result;
        }
    }
}
