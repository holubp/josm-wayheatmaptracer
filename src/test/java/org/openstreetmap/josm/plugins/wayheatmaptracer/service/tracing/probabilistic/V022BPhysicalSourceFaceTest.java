package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Genuine active TABLE source;test oracle reads never issue a new capability. */
class V022BPhysicalSourceFaceTest {
    @Test void actualTableRangeEnclosesSeparateOriginalRnAndExactCellExpressions() throws Exception {
        new V022BCapturedTableIntegrationTest().run(5);
    }
    @Test void exactSourceSitesBoundEveryLoopAndPreserveThePublicTap() throws Exception {
        String dir="src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/";
        String source=java.nio.file.Files.readString(java.nio.file.Path.of(dir+"BPhysicalPanelCertificate.java"));
        String adjacent=source.substring(source.indexOf("private boolean adjacentRange("),source.indexOf("private boolean rangeOp("));
        assertEquals(4,occurrences(adjacent,"parent.view.scalar("));
        assertEquals(11,occurrences(adjacent,"rangeOp("));
        assertEquals(4,occurrences(adjacent,"checkpoint();"),"entry,cell,domain,final;88 op checks are in rangeOp");
        assertTrue(adjacent.indexOf("Double.isFinite(pxl)")<adjacent.indexOf("rangeOp(55"));
        assertFalse(adjacent.contains("pxl>=0x1p-32"));assertFalse(adjacent.contains("0x1p-256"));
        assertTrue(adjacent.indexOf("n[29]=p[29]=lo[29]=hi[29]=error[29]=Double.NaN")<adjacent.indexOf("checkpoint();"));
        assertTrue(adjacent.indexOf("currentMax.requireFor(component)")<adjacent.indexOf("scratch=2;return true"));
        String original=source.substring(source.indexOf("    int componentTap("),source.indexOf("    /** TABLE-only extension"));
        String frozen=java.nio.file.Files.readString(java.nio.file.Path.of("build/captured-sourceface-implementation/final/base/BPhysicalPanelCertificate.java"));
        assertEquals(frozen.substring(frozen.indexOf("    int componentTap("),frozen.indexOf("    void certify(")),original);
        assertEquals(frozen.substring(frozen.indexOf("        private int signatures("),frozen.indexOf("        boolean interpolateFixed(")),
                source.substring(source.indexOf("        private int signatures("),source.indexOf("        boolean interpolateFixed(")),"Both existing mask proofs and fixed path unchanged");
        String table=source.substring(source.indexOf("    int componentTableTap("),source.indexOf("    void certify("));
        assertTrue(table.indexOf("work.admit(")<table.indexOf("currentMax.requireFor(component)"),"New checks belong to paid source body");
        assertTrue(table.indexOf("classifyPrepared()")<table.indexOf("bank.adjacentRange("),"No adjacent source without original masks/region");
        assertEquals(102,1+4+8+8*11+1);assertEquals(16,4*4);
        assertEquals(136754+292755+64L*(2834+10933)+6*2248+6*52+264+64+961L*3017+4887+24+9*8,BPhysicalWork.COMPONENT_PANEL_BODY);
    }
    static void independentRecord(Object source)throws ReflectiveOperationException {
        double[] p=(double[])field(source,"n"),q=(double[])field(source,"p"),lo=(double[])field(source,"lo"),hi=(double[])field(source,"hi"),error=(double[])field(source,"error");
        Object parent=field(source,"parent"),view=field(parent,"view");
        var bound=((BPhysicalResponse.SourceView)view).bound;
        var work=(BPhysicalWork)field(source,"work");
        long e=0;var polygons=bound.input.evidence.evidenceRegion().polygons();for(var polygon:polygons)e+=polygon.size();
        long polls=1645+122*e+2*polygons.size();
        long expected=BPhysicalWork.ENDPOINT_SOURCE_UNITS+BPhysicalWork.PANEL_INTERFACE_UNITS+12406+969*e+42*polygons.size()+169*polls+39992;
        assertEquals(expected,((long[])field(work,"costs"))[BPhysicalWork.SOURCE_PANEL],"Actual per-admit exclusive source debit");
        assertEquals(264+304*e+16*66,field(work,"sourceWrapperUnits"));
        Object table=field(work.tableRegistry(),"table"),budget=field(table,"budget");
        long descriptors=bound.indices[2].length,visits=50*Math.max(1,descriptors),taps=9*visits;
        long helper=BPhysicalWork.prefixNodeHelperUnits(Math.max(1,descriptors),e,polygons.size())+taps*8926;
        assertEquals(helper,field(budget,"nodeHelper"));assertEquals(helper,field(work,"adaptiveNodeHelpers"));
        assertEquals(taps*(24+4*e)+14*visits+taps+visits+3,field(budget,"admissions"));
        var roleSlope=budget.getClass().getDeclaredMethod("roleSlope",int.class);roleSlope.setAccessible(true);
        assertEquals((long)field(budget,"pairs")*taps*24,roleSlope.invoke(budget,3));
        int w=(int)field(source,"width"),h=(int)field(source,"height"),cells=0;
        int x0=(int)StrictMath.floor(Math.min(p[15],lo[15])),x1=(int)StrictMath.floor(Math.max(q[15],hi[15]));
        int y0=(int)StrictMath.floor(Math.min(p[16],lo[16])),y1=(int)StrictMath.floor(Math.max(q[16],hi[16]));
        assertTrue(x1-x0<=1&&y1-y0<=1);assertTrue(x0!=x1||y0!=y1,"New multi-owner path,not fixed-cell relabeling");
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){
            cells++;double a=bound.source[y*w+x],b=bound.source[y*w+Math.min(w-1,x+1)],
                c=bound.source[Math.min(h-1,y+1)*w+x],d=bound.source[Math.min(h-1,y+1)*w+Math.min(w-1,x+1)];
            for(int domain=0;domain<2;domain++){
                double lx=Math.max(domain==0?p[15]:lo[15],x),hx=Math.min(domain==0?q[15]:hi[15],Math.min(w-1,x+1));
                double ly=Math.max(domain==0?p[16]:lo[16],y),hy=Math.min(domain==0?q[16]:hi[16],Math.min(h-1,y+1));
                if(lx>hx||ly>hy)continue;
                for(double xx:new double[]{lx,hx})for(double yy:new double[]{ly,hy}){
                    if(domain==0){double fx=xx-x,fy=yy-y,top=a+fx*(b-a),bottom=c+fx*(d-c),v=top+fy*(bottom-top);
                        assertTrue(p[29]<=v&&q[29]>=v,"Original RN owner expression must be enclosed");}
                    else {
                        BigDecimal fx=exact(xx).subtract(BigDecimal.valueOf(x)),fy=exact(yy).subtract(BigDecimal.valueOf(y));
                        BigDecimal top=exact(a).add(fx.multiply(exact(b).subtract(exact(a)))),bottom=exact(c).add(fx.multiply(exact(d).subtract(exact(c))));
                        BigDecimal v=top.add(fy.multiply(bottom.subtract(top)));
                        assertTrue(exact(lo[29]).compareTo(v)<=0&&exact(hi[29]).compareTo(v)>=0,"Independent exact-real bilinear enclosure");
                    }
                }
            }
        }
        assertTrue(cells>=2&&cells<=4);assertTrue(Double.isFinite(error[29]));
        BigDecimal needed=exact(q[29]).subtract(exact(lo[29])).max(exact(hi[29]).subtract(exact(p[29]))).max(BigDecimal.ZERO);
        assertTrue(exact(error[29]).compareTo(needed)>=0,"Separate Cartesian coupling is funded,not assumed polynomial identity");
        System.out.println("ADJACENT_RECORD cells="+cells+" P=["+Double.toHexString(p[29])+","+Double.toHexString(q[29])+"] E=["+Double.toHexString(lo[29])+","+Double.toHexString(hi[29])+"] error="+Double.toHexString(error[29]));
    }
    private static BigDecimal exact(double x){return new BigDecimal(x);}
    /** Test-only phase fault on the genuine currently owned Max;no success
     * capability is manufactured and no numerical/source operands are changed.
     */
    static void failedMaxGuardKeepsPaidThrownFacts(Object source,Object component,Object maximum)throws ReflectiveOperationException {
        var panel=(BPhysicalPanelCertificate)field(source,"parent");
        var work=(BPhysicalWork)field(source,"work");
        long units=work.semanticUnits();long[] admitted=(long[])field(work,"admitted"),normal=(long[])field(work,"normal"),thrown=(long[])field(work,"thrown");
        long a=admitted[BPhysicalWork.SOURCE_PANEL],n=normal[BPhysicalWork.SOURCE_PANEL],t=thrown[BPhysicalWork.SOURCE_PANEL];
        var phase=maximum.getClass().getDeclaredField("phase");phase.setAccessible(true);int before=phase.getInt(maximum);
        try {
            phase.setInt(maximum,0);
            assertThrows(IllegalStateException.class,()->panel.componentTableTap((BPhysicalComponentPanel)component,(BPhysicalMaxPanel)maximum,0,0));
            assertEquals(units+((long[])field(work,"costs"))[BPhysicalWork.SOURCE_PANEL],work.semanticUnits());
            assertEquals(a+1,admitted[BPhysicalWork.SOURCE_PANEL]);assertEquals(n,normal[BPhysicalWork.SOURCE_PANEL]);assertEquals(t+1,thrown[BPhysicalWork.SOURCE_PANEL]);
            assertThrows(IllegalStateException.class,panel::status);
            assertThrows(IllegalStateException.class,((BPhysicalComponentPanel)component)::status,"In-progress candidate cannot expose the previous range");
        } finally {phase.setInt(maximum,before);}
    }
    private static int occurrences(String text,String needle){int n=0,at=0;while((at=text.indexOf(needle,at))>=0){n++;at+=needle.length();}return n;}
    private static Object field(Object object,String name)throws ReflectiveOperationException {
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException missing){}
        throw new NoSuchFieldException(name);
    }
}
