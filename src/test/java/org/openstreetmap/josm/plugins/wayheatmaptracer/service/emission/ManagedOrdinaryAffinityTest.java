package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

class ManagedOrdinaryAffinityTest {
    @TempDir Path cache;
    @Test void unresolvedRelevantCrossLinksCannotProveLocalIdentity()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("settled-matrix-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;
            var a=domain.observedAt(row).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            var b=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var actual=classifier.classify(a,b);assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE,actual.status());
                var links=new ArrayList<>(actual.proof().links().subList(0,4));
                var identity=ManagedOrdinaryAffinity.class.getDeclaredMethod("uniqueIdentity",List.class,int.class,int.class,int.class);identity.setAccessible(true);
                assertEquals(true,identity.invoke(classifier,links,0,2,2),"Issued ordinary rotated alternatives must be settled");
                var old=links.get(1);
                for(var unavailable:List.of(ManagedOrdinaryAffinity.LinkStatus.UNAVAILABLE,ManagedOrdinaryAffinity.LinkStatus.NOT_QUERIED)){
                    links.set(1,new ManagedOrdinaryAffinity.DirectLink(old.fromStation(),old.toStation(),old.fromMode(),old.toMode(),old.fromOwner(),old.toOwner(),old.threshold(),unavailable,ManagedOrdinaryAffinity.LinkReason.DOMAIN_UNRESOLVED,Optional.empty(),old.continuation()));
                    assertEquals(false,identity.invoke(classifier,links,0,2,2),"An unresolved issued relevant alternative cannot establish uniqueness: "+unavailable);
                }
            }
        }});
    }
    @Test void continuationEnclosureEqualityAndStraddlingAreNotExclusion(){
        assertEquals(ManagedOrdinaryAffinity.LinkStatus.UNAVAILABLE,new ManagedOrdinaryAffinity.ContinuationBounds(1,2,1.5,1.6).status());
        assertEquals(ManagedOrdinaryAffinity.LinkStatus.UNAVAILABLE,new ManagedOrdinaryAffinity.ContinuationBounds(1.6,2,1.5,1.6).status());
        assertEquals(ManagedOrdinaryAffinity.LinkStatus.ADMITTED,new ManagedOrdinaryAffinity.ContinuationBounds(1,1.5,1.5,1.6).status());
        assertEquals(ManagedOrdinaryAffinity.LinkStatus.REJECTED,new ManagedOrdinaryAffinity.ContinuationBounds(Math.nextUp(1.6),2,1.5,1.6).status());
    }
    @Test void realIssuedModeLinkAcrossOriginalSourceVoidIsUnavailableNotExcluded()throws Exception{
        managed(new Spec(.60,true,false,false,CancellationProbe.NONE,60,false,Defect.SOURCE_TURN),f->{try(var owner=f.input.openExecutionOwner("source-void-link")){
            var binding=f.input.legacyBinding().orElseThrow();var domain=new LegacyEmissionDomain(binding,f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            var context=f.input.context();int last=binding.observations().size()-1;
            var ra=binding.observations().get(0).measurementProfile().samplingAnchor().rasterPoint();var rb=binding.observations().get(last).measurementProfile().samplingAnchor().rasterPoint();
            var a=context.metricAt(ra);var b=context.metricAt(rb);double length=a.distanceTo(b);
            var v=new MetricPoint((b.xMeters()-a.xMeters())/length,(b.yMeters()-a.yMeters())/length);var n=new MetricPoint(-v.yMeters(),v.xMeters());
            try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input);var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var pa=session.metricAnchoredQuery(0,ra,n);var pb=session.metricAnchoredQuery(binding.observations().get(last).retainedObservationIndex(),rb,n);
                assertEquals(2,pa.modes().size());assertEquals(2,pb.modes().size());
                assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,pa.modes().get(0).support().availability());assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,pb.modes().get(0).support().availability());
                var family=session.freezeFamily(a,v,n,-100,length+100);
                var geometry=new ManagedOrdinaryAffinity.Domain(a,v,n,-90,length+90,-100,length+100,28,context.minimumNativePitchMeters().orElseThrow(),-28,28,4);
                var method=ManagedOrdinaryAffinity.class.getDeclaredMethod("link",NativeMeasuredProfileReceipt.Family.class,ManagedOrdinaryAffinity.Domain.class,int.class,int.class,int.class,int.class,NativeMeasuredProfileReceipt.Profile.class,NativeMeasuredProfileReceipt.Profile.class);method.setAccessible(true);
                var link=(ManagedOrdinaryAffinity.DirectLink)method.invoke(classifier,family,geometry,0,1,0,0,pa,pb);
                assertEquals(ManagedOrdinaryAffinity.LinkStatus.ADMITTED,link.continuation().orElseThrow().status());
                assertEquals(ManagedOrdinaryAffinity.LinkReason.SOURCE_AUTHORITY,link.reason());assertEquals(ManagedOrdinaryAffinity.LinkStatus.UNAVAILABLE,link.status());
                assertTrue(link.scalar().isEmpty(),"An unavailable original-route proof never authorizes scalar sampling of this link");
            }
        }});
    }
    @Test void nativeProfileMutationDuringFinalUnpinCannotPublishAResult()throws Exception{
        var armed=new AtomicBoolean();var changed=new AtomicBoolean();
        var target=new java.util.concurrent.atomic.AtomicReference<java.awt.geom.Point2D.Double>();
        var work=new java.util.concurrent.atomic.AtomicReference<EmissionWorkBudget>();var backpointers=new java.util.concurrent.atomic.AtomicLong();
        CancellationProbe callback=()->{
            long count=work.get()==null?0:work.get().operationUnits(EmissionWorkBudget.Operation.BACKPOINTER_VISIT);
            var stack=armed.get()&&!changed.get()&&backpointers.getAndSet(count)!=count?Arrays.asList(Thread.currentThread().getStackTrace()):List.<StackTraceElement>of();
            if(stack.stream().anyMatch(s->s.getMethodName().equals("unpinChain")&&s.getClassName().equals(LegacyEmissionKernel.class.getName()))
                    &&stack.stream().anyMatch(s->s.getMethodName().equals("result")&&s.getClassName().equals(LegacyEmissionKernel.class.getName()))){
                changed.set(true);target.get().x=Math.nextUp(target.get().x);
            }return false;
        };
        managed(new Spec(.60,true,false,false,callback,12,true),f->{
            var profiles=f.input.profiles().levelZeroProfiles();target.set(profiles.get(0).normalScreen());long baseline=f.ledger.currentBytes();
            work.set(f.input.workBudget());
            armed.set(true);
            var seeds=profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList();
            RuntimeException refusal=null;
            try(var unexpected=new LegacyEmissionKernel().solveManagedChartAffinity(f.input,profiles,seeds,List.of(),(p,o,s)->{armed.set(true);return o;},CancellationProbe.NONE)){}
            catch(RuntimeException failure){refusal=failure;}
            assertTrue(changed.get(),"Actual final charged unpin must execute the deterministic mutation");
            assertInstanceOf(IllegalStateException.class,refusal,"Publication must refuse the actual late-mutated producer profile");
            assertEquals(baseline,f.ledger.currentBytes(),"Refused publication closes every kernel/classifier/result payload");
        });
    }
    @ParameterizedTest @ValueSource(strings={"profile","selection","settings","input-close"})
    void laterFinalPrepaymentMutationCannotPublishEarlierValidatedFacts(String layer)throws Exception{
        var armed=new AtomicBoolean();var changed=new AtomicBoolean();var fixture=new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var finalPhase=new AtomicBoolean();var backpointers=new java.util.concurrent.atomic.AtomicLong();
        var work=new java.util.concurrent.atomic.AtomicReference<EmissionWorkBudget>();
        CancellationProbe callback=()->{
            long count=work.get()==null?0:work.get().operationUnits(EmissionWorkBudget.Operation.BACKPOINTER_VISIT);
            var stack=armed.get()&&!changed.get()&&(finalPhase.get()||backpointers.getAndSet(count)!=count)?Arrays.asList(Thread.currentThread().getStackTrace()):List.<StackTraceElement>of();
            if(stack.stream().anyMatch(s->s.getMethodName().equals("prepareManagedFinalPublication")))finalPhase.set(true);
            if(stack.stream().anyMatch(s->s.getMethodName().equals("prepareFinalPublicationCheck")&&s.getClassName().equals(ManagedSelectionWitness.class.getName()))){
                changed.set(true);var f=fixture.get();
                switch(layer){
                    case "profile" -> {var n=f.input.profiles().levelZeroProfiles().get(0).normalScreen();n.x=Math.nextUp(n.x);}
                    case "selection" -> {var n=f.source.getNode(0);var c=n.getCoor();n.setCoor(new LatLon(c.lat()+.000001,c.lon()));}
                    case "settings" -> f.settings.set(f.settings.get().withSearchHalfWidthMeters(29));
                    case "input-close" -> f.input.close();
                    default -> throw new AssertionError(layer);
                }
            }return false;
        };
        managed(new Spec(.60,true,false,false,callback,12,true),f->{fixture.set(f);
            work.set(f.input.workBudget());
            armed.set(true);
            var profiles=f.input.profiles().levelZeroProfiles();long baseline=f.ledger.currentBytes();
            assertThrows(IllegalStateException.class,()->{try(var unexpected=new LegacyEmissionKernel().solveManagedChartAffinity(f.input,profiles,profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList(),List.of(),(p,o,s)->{armed.set(true);return o;},CancellationProbe.NONE)){} });
            assertTrue(changed.get(),"Mutation must occur in the later source/witness prepayment");
            if(layer.equals("input-close")){
                assertTrue(f.retainedOwner.isClosed(),"Refused candidate must release the lease that deferred closed-input ownership");
                assertTrue(f.ledger.currentBytes()<baseline,"Only the independently open synthetic capture/witness remains");
            }else assertEquals(baseline,f.ledger.currentBytes());
        });
    }
    @ParameterizedTest @ValueSource(strings={"work","pair","cancel","memory"})
    void finalPublicationAdmissionRefusalsKeepPrimaryAndReleaseEveryPayload(String kind)throws Exception{
        var armed=new AtomicBoolean();var fired=new AtomicBoolean();var fixture=new java.util.concurrent.atomic.AtomicReference<Fixture>();
        var pressure=new java.util.concurrent.atomic.AtomicReference<AttemptMemoryLedger.Owner>();
        var primary=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        var backpointers=new java.util.concurrent.atomic.AtomicLong();
        CancellationProbe callback=()->{
            var f0=fixture.get();long count=f0==null?0:f0.input.workBudget().operationUnits(EmissionWorkBudget.Operation.BACKPOINTER_VISIT);
            if(armed.get()&&!fired.get()&&backpointers.getAndSet(count)!=count&&Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(s->s.getMethodName().equals("prepareManagedFinalPublication")&&s.getClassName().equals(SampledEmissionInput.class.getName()))){
                fired.set(true);var f=fixture.get();var budget=f.input.workBudget();
                try{switch(kind){
                    case "work" -> budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.workLimit()-budget.workUsed()+1);
                    case "pair" -> budget.charge(EmissionWorkBudget.Operation.TRANSITION,budget.pairLimit()-budget.pairVisits()+1);
                    case "cancel" -> throw new CancellationException("synthetic final publication cancel");
                    case "memory" -> pressure.get().reserve(AttemptMemoryLedger.MAX_BYTES-f.ledger.currentBytes()-64);
                    default -> throw new AssertionError(kind);
                }}catch(RuntimeException failure){primary.set(failure);throw failure;}
            }return false;
        };
        managed(new Spec(.60,true,false,false,callback,12,true),f->{fixture.set(f);
            armed.set(true);
            var profiles=f.input.profiles().levelZeroProfiles();long baseline=f.ledger.currentBytes();
            try(var owner=f.input.openExecutionOwner("synthetic-final-admission-pressure")){pressure.set(owner);
                var failure=assertThrows(RuntimeException.class,()->{try(var unexpected=new LegacyEmissionKernel().solveManagedChartAffinity(f.input,profiles,profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList(),List.of(),(p,o,s)->{armed.set(true);return o;},CancellationProbe.NONE)){} });
                assertTrue(fired.get());if(primary.get()!=null)assertSame(primary.get(),failure);
                switch(kind){
                    case "work" -> assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK,((EmissionWorkBudget.LimitExceededException)failure).kind());
                    case "pair" -> assertEquals(EmissionWorkBudget.LimitKind.PAIR_VISITS,((EmissionWorkBudget.LimitExceededException)failure).kind());
                    case "cancel" -> assertInstanceOf(CancellationException.class,failure);
                    case "memory" -> assertInstanceOf(AttemptMemoryLedger.ResourceLimitException.class,failure);
                }
                assertSame(failure,assertThrows(RuntimeException.class,f.input::requireOpenAndUnchanged));
            }assertEquals(baseline,f.ledger.currentBytes());
        });
    }
    @Test void prepaidFinalScanHasNoBudgetCallbackAndCannotBeReused()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("direct-final-ticket")){
            long baseline=f.ledger.currentBytes();
            try(var publication=f.input.prepareManagedFinalPublication(owner,64,1024)){
                var before=f.input.workBudget().snapshot();publication.validate();assertEquals(before,f.input.workBudget().snapshot());
                assertThrows(IllegalStateException.class,publication::validate);
            }assertEquals(baseline,f.ledger.currentBytes());
        }});
    }
    @Test void candidateFinalSettingsReadCannotHideAnAlreadyStickyTerminalFailure()throws Exception{
        managed(new Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            var primary=new CancellationException("synthetic terminal source callback");var fired=new AtomicBoolean();
            var profiles=f.input.profiles().levelZeroProfiles();long baseline=f.ledger.currentBytes();
            f.settingsHook.set(()->{
                if(!fired.get()&&Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(s->s.getClassName().equals(OptionalEmissionContextFactory.ManagedCaptureBinding.class.getName()+"$FinalPublicationCheck")&&s.getMethodName().equals("validate"))){
                    fired.set(true);f.input.workBudget().failTerminally(primary);
                }
            });
            var failure=assertThrows(CancellationException.class,()->{try(var unexpected=new LegacyEmissionKernel().solveManagedChartAffinity(f.input,profiles,profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList(),List.of(),(p,o,s)->o,CancellationProbe.NONE)){} });
            assertTrue(fired.get(),"The candidate exists before the final source/settings read");assertSame(primary,failure);
            assertSame(primary,assertThrows(CancellationException.class,f.input::requireOpenAndUnchanged));assertEquals(baseline,f.ledger.currentBytes());
        });
    }
    @Test void simpleCutRejectsAdjacentNativeReversalDegeneracyAndNonadjacentContact()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("simple-cut-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var simple=ManagedOrdinaryAffinity.class.getDeclaredMethod("simpleCut",List.class);simple.setAccessible(true);
                assertEquals(false,simple.invoke(classifier,List.of(new RasterPoint(0,0),new RasterPoint(2,0),new RasterPoint(1,0))),"Adjacent pieces overlap when they reverse on their shared line");
                assertEquals(false,simple.invoke(classifier,List.of(new RasterPoint(0,0),new RasterPoint(0,0),new RasterPoint(1,0))),"Zero-length cut pieces are unavailable");
                assertEquals(false,simple.invoke(classifier,List.of(new RasterPoint(-0.0,0),new RasterPoint(0,0),new RasterPoint(1,0))),"Signed zero cannot hide a geometrically empty piece");
                assertEquals(false,simple.invoke(classifier,List.of(new RasterPoint(0,0),new RasterPoint(2,0),new RasterPoint(2,2),new RasterPoint(1,0))),"Nonadjacent endpoint touch is not a simple cut");
                assertEquals(true,simple.invoke(classifier,List.of(new RasterPoint(0,0),new RasterPoint(1,0),new RasterPoint(2,0))),"Same-direction collinear pieces remain usable");
                assertEquals(true,simple.invoke(classifier,List.of(new RasterPoint(0,0),new RasterPoint(1,1),new RasterPoint(2,0))),"An ordinary adjacent bend remains usable");
            }
        }});
    }
    @ParameterizedTest @ValueSource(doubles={.30,.60})
    void exactSwappedNativePeaksBelowOwnedRawCoreRemainUnknown(double weak)throws Exception{
        managed(new Spec(weak,true,true,false,CancellationProbe.NONE),f->{try(var owner=f.input.openExecutionOwner("swapped-native-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            LegacyEmissionDomain.State a=null,b=null;
            for(int row=1;row+1<domain.rowCount();row++){
                var left=domain.observedAt(row).stream().max(Comparator.comparingDouble(s->s.peakOwner().intensity())).orElseThrow();
                var right=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(s->s.peakOwner().intensity())).orElseThrow();
                if(left.offsetRasterPixels()*right.offsetRasterPixels()<0){a=left;b=right;break;}
            }
            assertNotNull(a,"Actual original native best peak must switch sides under this strength swap");
            System.out.println("strength="+weak+", originalRows="+a.originalRow()+"->"+b.originalRow()+", genuineNativeStateCounts="+domain.observedAt(a.originalRow()).size()+"/"+domain.observedAt(b.originalRow()).size());
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var result=classifier.classify(a,b);
                assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN,result.status());
                assertEquals(ManagedOrdinaryAffinity.Reason.POINT_OWNERSHIP,result.reason());
                assertEquals(2,domain.observedAt(a.originalRow()).size());assertEquals(2,domain.observedAt(b.originalRow()).size());
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.fromOwnership().orElseThrow().availability());
                assertEquals(NativeMeasuredProfileReceipt.Reason.UNRESOLVED_COMPONENT,result.fromOwnership().orElseThrow().reason());
                assertTrue(result.proof().stations().isEmpty());assertFalse(result.proof().plan().isEmpty());assertTrue(result.proof().availableStation(0).isEmpty());
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.proof().globalModeFamilyAvailability());
                for(var point:List.of(result.fromOwnership().orElseThrow(),result.toOwnership().orElseThrow())){
                    assertEquals(2,point.probe().extraction().result().rawPeaks().size());assertEquals(2,point.probe().extraction().acceptedReceipts().size());
                    assertTrue(point.probe().extraction().result().suppressedModes().isEmpty());assertTrue(point.probe().extraction().result().baseline().censoredModes().isEmpty());
                }
                // Preserve the measured physical-P fact against the raw core that owns this location.
                var actualP=measureActualP(result.fromOwnership().orElseThrow(),f.input,owner);
                assertTrue(actualP.scalar().isPresent(),"Actual P scalar unavailable: "+bindingSummary(result.fromOwnership().orElseThrow(),f.input,owner));
                assertTrue(actualP.rawCoreThreshold().isPresent(),"No unique raw core owns actual P: "+bindingSummary(result.fromOwnership().orElseThrow(),f.input,owner));
                assertTrue(actualPBelowRawCore(actualP.scalar().getAsDouble(),actualP.rawCoreThreshold().getAsDouble()),
                    "Actual P must remain strictly below its uniquely owning raw core; actual="+actualP.scalar()+", threshold="+actualP.rawCoreThreshold()+", "+bindingSummary(result.fromOwnership().orElseThrow(),f.input,owner));
                assertEquals(weak==.60?NativeMeasuredProfileReceipt.Availability.COMPLETE:NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.toOwnership().orElseThrow().availability());
            }
        }});
    }
    @Test void actualPBelowRawCoreRequiresStrictInequality(){
        assertTrue(actualPBelowRawCore(.25,.26));
        assertFalse(actualPBelowRawCore(.25,.25),"Equality is not below the measured raw core");
        assertFalse(actualPBelowRawCore(.26,.25),"A value above the measured raw core cannot satisfy the premise");
    }
    @ParameterizedTest @ValueSource(doubles={.30,.60})
    void freshActualModeCentreFinalPointsHaveOwnProofWithoutNewNativeStates(double weak)throws Exception{
        managed(weak,true,f->{try(var owner=f.input.openExecutionOwner("fresh-final-mode-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;
            var a=domain.observedAt(row).stream().max(Comparator.comparingDouble(s->s.peakOwner().intensity())).orElseThrow();
            var b=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(s->s.peakOwner().intensity())).orElseThrow();
            int beforeA=domain.observedAt(row).size(),beforeB=domain.observedAt(row+1).size();
            var context=f.input.context();var ma=context.metricAt(domain.binding().observations().get(row).measurementProfile().samplingAnchor().rasterPoint());
            var mb=context.metricAt(domain.binding().observations().get(row+1).measurementProfile().samplingAnchor().rasterPoint());
            double dx=mb.xMeters()-ma.xMeters(),dy=mb.yMeters()-ma.yMeters(),length=Math.hypot(dx,dy);var normal=new MetricPoint(-dy/length,dx/length);
            try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input);var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var raw=domain.rasterPoint(b);var profile=session.metricAnchoredQuery(b.evaluatorState().row(),raw,normal);
                assertEquals(2,profile.modes().size());
                var mode=profile.modes().stream().max(Comparator.comparingDouble(m->m.forwardMetricPosition().distanceTo(context.metricAt(raw)))).orElseThrow();
                var actual=mode.rasterPosition();assertNotEquals(raw,actual);
                var result=classifier.classifyFinal(a,b,domain.rasterPoint(a),actual);
                assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE,result.status(),()->"Fresh actual Mode centre proposal: "+result.reason());
                var target=result.toOwnership().orElseThrow();assertTrue(target.moved());assertEquals(actual,target.actualRaster());
                assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,target.availability());assertTrue(target.strictScalar().isPresent());assertTrue(target.bridge().isPresent());assertTrue(target.componentBridge().isPresent());
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.proof().globalModeFamilyAvailability());
                assertEquals(beforeA,domain.observedAt(row).size());assertEquals(beforeB,domain.observedAt(row+1).size());
            }
        }});
    }
    @Test void broadUnqueriedOutsideSourceDoesNotBecomeAGlobalModeFamilyClaim()throws Exception{
        managed(new Spec(.60,true,false,true,CancellationProbe.NONE),f->{try(var owner=f.input.openExecutionOwner("outside-tail-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;var a=domain.observedAt(row).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            var b=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE,status(classifier,a,b,f.input,owner));
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,classifier.classify(a,b).proof().globalModeFamilyAvailability());
            }
        }});
    }
    @Test void cancellationImmediatelyClosesClassifierAndSessionPayload()throws Exception{
        var cancelled=new AtomicBoolean();
        managed(new Spec(.60,true,false,false,cancelled::get),f->{try(var owner=f.input.openExecutionOwner("cancel-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;long baseline=f.ledger.currentBytes();
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                cancelled.set(true);var failure=assertThrows(CancellationException.class,()->classifier.classify(domain.observedAt(row).get(0),domain.observedAt(row+1).get(0)));
                cancelled.set(false);assertEquals(baseline,f.ledger.currentBytes());
                assertSame(failure,assertThrows(CancellationException.class,classifier::stationExtractions));
            }
        }});
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void workAndMemoryLimitsAreStickyTerminalAndReleaseEveryClassifierPayload(boolean memoryFailure)throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("limit-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;long baseline=f.ledger.currentBytes();
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                RuntimeException failure;
                if(memoryFailure){
                    try(var pressure=owner.child("bounded-synthetic-pressure")){
                        pressure.reserve(AttemptMemoryLedger.MAX_BYTES-f.ledger.currentBytes()-4096);
                        failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->classifier.classify(domain.observedAt(row).get(0),domain.observedAt(row+1).get(0)));
                    }
                }else{
                    var before=f.input.workBudget().snapshot();f.input.workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,before.workLimit()-before.workUsed());
                    failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->classifier.classify(domain.observedAt(row).get(0),domain.observedAt(row+1).get(0)));
                }
                assertEquals(baseline,f.ledger.currentBytes());
                assertSame(failure,assertThrows(RuntimeException.class,classifier::preparedRowPairs));
                assertSame(failure,assertThrows(RuntimeException.class,()->classifier.classify(domain.observedAt(row).get(0),domain.observedAt(row+1).get(0))));
                assertSame(failure,assertThrows(RuntimeException.class,()->classifier.classify(null,null)),"A malformed retry cannot replace the attempt's original terminal failure");
            }
        }});
    }
    @Test void callerReconstructedNativeStateCannotAcquireActualDomainMembership()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("identity-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;var source=domain.observedAt(row).get(0);var next=domain.observedAt(row+1).get(0);
            var reconstructed=new LegacyEmissionDomain.State(source.originalRow(),source.nativeStateId(),source.kind(),source.offsetOriginId(),source.offsetRasterPixels(),source.peakOwner(),source.evaluatorState());
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                assertThrows(IllegalArgumentException.class,()->classifier.classify(reconstructed,next));
                assertNotNull(classifier.classify(source,next));
            }
        }});
    }
    @Test void actualManagedKernelPublishesLocalFactsWithOriginalStatesAndResultLifetime()throws Exception{
        managed(new Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            var binding=f.input.legacyBinding().orElseThrow();var profiles=f.input.profiles().levelZeroProfiles();
            assertTrue(profiles.stream().flatMap(p->p.peaks().stream()).anyMatch(p->p.syntheticCenter()),"Merged endpoint control includes actual native synthetic-center states; they cannot acquire measured ownership");
            var seeds=profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList();assertFalse(seeds.isEmpty());
            try(var result=new LegacyEmissionKernel().solveManagedChartAffinity(f.input,profiles,seeds,List.of(),(p,o,s)->o,CancellationProbe.NONE)){
                var diagnostics=result.managedAffinity().orElseThrow();assertEquals(ManagedOrdinaryAffinity.POLICY_ID,diagnostics.policyId());
                assertEquals("LEGACY_OPTIONAL_MANAGED_LOCAL_CONNECTOR_V1",result.sourceReference().modelPolicyId());assertTrue(result.affinity().isEmpty());
                assertEquals(result.inferenceVertices(),result.smoothedVertices());assertTrue(diagnostics.stationExtractions()>0);
                assertEquals(result.inferenceArcs().size(),diagnostics.inference().size());assertEquals(result.postSmoothingArcs().size(),diagnostics.finalGeometry().size());
                assertTrue(diagnostics.inference().stream().noneMatch(a->a.status()==OrdinaryAffinityClassifier.Status.INCOMPATIBLE));
                for(int row=0;row<binding.observations().size();row++)assertEquals(binding.observations().get(row).producerPeaks().stream().map(LegacyEmissionBinding.PeakOwner::originalPeakId).toList(),result.nativeStateTables().get(row).peaks().stream().map(EmissionResult.NativePeakReference::originalPeakId).toList());
                f.input.close();
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,diagnostics.globalModeFamilyAvailability());
                diagnostics.inference().stream().flatMap(a->a.receipt().stream()).forEach(r->{assertNotNull(r.proof().plan());assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,r.proof().globalModeFamilyAvailability());});
            }
        });
    }
    @Test void actualManagedKernelRestoresWholeRawRunForUnsafeMovedPoints()throws Exception{
        managed(new Spec(.60,true,false,false,CancellationProbe.NONE,40,true,Defect.SPARSE_STATIONS),f->{
            var profiles=f.input.profiles().levelZeroProfiles();var seeds=profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList();
            var binding=f.input.legacyBinding().orElseThrow();assertTrue(binding.observations().size()>=3);
            for(int i=2;i<binding.observations().size();i++)assertTrue(f.input.context().sourceSpanUpperMeters(binding.observations().get(i-2).retainedObservationIndex(),binding.observations().get(i).retainedObservationIndex())>20,
                "Declared sparse source stations exclude optional two-row skips; they do not alter the solver");
            try(var result=new LegacyEmissionKernel().solveManagedChartAffinity(f.input,profiles,seeds,List.of(),(p,o,s)->{
                var changed=new ArrayList<>(o);int i=changed.size()/2;if(i>0&&i+1<changed.size())changed.set(i,-changed.get(i));return changed;
            },CancellationProbe.NONE)){
                assertEquals(result.inferenceVertices(),result.smoothedVertices(),"Unsafe optional smoothing must retain the usable native route");
                var outcomes=result.managedAffinity().orElseThrow().smoothingOutcomes();assertFalse(outcomes.isEmpty());
                assertTrue(outcomes.stream().anyMatch(o->o.disposition()==EmissionResult.SmoothingDisposition.RAW_RETAINED));
                assertTrue(outcomes.stream().flatMap(o->o.proposedEdgeEvidence().stream()).anyMatch(r->r.status()!=OrdinaryAffinityClassifier.Status.COMPATIBLE));
                assertTrue(outcomes.stream().flatMap(o->o.proposedEdgeEvidence().stream()).anyMatch(r->r.fromOwnership().isEmpty()||r.toOwnership().isEmpty()),
                    "Unsupported native origins stay unowned; an UNKNOWN receipt must never authorize their newly proposed geometry");
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.managedAffinity().orElseThrow().globalModeFamilyAvailability());
            }
        });
    }
    @Test void genuineThreeRowRunRejectsFreshOppositeMiddlePointAndRetainsAllRawVertices()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("genuine-three-row-run")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;var states=new ArrayList<LegacyEmissionDomain.State>();var raw=new ArrayList<EmissionResult.Vertex>();
            for(int i=0;i<3;i++){
                var state=domain.observedAt(row+i).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                assertFalse(state.peakOwner().syntheticCenter());states.add(state);
                raw.add(new EmissionResult.Vertex(f.input.context().metricAt(domain.rasterPoint(state)),new EmissionMapping.ObservationVertex(row+i),EmissionResult.StateKind.OBSERVED_PEAK,Optional.of(state.nativeStateId()),Optional.of(state.offsetRasterPixels())));
            }
            var other=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            var proposed=new ArrayList<>(raw);var original=raw.get(1);var profile=domain.binding().observations().get(row+1).measurementProfile();
            var target=new RasterPoint(profile.anchorScreen().x+profile.normalScreen().x*other.offsetRasterPixels(),profile.anchorScreen().y+profile.normalScreen().y*other.offsetRasterPixels());
            proposed.set(1,new EmissionResult.Vertex(f.input.context().metricAt(target),original.owner(),original.kind(),original.nativeStateId(),Optional.of(other.offsetRasterPixels())));
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                for(int i=0;i<2;i++)assertTrue(classifier.classify(states.get(i),states.get(i+1)).ordinaryEligible());
                EmissionResult.ManagedSmoothingOutcome outcome;
                try{
                    var validator=ManagedOrdinaryAffinity.class.getDeclaredMethod("certifyRun",List.class,List.class,List.class,int.class);validator.setAccessible(true);
                    outcome=(EmissionResult.ManagedSmoothingOutcome)validator.invoke(classifier,states,raw,proposed,2);
                }catch(ReflectiveOperationException missing){throw new AssertionError("The production managed run validator must certify this actual source-owned proposal",missing);}
                assertEquals(raw,proposed);assertEquals(2,outcome.firstVertex());assertEquals(4,outcome.lastVertex());
                assertEquals(EmissionResult.SmoothingDisposition.RAW_RETAINED,outcome.disposition());assertEquals(EmissionResult.SmoothingReason.FINAL_BRANCH_CONFLICT,outcome.reason());
                assertEquals(2,outcome.proposedEdgeEvidence().size());assertTrue(outcome.proposedEdgeEvidence().stream().anyMatch(r->r.status()==OrdinaryAffinityClassifier.Status.INCOMPATIBLE));
                var changed=outcome.proposedEdgeEvidence().get(0).toOwnership().orElseThrow();assertEquals(row+1,changed.originalRow());assertEquals(target,changed.actualRaster());assertTrue(changed.moved());
                assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,changed.availability());
            }
        }});
    }
    @Test void measuredInDomainJoinCannotBecomeALocalConflict()throws Exception{
        managed(new Spec(.60,true,false,false,CancellationProbe.NONE,60,false,Defect.JOIN),f->{try(var owner=f.input.openExecutionOwner("join-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2-1;var a=domain.observedAt(row).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            var b=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var result=classifier.classify(a,b);assertNotEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE,result.status());
                assertTrue(result.ordinaryEligible());assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.proof().globalModeFamilyAvailability());
            }
        }});
    }
    @Test void actualBroadFootprintCrossingFrozenLocalBoundaryIsUnavailable()throws Exception{
        managed(new Spec(.60,true,false,false,CancellationProbe.NONE,60,false,Defect.WIDE),f->{try(var owner=f.input.openExecutionOwner("broad-footprint-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;assertTrue(domain.observedAt(row).size()>=2&&domain.observedAt(row+1).size()>=2);
            var a=domain.observedAt(row).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            var b=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
            var context=f.input.context();var x=context.metricAt(domain.binding().observations().get(row).measurementProfile().samplingAnchor().rasterPoint());
            var y=context.metricAt(domain.binding().observations().get(row+1).measurementProfile().samplingAnchor().rasterPoint());
            double dx=y.xMeters()-x.xMeters(),dy=y.yMeters()-x.yMeters(),length=Math.hypot(dx,dy);var normal=new MetricPoint(-dy/length,dx/length);
            try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input);var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var left=session.query(a.evaluatorState().row(),x,normal);var right=session.query(b.evaluatorState().row(),y,normal);
                assertEquals(2,left.modes().size());assertEquals(2,right.modes().size());
                // Fresh actual issued centres isolate footprint readiness from the
                // independently retained native raw-point membership limitation.
                var result=classifier.classifyFinal(a,b,left.modes().get(0).rasterPosition(),right.modes().get(1).rasterPosition());
                assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,result.fromOwnership().orElseThrow().availability());
                assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,result.toOwnership().orElseThrow().availability());
                assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN,result.status());
                var d=result.proof().domain().orElseThrow();
                assertTrue(result.proof().usedFootprints().stream().anyMatch(p->p.scope()==ManagedOrdinaryAffinity.UnknownScope.INTERSECTS_LOCAL_PROOF&&p.enclosure().isPresent()
                    &&(p.enclosure().orElseThrow().minimumU()<=d.localLowerU()||p.enclosure().orElseThrow().maximumU()>=d.localUpperU())),()->"Actual broad footprint must remain visible: "+result.reason());
            }
        }});
    }
    @Test void supportedStrongCurveRetainsNativeEligibilityAndExactNativePositions()throws Exception{
        managed(new Spec(.60,true,false,false,CancellationProbe.NONE,60,false,Defect.CURVE),f->{try(var owner=f.input.openExecutionOwner("curve-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;var a=domain.observedAt(row).stream().max(Comparator.comparingDouble(s->s.peakOwner().intensity())).orElseThrow();
            var b=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(s->s.peakOwner().intensity())).orElseThrow();
            var pa=domain.rasterPoint(a);var pb=domain.rasterPoint(b);
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var result=classifier.classify(a,b);assertTrue(result.ordinaryEligible());assertNotEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE,result.status());
                assertEquals(pa,domain.rasterPoint(a));assertEquals(pb,domain.rasterPoint(b));
            }
        }});
    }
    @Test void originalRouteRefusalWithAcquiredGeometryRemainsUnqueriedAndUnknown()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("route-refusal-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;var a=domain.observedAt(row).get(0);var b=domain.observedAt(row+1).get(0);var context=f.input.context();
            var x=context.metricAt(domain.binding().observations().get(row).measurementProfile().samplingAnchor().rasterPoint());
            var y=context.metricAt(domain.binding().observations().get(row+1).measurementProfile().samplingAnchor().rasterPoint());
            double dx=y.xMeters()-x.xMeters(),dy=y.yMeters()-x.yMeters(),length=Math.hypot(dx,dy);
            var original=context.metricAt(domain.rasterPoint(b));var outside=context.rasterAt(new MetricPoint(original.xMeters()+100*dx/length,original.yMeters()+100*dy/length));
            assertFalse(context.routeSegmentAuthorized(outside,outside,f.input.workBudget(),owner));assertTrue(context.evidenceSegmentAuthorized(outside,outside,f.input.workBudget(),owner));
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var result=classifier.classifyFinal(a,b,domain.rasterPoint(a),outside);assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN,result.status());
                var point=result.toOwnership().orElseThrow();assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,point.availability());
                assertTrue(point.queries().stream().noneMatch(NativeMeasuredProfileReceipt.QueryPoint::scalarQueried));
                assertTrue(point.queries().stream().noneMatch(NativeMeasuredProfileReceipt.QueryPoint::acquiredQueried));
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.proof().globalModeFamilyAvailability());
            }
        }});
    }
    @Test void actualContinuousInverseDetectsEscapeWithBothEndpointBoxesInside()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("escape-geometry-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            var grid=f.input.context().nativeGrid();var center=grid.toGrid(domain.rasterPoint(domain.observedAt(domain.rowCount()/2).get(0)));
            var a=new RasterPoint(center.x()-8,center.y()-8);var b=new RasterPoint(center.x()+8,center.y()+8);
            // Rounded G chooses a frozen test basis only. All endpoint/interior
            // claims below use the distinct continuous captured F_N inverse.
            var ma=f.input.context().metricAt(grid.toBaseRaster(a));var mb=f.input.context().metricAt(grid.toBaseRaster(b));
            double dx=mb.xMeters()-ma.xMeters(),dy=mb.yMeters()-ma.yMeters(),length=Math.hypot(dx,dy);
            var c=new MetricPoint((ma.xMeters()+mb.xMeters())*.5,(ma.yMeters()+mb.yMeters())*.5);
            try(var session=NativeConnectorEvaluator.openAffinityProfiles(f.input);var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var guard=ManagedOrdinaryAffinity.class.getDeclaredMethod("pathWithin",NativeMeasuredProfileReceipt.Family.class,RasterPoint.class,RasterPoint.class,double.class,double.class,double.class,double.class,List.class);
                guard.setAccessible(true);
                for(int orientation:new int[]{-1,1}){
                    var family=session.freezeFamily(c,new MetricPoint(orientation*-3*dy/length,orientation*3*dx/length),new MetricPoint(2*dx/length,2*dy/length),-1,1);
                    assertTrue(family.continuousTransportCertified());
                    var first=family.projectNativeSegment(a,a,0,1);var last=family.projectNativeSegment(b,b,0,1);var interior=family.projectNativeSegment(a,b,.5,.5);
                    double low=Math.min(first.minimumS(),last.minimumS()),high=Math.max(first.maximumS(),last.maximumS());
                    double s0,s1;
                    if(interior.minimumS()>high){double gap=interior.minimumS()-high;s0=low-gap*.25;s1=high+gap*.5;}
                    else {assertTrue(interior.maximumS()<low);double gap=low-interior.maximumS();s0=low-gap*.5;s1=high+gap*.25;}
                    assertTrue(first.minimumS()>s0&&first.maximumS()<s1&&last.minimumS()>s0&&last.maximumS()<s1);
                    assertTrue(interior.maximumS()<s0||interior.minimumS()>s1,"Actual native chord escapes and re-enters this narrow test strip");
                    assertFalse((boolean)guard.invoke(classifier,family,a,b,s0,s1,-28.0,28.0,new ArrayList<NativeMeasuredProfileReceipt.ParameterBox>()),"Endpoint ownership alone cannot authorize whole-path containment");
                }
            }
        }});
    }
    @ParameterizedTest @ValueSource(doubles={.60,.90})
    void everyThreeRowNativePathMatchesIndependentImageAdmissionOracle(double weak)throws Exception{
        managed(weak,true,f->{try(var owner=f.input.openExecutionOwner("managed-native-admission-oracle")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int start=domain.rowCount()/2;var rows=new ArrayList<List<LegacyEmissionDomain.State>>();
            for(int i=0;i<3;i++){assertEquals(2,domain.observedAt(start+i).size());rows.add(domain.observedAt(start+i));}
            var expected=new boolean[2][2][2];var actual=new boolean[2][2][2];int removedEdges=0;
            try(var oracle=NativeConnectorEvaluator.openAffinityProfiles(f.input);var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                for(int edge=0;edge<2;edge++){
                    var context=f.input.context();var a=context.metricAt(domain.binding().observations().get(start+edge).measurementProfile().samplingAnchor().rasterPoint());
                    var b=context.metricAt(domain.binding().observations().get(start+edge+1).measurementProfile().samplingAnchor().rasterPoint());
                    double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters(),length=Math.hypot(dx,dy);var normal=new MetricPoint(-dy/length,dx/length);
                    var owned=new boolean[2][2];
                    for(int side=0;side<2;side++)for(int mode=0;mode<2;mode++){
                        var state=rows.get(edge+side).get(mode);var profile=oracle.metricAnchoredQuery(state.evaluatorState().row(),domain.rasterPoint(state),normal);
                        owned[side][mode]=oracle.bindNativeState(profile,domain,state).observation().availability()==NativeMeasuredProfileReceipt.Availability.COMPLETE;
                    }
                    for(int from=0;from<2;from++)for(int to=0;to<2;to++){
                        var x=rows.get(edge).get(from);var y=rows.get(edge+1).get(to);
                        boolean sameImageBand=Math.signum(x.offsetRasterPixels())==Math.signum(y.offsetRasterPixels());
                        // The independently issued actual-P qualification is a premise;
                        // absent raw ownership preserves the old native edge as UNKNOWN.
                        expected[edge][from][to]=sameImageBand||!owned[0][from]||!owned[1][to];
                        var receipt=classifier.classify(x,y);actual[edge][from][to]=receipt.ordinaryEligible();
                        assertEquals(expected[edge][from][to],actual[edge][from][to],"Hard graph admission must agree with the actual two-band source and separately issued P ownership");
                        if(!actual[edge][from][to]){removedEdges++;assertEquals(ManagedOrdinaryAffinity.Reason.LOCAL_FIXED_CONNECTOR_DARK_CROSSCUT,receipt.reason());}
                    }
                }
                int paths=0,rejected=0;
                for(int mask=0;mask<8;mask++){
                    int a=mask&1,b=(mask>>>1)&1,c=(mask>>>2)&1;paths++;
                    boolean wanted=expected[0][a][b]&&expected[1][b][c],allowed=actual[0][a][b]&&actual[1][b][c];
                    assertEquals(wanted,allowed);if(!allowed)rejected++;
                }
                assertEquals(8,paths);assertTrue(removedEdges>0);assertTrue(rejected>0);
                assertTrue(actual[0][0][0]&&actual[1][0][0]);assertTrue(actual[0][1][1]&&actual[1][1][1]);
            }
        }});
    }
    @Test void cachedLocalReceiptDoesNotBypassFreshNativeSourceValidation()throws Exception{
        managed(.60,true,f->{try(var owner=f.input.openExecutionOwner("fresh-cache-domain")){
            var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
            int row=domain.rowCount()/2;var a=domain.observedAt(row).get(0);var b=domain.observedAt(row+1).get(0);
            try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                var historical=classifier.classify(a,b);var normal=f.input.profiles().levelZeroProfiles().get(0).normalScreen();double saved=normal.x;
                try{normal.x+=.125;assertThrows(IllegalStateException.class,()->classifier.classify(a,b));
                    assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,historical.proof().globalModeFamilyAvailability());
                }finally{normal.x=saved;}
            }
        }});
    }
    @Test void managedNativeBindingCannotBeReusedByASecondInputInstance()throws Exception{
        managed(.60,true,f->{
            var binding=f.input.legacyBinding().orElseThrow();
            assertThrows(IllegalStateException.class,()->new SampledEmissionInput(f.input.profiles(),
                f.input.context(),f.input.workBudget(),f.retainedOwner,Optional.of(binding)),
                "A managed native certificate belongs to the input instance that received the binding");
            assertDoesNotThrow(f.input::requireOpenAndUnchanged,
                "Rejecting a foreign input must leave the original association usable");
        });
    }
    @Test void managedNativeImmutableProofKeepsRowChargesAndRemovesOnlyPeakVisitCredits()throws Exception{
        var callbackCount=new java.util.concurrent.atomic.AtomicInteger();
        CancellationProbe callback=()->{callbackCount.incrementAndGet();return false;};
        managed(new Spec(.60,true,false,false,callback,368,false,Defect.PEAK_COUNTS_301),f->{
            var binding=f.input.legacyBinding().orElseThrow();
            int rows=binding.observations().size();
            long peakVisits=binding.observations().stream().mapToLong(row->row.profile().peaks().size()).sum();
            assertEquals(185,rows,"Synthetic counter fixture must exercise 185 native rows");
            assertEquals(301,peakVisits,"Synthetic counter fixture must exercise exactly 301 native peak visits");
            var managedPermitField=OptionalEmissionContext.class.getDeclaredField("managedBinding");
            managedPermitField.setAccessible(true);
            var managedPermit=(OptionalEmissionContextFactory.ManagedCaptureBinding)managedPermitField.get(f.input.context());
            long before=f.input.workBudget().operationUnits(EmissionWorkBudget.Operation.PREPARATION);
            int callbacksBefore=callbackCount.get();
            managedPermit.requireCurrent();
            long permitCost=f.input.workBudget().operationUnits(EmissionWorkBudget.Operation.PREPARATION)-before;
            int permitCallbacks=callbackCount.get()-callbacksBefore;
            before=f.input.workBudget().operationUnits(EmissionWorkBudget.Operation.PREPARATION);
            callbacksBefore=callbackCount.get();
            binding.requireNativeProfilesUnchanged(f.input.workBudget(),f.input);
            long bindingCost=f.input.workBudget().operationUnits(EmissionWorkBudget.Operation.PREPARATION)-before;
            int bindingCallbacks=callbackCount.get()-callbacksBefore;
            assertEquals(permitCost+rows,bindingCost,
                "The live permit scan and every row charge remain; only the proven immutable peak visits are removed");
            assertEquals(permitCallbacks+rows,bindingCallbacks,
                "Every row retains its own cancellation callback after the full live permit scan");
        });
    }
    @Test void managedNativeFreshnessStillRunsBothLiveSelectionSnapshots() throws Exception {
        var snapshots=new java.util.concurrent.atomic.AtomicInteger();
        CancellationProbe callback=()->{
            if(Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->
                    frame.getClassName().equals(ManagedSelectionWitness.class.getName())
                        &&frame.getMethodName().equals("snapshot")))snapshots.incrementAndGet();
            return false;
        };
        managed(new Spec(.60,true,false,false,callback),f->{
            int before=snapshots.get();
            f.input.requireOpenAndUnchanged();
            assertEquals(2,snapshots.get()-before,
                "Context and native binding retain independent live managed-source snapshots per admission");
        });
    }
    @Test void rotatedActualManagedNativePortsProveDarkCrossingAndActualSameChain() throws Exception {
        managed(.60,true,f->{
            try(var owner=f.input.openExecutionOwner("managed-affinity-test-domain")){
                var domain=new LegacyEmissionDomain(f.input.legacyBinding().orElseThrow(),f.input.profiles().levelZeroProfiles(),List.of(0.0),f.input.workBudget(),owner);
                int row=domain.rowCount()/2;
                var left=domain.observedAt(row);var right=domain.observedAt(row+1);
                assertTrue(left.size()>=2&&right.size()>=2,"Positive test needs two genuine producer-issued native states");
                try(var classifier=new ManagedOrdinaryAffinity(f.input,domain)){
                    var lo=left.stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                    var same=right.stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                    var opposite=right.stream().max(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                    assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE,status(classifier,lo,opposite,f.input,owner));
                    assertEquals(OrdinaryAffinityClassifier.Status.COMPATIBLE,status(classifier,lo,same,f.input,owner));
                    long extractions=classifier.stationExtractions();var cached=classifier.classify(lo,opposite);
                    assertSame(cached,classifier.classify(lo,opposite));assertEquals(extractions,classifier.stationExtractions());
                }
            }
        });
    }
    private OrdinaryAffinityClassifier.Status status(ManagedOrdinaryAffinity classifier,LegacyEmissionDomain.State a,LegacyEmissionDomain.State b,SampledEmissionInput input,AttemptMemoryLedger.Owner owner){
        var result=classifier.classify(a,b);var status=result.status();var reason=result.reason();assertNotNull(reason);
        if(status==OrdinaryAffinityClassifier.Status.COMPATIBLE){
            assertEquals(ManagedOrdinaryAffinity.Reason.LOCAL_OWNED_CONTINUATION,reason);
            assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.proof().globalModeFamilyAvailability());
            assertTrue(result.proof().usedFootprints().stream().anyMatch(f->f.scope()==ManagedOrdinaryAffinity.UnknownScope.OUTSIDE_LOCAL_PROOF&&f.enclosure().isPresent()));
            assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,result.fromOwnership().orElseThrow().availability());
            assertEquals(NativeMeasuredProfileReceipt.Availability.COMPLETE,result.toOwnership().orElseThrow().availability());
        }
        if(status==OrdinaryAffinityClassifier.Status.INCOMPATIBLE){
            var proof=result.proof();var d=proof.domain().orElseThrow();
            assertEquals(ManagedOrdinaryAffinity.Reason.LOCAL_FIXED_CONNECTOR_DARK_CROSSCUT,reason);
            assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,proof.globalModeFamilyAvailability());
            assertEquals(proof.plan().size(),proof.stations().size());assertFalse(proof.cut().isEmpty());assertFalse(proof.connectorBounds().isEmpty());
            assertTrue(proof.connectorBounds().stream().allMatch(p->p.minimumS()>d.innerFirst()&&p.maximumS()<d.innerLast()&&p.minimumU()>d.localLowerU()&&p.maximumU()<d.localUpperU()));
            var first=proof.stations().get(0);assertEquals(289,first.queries().size());
            for(int slot:new int[]{0,288}){
                var q=first.queries().get(slot);assertEquals(slot==0?-28:28,q.sample().offsetMeters());
                assertFalse(q.sample().valid());assertFalse(q.scalarQueried());assertFalse(q.acquiredQueried());
                assertTrue(proof.unknownIntervals().stream().anyMatch(u->u.profileIndex()==0&&u.sampleIndex()==slot&&u.scope()==ManagedOrdinaryAffinity.UnknownScope.OUTSIDE_LOCAL_PROOF));
            }
            for(var cut:proof.cut()){
                assertFalse(cut.cells().isEmpty());assertFalse(cut.wholePathBounds().isEmpty());
                assertEquals(Math.min(Math.min(cut.lowerFrom().shoulderThreshold(),cut.lowerTo().shoulderThreshold()),Math.min(cut.upperFrom().shoulderThreshold(),cut.upperTo().shoulderThreshold())),cut.threshold());
                assertTrue(cut.cells().stream().allMatch(c->c.maximumNodeIntensity()<cut.threshold()));
                assertTrue(cut.wholePathBounds().stream().allMatch(p->p.minimumU()>cut.lowerSupport()&&p.maximumU()<cut.upperSupport()));
            }
        }
        if(status==OrdinaryAffinityClassifier.Status.UNKNOWN){
            var proof=result.proof();
            fail("Positive managed control stayed UNKNOWN: "+reason+", failing station="+proof.failingStation()
                +", actualOwners="+List.of(result.fromOwnership().map(p->bindingSummary(p,input,owner)),result.toOwnership().map(p->bindingSummary(p,input,owner)))
                +", frozenDomains="+proof.domain().map(d->List.of(d.innerFirst(),d.innerLast(),d.outerFirst(),d.outerLast(),d.halfWidth(),Math.hypot(d.normal().xMeters(),d.normal().yMeters())))
                +", connectorLast="+(proof.connectorBounds().isEmpty()?"unqueried":proof.connectorBounds().get(proof.connectorBounds().size()-1))
                +", measured stations="+proof.stations().size()+", details="+proof.stations().stream().filter(s->s.index()==Math.max(0,proof.failingStation())).limit(1).map(s->
                    "stationS="+s.parameter()+", raw="+s.probe().extraction().result().rawPeaks().size()+", modes="+s.modes().size()+", suppressed="+s.probe().extraction().result().suppressedModes().size()
                    +", censored="+s.probe().extraction().result().baseline().censoredModes().size()+", invalid="+s.queries().stream().filter(q->!q.sample().valid()).count()
                    +", invalidFacts="+java.util.stream.IntStream.range(0,s.queries().size()).filter(i->!s.queries().get(i).sample().valid()).mapToObj(i->{var q=s.queries().get(i);var raster=input.context().rasterAt(q.metric());return i+":u="+q.sample().offsetMeters()+",metricGuard="+q.originalMetricQueryGuardAuthorized()+",route="+q.originalRouteAuthorized()+",acquiredQueried="+q.acquiredQueried()+",acquired="+q.acquiredAuthorized()+",scalar="+q.scalarQueried()+",sourcePointRecheck="+input.context().routeSegmentAuthorized(raster,raster,input.workBudget(),owner)+",acquiredGeometryRecheck="+input.context().evidenceSegmentAuthorized(raster,raster,input.workBudget(),owner);}).toList()
                    +", supports="+s.modes().stream().map(m->List.of(m.support().reason(),m.accepted().mode().coreMinimumMeters(),m.accepted().mode().coreMaximumMeters(),m.accepted().mode().localizationSigmaMeters(),m.support().minimumParameter(),m.support().maximumParameter(),m.accepted().shoulderThreshold())).toList()).toList());
        }return status;
    }
    private record ActualPMeasurement(OptionalDouble scalar,OptionalDouble rawCoreThreshold) { }
    private static boolean actualPBelowRawCore(double scalar,double threshold){return Double.isFinite(scalar)&&Double.isFinite(threshold)&&scalar<threshold;}
    private ActualPMeasurement measureActualP(ManagedOrdinaryAffinity.PointFact p,SampledEmissionInput input,AttemptMemoryLedger.Owner owner){
        input.requireOpenAndUnchanged();input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        BilinearSegmentIntegral.Result actualScalar=null;
        try(var scratch=owner.reserve(4096)){
            if(input.context().routeSegmentAuthorized(p.actualRaster(),p.actualRaster(),input.workBudget(),owner)
                    &&input.context().evidenceSegmentAuthorized(p.actualRaster(),p.actualRaster(),input.workBudget(),owner)){
                var point=input.context().nativeGrid().toGrid(p.actualRaster());
                actualScalar=BilinearSegmentIntegral.evaluate(input.context().nativeGrid(),point,point,CancellationProbe.NONE,()->{
                    input.workBudget().charge(EmissionWorkBudget.Operation.CELL_QUERY,1);input.workBudget().charge(EmissionWorkBudget.Operation.NODE_READ,4);
                });
            }
        }
        double lateral=(p.actualMetric().xMeters()-p.probe().metricPosition().xMeters())*p.probe().metricNormal().xMeters()
            +(p.actualMetric().yMeters()-p.probe().metricPosition().yMeters())*p.probe().metricNormal().yMeters();
        var matching=p.probe().extraction().acceptedReceipts().stream()
            .filter(m->m.rawCore().isPresent()&&lateral>=m.mode().coreMinimumMeters()&&lateral<=m.mode().coreMaximumMeters())
            .toList();
        OptionalDouble threshold=matching.size()==1?OptionalDouble.of(matching.get(0).rawCore().orElseThrow().threshold()):OptionalDouble.empty();
        return new ActualPMeasurement(actualScalar==null?OptionalDouble.empty():actualScalar.minimum(),threshold);
    }
    private String bindingSummary(ManagedOrdinaryAffinity.PointFact p,SampledEmissionInput input,AttemptMemoryLedger.Owner owner){
        var measurement=measureActualP(p,input,owner);
        var extraction=p.probe().extraction();
        return "row="+p.originalRow()+","+p.availability()+"/"+p.reason()+",issuedScalar="+p.strictScalar()+",diagnosticActualPScalar="+measurement.scalar()+",actualPOwningRawCoreThreshold="+measurement.rawCoreThreshold()+",bridge="+p.bridge().map(v->v.status()+"/"+v.minimum())
            +",raw/accepted/suppressed/censored="+List.of(extraction.result().rawPeaks().size(),extraction.acceptedReceipts().size(),extraction.result().suppressedModes().size(),extraction.result().baseline().censoredModes().size())
            +",numericModeSets="+extraction.acceptedReceipts().stream().map(m->List.of(m.mode().coreMinimumMeters(),m.mode().coreMaximumMeters(),m.mode().distanceToCenterSet(0),m.rawCore().orElseThrow().threshold())).toList()
            +",rawCoreBrackets="+extraction.result().rawCoreReceipts().stream().map(c->List.of(sampleOffset(p,c.leftAdjacentSampleIndex()),sampleOffset(p,c.rightAdjacentSampleIndex()),c.leftAdjacentBelowCore(),c.rightAdjacentBelowCore())).toList();
    }
    private OptionalDouble sampleOffset(ManagedOrdinaryAffinity.PointFact p,int i){return i<0||i>=p.probe().samples().size()?OptionalDouble.empty():OptionalDouble.of(p.probe().samples().get(i).offsetMeters());}
    record Fixture(SampledEmissionInput input,AttemptMemoryLedger ledger,Way source,java.util.concurrent.atomic.AtomicReference<ManagedHeatmapConfig> settings,java.util.concurrent.atomic.AtomicReference<Runnable> settingsHook,AttemptMemoryLedger.Owner retainedOwner){}
    enum Defect { NONE, JOIN, WIDE, CURVE, SPARSE_STATIONS, SOURCE_TURN, PEAK_COUNTS_301 }
    record Spec(double weak,boolean rotated,boolean swapped,boolean broadOutside,CancellationProbe cancelled,double halfLength,boolean joinedEnds,Defect defect){
        Spec(double weak,boolean rotated,boolean swapped,boolean broadOutside,CancellationProbe cancelled){this(weak,rotated,swapped,broadOutside,cancelled,60,false,Defect.NONE);}
        Spec(double weak,boolean rotated,boolean swapped,boolean broadOutside,CancellationProbe cancelled,double halfLength,boolean joinedEnds){this(weak,rotated,swapped,broadOutside,cancelled,halfLength,joinedEnds,Defect.NONE);}
    }
    @FunctionalInterface interface Check{void accept(Fixture fixture)throws Exception;}
    void managed(double weak,boolean rotated,Check check)throws Exception{
        managed(new Spec(weak,rotated,false,false,CancellationProbe.NONE),check);
    }
    void managed(Spec spec,Check check)throws Exception{
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories(){
            public java.io.File getPreferencesDirectory(boolean create){return cache.toFile();}
            public java.io.File getUserDataDirectory(boolean create){return cache.toFile();}
            public java.io.File getCacheDirectory(boolean create){return cache.toFile();}
        });
        var projection=Projections.getProjectionByCode("EPSG:3857");ProjectionRegistry.setProjection(projection);
        var center=new LatLon(49.44,14);var midpoint=projection.latlon2eastNorth(center);
        double angle=spec.rotated?.47:0,dx=spec.halfLength*Math.cos(angle),dy=spec.halfLength*Math.sin(angle);
        var start=new EastNorth(midpoint.east()-dx,midpoint.north()-dy);var end=new EastNorth(midpoint.east()+dx,midpoint.north()+dy);
        var source=spec.defect==Defect.SOURCE_TURN?List.of(start,new EastNorth(midpoint.east()-100*Math.sin(angle),midpoint.north()+100*Math.cos(angle)),end):List.of(start,end);
        var dataSet=new DataSet();var nodes=source.stream().map(p->new Node(projection.eastNorth2latlon(p))).toList();nodes.forEach(dataSet::addPrimitive);
        var way=new Way();way.setNodes(nodes);dataSet.addPrimitive(way);dataSet.setSelected(way);
        var layer=new OsmDataLayer(dataSet,"synthetic managed affinity",null);MainApplication.getLayerManager().addLayer(layer);
        var selection=SelectionResolver.resolve(dataSet,false);var projected=nodes.stream().map(n->n.getEastNorth(projection)).toList();
        var settings=new ManagedHeatmapConfig("synthetic","synthetic","synthetic","synthetic","all","hot","",".*",
            AlignmentMode.MOVE_EXISTING_NODES,TrackerMode.LEGACY_V02,false,false,false,false,false,false,true,false,false,false,
            18,spec.defect==Defect.SPARSE_STATIONS?30:4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,28,
            spec.defect==Defect.SPARSE_STATIONS?15:1.56,IntensitySamplingMode.COLOR_MAPPING,0);
        double size=TileHeatmapSampler.TILE_SIZE*Math.scalb(1.0,15),sine=Math.sin(Math.toRadians(center.lat()));
        double cx=(center.lon()+180)/360*size,cy=(.5-Math.log((1+sine)/(1-sine))/(4*Math.PI))*size;
        var tip=projection.eastNorth2latlon(source.get(1));double tipSine=Math.sin(Math.toRadians(tip.lat()));
        double tipX=(tip.lon()+180)/360*size,tipY=(.5-Math.log((1+tipSine)/(1-tipSine))/(4*Math.PI))*size;
        double nativeExtent=(tipX-cx)*Math.cos(angle)-(tipY-cy)*Math.sin(angle);
        var ledger=AttemptMemoryLedger.production();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),spec.cancelled);var decoder=new TileDecoderClassifier();
        var currentSettings=new java.util.concurrent.atomic.AtomicReference<>(settings);
        var settingsHook=new java.util.concurrent.atomic.AtomicReference<Runnable>();
        try(var owner=ledger.rootOwner();var coordinator=new TileFetchCoordinator((request,credentials)->{
            int width=TileHeatmapSampler.TILE_SIZE;var image=new BufferedImage(width,width,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<width;y++)for(int x=0;x<width;x++){
                double xx=request.address().x()*width+x+.5-cx,yy=request.address().y()*width+y+.5-cy;
                double transverse=xx*Math.sin(angle)+yy*Math.cos(angle);
                double along=xx*Math.cos(angle)-yy*Math.sin(angle);
                double first=spec.swapped&&along>0?spec.weak:.90,second=spec.swapped&&along>0?.90:spec.weak;
                if(spec.defect==Defect.PEAK_COUNTS_301&&along<-.25*nativeExtent)second=0;
                double band=spec.defect==Defect.WIDE?12:spec.joinedEnds?4*Math.max(0,Math.min(1,(nativeExtent-Math.abs(along))/(nativeExtent*.5))):4;
                double centerline=spec.defect==Defect.CURVE?1.5*Math.sin(along*.4):0;
                double bandSigma=spec.defect==Defect.WIDE?4:.70;
                double left=transverse-centerline-band,right=transverse-centerline+band;
                double value=Math.max((spec.defect==Defect.WIDE&&Math.abs(left)>4.5)?0:first*Math.exp(-.5*Math.pow(left/bandSigma,2)),
                    (spec.defect==Defect.WIDE&&Math.abs(right)>4.5)?0:second*Math.exp(-.5*Math.pow(right/bandSigma,2)));
                if(spec.defect==Defect.JOIN&&Math.abs(along+.84)<.4&&Math.abs(transverse)<5)value=Math.max(value,.90);
                // Outside the unchanged original ±28m source-query authority. This
                // broad acquired-only signal changes no measured local proof pixel.
                if(spec.broadOutside&&Math.abs(transverse)>22)value=Math.max(value,.15+.7*(.5+.5*Math.sin(xx*.03)*Math.cos(yy*.02)));
                int red=(int)Math.round(255*value);image.setRGB(x,y,(red==0?0:0xff000000)|(red<<16));
            }
            var bytes=new ByteArrayOutputStream();try{javax.imageio.ImageIO.write(image,"png",bytes);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes.toByteArray(),null,Duration.ZERO,"");
        },new ManagedTileCache(cache,decoder),decoder,TileReliabilityPolicy.defaults())){
            var sampler=new TileHeatmapSampler(coordinator);var witness=ManagedSelectionWitness.capture(dataSet,selection,projection,projected,budget,owner);
            try(var capture=sampler.prepareForEmission(settings,witness,List.of("hot"),"hot",false,budget,owner);
                var permit=OptionalEmissionContextFactory.bindManagedCapture(capture,()->{var hook=settingsHook.get();if(hook!=null)hook.run();return currentSettings.get();},budget,owner);
                var input=sampler.sampleProfilesForEmission(permit,CancellationProbe.NONE)){check.accept(new Fixture(input,ledger,way,currentSettings,settingsHook,permit.retainedOwner()));}
        }finally{MainApplication.getLayerManager().removeLayer(layer);}
        assertEquals(0,ledger.currentBytes());
    }
}
