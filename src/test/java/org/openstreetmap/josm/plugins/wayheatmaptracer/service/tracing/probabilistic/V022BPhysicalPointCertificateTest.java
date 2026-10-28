package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Tiny original-factory point certificates, never a chord/table or inference. */
class V022BPhysicalPointCertificateTest {
    @Test void translatedAdapterRejectsNullStaleAndForeignProducersWithoutLeakingPriorR() {
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE);var foreign=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=rotated(work);var p=f.profiles().get(1);
            var ff=rotated(foreign);var fp=ff.profiles().get(1);
            var l=V022BSharedPositionFieldTest.tinyLattice(work.owner(),p);
            var fl=V022BSharedPositionFieldTest.tinyLattice(foreign.owner(),fp);
            try(var r=BPhysicalResponse.open(work,f.snapshot(),"scalar",List.of(p),List.of(l));
                    var c=BPhysicalContext.open(r);
                    var fr=BPhysicalResponse.open(foreign,ff.snapshot(),"scalar",List.of(fp),List.of(fl));
                    var fc=BPhysicalContext.open(fr)) {
                for(int variant=0;variant<3;variant++) {
                    r.ownLocal(0,0,1);assertTrue(r.value()>0);
                    BPhysicalContext producer=variant==0?null:variant==1?c:fc;
                    // Invalid index proves producer validation/invalidation comes
                    // BEFORE fallible descriptor indexing; phase0 is not a frame.
                    assertThrows(IllegalArgumentException.class,()->r.componentAtContext(Integer.MAX_VALUE,producer));
                    assertThrows(IllegalStateException.class,r::value);
                }
                c.query(Math.nextDown(8.0),8.6,4);assertEquals(BPhysicalContext.Reason.AVAILABLE,c.reason());
                r.ownLocal(0,0,1);assertTrue(r.value()>0);
                assertThrows(IllegalArgumentException.class,()->r.componentAtContext(0,c),"completed frame is not reusable");
                assertThrows(IllegalStateException.class,r::value);
            }
        }
    }

    @Test void callbackCannotPublishOrBorrowInProgressContextAfterClosingItsOwner() {
        BPhysicalWork[] wr={null};BPhysicalContext[] cr={null};boolean[] armed={false},observed={false};
        CancellationProbe probe=()->{
            if(armed[0]&&wr[0].queries()>=2) {
                armed[0]=false;observed[0]=true;
                assertThrows(IllegalStateException.class,cr[0]::context);
                assertThrows(IllegalStateException.class,cr[0]::deficit);
                assertThrows(IllegalStateException.class,cr[0]::reason);
                cr[0].close();
            }
            return false;
        };
        try(var work=BPhysicalWork.createExploratory(probe)) {
            wr[0]=work;var f=rotated(work);var p=f.profiles().get(1);
            var l=V022BSharedPositionFieldTest.tinyLattice(work.owner(),p);
            try(var r=BPhysicalResponse.open(work,f.snapshot(),"scalar",List.of(p),List.of(l));var c=BPhysicalContext.open(r)) {
                cr[0]=c;armed[0]=true;
                assertThrows(IllegalStateException.class,()->c.query(Math.nextDown(8.0),8.6,4));
                assertTrue(observed[0]);assertFalse(work.owner().isClosed());
                assertThrows(IllegalStateException.class,c::deficit);
                assertThrows(IllegalStateException.class,r::value);
            }
        }
    }

    @Test void rotatedContextCarriesItsRealTranslationAndMatchesIndependentFortyNineMax() {
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=rotated(work);var p=f.profiles().get(1);
            var l=V022BSharedPositionFieldTest.tinyLattice(work.owner(),p);
            try(var r=BPhysicalResponse.open(work,f.snapshot(),"scalar",List.of(p),List.of(l));var c=BPhysicalContext.open(r)) {
                // Declared before results: not an exact class point; the final
                // (+4,0) context addition rounds12-2^-50 to12. No h/grid tuning.
                double x=Math.nextDown(8.0),y=8.6;int gain=4;
                long before=work.queries();c.query(x,y,gain);
                assertTrue(c.field()>0);
                assertEquals(BPhysicalContext.Reason.AVAILABLE,c.reason());
                assertEquals(50,work.queries()-before);
                var field=f.snapshot().fields().get("scalar");var b=r.bound();
                double expectedF=nominal(field,b,x,y),expectedC=0;
                for(int a=-4;a<=4;a++)for(int d=-4;d<=4;d++)if(a*a+d*d<=16) {
                    double qx=(x+a*b.grid.ux)+d*b.grid.vx,qy=(y+a*b.grid.uy)+d*b.grid.vy;
                    double v=nominal(field,b,qx,qy),weight=1-StrictMath.sqrt(a*a+d*d)/4;
                    expectedC=Math.max(expectedC,v*weight);
                }
                double expectedD=StrictMath.log1p(gain*expectedC)-StrictMath.log1p(gain*expectedF);
                assertEquals(Double.doubleToRawLongBits(expectedF),Double.doubleToRawLongBits(c.field()));
                assertEquals(Double.doubleToRawLongBits(expectedC),Double.doubleToRawLongBits(c.context()));
                assertEquals(Double.doubleToRawLongBits(expectedD),Double.doubleToRawLongBits(c.deficit()));
                assertTrue(c.lower()<=expectedD&&c.upper()>=expectedD);
                BigInteger exactX=dyadic(x).add(dyadic(b.grid.ux).multiply(BigInteger.valueOf(4)));
                assertEquals(12,c.n[14],0);assertTrue(c.lo[14]<c.n[14]);
                assertTrue(dyadic(c.lo[14]).compareTo(exactX)<=0);
                assertTrue(dyadic(c.hi[14]).compareTo(exactX)>=0);
            }
        }
    }
    @Test void rotatedFactoryOwnRidgeHasCertifiedPositivePhysicalResponse() {
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=rotated(work);var p=f.profiles().get(1);
            var l=V022BSharedPositionFieldTest.tinyLattice(work.owner(),p);
            try(var r=BPhysicalResponse.open(work,f.snapshot(),"scalar",List.of(p),List.of(l))) {
                var b=r.bound();
                assertEquals(BSharedPositionEvidence.Status.AVAILABLE.ordinal(),b.indices[2][0],
                        "original extractor issued an eligible elementary receipt");
                assertNotEquals(0,b.d[BSharedPositionEvidence.NX][0]);
                assertNotEquals(0,b.d[BSharedPositionEvidence.NY][0]);
                assertFalse(BPhysicalResponse.exactGeometry(b.grid,b.stateX[1],b.stateY[1],
                        b.d[0][0],b.d[1][0],b.d[2][0],b.d[3][0]));
                r.ownLocal(0,0,1);
                assertEquals(BPhysicalResponse.Reason.AVAILABLE,r.reason());
                assertTrue(r.lower()>0,"independently positive actual ridge must survive rotation");
                assertTrue(r.lower()<=r.value()&&r.value()<=r.upper());
                assertTrue(Double.isFinite(r.error()));
                double expected=nominal(f.snapshot().fields().get("scalar"),b,b.stateX[1],b.stateY[1]);
                assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(r.value()),
                        "independent original grouping,all9taps and own projected tents");
                double central=Math.max(0,f.snapshot().fields().get("scalar").sampleBilinear(b.stateX[1],b.stateY[1]).orElseThrow()
                        -b.d[BSharedPositionEvidence.BACKGROUND][0]-b.d[BSharedPositionEvidence.NUISANCE][0]);
                double ku=Math.max(0,1-Math.abs(b.d[BSharedPositionEvidence.CENTRE][0])/b.d[BSharedPositionEvidence.H][0]);
                assertTrue(central>0&&ku>0);
                assertTrue(r.lower()>=central*ku/8,"central B3 weight1/4 with a strict factor2 error margin");
            }
        }
    }

    @Test void ownStateCouplingUsesOriginalOperandsAndDirectPointsUseExactInputs() {
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=rotated(work);var p=f.profiles().get(1);
            var l=V022BSharedPositionFieldTest.tinyLattice(work.owner(),p);
            try(var r=BPhysicalResponse.open(work,f.snapshot(),"scalar",List.of(p),List.of(l))) {
                for(int i:new int[]{0,2}) {
                    r.ownLocal(0,0,i);double offset=l.cells().get(i).offsetMeters();
                    for(int axis=0;axis<2;axis++) {
                        double a=axis==0?p.anchor().xMeters():p.anchor().yMeters();
                        double n=axis==0?p.normalUnit().xMeters():p.normalUnit().yMeters();
                        BigInteger exact=dyadic(a).shiftLeft(1074).add(dyadic(n).multiply(dyadic(offset)));
                        assertTrue(dyadic(r.lo[11+axis]).shiftLeft(1074).compareTo(exact)<=0);
                        assertTrue(dyadic(r.hi[11+axis]).shiftLeft(1074).compareTo(exact)>=0);
                        assertEquals(Double.doubleToRawLongBits(axis==0?r.bound().stateX[i]:r.bound().stateY[i]),
                                Double.doubleToRawLongBits(r.n[11+axis]));
                    }
                }
                double x=Math.nextUp(p.anchor().xMeters()),y=Math.nextDown(p.anchor().yMeters());
                r.query(0,0,x,y);assertEquals(BPhysicalResponse.Reason.AVAILABLE,r.reason());
                assertEquals(x,r.lo[11],0);assertEquals(x,r.hi[11],0);
                assertEquals(y,r.lo[12],0);assertEquals(y,r.hi[12],0);
                assertEquals(Double.doubleToRawLongBits(nominal(f.snapshot().fields().get("scalar"),r.bound(),x,y)),
                        Double.doubleToRawLongBits(r.value()));
            }
        }
    }

    @Test void closedCapBranchUnionPreservesTheCompleteNominalContextProgram() {
        try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=rotated(work);var p=f.profiles().get(1);
            var l=V022BSharedPositionFieldTest.tinyLattice(work.owner(),p);
            try(var r=BPhysicalResponse.open(work,f.snapshot(),"scalar",List.of(p),List.of(l));var c=BPhysicalContext.open(r)) {
                c.query(p.anchor().xMeters(),p.anchor().yMeters(),1);
                assertEquals(1,r.bound().indices[2].length,"independent oracle covers the complete single-mode fixture");
                var field=f.snapshot().fields().get("scalar");var b=r.bound();double x=p.anchor().xMeters(),y=p.anchor().yMeters();
                double expectedField=nominal(field,b,x,y),expectedContext=0;
                for(int i=0;i<49;i++){
                    int pair=BPhysicalContext.offset(i),a=(pair>>>4)-4,d=(pair&15)-4;
                    double qx=(x+a*b.grid.ux)+d*b.grid.vx,qy=(y+a*b.grid.uy)+d*b.grid.vy;
                    double weight=1-StrictMath.sqrt(a*a+d*d)/4;
                    expectedContext=Math.max(expectedContext,nominal(field,b,qx,qy)*weight);
                }
                assertTrue(expectedField>0);
                assertEquals(BPhysicalContext.Reason.AVAILABLE,c.reason(),"closed-cap uncertainty has valid finite branch-union bounds");
                assertEquals(Double.doubleToRawLongBits(expectedField),Double.doubleToRawLongBits(c.field()));
                assertEquals(Double.doubleToRawLongBits(expectedContext),Double.doubleToRawLongBits(c.context()));
                assertEquals(Double.doubleToRawLongBits(StrictMath.log1p(expectedContext)-StrictMath.log1p(expectedField)),Double.doubleToRawLongBits(c.deficit()));
            }
        }
    }

    @Test void nonconvexAuthorizationAndInvalidCornerRemainFactualSourceUnknown() {
        for(int variant:new int[]{1,2})try(var work=BPhysicalWork.createExploratory(CancellationProbe.NONE)) {
            var f=rotated(work,variant);var p=f.profiles().get(1);
            var l=V022BSharedPositionFieldTest.tinyLattice(work.owner(),p);
            try(var r=BPhysicalResponse.open(work,f.snapshot(),"scalar",List.of(p),List.of(l))) {
                assertTrue(r.bound().indices[2].length>0,"actual factory retains the measured contributor");
                assertEquals(BSharedPositionEvidence.Status.AVAILABLE.ordinal(),r.bound().indices[2][0]);
                // The invalid corner is outside the selected original profile's
                // samples. Queryx9 makes tapx10 own the x11 zero-weight corner;
                // changing source support must not erase the fixture's issuer.
                double x=variant==1?9:p.anchor().xMeters(),y=p.anchor().yMeters();
                var tap=new MetricPoint(x+1,y);
                if(variant==1)assertTrue(f.snapshot().fields().get("scalar").sampleBilinear(tap.xMeters(),tap.yMeters()).isEmpty());
                else assertFalse(f.snapshot().evidenceRegion().contains(tap));
                r.query(0,0,x,y);assertEquals(BPhysicalResponse.Reason.SOURCE_UNKNOWN,r.reason());
                assertTrue(Double.isNaN(r.value()));
            }
        }
    }

    @Test void generalInterfaceCeilingIsSeparateAndChecked() {
        assertEquals(746+2*32+48,BPhysicalWork.POINT_COUPLING_UNITS);
        assertEquals(256,BPhysicalWork.EXACT_GEOMETRY_UNITS,"old guard currencyunchanged");
    }

    /** Exact dyadic integer in2^-1074 units;test-only,not the implementation. */
    private static BigInteger dyadic(double x) {
        assertTrue(Double.isFinite(x));long bits=Double.doubleToRawLongBits(x);
        int e=(int)((bits>>>52)&2047);long m=bits&0xfffffffffffffL;
        BigInteger value=BigInteger.valueOf(e==0?m:m|(1L<<52));
        if(e!=0)value=value.shiftLeft(e-1);return bits<0?value.negate():value;
    }
    private static double nominal(ScalarEvidenceField f,BSharedPositionEvidence.Bound b,double x,double y) {
        double ax=b.d[0][0],ay=b.d[1][0],nx=b.d[2][0],ny=b.d[3][0];
        double u=(x-ax)*nx+(y-ay)*ny,z=(x-ax)*(-ny)+(y-ay)*nx;
        if(u<b.d[9][0]||u>b.d[10][0])return 0;
        double[] row=new double[3];
        for(int by=-1;by<=1;by++) {
            double[] tap=new double[3];
            for(int tx=-1;tx<=1;tx++) {
                double qx=(x+tx*b.grid.ux)+by*b.grid.vx,qy=(y+tx*b.grid.uy)+by*b.grid.vy;
                double v=f.sampleBilinear(qx,qy).orElseThrow();
                double own=(qx-ax)*nx+(qy-ay)*ny;
                tap[tx+1]=own<b.d[9][0]||own>b.d[10][0]?0:Math.max(0,(v-b.d[5][0])-b.d[6][0]);
            }
            row[by+1]=((tap[0]+2*tap[1])+tap[2])/4;
        }
        double b3=((row[0]+2*row[1])+row[2])/4;
        return (Math.max(0,1-Math.abs(z)/b.d[8][0])*Math.max(0,1-Math.abs(u-b.d[4][0])/b.d[7][0]))*b3;
    }

    private record Fixture(EvidenceSnapshot snapshot,List<ProbabilisticProfile> profiles) { }
    private static Fixture rotated(BPhysicalWork work) {
        return rotated(work,0);
    }
    private static Fixture rotated(BPhysicalWork work,int variant) {
        // Declared input before any result: constant-tangent horizontal RAW
        // stripe; an oblique ORIGINAL factory path, not forged profile receipts.
        try(var retained=work.owner().reserve(32L<<20)) {
            double[] values=new double[17*25];boolean[] valid=new boolean[values.length];
            Arrays.fill(values,.02);Arrays.fill(valid,true);Arrays.fill(values,8*17,10*17,1);
            if(variant==1)valid[8*17+11]=false;
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                    EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"scalar",
                    EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
            var field=new ScalarEvidenceField(17,25,values,valid,lineage);
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),
                    new GeographicPoint(-1,-1),new GeographicPoint(1,1));
            var region=variant!=2?MetricRegion.rectangle(0,0,16,24):new MetricRegion(List.of(List.of(
                    new MetricPoint(0,0),new MetricPoint(16,0),new MetricPoint(16,8.2),
                    new MetricPoint(9,8.2),new MetricPoint(9,8.8),new MetricPoint(16,8.8),
                    new MetricPoint(16,24),new MetricPoint(0,24))));
            var snapshot=new EvidenceSnapshot("physical-rotated-native",frame,
                    RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),
                    EvidenceResolution.nativeSource(1,1),region,region,Map.of("scalar",field),"synthetic");
            retained.adopt(snapshot);
            var profiles=new ProbabilisticProfileFactory().create(
                    List.of(new MetricPoint(7.45,7.9),new MetricPoint(9.05,9.1)),
                    new ProfileChainage(List.of(0.0,1.0,2.0),1),3,true,snapshot,field,
                    EvidenceModelParameters.defaults(),work.probe(),true,work.owner());
            assertEquals(3,profiles.size());return new Fixture(snapshot,profiles);
        }
    }
}
