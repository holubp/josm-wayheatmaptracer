package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.util.function.Supplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Inert source evidence receipt. No histories, inference or applicability authority. */
public final class SourceContinuitySnapshot {
    /** Local ordinal only; this is never a persistent track ID. */
    public record SourceBandRef(int row,int localOrdinal) { }
    /** An existing interval invocation, or a pair for which no interval was requested. */
    public enum IntervalStatus { NOT_EVALUATED, EVALUATED_CENTRE_LINK }
    /** Source query purpose, without inventing full cell-segment authority. */
    public enum QueryKind { MODE_CENTRE, RMS_CANDIDATE_CENTRE, CENSORED_BAND_SCALAR }
    /** Required persistent authority that the current source producer does not supply. */
    public enum MissingCertificate { SPLIT_REJOIN, ENDPOINT_PORT, GAP }
    private static final Set<MissingCertificate> MISSING=Set.of(MissingCertificate.SPLIT_REJOIN,MissingCertificate.ENDPOINT_PORT,MissingCertificate.GAP);
    /** Factual increments of the existing raster interval counters. */
    public record QueryWork(long localProfileQueries,long scalarQueries,long samplingUnits,long featureUnits) { }
    /** Already-computed mutual-best relation facts, including unqueried unknown pairs. */
    public record AdjacentRelation(SourceBandRef from,SourceBandRef to,double pairScore,
            boolean mutualBest,IntervalStatus status,OptionalDouble support,double confidence) { }
    /** An already-computed interval result; endpoint IDs remain local observations. */
    public record IntervalObservation(int leftRow,String leftLocalId,int rightRow,String rightLocalId,
            QueryKind kind,double result,QueryWork work) { }
    /** Indices derived by testing the actual original cells, never by rebuilding a lattice. */
    public record BoundBand(SourceBandRef ref,List<Integer> coreIndices,List<Integer> neighbourIndices,
            boolean measuredComponentPresent) {
        public BoundBand {coreIndices=List.copyOf(coreIndices);neighbourIndices=List.copyOf(neighbourIndices);}
    }
    /** Original cell object and exact cost bits, in the actual original index order. */
    public record StateWitness(int originalIndex,LateralStateCell originalCell,MetricPoint point,
            long unaryCostBits,long guideCostBits) { }
    /** Complete evaluated inventory for one source row; ownership is the evaluator's factual value. */
    public record BoundRow(int originalRow,List<StateWitness> states,List<BoundBand> bands,
            List<ObservationComponent> components,ObservationOwnership ownership,boolean entirelyMissing,
            boolean resolutionLimited) {
        public BoundRow {states=List.copyOf(states);bands=List.copyOf(bands);components=List.copyOf(components);}
    }
    /** Receipt lifetime is bounded by both its source scope and its binding scope. */
    public static final class BoundReceipt {
        private final SourceContinuitySnapshot source;
        private final List<BoundRow> rows;
        private final AttemptMemoryLedger.Owner owner;
        private BoundReceipt(SourceContinuitySnapshot source,List<BoundRow> rows,AttemptMemoryLedger.Owner owner){this.source=source;this.rows=List.copyOf(rows);this.owner=owner;}
        /** Returns immutable complete rows while both ownership scopes remain alive. */
        public List<BoundRow> rows(){source.open();if(owner.isClosed())throw new IllegalStateException("Closed binding owner");return rows;}
        /** Returns the exact source receipt, never a reconstructed frame. */
        public SourceContinuitySnapshot source(){rows();return source;}
    }
    private final EvidenceSnapshot evidence;
    private final ScalarEvidenceField field;
    private final List<MetricPoint> sourcePolyline;
    private final List<ProbabilisticProfile> profiles;
    private final List<AdjacentRelation> relations;
    private final List<IntervalObservation> intervals;
    private final double configuredHalfWidth;
    private final AttemptMemoryLedger.Owner owner;
    private final long pairEvaluations,supportEdgeVisits;
    private SourceContinuitySnapshot(EvidenceSnapshot evidence,ScalarEvidenceField field,List<MetricPoint> source,
            List<ProbabilisticProfile> profiles,Collector facts,double width,AttemptMemoryLedger.Owner owner) {
        this.evidence=evidence;this.field=field;this.sourcePolyline=List.copyOf(source);this.profiles=List.copyOf(profiles);
        this.relations=List.copyOf(facts.relations);this.intervals=List.copyOf(facts.intervals);this.configuredHalfWidth=width;this.owner=owner;
        pairEvaluations=facts.pairs;supportEdgeVisits=facts.visits;
    }
    private void open(){if(owner.isClosed())throw new IllegalStateException("Closed source snapshot owner");}
    /** Exact immutable input evidence, including frame, transform and original regions. */
    public EvidenceSnapshot evidence(){open();return evidence;}
    /** Exact selected input field; no new field or feature computation is performed. */
    public ScalarEvidenceField field(){open();return field;}
    /** Immutable copy of the actual source selection. */
    public List<MetricPoint> sourcePolyline(){open();return sourcePolyline;}
    /** Actual original profiles with factual bounds, pitch, modes, lineage, orientation and censored records. */
    public List<ProbabilisticProfile> profiles(){open();return profiles;}
    /** Already-computed adjacent mutual-best facts; absent interval values mean not evaluated. */
    public List<AdjacentRelation> adjacentRelations(){open();return relations;}
    /** Actual interval function returns and counter increments, including early returns with no raster query. */
    public List<IntervalObservation> intervalObservations(){open();return intervals;}
    /** Requested width, kept separate from each profile's factual authorized offset bounds. */
    public double configuredHalfWidthMeters(){open();return configuredHalfWidth;}
    /** Existing association pair count; snapshot copy work is additional and separately bounded. */
    public long associationPairEvaluations(){open();return pairEvaluations;}
    /** Existing reliability support-edge count, without an inferred persistent identity. */
    public long supportEdgeVisits(){open();return supportEdgeVisits;}
    /** Existing B supplies no observed split/rejoin, endpoint-port or missing-row gap certificate. */
    public boolean hasPersistentCertificates(){open();return false;}
    /** All currently unavailable certificate kinds, with no source-anchor substitution. */
    public Set<MissingCertificate> missingCertificates(){open();return MISSING;}
    /** Centre links alone cannot certify a complete persistent carrier or off-centre valley edge. */
    public String associationCapability(){open();return "SOURCE_ASSOCIATION_UNAVAILABLE";}

    /**
     * Refuses the historical experimental entry lacking original-domain provenance.
     * Matching retained lattice/evaluated views cannot prove complete original indices.
     */
    public BoundReceipt bind(List<ProbabilisticStateLattice> lattices,List<InferenceProfile> evaluated,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner bindingOwner) {
        open();throw new IllegalArgumentException("Actual state-builder original-domain handles required");
    }

    /**
     * Binds actual evaluator outputs against opaque original domains minted by the
     * state builder. No state construction, evaluation, raster read or inferred
     * association occurs here; paired retained views cannot rename original indices.
     */
    public BoundReceipt bind(List<ProbabilisticStateBuilder.OriginalDomain> originals,
            List<ProbabilisticStateLattice> lattices,List<InferenceProfile> evaluated,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner bindingOwner) {
        open();Objects.requireNonNull(cancellation);Objects.requireNonNull(bindingOwner);
        if(originals==null||lattices==null||evaluated==null||originals.size()!=profiles.size()||lattices.size()!=profiles.size()||evaluated.size()!=profiles.size())throw new IllegalArgumentException("Complete original evaluated row inventory required");
        try(var transaction=bindingOwner.child("source-continuity-state-binding")) {
            cancellation.checkpoint();var budget=ProbabilisticInference.allocated(transaction,AttemptMemoryLedger.objectBytes(32),"source binding budget",()->new Budget(cancellation,transaction));List<BoundRow> rows=budget.own(64L+profiles.size()*16L,ArrayList::new);
            for(int row=0;row<profiles.size();row++) {
                budget.step();var source=profiles.get(row);var lattice=lattices.get(row);var actual=evaluated.get(row);
                if(originals.get(row)==null)throw new IllegalArgumentException("Missing original state-domain handle");
                originals.get(row).requireOriginal(this,row,lattice);
                if(actual.chainageMeters()!=source.chainageMeters()||!actual.anchor().equals(source.anchor())||!actual.normalUnit().equals(source.normalUnit())
                    ||actual.cells().size()!=lattice.cells().size()||actual.componentCount()!=lattice.components().size())throw new IllegalArgumentException("Stale or foreign evaluated row/frame");
                List<StateWitness> states=budget.own(64L+actual.cells().size()*16L,ArrayList::new);
                for(int state=0;state<actual.cells().size();state++) {
                    budget.step();var cell=lattice.cells().get(state);int index=state;
                    if(actual.cells().get(state)!=cell||cell.offsetMeters()<source.minimumOffsetMeters()||cell.offsetMeters()>source.maximumOffsetMeters()
                        ||actual.structuralGuideCost(state)!=0.0)throw new IllegalArgumentException("Not the original evaluated lattice/cost domain");
                    if(source.exactAnchorOffsetMeters().isPresent()&&(actual.cells().size()!=1||!cell.exactAnchor()||cell.offsetMeters()!=source.exactAnchorOffsetMeters().getAsDouble()))throw new IllegalArgumentException("Original exact anchor changed");
                    states.add(budget.own(160L+cell.branchLabel().length()*2L,()->new StateWitness(index,cell,actual.point(index),Double.doubleToRawLongBits(actual.unaryCost(index)),Double.doubleToRawLongBits(actual.structuralGuideCost(index)))));
                }
                List<BoundBand> bands=budget.own(64L+source.modes().size()*16L,ArrayList::new);
                for(int modeIndex=0;modeIndex<source.modes().size();modeIndex++) {
                    budget.step();var mode=source.modes().get(modeIndex);List<Integer> core=budget.own(64L,ArrayList::new),near=budget.own(64L,ArrayList::new);int ordinal=modeIndex,r=row;
                    for(int state=0;state<lattice.cells().size();state++) {
                        budget.step();double d=lattice.cells().get(state).offsetMeters();
                        if(d>=mode.coreMinimumMeters()&&d<=mode.coreMaximumMeters())core.add(state);
                        if(d>=Math.max(source.minimumOffsetMeters(),mode.coreMinimumMeters()-source.sourcePitchMeters())&&d<=Math.min(source.maximumOffsetMeters(),mode.coreMaximumMeters()+source.sourcePitchMeters()))near.add(state);
                    }
                    boolean measured=false;for(var component:lattice.components()){budget.step();if(component.kind()==ObservationComponent.Kind.MEASURED&&component.id().equals(mode.id())&&component.evidenceLineage().equals(mode.evidenceLineage()))measured=true;}
                    boolean present=measured;bands.add(budget.own(96L+(core.size()+near.size())*40L,()->new BoundBand(new SourceBandRef(r,ordinal),core,near,present)));
                }
                int r=row;rows.add(budget.own(128L+lattice.components().size()*128L,()->new BoundRow(r,states,bands,lattice.components(),actual.ownership(),actual.entirelyMissing(),lattice.resolutionLimited())));
            }
            var receipt=budget.own(64L+rows.size()*16L,()->new BoundReceipt(this,rows,bindingOwner));cancellation.checkpoint();transaction.transferTo(bindingOwner);return receipt;
        }
    }
    /** Profiles and their explicitly requested source receipt. */
    public record Produced(List<ProbabilisticProfile> profiles,SourceContinuitySnapshot snapshot) {
        public Produced {profiles=List.copyOf(profiles);}
    }
    /** Additional receipt work is separately bounded; ordinary/null calls instantiate none of it. */
    static final class Budget {
        static final long MAX_UNITS=8_000_000;
        final CancellationProbe cancellation;final AttemptMemoryLedger.Owner owner;long units;
        Budget(CancellationProbe cancellation,AttemptMemoryLedger.Owner owner){this.cancellation=cancellation;this.owner=owner;}
        void step(){cancellation.checkpoint();if(++units>MAX_UNITS)throw new IllegalStateException("Source receipt copy-work limit");}
        <T>T own(long bytes,Supplier<T> allocation){step();return ProbabilisticInference.allocated(owner,bytes,"source continuity receipt",allocation);}
    }
    static final class Collector implements LongitudinalModeReliability.Observer {
        final Budget budget;
        final List<AdjacentRelation> relations;
        final List<IntervalObservation> intervals;
        long pairs,visits;
        Collector(CancellationProbe cancellation,AttemptMemoryLedger.Owner owner){budget=new Budget(cancellation,owner);relations=budget.own(64L,ArrayList::new);intervals=budget.own(64L,ArrayList::new);}
        public void relation(int row,int first,int second,double score,boolean mutual,boolean queried,double support,double confidence) {
            if(relations.size()>=1_000_000)throw new IllegalStateException("Source relation inventory limit");
            relations.add(budget.own(192L,()->new AdjacentRelation(new SourceBandRef(row,first),new SourceBandRef(row+1,second),score,mutual,
                queried?IntervalStatus.EVALUATED_CENTRE_LINK:IntervalStatus.NOT_EVALUATED,queried?OptionalDouble.of(support):OptionalDouble.empty(),confidence)));
        }
        void interval(int left,String leftId,int right,String rightId,QueryKind kind,double value,long queries,long scalar,long sampling,long features) {
            if(intervals.size()>=1_000_000)throw new IllegalStateException("Source interval inventory limit");
            intervals.add(budget.own(192L+2L*(leftId.length()+rightId.length()),()->new IntervalObservation(left,leftId,right,rightId,kind,value,new QueryWork(queries,scalar,sampling,features))));
        }
        SourceContinuitySnapshot freeze(EvidenceSnapshot evidence,ScalarEvidenceField field,List<MetricPoint> source,List<ProbabilisticProfile> profiles,double width,AttemptMemoryLedger.Owner resultOwner) {
            for(int row=0;row<profiles.size();row++){budget.step();if(profiles.get(row).profileIndex()!=row)throw new IllegalArgumentException("Source row order changed");}
            for(var relation:relations){budget.step();if(relation.from().row()<0||relation.to().row()>=profiles.size()||relation.from().localOrdinal()>=profiles.get(relation.from().row()).modes().size()||relation.to().localOrdinal()>=profiles.get(relation.to().row()).modes().size())throw new IllegalArgumentException("Source mode order changed");}
            return budget.own(256L+source.size()*32L+profiles.size()*16L+(relations.size()+intervals.size())*16L,()->new SourceContinuitySnapshot(evidence,field,source,profiles,this,width,resultOwner));
        }
    }
}
