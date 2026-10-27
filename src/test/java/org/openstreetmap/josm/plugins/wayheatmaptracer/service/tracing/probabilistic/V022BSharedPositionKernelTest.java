package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BSharedPositionKernelTest {
    @Test void distinctOwnRowsCensoredAndGroupedOutcomesUseTheExistingReducer() {
        try(var work=BPhysicalWork.create(CancellationProbe.NONE)) {
            double[] widths={.1,.1,.1};var owner=work.owner();
            var r=BSharedPositionKernel.calculateMeasuredRows(new double[][]{{0,.5,1},{1,.5,0}},
                new BPhysicalResponse.Reason[][]{known(3),known(3)},widths,4,.25,owner);
            var definitions=List.of(new ObservationComponent("u","u",ObservationComponent.Kind.MISSING,.2,false),
                new ObservationComponent("a","a",ObservationComponent.Kind.MEASURED,.2,false),
                new ObservationComponent("b","b",ObservationComponent.Kind.MEASURED,.2,false),
                new ObservationComponent("c","c",ObservationComponent.Kind.CENSORED,.2,false),
                new ObservationComponent("parent","parent",ObservationComponent.Kind.MEASURED,.2,true));
            var rows=new java.util.ArrayList<BObservationMixture.Component>();
            double logUniform=-StrictMath.log(.3);
            rows.add(BObservationMixture.Component.create("u","u",ObservationComponent.Kind.MISSING,r.missingMass(),new double[]{logUniform,logUniform,logUniform},owner));
            for(int m=0;m<2;m++)rows.add(BObservationMixture.Component.create(m==0?"a":"b",m==0?"a":"b",ObservationComponent.Kind.MEASURED,r.mass(m),new double[]{r.logDensity(m,0),r.logDensity(m,1),r.logDensity(m,2)},BObservationMixture.Outcome.MEASURED,owner));
            rows.add(BObservationMixture.Component.create("c","c",ObservationComponent.Kind.CENSORED,.25,new double[]{logUniform,logUniform,logUniform},owner));
            rows.add(BObservationMixture.Component.create("parent","parent",ObservationComponent.Kind.MEASURED,0,new double[]{Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY},BObservationMixture.Outcome.GROUPED_PARENT_UNAVAILABLE,owner));
            var lattice=new ProbabilisticStateLattice(List.of(new LateralStateCell(-.1,.1,true,false,"a"),new LateralStateCell(0,.1,false,false,"a"),new LateralStateCell(.1,.1,false,false,"a")),definitions,.1,false);
            var reduced=BObservationMixture.create(rows,owner).reduce(lattice,CancellationProbe.NONE,owner);
            assertEquals(0,reduced.unaryCost(0));assertTrue(reduced.unaryCost(1)<0);
            assertEquals(0,reduced.responsibility(0,1));assertTrue(reduced.responsibility(0,2)>0);
            assertEquals(0,reduced.responsibility(2,2));assertTrue(reduced.responsibility(2,1)>0);
            double normalized=0;
            for(int s=0;s<3;s++){double sum=0;for(int m=0;m<5;m++)sum+=reduced.responsibility(s,m);assertEquals(1,sum,2e-15);assertEquals(0,reduced.responsibility(s,4));normalized+=StrictMath.exp(reduced.logMixtureDensity(s))*.1;}
            assertEquals(1,normalized,2e-15);
            var bad=BObservationMixture.create(List.of(BObservationMixture.Component.create("u","u",ObservationComponent.Kind.MISSING,.5,new double[]{logUniform,logUniform,logUniform},owner),
                BObservationMixture.Component.create("a","a",ObservationComponent.Kind.MEASURED,.5,new double[]{0,0,0},owner)),owner);
            var badLattice=new ProbabilisticStateLattice(lattice.cells(),definitions.subList(0,2),.1,false);
            assertThrows(IllegalArgumentException.class,()->bad.reduce(badLattice,CancellationProbe.NONE,owner));
        }
    }
    @Test void declaredFieldCensusAndCalculationAllocationMatchOwnedPayload() {
        assertEquals(18,fields(BSharedPositionKernel.Field.class));assertEquals(16,fields(BSharedPositionKernel.class));
        assertEquals(4,fields(BSharedPositionKernel.Parameters.class));assertEquals(56,fields(BSharedPositionKernel.Calculation.class));
        assertEquals(16,fields(BSharedPositionKernel.Evaluation.class));assertEquals(15,fields(BPhysicalResponse.OwnedLocal.class));
        assertEquals(816,BSharedPositionKernel.NATIVE_ROW_API_FRAME);
        try(var work=BPhysicalWork.create(CancellationProbe.NONE)) {
            long before=work.owner().currentBytes();
            var result=BSharedPositionKernel.calculateMeasuredRows(new double[][]{{0,.5,1}},new BPhysicalResponse.Reason[][]{known(3)},new double[]{1,1,1},4,0,work.owner());
            long expected=AttemptMemoryLedger.objectBytes(56)+5*AttemptMemoryLedger.arrayBytes(1,8)+2*AttemptMemoryLedger.arrayBytes(3,8);
            assertEquals(expected,work.owner().currentBytes()-before);assertEquals(1.5,result.area(0));
        }
    }
    private static long fields(Class<?> type){long n=0;for(var f:type.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(f.getModifiers())){var t=f.getType();n+=!t.isPrimitive()?8:t==boolean.class||t==byte.class?1:t==int.class?4:8;}return n;}
    private static BPhysicalResponse.Reason[] known(int n){var r=new BPhysicalResponse.Reason[n];Arrays.fill(r,BPhysicalResponse.Reason.AVAILABLE);return r;}
    @Test void unknownCellsRetainReasonAndDoNotRemoveWidthOrInventMeasuredZero() {
        var ledger=new AttemptMemoryLedger(1<<20);BSharedPositionKernel.Calculation saved;
        try(var owner=ledger.rootOwner()) {
            double[][] values={{Double.NaN,.5,1}};
            var reasons=new BPhysicalResponse.Reason[][]{{BPhysicalResponse.Reason.SOURCE_UNKNOWN,BPhysicalResponse.Reason.AVAILABLE,BPhysicalResponse.Reason.AVAILABLE}};
            saved=BSharedPositionKernel.calculateMeasuredRows(values,reasons,new double[]{1,1,1},4,.25,owner);
            assertEquals(1.5,saved.area(0),0);assertEquals(.75/7,saved.missingMass(),1e-16);
            assertEquals(BPhysicalResponse.Reason.SOURCE_UNKNOWN,saved.reason(0,0));assertEquals(Double.NEGATIVE_INFINITY,saved.logDensity(0,0));
            values[0][1]=99;reasons[0][0]=BPhysicalResponse.Reason.OBSERVED_ZERO;
            assertEquals(1.5,saved.area(0),0);assertEquals(BPhysicalResponse.Reason.SOURCE_UNKNOWN,saved.reason(0,0));
            double norm=0;for(int s=0;s<3;s++)norm+=saved.missingMass()/3+saved.mass(0)*StrictMath.exp(saved.logDensity(0,s))+.25/3;
            assertEquals(1,norm,3e-16);
        }
        assertEquals(0,ledger.currentBytes());assertThrows(IllegalStateException.class,()->saved.area(0));
    }
    @Test void zeroAreaAndDiagnosticZeroGainHaveNoUndefinedDensityOrSignalFloor() {
        try(var work=BPhysicalWork.create(CancellationProbe.NONE)) {
            var zero=BSharedPositionKernel.calculateMeasuredRows(new double[][]{{0,0}},new BPhysicalResponse.Reason[][]{known(2)},new double[]{1,1},4,0,work.owner());
            assertEquals(0,zero.area(0));assertEquals(0,zero.mass(0));assertEquals(1,zero.missingMass());assertEquals(Double.NEGATIVE_INFINITY,zero.logDensity(0,0));
            var gainZero=BSharedPositionKernel.calculateMeasuredRows(new double[][]{{.5,1}},new BPhysicalResponse.Reason[][]{known(2)},new double[]{1,1},0,.25,work.owner());
            assertEquals(1.5,gainZero.area(0));assertEquals(0,gainZero.mass(0));assertEquals(.75,gainZero.missingMass());
            assertFalse(BSharedPositionKernel.Parameters.diagnosticZeroGain().candidateEligible());
            for(int g:new int[]{1,4,16,64})assertTrue(new BSharedPositionKernel.Parameters(g).candidateEligible());
            for(int g:new int[]{0,-1,2,8,65})assertThrows(IllegalArgumentException.class,()->new BSharedPositionKernel.Parameters(g));
        }
    }
    @Test void positiveProductAndPostDivisionUnderflowAndNonfiniteInputsFailWithoutLeakingRows() {
        try(var work=BPhysicalWork.create(CancellationProbe.NONE)) {
            var owner=work.owner();long before=owner.currentBytes();
            assertThrows(ArithmeticException.class,()->BSharedPositionKernel.calculateMeasuredRows(new double[][]{{Double.MIN_VALUE}},new BPhysicalResponse.Reason[][]{known(1)},new double[]{.5},1,0,owner));
            assertThrows(ArithmeticException.class,()->BSharedPositionKernel.calculateMeasuredRows(new double[][]{{Double.MIN_VALUE},{1}},new BPhysicalResponse.Reason[][]{known(1),known(1)},new double[]{1},1,0,owner),"positive MIN_VALUE/2 must not become mass zero");
            assertThrows(ArithmeticException.class,()->BSharedPositionKernel.calculateMeasuredRows(new double[][]{{1,1}},new BPhysicalResponse.Reason[][]{known(2)},new double[]{Double.MAX_VALUE,Double.MAX_VALUE},1,0,owner));
            assertThrows(IllegalArgumentException.class,()->BSharedPositionKernel.calculateMeasuredRows(new double[][]{{Double.POSITIVE_INFINITY}},new BPhysicalResponse.Reason[][]{known(1)},new double[]{1},1,0,owner));
            assertThrows(ArithmeticException.class,()->BSharedPositionKernel.calculateMeasuredRows(new double[][]{{Double.NaN}},new BPhysicalResponse.Reason[][]{{BPhysicalResponse.Reason.UNRESOLVED}},new double[]{1},1,0,owner));
            assertEquals(before,owner.currentBytes());
        }
        var tiny=new AttemptMemoryLedger(8);try(var owner=tiny.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->BSharedPositionKernel.calculateMeasuredRows(new double[][]{{1}},new BPhysicalResponse.Reason[][]{known(1)},new double[]{1},4,0,owner));assertEquals(0,tiny.currentBytes());
        }
    }
    @Test void immutablePolicyIdentitiesBindEveryRegisteredGainAndSeparateDiagnosticZero() throws Exception {
        String policy="shared-position-physical-own-r-task2-v1;A=sum(r*width);H=gain*A/1m;q=R*H/(1+sumH);q0=R/(1+sumH);f=r/A;frozen-censored;full-domain-uniform;no-F-C";
        var seen=new java.util.HashSet<String>();
        for(int gain:new int[]{0,1,4,16,64}) {
            var parameters=gain==0?BSharedPositionKernel.Parameters.diagnosticZeroGain():new BSharedPositionKernel.Parameters(gain);
            String expected=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((policy+"\n"+BIdentificationFacts.VERSION+"\n"+gain).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(expected,BSharedPositionKernel.policyIdentity(parameters));assertTrue(seen.add(expected));
        }
    }
    @Test void sourceDerivedLocalBodyAndExactOwnCoordinateJoinsAreIndependentOfContext() throws Exception {
        assertEquals(50560+32+64+32,BPhysicalWork.localKernelUnits(3,3,5,1));
        assertEquals(4608,BPhysicalWork.localKernelUnits(3,3,6,1)-BPhysicalWork.localKernelUnits(3,3,5,1));
        assertEquals(1536,BPhysicalWork.localKernelUnits(3,3,5,2)-BPhysicalWork.localKernelUnits(3,3,5,1));
        assertThrows(IllegalArgumentException.class,()->BPhysicalWork.localKernelUnits(1,97,1,1));
        assertThrows(IllegalArgumentException.class,()->BPhysicalWork.localKernelUnits(8192,1,0,Integer.MAX_VALUE),"Dimension arithmetic must refuse,never wrap into a negative admission");
        assertEquals(2*32+48,BPhysicalWork.OWN_COORDINATE_EXACT_UNITS);
        assertEquals(746+112,BPhysicalWork.POINT_COUPLING_UNITS);
        var grid=new BSharedPositionEvidence.Grid(9,17,0,0,1,0,0,1,true);
        //Exact decimal construction from binary64,not tolerance/rounded equality.
        for(double offset:new double[]{-1,-.5,.5,1,0x1p40}) {
            double anchor=8.5,nominal=anchor+offset;
            assertTrue(BPhysicalResponse.exactGeometry(grid,3.5,nominal,3.5,anchor,0,1));
            assertEquals(0,new java.math.BigDecimal(anchor).add(new java.math.BigDecimal(offset)).compareTo(new java.math.BigDecimal(nominal)));
        }
        assertFalse(BPhysicalResponse.exactGeometry(grid,3.5,8.5+Math.nextUp(.5),3.5,8.5,.6,.8));
        var predicate=BPhysicalResponse.class.getDeclaredMethod("exactHalfGrid",double.class,double.class);predicate.setAccessible(true);
        double nearby=Math.nextUp(.5),rounded=8.5+nearby;
        assertTrue(BPhysicalResponse.exactGeometry(grid,3.5,rounded,3.5,8.5,0,1));
        assertFalse((boolean)predicate.invoke(null,nearby,.5),"Original offset proof is necessary even when the rounded point lies on the grid");
        assertNotEquals(0,new java.math.BigDecimal(8.5).add(new java.math.BigDecimal(nearby)).compareTo(new java.math.BigDecimal(rounded)));
    }
    @Test void independentThreeCellMassDensityAndUnits() {
        try(var work=BPhysicalWork.create(CancellationProbe.NONE)) {
            for(double width:new double[]{1,.1}) {
                var c=assertDoesNotThrow(()->BSharedPositionKernel.calculateMeasuredRows(
                    new double[][]{{0,.5,1}},new BPhysicalResponse.Reason[][]{{BPhysicalResponse.Reason.OBSERVED_ZERO,
                        BPhysicalResponse.Reason.AVAILABLE,BPhysicalResponse.Reason.AVAILABLE}},
                    new double[]{width,width,width},4,0,work.owner()));
                assertEquals(width==1?3.0/2:3.0/20,c.area(0),2e-16);
                assertEquals(width==1?6:3.0/5,c.intensity(0),8e-16);
                assertEquals(width==1?1.0/7:5.0/8,c.missingMass(),2e-16);
                assertEquals(width==1?6.0/7:3.0/8,c.mass(0),2e-16);
                double[] expected=width==1?new double[]{1.0/21,1.0/3,13.0/21}
                    :new double[]{25.0/12,10.0/3,55.0/12};
                double norm=0;
                for(int s=0;s<3;s++) {
                    double p=c.missingMass()/(3*width)+c.mass(0)*StrictMath.exp(c.logDensity(0,s));
                    assertEquals(expected[s],p,2e-15);norm+=p*width;
                    if(width==.1)assertTrue(-StrictMath.log(p)<0,"Density above one has negative unary");
                }
                assertEquals(1,norm,8e-16);
            }
        }
    }
    @Test void authenticCapturedQuarterOwnAccessPreservesPositivePhysicalResponseAndBorrowedFacts() {capturedKernelControls(false);}
    @Test void actualCapturedCensoredMassesAndNativeRowsAreFrozenAtPositiveAndDiagnosticGain() {capturedKernelControls(true);}
    private void capturedKernelControls(boolean censoredFixture) {
        Runnable[] callback={null};boolean[] cancelled={false};
        CancellationProbe probe=()->{var action=callback[0];callback[0]=null;if(action!=null)action.run();return cancelled[0];};
        try(var work=BPhysicalWork.create(probe)) {
            var root=work.owner();var sourceWork=work.sourceWork();
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),
                new GeographicPoint(-1,-1),new GeographicPoint(1,1));
            var path=List.of(new MetricPoint(2.5,8.5),new MetricPoint(5.5,8.5));
            var geo=path.stream().map(frame::toGeographic).toList();
            var image=new BufferedImage(9,17,BufferedImage.TYPE_INT_ARGB);
            var values=new double[9*17];Arrays.fill(values,.02);Arrays.fill(values,8*9,censoredFixture?values.length:10*9,1);
            var valid=new boolean[values.length];Arrays.fill(valid,true);
            var input=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(0,0),1,0,0,1);
            var grid=new MetricRasterGrid(frame,new MetricPoint(-.25,-.25),1,0,0,1,.5,19,35);
            var resolution=EvidenceResolution.nativeSource(1,1);
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"task2-quarter",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
            root.reserve(536+AttemptMemoryLedger.arrayBytes(values.length,4)).adopt(image);
            root.reserve(AttemptMemoryLedger.arrayBytes(values.length,8)).adopt(values);
            root.reserve(AttemptMemoryLedger.arrayBytes(valid.length,1)).adopt(valid);
            root.reserve(128).adopt(geo);root.reserve(96).adopt(path);root.reserve(96).adopt(input);
            root.reserve(112).adopt(grid);root.reserve(56).adopt(resolution);root.reserve(64).adopt(lineage);
            var chainage=new ProbabilisticProfileFactory().profileChainage(path,1);
            var parameters=EvidenceModelParameters.defaults();
            root.reserve(40+32+AttemptMemoryLedger.arrayBytes(4,8)+4*24).adopt(chainage);
            root.reserve(96+112+32+AttemptMemoryLedger.arrayBytes(parameters.version().length(),2)).adopt(parameters);
            try(var capture=BExperimentalScalarCapture.open(root,sourceWork,probe)) {
                var view=capture.capture("task2-quarter",image,valid,values,geo,path,input,grid,resolution,3,
                    "task2-original-quarter-source","scalar",lineage);
                try(var profiles=capture.createProfiles(view,"scalar",chainage,3,true,parameters);
                        var facts=new BIdentificationFactsExtractor().extractCapturedPosition(capture,view,"scalar",profiles,
                            probe,root,sourceWork);var graph=capture.createLattices(profiles,facts)) {
                    assertEquals(4,profiles.profiles().size());
                    int row=1;var profile=profiles.profiles().get(row);
                    if(censoredFixture) {
                        assertFalse(profile.censoredModes().isEmpty(),"Declared one-sided plateau must issue genuine censored receipts");
                        assertTrue(profile.modes().isEmpty(),"No elementary response may replace a censored row");
                        var original=graph.lattices().get(row);var originalFacts=facts.facts().get(row);
                        double[] priors=ProbabilisticObservationModel.effectiveComponentPriors(profile,original.components());
                        var inputFacts=BSharedPositionEvidence.CapturedInput.create(capture,view,"scalar",profiles,facts,graph,root,probe,sourceWork);
                        try(var own=BPhysicalResponse.openCapturedOwn(work,inputFacts);var field=BSharedPositionKernel.Field.open(own,work)) {
                            for(var parameter:List.of(new BSharedPositionKernel.Parameters(4),BSharedPositionKernel.Parameters.diagnosticZeroGain())) {
                                var kernel=BSharedPositionKernel.create(field,parameter,work);var evaluation=kernel.evaluateWithReceipt(originalFacts,original,probe,root);
                                double remainder=1;
                                for(int c=0;c<original.components().size();c++)if(original.components().get(c).kind()==ObservationComponent.Kind.CENSORED) {
                                    remainder-=priors[c];assertEquals(priors[c],evaluation.mixture().component(c).mass(),0);
                                    assertEquals(BObservationMixture.Outcome.BASELINE_CENSORED,evaluation.mixture().component(c).outcome());
                                    double[] expected=new double[original.cells().size()];ProbabilisticObservationModel.fillBaselineCensoredRow(expected,profile,original.cells(),original.components().get(c),probe);
                                    for(int s=0;s<expected.length;s++)assertEquals(Double.doubleToRawLongBits(expected[s]),Double.doubleToRawLongBits(evaluation.mixture().component(c).logL0DensityAt(s)));
                                }
                                assertEquals(remainder,evaluation.calculation().missingMass(),2e-16);
                                assertDoesNotThrow(()->evaluation.mixture().reduce(original,probe,root));
                            }
                        }
                        return;
                    }
                    assertFalse(profile.modes().isEmpty());
                    int state=-1;for(int s=0;s<graph.lattices().get(row).cells().size();s++)
                        if(graph.lattices().get(row).cells().get(s).offsetMeters()==0)state=s;
                    assertTrue(state>=0);var mode=facts.facts().get(row).modes().get(0);
                    double background=mode.fact(BIdentificationFacts.Feature.BACKGROUND).value().orElseThrow();
                    double nuisance=Math.max(mode.fact(BIdentificationFacts.Feature.RMS_DESCRIPTOR).value().orElseThrow(),
                        mode.fact(BIdentificationFacts.Feature.FILTER_DESCRIPTOR).value().orElseThrow());
                    var pixel=view.transform().metricToPixelCenter(profile.anchor());
                    double central=view.fields().get("scalar").sampleBilinear(pixel.x(),pixel.y()).orElseThrow();
                    double lower=(central-background-nuisance)/4;assertTrue(lower>0);
                    var boundInput=BSharedPositionEvidence.CapturedInput.create(capture,view,"scalar",profiles,facts,graph,
                        root,probe,sourceWork);
                    try(var own=assertDoesNotThrow(()->BPhysicalResponse.openCapturedOwn(work,boundInput))) {
                        own.query(row,0,state);assertEquals(BPhysicalResponse.Reason.AVAILABLE,own.reason());
                        assertTrue(own.value()>=lower);assertTrue(own.lower()<=own.value());assertTrue(own.upper()>=own.value());
                        for(int s=0;s<graph.lattices().get(row).cells().size();s++) {
                            own.query(row,0,s);
                            if(own.reason()==BPhysicalResponse.Reason.UNRESOLVED) {
                                try {
                                    var f=BPhysicalResponse.OwnedLocal.class.getDeclaredField("response");f.setAccessible(true);
                                    var r=(BPhysicalResponse)f.get(own);
                                    System.out.println("TASK2_FIRST_UNRESOLVED row="+row+" mode=0 state="+s+" offset="+graph.lattices().get(row).cells().get(s).offsetMeters()
                                        +" anchor="+profile.anchor()+" normal="+profile.normalUnit()
                                        +" x="+r.n[11]+" E=["+r.lo[11]+","+r.hi[11]+"] y="+r.n[12]+" E=["+r.lo[12]+","+r.hi[12]+"]"
                                        +" u="+r.n[13]+" E=["+r.lo[13]+","+r.hi[13]+"] shoulder=["+r.n[9]+","+r.n[10]+"]"
                                        +" tapU="+r.n[20]+" E=["+r.lo[20]+","+r.hi[20]+"] exact="+BPhysicalResponse.exactGeometry(r.bound().grid,r.n[11],r.n[12],r.n[0],r.n[1],r.n[2],r.n[3]));
                                } catch(ReflectiveOperationException exception){throw new AssertionError(exception);}
                                break;
                            }
                        }
                        try(var field=BSharedPositionKernel.Field.open(own,work)) {
                            long beforePolicy=sourceWork.remainingMetadataBytes();
                            var kernel=BSharedPositionKernel.create(field,new BSharedPositionKernel.Parameters(4),work);
                            assertTrue(fields(String.class)<=24,"Pinned String instance-field upper");
                            long policySlice=AttemptMemoryLedger.objectBytes(fields(BSharedPositionKernel.class))
                                +AttemptMemoryLedger.objectBytes(fields(BSharedPositionKernel.Parameters.class))
                                +AttemptMemoryLedger.objectBytes(24)+AttemptMemoryLedger.arrayBytes(64,2);
                            assertEquals(policySlice,beforePolicy-sourceWork.remainingMetadataBytes(),"Shell/config and full UTF16 policy handle must all be funded");
                            callback[0]=()->assertThrows(IllegalStateException.class,()->kernel.evaluate(facts.facts().get(row),graph.lattices().get(row),probe,root),"A same-field callback cannot reenter the shared own-response worker");
                            var evaluated=assertDoesNotThrow(()->kernel.evaluateWithReceipt(facts.facts().get(row),graph.lattices().get(row),
                                probe,root));
                            var mixture=evaluated.mixture();
                            var reduced=mixture.reduce(graph.lattices().get(row),probe,root);
                            int measured=-1;for(int c=0;c<graph.lattices().get(row).components().size();c++)
                                if(graph.lattices().get(row).components().get(c).kind()==ObservationComponent.Kind.MEASURED)measured=c;
                            assertTrue(measured>=0);assertTrue(reduced.responsibility(state,measured)>0,"Corrected physical ridge contributes measured mass");
                            assertTrue(evaluated.calculation().area(measured)>0,"Receipt survives transaction transfer");
                            double independentArea=0,totalWidth=0;
                            for(int s=0;s<graph.lattices().get(row).cells().size();s++) {
                                own.query(row,0,s);var reason=own.reason();assertNotEquals(BPhysicalResponse.Reason.UNRESOLVED,reason);
                                double width=graph.lattices().get(row).cells().get(s).quadratureWidthMeters();totalWidth+=width;
                                if(reason.ordinal()<=BPhysicalResponse.Reason.GEOMETRIC_ZERO.ordinal())independentArea+=own.value()*width;
                                double sum=0;for(int c=0;c<graph.lattices().get(row).components().size();c++)sum+=reduced.responsibility(s,c);
                                assertEquals(1,sum,2e-15);
                            }
                            assertEquals(independentArea,evaluated.calculation().area(measured),0,"Only original physical R_m enters A");
                            var adapted=BObservationAdapter.evaluate(facts.facts().get(row),graph.lattices().get(row),parameters,true,kernel,probe,root);
                            for(int s=0;s<graph.lattices().get(row).cells().size();s++) {
                                assertEquals(reduced.unaryCost(s),adapted.unaryCost(s),0,"Real adapter closes its nested replacement-scratch after copying");
                                for(int c=0;c<graph.lattices().get(row).components().size();c++)assertEquals(reduced.responsibility(s,c),adapted.componentResponsibility(s,c),0);
                            }
                            var copiedFacts=new BIdentificationFacts.ProfileFacts(profile,facts.facts().get(row).modes(),BIdentificationFacts.VERSION);
                            assertThrows(IllegalArgumentException.class,()->kernel.evaluate(copiedFacts,graph.lattices().get(row),probe,root));
                            var originalLattice=graph.lattices().get(row);
                            var copiedLattice=new ProbabilisticStateLattice(originalLattice.cells(),originalLattice.components(),originalLattice.actualPitchMeters(),originalLattice.resolutionLimited());
                            assertThrows(IllegalArgumentException.class,()->kernel.evaluate(facts.facts().get(row),copiedLattice,probe,root));
                            var foreign=new AttemptMemoryLedger(1<<20);try(var other=foreign.rootOwner()) {
                                assertThrows(IllegalArgumentException.class,()->kernel.evaluate(facts.facts().get(row),originalLattice,probe,other));assertEquals(0,foreign.currentBytes());
                            }
                            var zeroKernel=BSharedPositionKernel.create(field,BSharedPositionKernel.Parameters.diagnosticZeroGain(),work);
                            var zero=zeroKernel.evaluate(facts.facts().get(row),originalLattice,probe,root).reduce(originalLattice,probe,root);
                            for(int s=0;s<originalLattice.cells().size();s++){assertEquals(-StrictMath.log(totalWidth),zero.logMixtureDensity(s),2e-15);assertEquals(0,zero.responsibility(s,measured));}
                            var endpoint=graph.lattices().get(0);assertTrue(endpoint.cells().get(0).exactAnchor());
                            var exact=kernel.evaluate(facts.facts().get(0),endpoint,probe,root).reduce(endpoint,probe,root);assertEquals(0,exact.unaryCost(0));
                            long before=root.currentBytes();cancelled[0]=true;
                            assertThrows(java.util.concurrent.CancellationException.class,()->kernel.evaluate(facts.facts().get(row),originalLattice,probe,root));
                            cancelled[0]=false;assertEquals(before,root.currentBytes());
                            callback[0]=field::close;
                            assertThrows(IllegalStateException.class,()->kernel.evaluate(facts.facts().get(row),originalLattice,probe,root));
                            assertThrows(IllegalStateException.class,()->kernel.evaluate(facts.facts().get(row),originalLattice,probe,root));
                            long afterSourceClose=root.currentBytes();
                            assertThrows(IllegalStateException.class,()->BObservationAdapter.evaluate(facts.facts().get(row),originalLattice,parameters,true,kernel,probe,root));
                            assertEquals(afterSourceClose,root.currentBytes(),"Failed actual adapter retains no baseline/replacement child after source closure");
                        }
                    }
                    assertDoesNotThrow(facts::facts);assertDoesNotThrow(graph::lattices);assertDoesNotThrow(view::fields);
                    assertThrows(UnsupportedOperationException.class,capture::requireTableSourceAuthority);
                }
            }
        }
    }
}
