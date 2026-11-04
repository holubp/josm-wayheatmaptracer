package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Opaque original-L0 A geometry alias for declared synthetic references. Measurement pyramid levels
 * remain retained source data; this explicit alias preserves the historical native L0 entry only.
 * Actual managed A multi-level producer authority is unavailable. A issues its own bands/states.
 */
public final class NativeAEmissionBinding implements AutoCloseable {
    private final SampledEmissionInput input;
    private final AttemptMemoryLedger.Owner owner;
    private final MultiScaleProfileSet profiles;
    private final List<Geometry> rows;
    private final Map<Integer,Integer> originalOrdinals;
    private final double normalization;
    private final int cropX,cropY;
    private boolean closed;

    private record Geometry(CrossSectionProfile nativeProfile,CrossSectionProfile measurementProfile,
            int retainedRow,double x,double y,double nx,double ny,double nativeS,
            Object anchor,List<?> samples,List<?> nativeLocalizationHints) {
        boolean unchanged() {
            return bits(x,nativeProfile.anchorScreen().x)&&bits(y,nativeProfile.anchorScreen().y)
                &&bits(nx,nativeProfile.normalScreen().x)&&bits(ny,nativeProfile.normalScreen().y)
                &&bits(nativeS,nativeProfile.cumulativeGroundDistanceMeters())
                &&anchor==nativeProfile.samplingAnchor()&&samples==nativeProfile.intensitySamples()
                &&nativeLocalizationHints==nativeProfile.peaks();
        }
    }
    private NativeAEmissionBinding(SampledEmissionInput input,AttemptMemoryLedger.Owner owner) {
        this.input=input;this.owner=owner;
        var context=input.context();
        if(context.eligibility()!=OptionalEmissionContext.Eligibility.DECLARED_SYNTHETIC)
            throw new IllegalArgumentException("Original A alias currently requires declared synthetic source authority");
        var geometry=input.legacyBinding().orElseThrow(()->new IllegalArgumentException("Original profile geometry is unavailable"));
        var receipt=geometry.syntheticRasterReceipt();
        cropX=receipt==null?0:receipt.cropOriginX();cropY=receipt==null?0:receipt.cropOriginY();
        normalization=geometry.trackerNormalizationRasterPx();
        if(!bits(normalization,context.trackerNormalizationRasterPx()))
            throw new IllegalArgumentException("Original A normalization differs from its captured source");
        if(!geometry.protectedOccurrences().isEmpty())
            throw new IllegalArgumentException("Original A protected occurrence alias is unavailable");
        var facts=new ArrayList<Geometry>();var mapping=new LinkedHashMap<Integer,Integer>();
        int previous=-1;
        for(var original:geometry.observations()) {
            input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,1);
            int row=original.originalRow(),retained=original.retainedObservationIndex();
            var nativeProfile=original.profile();var measurement=original.measurementProfile();
            if(row!=facts.size()||retained<=previous||retained>=context.observations().size()
                    ||input.profiles().levelZeroProfiles().get(retained)!=measurement
                    ||receipt==null&&nativeProfile!=measurement)
                throw new IllegalArgumentException("Original A profile geometry has foreign or unordered rows");
            var observation=context.observations().get(retained);
            input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,
                Math.multiplyExact(16L,(long)nativeProfile.intensitySamples().size()+measurement.intensitySamples().size()));
            if(observation.location()!=measurement.samplingAnchor()
                    ||!bits(nativeProfile.anchorScreen().x-cropX,measurement.anchorScreen().x)
                    ||!bits(nativeProfile.anchorScreen().y-cropY,measurement.anchorScreen().y)
                    ||!bits(nativeProfile.normalScreen().x,observation.normal().x())
                    ||!bits(nativeProfile.normalScreen().y,observation.normal().y())
                    ||nativeProfile.anchorWithinRaster()!=measurement.anchorWithinRaster()
                    ||!sameSamplingGeometry(nativeProfile,measurement))
                throw new IllegalArgumentException("Original A crop/normal differs from its factual retained producer");
            facts.add(new Geometry(nativeProfile,measurement,retained,nativeProfile.anchorScreen().x,nativeProfile.anchorScreen().y,
                nativeProfile.normalScreen().x,nativeProfile.normalScreen().y,nativeProfile.cumulativeGroundDistanceMeters(),
                nativeProfile.samplingAnchor(),nativeProfile.intensitySamples(),nativeProfile.peaks()));
            mapping.put(retained,row);previous=retained;
        }
        if(facts.size()<2)throw new IllegalArgumentException("Original A requires at least two source observations");
        rows=List.copyOf(facts);originalOrdinals=Map.copyOf(mapping);
        profiles=new MultiScaleProfileSet(List.of(new MultiScaleProfileSet.ScaleProfileLevel(0,1,0,
            rows.stream().map(Geometry::nativeProfile).toList())),0,0);
    }
    private static boolean sameSamplingGeometry(CrossSectionProfile original,CrossSectionProfile measured) {
        var a=original.intensitySamples();var b=measured.intensitySamples();
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!bits(a.get(i).offsetPx(),b.get(i).offsetPx())
                ||a.get(i).insideRaster()!=b.get(i).insideRaster())return false;
        // Distinct full/cropped sampler arithmetic is covered by the issued raster receipt's
        // factual node/profile proof. Each side keeps its own exact immutable scalar records.
        return true;
    }

    /** Reuses validated geometry only; no Legacy semantic peak, source class or seed is consumed. */
    public static NativeAEmissionBinding fromOriginalGeometry(SampledEmissionInput input) {
        Objects.requireNonNull(input);input.requireRetainedSourceUnchanged();
        var owner=input.openRetainedSourceExecutionOwner("native-a-original-profile-geometry");
        try {
            long size=input.legacyBinding().orElseThrow(()->new IllegalArgumentException("Original A geometry is unavailable")).observations().size();
            input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,1);
            owner.reserve(Math.addExact(2048,Math.multiplyExact(size,512)));
            return new NativeAEmissionBinding(input,owner);
        }catch(RuntimeException failure) {
            if(failure instanceof EmissionWorkBudget.LimitExceededException
                    ||failure instanceof AttemptMemoryLedger.ResourceLimitException
                    ||failure instanceof java.util.concurrent.CancellationException)input.recordTerminalExecutionFailure(failure);
            owner.close();throw failure;
        }
    }
    public boolean belongsTo(SampledEmissionInput expected){return !closed&&!owner.isClosed()&&input==expected;}
    public void requireCurrent(SampledEmissionInput expected) {
        if(!belongsTo(expected))throw new IllegalArgumentException("Original A geometry binding is foreign or closed");
        input.requireRetainedSourceUnchanged();
        for(var row:rows) {
            // Full sample/peak record lists are immutable, constructor-copied native inputs.
            // Pin their object identity; do not scan incidental Legacy source classes/peak owners.
            input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,16);
            if(!row.unchanged()||input.profiles().levelZeroProfiles().get(row.retainedRow())!=row.measurementProfile())
                throw new IllegalStateException("Original A source geometry changed after binding");
        }
    }
    /** Final A publication gate: prepay every callback before validating either source receipt. */
    public void requireCurrentForFinalPublication(SampledEmissionInput expected) {
        if(!belongsTo(expected))throw new IllegalArgumentException("Original A geometry binding is foreign or closed");
        input.requireRetainedOpen();
        long retainedWork=Math.multiplyExact((long)input.context().observations().size(),input.profiles().levels().size());
        input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,
            Math.addExact(retainedWork,Math.multiplyExact(16L,rows.size())));
        // No charge/cancellation/observer callback may follow before the complete receipt scan.
        if(!belongsTo(expected))throw new IllegalArgumentException("Original A geometry binding is foreign or closed");
        input.requireRetainedOpen();
        input.context().requireProfilesUnchanged(input.profiles());
        for(var row:rows)if(!row.unchanged()||input.profiles().levelZeroProfiles().get(row.retainedRow())!=row.measurementProfile())
            throw new IllegalStateException("Original A source geometry changed after binding");
    }
    public AttemptMemoryLedger.Owner openExecutionOwner(String phase) {
        requireCurrent(input);return input.openRetainedSourceExecutionOwner(phase);
    }
    public MultiScaleProfileSet profiles(){requireCurrent(input);return profiles;}
    public int retainedRow(int row){return rows.get(row).retainedRow();}
    public int originalRow(int retainedRow) {
        var row=originalOrdinals.get(retainedRow);
        if(row==null)throw new IllegalArgumentException("Retained row is outside the original A selection");return row;
    }
    public Map<Integer,Integer> originalOrdinalByContextRow(){return originalOrdinals;}
    public double trackerNormalizationRasterPx(){return normalization;}
    public double sourceChainageMeters(int row){return input.context().sourceChainageMeters(retainedRow(row));}
    public double sourceSpanUpperMeters(int from,int to){return input.context().sourceSpanUpperMeters(retainedRow(from),retainedRow(to));}
    public RasterPoint nativePoint(int row,double offset) {
        var fact=rows.get(row);return new RasterPoint(fact.x()+fact.nx()*offset,fact.y()+fact.ny()*offset);
    }
    /** Translate only after computing the original native point; reassociation can change IEEE bits. */
    public RasterPoint measurementPoint(int row,double offset){return toMeasurement(nativePoint(row,offset));}
    public RasterPoint toMeasurement(RasterPoint nativePoint){return new RasterPoint(nativePoint.x()-cropX,nativePoint.y()-cropY);}
    private static boolean bits(double left,double right){return Double.doubleToLongBits(left)==Double.doubleToLongBits(right);}
    @Override public void close(){if(!closed){closed=true;owner.close();}}
}
