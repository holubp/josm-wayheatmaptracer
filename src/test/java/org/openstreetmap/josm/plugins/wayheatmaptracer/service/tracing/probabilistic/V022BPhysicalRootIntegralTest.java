package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Tiny actual factory/retained source only;no inference or table. */
class V022BPhysicalRootIntegralTest {
    @Test void positiveSignalSelfMaximumHasExactZeroDeficitWithoutLogMoments() {
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)){
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var c=BPhysicalContext.open(r)){
                c.query(8.125,8.625,4);assertTrue(c.field()>0);assertEquals(c.field(),c.context(),0);
                try(var j=BPhysicalRootIntegral.open(r)){
                j.certify(8.0625,8.625,8.1875,8.625,4);
                assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,j.status());
                assertEquals(0,j.value(),0);assertEquals(0,j.error(),0);
                assertEquals(0,j.attempted());assertEquals(0,j.logs());assertEquals(0,j.unknownUpper(),0);
                }
            }
        }
    }
    @Test void signedZeroCoordinatesKeepTheSameSelfMaximumIdentity() {
        for(double x:new double[]{0.0,-0.0})try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)){
            var f=fixture(w,false,-8);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var j=BPhysicalRootIntegral.open(r)){
                j.certify(x,8.625,x,8.6875,4);
                assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,j.status());
                assertEquals(0,j.value(),0);assertEquals(0,j.error(),0);assertEquals(0,j.attempted());
            }
        }
    }
    @Test void positiveAffineLogNeedsBothCertifiedMomentsAndTheirTails() {
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var c=BPhysicalContext.open(r)) {
                c.query(8.125,6.625,4);assertEquals(BPhysicalContext.Reason.AVAILABLE,c.reason());
                assertEquals(0,c.field(),0);assertTrue(c.context()>0&&c.deficit()>0);
                try(var integral=BPhysicalRootIntegral.open(r)) {
                    assertDoesNotThrow(()->integral.certify(8.0625,6.625,8.1875,6.625,4),"genuine affine-log integral needs an implemented moment");
                    assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,integral.status());assertTrue(integral.value()>0);
                    assertEquals(8550,integral.attempted());assertEquals(7790,integral.valid());assertEquals(760,integral.rejected());
                    assertTrue(integral.error()<=Math.scalb(1.0,-32));assertEquals(0,integral.unknownUpper(),0);
                    assertEquals(2,integral.logs());assertEquals(25255,integral.coefficientOperations());assertEquals(12055,integral.checkpointCalls());
                    Q tail=Q.ratio(1,146444944842L);
                    assertTrue(Q.of(integral.tailUpper()).compare(tail)>=0);
                    assertTrue(Q.of(integral.tailUpper()).mul(Q.ratio(2,1)).compare(Q.ratio(1,1L<<36))<0);
                    //Independent exact retained-source affine R,then analytic
                    //integral of log(Y). Log bounds use an80-term positive
                    //atanh series/rational remainder,not this Taylor/StrictMath
                    //implementation or a sampled quadrature.
                    var values=f.snapshot().fields().get("scalar").copiedValues();
                    Q y0=Q.ONE.add(reference(r,values,Q.of(8.0625),Q.of(8.625)).mul(Q.ratio(2,1)));
                    Q y1=Q.ONE.add(reference(r,values,Q.of(8.1875),Q.of(8.625)).mul(Q.ratio(2,1)));
                    var a=logOracle(y0);var b=logOracle(y1);Q beta=y1.sub(y0);assertTrue(beta.compare(Q.ZERO)<0);
                    Q nl=y1.mul(b.lo).sub(y1).sub(y0.mul(a.hi)).add(y0);
                    Q nh=y1.mul(b.hi).sub(y1).sub(y0.mul(a.lo)).add(y0);
                    Q exactLo=nh.div(beta).mul(Q.ratio(1,8)),exactHi=nl.div(beta).mul(Q.ratio(1,8));
                    assertTrue(exactLo.compare(Q.ZERO)>0);
                    assertTrue(Q.of(integral.lower()).compare(exactLo)<=0);assertTrue(Q.of(integral.upper()).compare(exactHi)>=0);
                    assertTrue(Q.of(integral.value()).sub(exactLo).abs().compare(Q.of(integral.error()))<=0);
                    assertTrue(Q.of(integral.value()).sub(exactHi).abs().compare(Q.of(integral.error()))<=0);
                    assertTrue(integral.lengthLower()<=.125&&integral.lengthUpper()>=.125);
                    System.out.println("ROOT_POSITIVE J="+Double.toHexString(integral.value())+",bounds=["+Double.toHexString(integral.lower())
                            +","+Double.toHexString(integral.upper())+"],error="+Double.toHexString(integral.error())+",budget="+Double.toHexString(integral.budget())
                            +",singleTail="+Double.toHexString(integral.tailUpper())+",ops="+integral.coefficientOperations()+",checks="+integral.checkpointCalls());
                }
            }
        }
    }
    @Test void exactGeometricZeroRequiresCertifiedRootRatherThanUnimplementedEntry() {
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l))) {
                assertEquals(8,r.bound().d[0][0],0);assertEquals(.5,r.bound().d[7][0],0);
                r.ownLocal(0,0,1);assertTrue(r.value()>0,"real issued ridge is supported");
                //For every context a in[-4,4],this chord's longitudinal
                //distance to anchor8 exceeds L1. All R and hence D are0;
                //this is geometric0,not an invented source measurement.
                assertEquals(1,r.bound().d[8][0],0);assertTrue(22.0625-4-8>1);
                long reads=w.observed(0),queries=w.queries();
                try(var integral=assertDoesNotThrow(()->BPhysicalRootIntegral.open(r),"requested J root must not be an UNIMPLEMENTED source result")) {
                    integral.certify(22.0625,8.625,22.1875,8.625,4);
                    assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,integral.status());
                    assertEquals(0,integral.value(),0);assertTrue(integral.lower()<=0&&integral.upper()>=0);
                    assertTrue(Double.isFinite(integral.error())&&integral.error()<=Math.scalb(1.0,-32));
                    assertEquals(reads,w.observed(0));assertEquals(queries,w.queries());
                }
            }
        }
    }
    @Test void canonicalReversalAndSignedZeroLengthPreserveRootMeaning() {
        long[] original=null;
        for(boolean reverse:new boolean[]{false,true})try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var j=BPhysicalRootIntegral.open(r)) {
                j.certify(reverse?8.1875:8.0625,6.625,reverse?8.0625:8.1875,6.625,4);
                assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,j.status());
                long[] bits={Double.doubleToRawLongBits(j.value()),Double.doubleToRawLongBits(j.lower()),Double.doubleToRawLongBits(j.upper()),Double.doubleToRawLongBits(j.error())};
                if(original==null)original=bits;else assertArrayEquals(original,bits);
            }
        }
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var j=BPhysicalRootIntegral.open(r)) {
                long reads=w.observed(0);j.certify(-0.0,8.625,+0.0,8.625,64);
                assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,j.status());assertEquals(0,j.value(),0);assertEquals(0,j.error(),0);
                assertEquals(0,j.unknownUpper(),0);assertEquals(0,j.lengthUpper(),0);assertEquals(0,j.logs());assertEquals(0,j.attempted());
                assertEquals(reads,w.observed(0));
            }
        }
    }
    @Test void knownSelfDominatedRangeMissingSupportAndExactBlindBoundaryHaveDistinctMeanings() {
        for(int variant=0;variant<3;variant++)try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,variant==1);var ps=variant==0?f.profiles():List.of(f.profiles().get(1));
            var ls=ps.stream().map(p->V022BSharedPositionFieldTest.tinyLattice(w.owner(),p)).toList();
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",ps,ls);var j=BPhysicalRootIntegral.open(r)) {
                j.certify(variant==0?7.4375:8.0625,variant==0?8.625:variant==1?4.625:6,
                        variant==0?7.5625:8.1875,variant==0?8.625:variant==1?4.625:6,4);
                assertEquals(0,j.attempted());assertEquals(0,j.logs());
                if(variant==0) {
                    // Both original components can win, but their complete
                    // self stream equals F and dominates all other contexts.
                    assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,j.status());
                    assertEquals(0,j.value(),0);assertEquals(0,j.error(),0);
                    assertEquals(0,j.lower(),0);assertEquals(0,j.upper(),0);assertEquals(0,j.unknownUpper(),0);
                } else if(variant==1) {
                    assertTrue(Double.isNaN(j.value()));
                    assertEquals(BPhysicalRootIntegral.Status.SOURCE_UNKNOWN,j.status());
                    assertEquals(0,j.unknownLower(),0);assertEquals(j.lengthUpper(),j.unknownUpper(),0);
                    assertTrue(j.unknownUpper()>.125&&Double.isNaN(j.error())&&Double.isNaN(j.lower()));
                } else {
                    assertEquals(0,r.bound().d[4][0],0);assertEquals(.5,r.bound().d[7][0],0);
                    //Integer translated y6+2b never enters the open .5 tent
                    //centred at8.5. Exact closed-endpoint production proves R0.
                    assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,j.status());
                    assertEquals(0,j.value(),0);assertEquals(0,j.error(),0);
                    assertEquals(0,j.unknownLower(),0);assertEquals(0,j.unknownUpper(),0);
                }
            }
        }
    }
    @Test void finalCheckpointCancelClosedBorrowerAndClosedSourceCannotPublish() {
        for(int kind=0;kind<3;kind++) {
            final int action=kind;boolean[] armed={false},fired={false};BPhysicalRootIntegral[] box={null};BPhysicalResponse[] source={null};
            CancellationProbe probe=()->{
                if(armed[0]&&!fired[0]&&box[0].completedMomentCalls()==2&&box[0].checkpointIntents()==12055) {
                    fired[0]=true;assertThrows(IllegalStateException.class,box[0]::value);assertThrows(IllegalStateException.class,box[0]::error);
                    if(action==0)return true;if(action==1)box[0].close();else source[0].close();
                }return false;
            };
            var w=BPhysicalWork.createExploratory(probe);var root=w.owner();
            try(w) {
                var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(root,p);
                try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var j=BPhysicalRootIntegral.open(r)) {
                    box[0]=j;source[0]=r;armed[0]=true;
                    if(action==0)assertThrows(java.util.concurrent.CancellationException.class,()->j.certify(8.0625,6.625,8.1875,6.625,4));
                    else assertThrows(IllegalStateException.class,()->j.certify(8.0625,6.625,8.1875,6.625,4));
                    assertTrue(fired[0]);assertThrows(IllegalStateException.class,j::value);assertThrows(IllegalStateException.class,j::status);
                    assertFalse(root.isClosed());
                }
            }assertEquals(0,root.currentBytes());
        }
    }
    @Test void invalidConsumedAndOverflowEntriesNeverExposeOldRoot() {
        for(int variant=0;variant<3;variant++)try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var j=BPhysicalRootIntegral.open(r)) {
                assertThrows(IllegalStateException.class,j::value);
                if(variant==0) {
                    j.certify(22.0625,8.625,22.1875,8.625,1);assertEquals(0,j.value(),0);
                    assertThrows(IllegalStateException.class,()->j.certify(Double.NaN,0,0,0,3));
                } else if(variant==1)assertThrows(IllegalArgumentException.class,()->j.certify(Double.NaN,0,0,0,3));
                else assertThrows(ArithmeticException.class,()->j.certify(-Double.MAX_VALUE,0,Double.MAX_VALUE,0,1));
                assertThrows(IllegalStateException.class,j::status);assertThrows(IllegalStateException.class,j::lower);
                assertFalse(w.owner().isClosed());
            }
        }
    }
    @Test void rootBodyHasIndependentClosedCountsAndSeparateActualDebit() throws Exception {
        assertEquals(25255,BPhysicalWork.ROOT_COEFFICIENT_OPS);assertEquals(12055,BPhysicalWork.ROOT_LOOP_CHECKS);
        long expected=2*2470117L+199+451+2*51+15+531+149+59+43+57+16+25255L*178+12055L*356+128L*8550+2*(109+52);
        assertEquals(expected,BPhysicalWork.ROOT_BODY);
        String text=java.nio.file.Files.readString(java.nio.file.Path.of("src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BPhysicalWork.java"));
        String method=text.substring(text.indexOf("    void claimRootIntegral()"),text.indexOf("    //Adaptive parameter replay:"));
        //Source partition:one admission sum;three nested dictionary sums;
        //helper multiplication+sum;one combined representation check;one
        //setup debit. All8 precede/use the separate32-slot prepaid join.
        int checkedApis=1+3+2+1+1;assertEquals(8,checkedApis);
        assertEquals(checkedApis,java.util.regex.Pattern.compile("Math\\.(?:add|multiply)Exact\\(").matcher(method).results().count());
        assertTrue(method.indexOf("semantic=next")<method.indexOf("long revised="));
        long[] maxOnly=null;
        for(boolean root:new boolean[]{false,true})try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l))) {
                long beforeUpper=w.registeredUpper(),beforeUnits=w.semanticUnits();
                if(!root)try(var max=BPhysicalMaxPanel.open(r)) {
                    max.certify(8.0625,6.625,8.1875,6.625,4);
                    maxOnly=new long[]{w.registeredUpper()-beforeUpper,w.semanticUnits()-beforeUnits,w.observed(0),w.observed(1),w.observed(3),w.observed(4)};
                } else try(var j=BPhysicalRootIntegral.open(r)) {
                    j.certify(8.0625,6.625,8.1875,6.625,4);
                    assertEquals(maxOnly[0]+256+12977+expected,w.registeredUpper()-beforeUpper);
                    assertEquals(maxOnly[1]+256+12977+expected,w.semanticUnits()-beforeUnits);
                    assertEquals(maxOnly[2],w.observed(0));assertEquals(maxOnly[3],w.observed(1));
                    assertEquals(maxOnly[4],w.observed(3));assertEquals(maxOnly[5],w.observed(4));
                    assertEquals(0,w.queries());assertEquals(25255,j.coefficientOperations());
                    System.out.println("ROOT_CENSUS body="+expected+",semantic="+w.semanticUnits()+",upper="+w.registeredUpper()
                            +",sourceReads="+w.observed(0)+",masks="+w.observed(1)+",descriptors="+w.observed(3)+",region="+w.observed(4));
                }
            }
        }
    }
    @Test void actualWrapperBytesRollbackAndOldCurrencyIsolation() {
        assertEquals(173,fields(BPhysicalRootIntegral.class));assertEquals(192,o(173));
        assertEquals(173,fields(BPhysicalMaxPanel.class));assertEquals(18112,17920+192);assertEquals(200,18312-18112);
        for(boolean refuse:new boolean[]{false,true})try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=fixture(w,false);var p=f.profiles().get(1);var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l))) {
                var old=w.sourceWork().snapshot();long bytes=w.owner().currentBytes();
                long prefixCells=(long)(r.sourceWidth()+1)*(r.sourceHeight()+1);
                long prefixDelta=8+64+(16+4*prefixCells+7)/8*8;
                //Four new fixed arrays (18376+48+128+344) and40B shell.
                long reuseDelta=18936;
                if(refuse)try(var held=w.owner().reserve((64L<<20)-bytes-(18304+prefixDelta+reuseDelta-1))) {
                    long filled=w.owner().currentBytes();
                    assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException.class,()->BPhysicalRootIntegral.open(r));
                    assertEquals(filled,w.owner().currentBytes());assertFalse(w.owner().isClosed());
                } else try(var j=BPhysicalRootIntegral.open(r)) {
                    assertEquals(bytes+18304+prefixDelta+reuseDelta,w.owner().currentBytes());j.certify(8.0625,6.625,8.1875,6.625,4);
                    assertEquals(BPhysicalRootIntegral.Status.CERTIFIED,j.status());
                }
                assertEquals(bytes,w.owner().currentBytes());assertFalse(w.owner().isClosed());
                var after=w.sourceWork().snapshot();assertEquals(old.buildUnits(),after.buildUnits());assertEquals(old.checkpoints(),after.checkpoints());
                assertEquals(old.sourceReads(),after.sourceReads());assertEquals(old.maskReads(),after.maskReads());
                assertEquals(old.sourceExtractionStartedCalls(),after.sourceExtractionStartedCalls());
                assertThrows(IllegalStateException.class,()->BPhysicalRootIntegral.open(r));
            }
        }
    }
    private static long fields(Class<?> type){long n=0;for(Class<?> c=type;c!=null&&c!=Object.class;c=c.getSuperclass())for(var f:c.getDeclaredFields()) {
        if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;var t=f.getType();n+=t==boolean.class||t==byte.class?1:t==short.class||t==char.class?2:t==int.class||t==float.class?4:8;
    }return n;}
    private static long o(long n){return (16+n+7)/8*8;}
    private record Fixture(EvidenceSnapshot snapshot,List<ProbabilisticProfile> profiles) { }
    private static Fixture fixture(BPhysicalWork work,boolean masked) {
        return fixture(work,masked,0);
    }
    private static Fixture fixture(BPhysicalWork work,boolean masked,double shiftX) {
        try(var held=work.owner().reserve(32L<<20)) {
            double[] values=new double[17*25];boolean[] valid=new boolean[values.length];
            Arrays.fill(values,.02);Arrays.fill(valid,true);Arrays.fill(values,8*17,10*17,1);if(masked)valid[8*17+10]=false;
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                    EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
            var raw=new ScalarEvidenceField(17,25,values,valid,lineage);
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
            var path=List.of(new MetricPoint(7+shiftX,8.5),new MetricPoint(9+shiftX,8.5));
            var inverse=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(-shiftX,0),1,0,0,1);
            var grid=new MetricRasterGrid(frame,new MetricPoint(-.25+shiftX,-.25),1,0,0,1,.5,35,51);
            var snapshot=new RasterEvidenceCapture(32L<<20).captureWithMetricScalarSource("unit-wide-quarter",
                    new BufferedImage(17,25,BufferedImage.TYPE_INT_ARGB),raw.copiedValidity(),raw.copiedValues(),
                    path.stream().map(frame::toGeographic).toList(),path,inverse,grid,EvidenceResolution.nativeSource(1,1),3,
                    "synthetic",EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",raw.lineage(),work.probe());
            held.adopt(snapshot);var profiles=new ProbabilisticProfileFactory().create(path,new ProfileChainage(List.of(0.0,1.0,2.0),1),3,true,
                    snapshot,snapshot.fields().get("scalar"),EvidenceModelParameters.defaults(),work.probe(),true,work.owner());
            assertEquals(3,profiles.size());return new Fixture(snapshot,profiles);
        }
    }
    private record Bounds(Q lo,Q hi) { }
    private static Bounds logOracle(Q y) {
        assertTrue(y.compare(Q.ONE)>=0);Q z=y.sub(Q.ONE).div(y.add(Q.ONE));assertTrue(z.compare(Q.ONE)<0);
        Q z2=z.mul(z),power=z,sum=Q.ZERO;
        for(int k=0;k<80;k++){sum=sum.add(power.div(Q.ratio(2*k+1,1)));power=power.mul(z2);}
        Q low=sum.mul(Q.ratio(2,1));
        Q remainder=power.mul(Q.ratio(2,1)).div(Q.ratio(161,1).mul(Q.ONE.sub(z2)));
        return new Bounds(low,low.add(remainder));
    }
    private static Q reference(BPhysicalResponse r,double[] values,Q x,Q y) {
        var b=r.bound();Q ax=Q.of(b.d[0][0]),ay=Q.of(b.d[1][0]),nx=Q.of(b.d[2][0]),ny=Q.of(b.d[3][0]);
        Q u=x.sub(ax).mul(nx).add(y.sub(ay).mul(ny)),z=x.sub(ax).mul(ny.negate()).add(y.sub(ay).mul(nx));
        Q left=Q.of(b.d[9][0]),right=Q.of(b.d[10][0]);if(u.compare(left)<0||u.compare(right)>0)return Q.ZERO;
        Q tu=Q.ONE.sub(u.sub(Q.of(b.d[4][0])).abs().div(Q.of(b.d[7][0]))).positive();
        Q tz=Q.ONE.sub(z.abs().div(Q.of(b.d[8][0]))).positive(),sum=Q.ZERO;
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
            Q qx=x.add(Q.of(b.grid.ux).mul(Q.ratio(dx,1))).add(Q.of(b.grid.vx).mul(Q.ratio(dy,1)));
            Q qy=y.add(Q.of(b.grid.uy).mul(Q.ratio(dx,1))).add(Q.of(b.grid.vy).mul(Q.ratio(dy,1)));
            Q tapU=qx.sub(ax).mul(nx).add(qy.sub(ay).mul(ny));if(tapU.compare(left)<0||tapU.compare(right)>0)continue;
            Q excess=source(r,values,qx,qy).sub(Q.of(b.d[5][0])).sub(Q.of(b.d[6][0])).positive();
            sum=sum.add(excess.mul(Q.ratio((dx==0?2:1)*(dy==0?2:1),16)));
        }return tu.mul(tz).mul(sum);
    }
    private static Q source(BPhysicalResponse r,double[] values,Q x,Q y) {
        var t=r.bound().input.evidence.transform();Q dx=x.sub(Q.of(t.origin().xMeters())),dy=y.sub(Q.of(t.origin().yMeters()));
        Q ux=Q.of(t.xAxisEastMetersPerSourcePixel()),uy=Q.of(t.xAxisNorthMetersPerSourcePixel()),vx=Q.of(t.yAxisEastMetersPerSourcePixel()),vy=Q.of(t.yAxisNorthMetersPerSourcePixel());
        Q det=ux.mul(vy).sub(uy.mul(vx)),phase=Q.of(t.originKind()==RasterMetricTransform.OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY?.5:0);
        Q scale=Q.of(t.axisUnit()==RasterMetricTransform.AxisUnit.SOURCE_PIXEL?t.rasterPixelsPerSourcePixel():1);
        Q rx=dx.mul(vy).sub(dy.mul(vx)).div(det).sub(phase).mul(scale),ry=dy.mul(ux).sub(dx.mul(uy)).div(det).sub(phase).mul(scale);
        int ix=rx.floor(),iy=ry.floor(),w=r.sourceWidth(),h=r.sourceHeight(),jx=Math.min(w-1,ix+1),jy=Math.min(h-1,iy+1);
        assertTrue(ix>=0&&iy>=0&&ix<w&&iy<h);Q fx=rx.sub(Q.ratio(ix,1)),fy=ry.sub(Q.ratio(iy,1));
        Q a=Q.of(values[iy*w+ix]).mul(Q.ONE.sub(fx)).add(Q.of(values[iy*w+jx]).mul(fx));
        Q b=Q.of(values[jy*w+ix]).mul(Q.ONE.sub(fx)).add(Q.of(values[jy*w+jx]).mul(fx));return a.mul(Q.ONE.sub(fy)).add(b.mul(fy));
    }
    /**Independent rational test arithmetic;no field/integral implementation calls. */
    private record Q(BigInteger n,BigInteger d) {
        static final Q ZERO=ratio(0,1),ONE=ratio(1,1);
        Q{if(d.signum()<0){n=n.negate();d=d.negate();}if(d.signum()==0)throw new ArithmeticException();var g=n.gcd(d);n=n.divide(g);d=d.divide(g);}
        static Q ratio(long n,long d){return new Q(BigInteger.valueOf(n),BigInteger.valueOf(d));}
        static Q of(double x){assertTrue(Double.isFinite(x));long bits=Double.doubleToRawLongBits(x),m=bits&0xfffffffffffffL;int e=(int)((bits>>>52)&2047);
            BigInteger n=BigInteger.valueOf(e==0?m:m|(1L<<52)),d=BigInteger.ONE;int shift=e==0?-1074:e-1075;if(shift>=0)n=n.shiftLeft(shift);else d=d.shiftLeft(-shift);return new Q(bits<0?n.negate():n,d);}
        Q add(Q q){return new Q(n.multiply(q.d).add(q.n.multiply(d)),d.multiply(q.d));}Q sub(Q q){return add(q.negate());}
        Q negate(){return new Q(n.negate(),d);}Q abs(){return n.signum()<0?negate():this;}
        Q mul(Q q){return new Q(n.multiply(q.n),d.multiply(q.d));}Q div(Q q){return new Q(n.multiply(q.d),d.multiply(q.n));}
        Q positive(){return n.signum()<0?ZERO:this;}int compare(Q q){return n.multiply(q.d).compareTo(q.n.multiply(d));}
        int floor(){var q=n.divideAndRemainder(d);return q[0].intValueExact()-(n.signum()<0&&q[1].signum()!=0?1:0);}
    }
}
