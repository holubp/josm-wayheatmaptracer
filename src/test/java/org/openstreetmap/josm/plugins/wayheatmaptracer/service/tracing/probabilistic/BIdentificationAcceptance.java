package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Offline preregistered controls; no result from this class grants production safety authority. */
final class BIdentificationAcceptance {
    enum Capability { KNOWN_NATIVE, PHYSICAL_SAMPLING_ONLY }
    enum PairKind { OVERSAMPLING, REVERSAL }
    enum Verdict { PASS, FAIL, UNAVAILABLE }
    enum Code {
        NORMALIZATION, INVALID_MEASURED_SUPPORT, ZERO_EXCESS_MASS, CENSORED_PARITY,
        ELEMENTARY_COMPONENTS, EXACT_ANCHORS, SOURCE_IDENTITY, PROFILE_CORRESPONDENCE,
        MODE_CORRESPONDENCE, POSITIVE_MATCH, MASS_DELTA, MEDIAN_SHIFT, IQR_SHRINK,
        RAW_ROUTE, APEX_HEIGHT, APEX_POLYLINE_DISTANCE, DISPLACED_LATERAL_ERROR,
        PARALLEL_SEGMENT_UNION, EMPTY_SELECTOR
    }
    enum GeometrySelector { APEX_X60_PM2, DISPLACED_X40_80, PARALLEL_SEGMENTS_X40_80 }
    record Check(Code code,Verdict verdict,int count,OptionalDouble observed,OptionalDouble limit) {
        Check(Code code,Verdict verdict,int count) {this(code,verdict,count,OptionalDouble.empty(),OptionalDouble.empty());}
    }
    record Checks(List<Check> checks) {
        Checks {checks=List.copyOf(checks);}
        boolean passed() {return !checks.isEmpty()&&checks.stream().allMatch(c->c.verdict()==Verdict.PASS);}
    }
    record Quantiles(double q25Meters,double medianMeters,double q75Meters) {
        Quantiles {
            if(!Double.isFinite(q25Meters)||!Double.isFinite(medianMeters)||!Double.isFinite(q75Meters)
                    ||q25Meters>medianMeters||medianMeters>q75Meters)throw new IllegalArgumentException("Invalid conditional quantiles");
        }
    }
    record SourceKey(String sourceGroup,String nativeHash,String nativeFrame,Capability capability) {
        SourceKey {
            if(sourceGroup==null||sourceGroup.isBlank()||nativeHash==null||!nativeHash.matches("[0-9a-f]{64}")
                    ||nativeFrame==null||nativeFrame.isBlank()||capability==null)throw new IllegalArgumentException("Original native-source identity is required");
        }
    }
    record Interval(double minimumMeters,double maximumMeters) {
        Interval {
            if(!Double.isFinite(minimumMeters)||!Double.isFinite(maximumMeters)||minimumMeters>maximumMeters)
                throw new IllegalArgumentException("Invalid original interval");
        }
    }
    record ModeSummary(String lineage,Optional<Interval> core,Optional<Interval> shoulder,
            double mass,OptionalDouble rhoMeters,Optional<Quantiles> quantiles) {
        ModeSummary {
            if(lineage==null||lineage.isBlank()||core==null||shoulder==null||rhoMeters==null||quantiles==null
                    ||!Double.isFinite(mass)||mass<0||mass>1
                    ||rhoMeters.isPresent()&&(!(rhoMeters.getAsDouble()>0)||!Double.isFinite(rhoMeters.getAsDouble())))
                throw new IllegalArgumentException("Invalid optional mode summary");
        }
    }
    record ProfileSummary(double chainageMeters,MetricPoint anchor,MetricPoint canonicalNormal,
            boolean exactAnchor,List<ModeSummary> modes) {
        ProfileSummary {modes=List.copyOf(modes);}
    }
    record SceneSummary(CaseDefinition definition,SourceKey source,List<ProfileSummary> profiles) {
        SceneSummary {profiles=List.copyOf(profiles);}
    }
    record PairBinding(PairKind kind,CaseDefinition first,CaseDefinition second,SourceKey source) { }
    record ProfileInspection(ProfileSummary summary,Checks checks,BObservationMixture.ReducedRows rows) { }

    private static final long MAX_WORK=8_000_000;
    private static final class Work {
        final CancellationProbe cancellation;long count;
        Work(CancellationProbe cancellation) {
            if(cancellation==null)throw new IllegalArgumentException("Cancellation is required");this.cancellation=cancellation;
        }
        void step() {charge(1);}
        void charge(long amount) {
            cancellation.checkpoint();if(amount<0||amount>MAX_WORK-count)throw new IllegalArgumentException("Acceptance work limit");count+=amount;
        }
    }
    private static final class Results {
        final EnumMap<Code,Check> checks=new EnumMap<>(Code.class);
        void test(Code code,boolean passed,int count) {add(new Check(code,passed?Verdict.PASS:Verdict.FAIL,count));}
        void add(Check check) {
            var old=checks.get(check.code());
            if(old==null)checks.put(check.code(),check);
            else checks.put(check.code(),new Check(check.code(),old.verdict()==Verdict.FAIL||check.verdict()==Verdict.FAIL?Verdict.FAIL:
                    old.verdict()==Verdict.UNAVAILABLE||check.verdict()==Verdict.UNAVAILABLE?Verdict.UNAVAILABLE:Verdict.PASS,
                    Math.addExact(old.count(),check.count()),maximum(old.observed(),check.observed()),check.limit().isPresent()?check.limit():old.limit()));
        }
        void metric(Code code,boolean pass,double observed,double limit) {
            add(new Check(code,pass?Verdict.PASS:Verdict.FAIL,1,OptionalDouble.of(observed),OptionalDouble.of(limit)));
        }
        Checks finish() {return new Checks(new ArrayList<>(checks.values()));}
        private static OptionalDouble maximum(OptionalDouble a,OptionalDouble b) {
            return a.isEmpty()?b:b.isEmpty()?a:OptionalDouble.of(Math.max(a.getAsDouble(),b.getAsDouble()));
        }
    }

    static Optional<Quantiles> quantiles(ProbabilisticProfile profile,ProbabilisticStateLattice lattice,
            BObservationMixture.Component component,boolean reverseAxis,CancellationProbe cancellation) {
        Work work=new Work(cancellation);work.step();
        if(profile==null||lattice==null||component==null||component.kind()!=ObservationComponent.Kind.MEASURED
                ||component.cellCount()!=lattice.cells().size()||lattice.cells().size()>96)
            throw new IllegalArgumentException("A bounded measured row on original cells is required");
        int size=lattice.cells().size();
        for(int s=0;s<size;s++) {work.step();bounds(profile,lattice,s);}
        if(component.mass()==0)return Optional.empty();
        double[] targets={.25,.5,.75};double[] values=new double[3];
        for(int q=0;q<targets.length;q++) {
            double cumulative=0;boolean found=false;
            for(int index=0;index<size;index++) {
                work.step();int s=reverseAxis?size-1-index:index;
                var interval=bounds(profile,lattice,s);
                double left=reverseAxis?-interval.maximumMeters():interval.minimumMeters();
                double right=reverseAxis?-interval.minimumMeters():interval.maximumMeters();
                double mass=StrictMath.exp(component.logL0DensityAt(s))*lattice.cells().get(s).quadratureWidthMeters()/BObservationMixture.REFERENCE_LENGTH_METERS;
                if(!Double.isFinite(mass)||mass<0)throw new IllegalArgumentException("Invalid conditional cell probability");
                double next=cumulative+mass;
                if(mass>0&&next>=targets[q]) {
                    values[q]=left+(targets[q]-cumulative)/mass*(right-left);found=true;break;
                }
                cumulative=next;
            }
            if(!found)return Optional.empty();
        }
        cancellation.checkpoint();return Optional.of(new Quantiles(values[0],values[1],values[2]));
    }
    static Optional<PairBinding> bindPair(CaseDefinition first,SourceKey firstSource,
            CaseDefinition second,SourceKey secondSource) {
        if(first==null||second==null||firstSource==null||secondSource==null||!firstSource.equals(secondSource)
                ||first.key().split()!=Split.SELECT||second.key().split()!=Split.SELECT
                ||!first.key().sourceGroupId().equals(firstSource.sourceGroup())
                ||!first.key().sourceGroupId().equals(second.key().sourceGroupId())
                ||!first.key().geometryGroupId().equals(second.key().geometryGroupId())
                ||!first.contrastId().equals(second.contrastId())||first.phase()!=second.phase()
                ||first.sourcePitchMeters()!=second.sourcePitchMeters())return Optional.empty();
        if(first.variantId().equals("v0")&&second.variantId().equals("v6")&&first.oversampling()==1&&second.oversampling()==2
                &&first.reversed()==second.reversed()&&first.side().equals(second.side()))
            return Optional.of(new PairBinding(PairKind.OVERSAMPLING,first,second,firstSource));
        if(first.contrastId().equals("multiple-physical-pitches-vs-reversed-source-order")
                &&first.variantId().equals(second.variantId())&&first.oversampling()==second.oversampling()
                &&first.side().equals("left")&&second.side().equals("right")&&!first.reversed()&&second.reversed())
            return Optional.of(new PairBinding(PairKind.REVERSAL,first,second,firstSource));
        return Optional.empty();
    }
    static Checks comparePair(PairBinding binding,SceneSummary first,SceneSummary second,CancellationProbe cancellation) {
        Work work=new Work(cancellation);work.step();var results=new Results();
        boolean identity=binding!=null&&first!=null&&second!=null&&binding.first().equals(first.definition())
                &&binding.second().equals(second.definition())&&binding.source().equals(first.source())&&binding.source().equals(second.source());
        results.test(Code.SOURCE_IDENTITY,identity,1);if(!identity)return results.finish();
        int size=first.profiles().size();
        if(size==0||size>4096||size!=second.profiles().size()) {results.test(Code.PROFILE_CORRESPONDENCE,false,1);return results.finish();}
        double length=first.profiles().get(size-1).chainageMeters();int eligible=0;
        boolean reversalGeometry=binding.kind()!=PairKind.REVERSAL
                ||registeredAffineReversal(first,false,work)&&registeredAffineReversal(second,true,work);
        for(int i=0;i<size;i++) {
            work.step();var a=first.profiles().get(i);var b=second.profiles().get(binding.kind()==PairKind.REVERSAL?size-1-i:i);
            boolean chainage=binding.kind()==PairKind.REVERSAL?a.chainageMeters()+b.chainageMeters()==length:a.chainageMeters()==b.chainageMeters();
            boolean anchors=binding.kind()==PairKind.REVERSAL?reversalGeometry:a.anchor().equals(b.anchor());
            boolean matched=chainage&&anchors&&a.canonicalNormal().equals(b.canonicalNormal())&&a.exactAnchor()==b.exactAnchor();
            results.test(Code.PROFILE_CORRESPONDENCE,matched,1);if(!matched||a.exactAnchor())continue;
            if(a.modes().isEmpty()&&b.modes().isEmpty())continue; // Separate null checks; no fake zero quantile.
            if(a.modes().size()!=b.modes().size()) {results.test(Code.MODE_CORRESPONDENCE,false,1);continue;}
            var used=new HashSet<Integer>();
            for(var mode:a.modes()) {
                int selected=-1,count=0;
                for(int j=0;j<b.modes().size();j++) {work.step();if(corresponds(mode,b.modes().get(j))){selected=j;count++;}}
                boolean unique=count==1&&used.add(selected);
                if(unique) {
                    int reverseCount=0;
                    for(var other:a.modes()) {work.step();if(corresponds(other,b.modes().get(selected)))reverseCount++;}
                    unique=reverseCount==1;
                }
                results.test(Code.MODE_CORRESPONDENCE,unique,1);if(!unique)continue;
                var other=b.modes().get(selected);
                boolean available=mode.mass()>0&&other.mass()>0&&mode.rhoMeters().isPresent()&&other.rhoMeters().isPresent()
                        &&mode.quantiles().isPresent()&&other.quantiles().isPresent();
                results.test(Code.POSITIVE_MATCH,available,1);if(!available)continue;eligible++;
                double delta=Math.abs(mode.mass()-other.mass());results.metric(Code.MASS_DELTA,delta<=.02,delta,.02);
                var aq=mode.quantiles().orElseThrow();var bq=other.quantiles().orElseThrow();
                double displacement=Math.abs(aq.medianMeters()-bq.medianMeters())/Math.min(mode.rhoMeters().getAsDouble(),other.rhoMeters().getAsDouble());
                results.metric(Code.MEDIAN_SHIFT,displacement<=1,displacement,1);
                double aWidth=aq.q75Meters()-aq.q25Meters(),bWidth=bq.q75Meters()-bq.q25Meters();
                if(!(aWidth>0)||!(bWidth>0))results.test(Code.IQR_SHRINK,false,1);
                else {
                    double shrink=1-bWidth/aWidth;
                    if(binding.kind()==PairKind.REVERSAL)shrink=Math.max(shrink,1-aWidth/bWidth);
                    results.metric(Code.IQR_SHRINK,shrink<=.1,shrink,.1);
                }
            }
        }
        results.test(Code.POSITIVE_MATCH,eligible>0,eligible);cancellation.checkpoint();return results.finish();
    }

    /**
     * The registered reversal contrast uses only the source segment (0,30)-(120,30).
     * Validate every summary anchor against that scene's own ordered endpoint arithmetic;
     * forward and reversed interpolation can round differently at the same physical point.
     */
    private static boolean registeredAffineReversal(SceneSummary scene,boolean reversed,Work work) {
        if(scene==null||scene.profiles().size()<2) return false;
        var definition=scene.definition();
        if(!"multiple-physical-pitches-vs-reversed-source-order".equals(definition.contrastId())
                ||definition.reversed()!=reversed
                ||!definition.side().equals(reversed?"right":"left")) return false;
        MetricPoint start=reversed?new MetricPoint(120.0,30.0):new MetricPoint(0.0,30.0);
        MetricPoint end=reversed?new MetricPoint(0.0,30.0):new MetricPoint(120.0,30.0);
        var profiles=scene.profiles();
        double length=profiles.get(profiles.size()-1).chainageMeters();
        if(length!=120.0||profiles.get(0).chainageMeters()!=0.0
                ||!profiles.get(0).anchor().equals(start)
                ||!profiles.get(profiles.size()-1).anchor().equals(end)) return false;
        double previous=Double.NEGATIVE_INFINITY;
        for(int index=0;index<profiles.size();index++) {
            work.step();var profile=profiles.get(index);double chainage=profile.chainageMeters();
            double expectedTarget=index==profiles.size()-1?length:index*length/(profiles.size()-1);
            if(!Double.isFinite(chainage)||chainage<0.0||chainage>length||chainage<=previous
                    ||chainage!=expectedTarget) return false;
            previous=chainage;
            double fraction=(chainage-0.0)/(length-0.0);
            MetricPoint expected=new MetricPoint(start.xMeters()+fraction*(end.xMeters()-start.xMeters()),
                    start.yMeters()+fraction*(end.yMeters()-start.yMeters()));
            boolean endpoint=index==0||index==profiles.size()-1;
            if(!profile.anchor().equals(expected)||!profile.canonicalNormal().equals(new MetricPoint(0.0,1.0))
                    ||profile.exactAnchor()!=endpoint) return false;
        }
        return true;
    }

    static ProfileInspection inspectProfile(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
            BObservationMixture mixture,BIdentifiedObservationKernel.Parameters parameters,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        Work work=new Work(cancellation);work.step();
        if(facts==null||lattice==null||mixture==null||parameters==null)throw new IllegalArgumentException("Complete profile controls are required");
        if(lattice.cells().size()>96||facts.baseline().modes().size()>4096||facts.modes().size()>4096)
            throw new IllegalArgumentException("Acceptance profile size limit");
        work.charge(Math.multiplyExact((long)mixture.componentCount(),
                1L+facts.baseline().modes().size()+facts.modes().size()));
        var transaction=owner==null?null:owner.child("observation-acceptance");
        try {
            var reduced=mixture.reduce(lattice,cancellation,transaction); // The sole normalization/full-U authority.
            var profile=facts.baseline();var results=new Results();results.test(Code.NORMALIZATION,true,1);
            long bytes=8192L+4096L*profile.modes().size();
            for(var mode:profile.modes())bytes=Math.addExact(bytes,4L*mode.evidenceLineage().length());
            var output=transaction==null?null:transaction.reserve(bytes);
            var scratch=transaction==null?null:transaction.child("acceptance-scratch");
            try {
                if(scratch!=null)scratch.reserve(1024L+128L*lattice.cells().size());
                double[] priors=allocated(scratch,AttemptMemoryLedger.arrayBytes(lattice.components().size(),8),
                        ()->ProbabilisticObservationModel.effectiveComponentPriors(profile,lattice.components()));
                boolean invalid=true,censored=true,zero=true,elementary=true;double missingMass=-1,expectedCensored=0,measuredMass=0;
                boolean reverse=reverse(profile.normalUnit());var modes=new ArrayList<ModeSummary>();var seen=new HashSet<String>();
                for(int index=0;index<mixture.componentCount();index++) {
                    work.step();var component=mixture.component(index);
                    switch(component.kind()) {
                        case MISSING -> missingMass=component.mass();
                        case CENSORED -> {
                            expectedCensored+=priors[index];
                            double[] original=allocated(scratch,AttemptMemoryLedger.arrayBytes(lattice.cells().size(),8),()->new double[lattice.cells().size()]);
                            ProbabilisticObservationModel.fillBaselineCensoredRow(original,profile,lattice.cells(),lattice.components().get(index),cancellation);
                            censored&=component.mass()==priors[index];
                            for(int s=0;s<original.length;s++) {work.step();censored&=Double.doubleToLongBits(original[s])==Double.doubleToLongBits(component.logL0DensityAt(s));}
                        }
                        case MEASURED -> {
                            measuredMass+=component.mass();
                            var original=profile.modes().stream().filter(m->m.evidenceLineage().equals(component.lineage())).toList();
                            if(original.isEmpty())elementary=false;
                            boolean parent=!original.isEmpty()&&original.stream().allMatch(ProbabilisticProfile.Mode::groupedParent);
                            if(parent&&component.mass()!=0)elementary=false;
                            var measurements=facts.modes().stream().filter(m->m.lineage().equals(component.lineage())).toList();
                            BIdentificationFacts.ModeFacts fact=measurements.size()==1?measurements.get(0):null;
                            boolean nullExcess=fact!=null&&has(fact,BIdentificationFacts.Feature.BACKGROUND)
                                    &&has(fact,BIdentificationFacts.Feature.RMS_DESCRIPTOR)&&has(fact,BIdentificationFacts.Feature.FILTER_DESCRIPTOR)
                                    &&has(fact,BIdentificationFacts.Feature.SHOULDER_MINIMUM)&&has(fact,BIdentificationFacts.Feature.SHOULDER_MAXIMUM);
                            double threshold=nullExcess?value(fact,BIdentificationFacts.Feature.BACKGROUND)+parameters.nuisanceMultiplier()*Math.max(
                                    value(fact,BIdentificationFacts.Feature.RMS_DESCRIPTOR),value(fact,BIdentificationFacts.Feature.FILTER_DESCRIPTOR)):0;
                            boolean noPositive=nullExcess;
                            for(int s=0;s<lattice.cells().size();s++) {
                                // Original interpolation is a binary search; reserve its bounded temporary objects in scratch.
                                work.charge(2L+32-Integer.numberOfLeadingZeros(Math.max(1,profile.samples().size())));
                                double u=lattice.cells().get(s).offsetMeters();
                                double intensity=ProbabilisticObservationModel.interpolate(profile.samples(),u);
                                boolean density=component.logL0DensityAt(s)!=Double.NEGATIVE_INFINITY;
                                if(!Double.isFinite(intensity)&&density)invalid=false;
                                if(nullExcess&&u>=value(fact,BIdentificationFacts.Feature.SHOULDER_MINIMUM)
                                        &&u<=value(fact,BIdentificationFacts.Feature.SHOULDER_MAXIMUM)
                                        &&Double.isFinite(intensity)&&intensity>threshold)noPositive=false;
                            }
                            if(noPositive) {
                                zero&=component.mass()==0;
                                for(int s=0;s<component.cellCount();s++){work.step();zero&=component.logL0DensityAt(s)==Double.NEGATIVE_INFINITY;}
                            }
                            if(!parent&&seen.add(component.lineage())) {
                                var core=core(fact,reverse);var shoulder=shoulder(fact,reverse);
                                var rho=rho(profile,fact,work);Optional<Quantiles> q;
                                try {q=quantiles(profile,lattice,component,reverse,cancellation);}
                                catch(IllegalArgumentException badGeometry){q=Optional.empty();}
                                modes.add(new ModeSummary(component.lineage(),core,shoulder,component.mass(),rho,q));
                            }
                        }
                    }
                }
                for(var original:profile.modes())if(!original.groupedParent()) {
                    work.step();long matches=0;
                    for(int j=0;j<mixture.componentCount();j++) {work.step();if(mixture.component(j).kind()==ObservationComponent.Kind.MEASURED
                            &&mixture.component(j).lineage().equals(original.evidenceLineage()))matches++;}
                    elementary&=matches==1;
                }
                if(measuredMass==0)zero&=missingMass==1-expectedCensored;
                results.test(Code.INVALID_MEASURED_SUPPORT,invalid,lattice.cells().size());results.test(Code.ZERO_EXCESS_MASS,zero,1);
                results.test(Code.CENSORED_PARITY,censored,1);results.test(Code.ELEMENTARY_COMPONENTS,elementary,profile.modes().size());
                var normal=profile.normalUnit();double nx=reverse?-normal.xMeters():normal.xMeters(),ny=reverse?-normal.yMeters():normal.yMeters();
                var summary=new ProfileSummary(profile.chainageMeters(),new MetricPoint(profile.anchor().xMeters(),profile.anchor().yMeters()),
                        new MetricPoint(nx==0?0:nx,ny==0?0:ny),profile.exactAnchorOffsetMeters().isPresent()||lattice.cells().stream().anyMatch(LateralStateCell::exactAnchor),modes);
                var result=new ProfileInspection(summary,results.finish(),reduced);cancellation.checkpoint();
                if(output!=null)output.adopt(result);if(scratch!=null)scratch.close();
                if(transaction!=null)transaction.transferTo(owner);return result;
            } finally {if(output!=null)output.close();if(scratch!=null)scratch.close();}
        } finally {if(transaction!=null)transaction.close();}
    }
    static Checks exactAnchors(List<ProbabilisticProfile> profiles,List<MetricPoint> points,CancellationProbe cancellation) {
        Work work=new Work(cancellation);work.step();var result=new Results();
        if(profiles==null||points==null||profiles.size()!=points.size()||profiles.size()>4096) {
            result.test(Code.EXACT_ANCHORS,false,0);return result.finish();
        }
        int count=0;boolean pass=true;
        for(int i=0;i<profiles.size();i++) {
            work.step();var p=profiles.get(i);
            if(p.exactAnchorOffsetMeters().isPresent()) {
                count++;double u=p.exactAnchorOffsetMeters().getAsDouble();
                var expected=new MetricPoint(p.anchor().xMeters()+p.normalUnit().xMeters()*u,p.anchor().yMeters()+p.normalUnit().yMeters()*u);
                pass&=expected.equals(points.get(i));
            }
        }
        result.test(Code.EXACT_ANCHORS,pass,count);return result.finish();
    }
    static Checks geometry(GeometrySelector selector,Scene scene,List<MetricPoint> points,double pitchMeters,
            CancellationProbe cancellation) {
        return geometry(selector,scene,points,pitchMeters,cancellation,null);
    }
    static Checks geometry(GeometrySelector selector,Scene scene,List<MetricPoint> points,double pitchMeters,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        Work work=new Work(cancellation);work.step();
        if(selector==null||scene==null||points==null||points.size()>4096||!(pitchMeters>0)||!Double.isFinite(pitchMeters))
            throw new IllegalArgumentException("Registered physical geometry inputs are required");
        long vertices=0,curves=0;
        for(var label:scene.truth().regions())for(var curve:label.acceptableCurves()) {
            work.step();vertices=Math.addExact(vertices,curve.size());curves++;
        }
        work.charge(vertices);
        var transaction=owner==null?null:owner.child("acceptance-geometry");
        try {
            var output=transaction==null?null:transaction.reserve(4096);
            var scratch=transaction==null?null:transaction.child("geometry-scratch");
            try {
                // Polygon copies, original-boundary points, clipped endpoints and temporary lists.
                if(scratch!=null)scratch.reserve(Math.addExact(8192L,Math.addExact(256L*vertices,128L*curves)));
                var checks=geometry(selector,scene,points,pitchMeters,work,scratch);
                cancellation.checkpoint();if(output!=null)output.adopt(checks);
                if(scratch!=null)scratch.close();if(transaction!=null)transaction.transferTo(owner);return checks;
            } finally {if(output!=null)output.close();if(scratch!=null)scratch.close();}
        } finally {if(transaction!=null)transaction.close();}
    }
    private static Checks geometry(GeometrySelector selector,Scene scene,List<MetricPoint> points,double pitchMeters,
            Work work,AttemptMemoryLedger.Owner scratch) {
        var cancellation=work.cancellation;
        var result=new Results();result.test(Code.RAW_ROUTE,points.size()>=2,points.size());
        if(points.size()<2)return result.finish();
        var curves=new ArrayList<List<MetricPoint>>();
        for(var label:scene.truth().regions())for(var curve:label.acceptableCurves()) {work.step();if(curve.size()>=2)curves.add(curve);}
        if(curves.isEmpty()){result.add(new Check(Code.RAW_ROUTE,Verdict.UNAVAILABLE,0));return result.finish();}
        switch(selector) {
            case APEX_X60_PM2 -> {
                var expected=uniqueY(curves,60,work);double height=Double.NEGATIVE_INFINITY;int count=0;
                for(var point:points){work.step();if(Math.abs(point.xMeters()-60)<=2){height=Math.max(height,point.yMeters());count++;}}
                result.test(Code.EMPTY_SELECTOR,count>0,count);
                if(expected.isEmpty())result.add(new Check(Code.APEX_HEIGHT,Verdict.UNAVAILABLE,0));
                else {
                    result.metric(Code.APEX_HEIGHT,count>0&&height>=expected.getAsDouble()-pitchMeters,
                            count==0?0:height,expected.getAsDouble()-pitchMeters);
                    var apex=new MetricPoint(60,expected.getAsDouble());double distance=Double.POSITIVE_INFINITY;
                    for(int i=1;i<points.size();i++){work.step();distance=Math.min(distance,distance(apex,points.get(i-1),points.get(i)));}
                    result.metric(Code.APEX_POLYLINE_DISTANCE,distance<=pitchMeters,distance,pitchMeters);
                }
            }
            case DISPLACED_X40_80 -> {
                double maximum=0;int count=0;boolean measured=true;
                for(int i=1;i<points.size();i++) {
                    work.step();var segment=clip(points.get(i-1),points.get(i),40,80);if(segment.isEmpty())continue;count++;
                    var a=segment.get().get(0);var b=segment.get().get(1);
                    var ya=uniqueY(curves,a.xMeters(),work);var yb=uniqueY(curves,b.xMeters(),work);
                    if(ya.isEmpty()||yb.isEmpty()){measured=false;continue;}
                    maximum=Math.max(maximum,Math.max(Math.abs(a.yMeters()-ya.getAsDouble()),Math.abs(b.yMeters()-yb.getAsDouble())));
                    for(var curve:curves)for(var point:curve) {
                        work.step();double x=point.xMeters();
                        if(x>Math.min(a.xMeters(),b.xMeters())&&x<Math.max(a.xMeters(),b.xMeters())) {
                            double y=a.yMeters()+(b.yMeters()-a.yMeters())*(x-a.xMeters())/(b.xMeters()-a.xMeters());
                            maximum=Math.max(maximum,Math.abs(y-point.yMeters()));
                        }
                    }
                }
                result.test(Code.EMPTY_SELECTOR,count>0,count);
                result.metric(Code.DISPLACED_LATERAL_ERROR,count>0&&measured&&maximum<=pitchMeters,maximum,pitchMeters);
            }
            case PARALLEL_SEGMENTS_X40_80 -> {
                var polygons=new ArrayList<List<MetricPoint>>();
                for(var curve:curves) {
                    work.charge(Math.multiplyExact(4L,Math.multiplyExact(curve.size(),curve.size())));
                    var polygon=new ArrayList<MetricPoint>();
                    for(var p:curve)polygon.add(new MetricPoint(p.xMeters(),p.yMeters()-pitchMeters));
                    for(int i=curve.size()-1;i>=0;i--){var p=curve.get(i);polygon.add(new MetricPoint(p.xMeters(),p.yMeters()+pitchMeters));}
                    polygons.add(polygon);
                }
                var union=new MetricRegion(polygons);int count=0;boolean pass=true;
                for(int i=1;i<points.size();i++) {
                    work.step();var segment=clip(points.get(i-1),points.get(i),40,80);if(segment.isEmpty())continue;count++;
                    pass&=union.containsSegment(segment.get().get(0),segment.get().get(1),cancellation,work::charge,scratch);
                }
                result.test(Code.EMPTY_SELECTOR,count>0,count);result.test(Code.PARALLEL_SEGMENT_UNION,count>0&&pass,count);
            }
        }
        cancellation.checkpoint();return result.finish();
    }

    private static Interval bounds(ProbabilisticProfile profile,ProbabilisticStateLattice lattice,int s) {
        var cells=lattice.cells();double left=s==0?profile.minimumOffsetMeters():.5*(cells.get(s-1).offsetMeters()+cells.get(s).offsetMeters());
        double right=s==cells.size()-1?profile.maximumOffsetMeters():.5*(cells.get(s).offsetMeters()+cells.get(s+1).offsetMeters());
        if(!(right>left)||!Double.isFinite(left)||!Double.isFinite(right)||right-left!=cells.get(s).quadratureWidthMeters())
            throw new IllegalArgumentException("Original admitted boundaries and quadrature width disagree");
        return new Interval(left,right);
    }
    private static boolean corresponds(ModeSummary a,ModeSummary b) {
        if(a.core().isEmpty()||b.core().isEmpty()||a.shoulder().isEmpty()||b.shoulder().isEmpty())return false;
        var ac=a.core().get();var bc=b.core().get();var as=a.shoulder().get();var bs=b.shoulder().get();
        return Math.max(as.minimumMeters(),bs.minimumMeters())<Math.min(as.maximumMeters(),bs.maximumMeters())
                &&Math.max(ac.minimumMeters(),bs.minimumMeters())<=Math.min(ac.maximumMeters(),bs.maximumMeters())
                &&Math.max(bc.minimumMeters(),as.minimumMeters())<=Math.min(bc.maximumMeters(),as.maximumMeters());
    }
    private static boolean reverse(MetricPoint normal) {return normal.xMeters()<0||normal.xMeters()==0&&normal.yMeters()<0;}
    private static boolean has(BIdentificationFacts.ModeFacts facts,BIdentificationFacts.Feature feature) {
        return facts.fact(feature).availability()!=BIdentificationFacts.Availability.UNAVAILABLE;
    }
    private static double value(BIdentificationFacts.ModeFacts facts,BIdentificationFacts.Feature feature) {return facts.fact(feature).value().orElseThrow();}
    private static Optional<Interval> core(BIdentificationFacts.ModeFacts facts,boolean reverse) {
        if(facts==null)return Optional.empty();
        for(var receipt:facts.provenance())if(receipt instanceof BIdentificationFacts.StageReceipt stage
                &&stage.original().stage()==org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.ObservationStage.RAW
                &&stage.original().available())return Optional.of(reverse?new Interval(-stage.lastOffsetMeters(),-stage.firstOffsetMeters()):new Interval(stage.firstOffsetMeters(),stage.lastOffsetMeters()));
        return Optional.empty();
    }
    private static Optional<Interval> shoulder(BIdentificationFacts.ModeFacts facts,boolean reverse) {
        if(facts==null||!has(facts,BIdentificationFacts.Feature.SHOULDER_MINIMUM)||!has(facts,BIdentificationFacts.Feature.SHOULDER_MAXIMUM))return Optional.empty();
        double a=value(facts,BIdentificationFacts.Feature.SHOULDER_MINIMUM),b=value(facts,BIdentificationFacts.Feature.SHOULDER_MAXIMUM);
        return Optional.of(reverse?new Interval(-b,-a):new Interval(a,b));
    }
    private static OptionalDouble rho(ProbabilisticProfile profile,BIdentificationFacts.ModeFacts facts,Work work) {
        if(facts==null)return OptionalDouble.empty();
        if(profile.nativePitchKnown())return has(facts,BIdentificationFacts.Feature.NATIVE_PITCH)?OptionalDouble.of(value(facts,BIdentificationFacts.Feature.NATIVE_PITCH)):OptionalDouble.empty();
        var bracket=shoulder(facts,false);if(bracket.isEmpty())return OptionalDouble.empty();double maximum=0;
        for(int i=1;i<profile.samples().size();i++) {
            work.step();var a=profile.samples().get(i-1);var b=profile.samples().get(i);
            if(a.offsetMeters()>=bracket.get().minimumMeters()&&b.offsetMeters()<=bracket.get().maximumMeters())maximum=Math.max(maximum,b.offsetMeters()-a.offsetMeters());
        }
        return maximum>0?OptionalDouble.of(maximum):OptionalDouble.empty();
    }
    private static OptionalDouble uniqueY(List<List<MetricPoint>> curves,double x,Work work) {
        double value=0;boolean found=false;
        for(var curve:curves)for(int i=1;i<curve.size();i++) {
            work.step();var a=curve.get(i-1);var b=curve.get(i);
            if(x<Math.min(a.xMeters(),b.xMeters())||x>Math.max(a.xMeters(),b.xMeters()))continue;
            if(a.xMeters()==b.xMeters()) {if(x==a.xMeters())return OptionalDouble.empty();continue;}
            double y=a.yMeters()+(b.yMeters()-a.yMeters())*(x-a.xMeters())/(b.xMeters()-a.xMeters());
            if(found&&value!=y)return OptionalDouble.empty();value=y;found=true;
        }
        return found?OptionalDouble.of(value):OptionalDouble.empty();
    }
    private static Optional<List<MetricPoint>> clip(MetricPoint a,MetricPoint b,double left,double right) {
        if(a.equals(b))return Optional.empty();
        double dx=b.xMeters()-a.xMeters(),lo=0,hi=1;
        if(dx==0) {if(a.xMeters()<left||a.xMeters()>right)return Optional.empty();}
        else {lo=Math.max(0,Math.min((left-a.xMeters())/dx,(right-a.xMeters())/dx));hi=Math.min(1,Math.max((left-a.xMeters())/dx,(right-a.xMeters())/dx));}
        if(hi<=lo)return Optional.empty();
        return Optional.of(List.of(new MetricPoint(a.xMeters()+lo*dx,a.yMeters()+lo*(b.yMeters()-a.yMeters())),
                new MetricPoint(a.xMeters()+hi*dx,a.yMeters()+hi*(b.yMeters()-a.yMeters()))));
    }
    private static double distance(MetricPoint p,MetricPoint a,MetricPoint b) {
        double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters(),squared=dx*dx+dy*dy;
        double t=squared==0?0:Math.max(0,Math.min(1,((p.xMeters()-a.xMeters())*dx+(p.yMeters()-a.yMeters())*dy)/squared));
        return StrictMath.hypot(p.xMeters()-a.xMeters()-t*dx,p.yMeters()-a.yMeters()-t*dy);
    }
    private static <T> T allocated(AttemptMemoryLedger.Owner owner,long bytes,java.util.function.Supplier<T> supplier) {
        if(owner==null)return supplier.get();
        try(var reservation=owner.reserve(bytes)){T value=supplier.get();reservation.adopt(value);return value;}
    }
    private BIdentificationAcceptance() { }
}
