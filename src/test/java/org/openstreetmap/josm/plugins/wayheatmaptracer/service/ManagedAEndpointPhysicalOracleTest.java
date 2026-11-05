package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Independent three-row full-path oracle: no production admission, key merging or cost method supplies expectations. */
class ManagedAEndpointPhysicalOracleTest {
    @TempDir Path cache;
    record PathCost(List<Integer> ids,int consumed,double q,double c,double t,double k,double total){}
    @Test void everyOrdinaryPrefixAndCompleteWinnerUseActualCanonicalPhysicalCoordinates()throws Exception{
        var fixture=new ManagedAEndpointProducerTest();fixture.cache=cache;
        fixture.withCapture(false,true,.25,CancellationProbe.NONE,f->{
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit);
                var tracked=new CorridorAwareTracker().trackManagedAEndpointEmission(input,ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION)){
                assertFalse(tracked.results().isEmpty());
                for(var actual:tracked.results()){
                    Object engine=field(actual,"engine");var provider=(TransitionProvider)engine;
                    var vertices=(List<?>)field(engine,"vertices");var arcs=arcs(engine);
                    var domain=(CorridorCenterlineOptimizer.NativeStateDomain)field(engine,"domain");
                    var tube=(LongitudinalCorridorTube)field(engine,"tube");
                    assertEquals(3,domain.rows().size());
                    Object root=vertices.get(0),last=vertices.get(vertices.size()-1);int rootId=id(root),lastId=id(last);
                    var source=input.profiles().levelZeroProfiles();double normalization=input.context().trackerNormalizationRasterPx();
                    double h1=Math.max(normalization,source.get(0).anchorScreen().distance(source.get(1).anchorScreen()));
                    double h2=Math.max(normalization,source.get(1).anchorScreen().distance(source.get(2).anchorScreen()));
                    double pitch=input.context().nativePitchMeters().orElseThrow();
                    MetricPoint e0=input.context().coordinateFrame().toMetric(new GeographicPoint(f.nodes().get(0).lat(),f.nodes().get(0).lon()));
                    MetricPoint e2=input.context().coordinateFrame().toMetric(new GeographicPoint(f.nodes().get(2).lat(),f.nodes().get(2).lon()));
                    RasterPoint r0=input.context().rasterAt(e0),r2=input.context().rasterAt(e2);
                    RasterPoint p0=raster(root),p2=raster(last);
                    double s0=input.context().sourceChainageMeters(0),s1=input.context().sourceChainageMeters(1),s2=input.context().sourceChainageMeters(2);
                    var candidates=new ArrayList<PathCost>();boolean physicalKDiffers=false;
                    var firstKey=new CanonicalKey(rootId,-1,rootId,0);
                    RasterPoint ref0=reference(source.get(0),tube.at(0).centerOffsetPx());
                    RasterPoint ref1=reference(source.get(1),tube.at(1).centerOffsetPx());
                    RasterPoint ref2=reference(source.get(2),tube.at(2).centerOffsetPx());
                    double referenceTurn=wrap(angle(ref1,ref2)-angle(ref0,ref1));
                    for(Object middle:vertices)if((int)value(middle,"row")==1){
                        int mid=id(middle);double offset=(double)value(middle,"offset");RasterPoint p1=raster(middle);
                        MetricPoint m1=input.context().metricAt(p1);
                        double q1=(s1-s0)/pitch*(1-mean(input.context().nativeGrid(),r0,p1));
                        double q2=(s2-s1)/pitch*(1-mean(input.context().nativeGrid(),p1,r2));
                        double c1=square(offset/h1)*domain.rows().get(1).continuityWeight();
                        double c2=square(-offset/h2)*domain.rows().get(2).continuityWeight();
                        double turn=wrap(angle(p1,p2)-angle(p0,p1));
                        double t=domain.rows().get(2).accelerationWeight()*square(wrap(turn-referenceTurn)/Math.max(1,(h1+h2)/(2*normalization)));
                        double k=physicalK(e0,m1,e2,pitch);
                        physicalKDiffers|=Double.doubleToLongBits(k)!=Double.doubleToLongBits(physicalK(input.context().metricAt(p0),m1,input.context().metricAt(p2),pitch));
                        Arc left=arc(arcs,rootId,mid),right=arc(arcs,mid,lastId);
                        // Actual admissions are queried only AFTER the expected costs above exist.
                        var a=provider.certify(firstKey,left);
                        var b=provider.certify(new CanonicalKey(mid,left.id(),mid,0),right);
                        assertTrue(a.admitted());assertTrue(b.admitted());
                        assertEquals(q1,a.q(),1e-12);assertEquals(q2,b.q(),1e-12);
                        assertEquals(c1,a.c(),1e-12);assertEquals(c2,b.c(),1e-12);assertEquals(t,b.t(),1e-12);assertEquals(k,b.k(),1e-12);
                        candidates.add(new PathCost(List.of(rootId,mid,lastId),0,q1+q2,c1+c2,t,k,(q1+c1)+(q2+c2+t+k)));
                    }
                    Arc optional=arc(arcs,rootId,lastId);
                    assertTrue(optional.optional());
                    // This fixture is one persistent wide Gaussian stripe; its short chord stays in the single high core.
                    double q=(s2-s0)/pitch*(1-mean(input.context().nativeGrid(),r0,r2));
                    var optionalActual=provider.certify(firstKey,optional);assertTrue(optionalActual.admitted(),optionalActual.reason());
                    assertEquals(q,optionalActual.q(),1e-12);
                    candidates.add(new PathCost(List.of(rootId,lastId),1,q,0,0,0,q));
                    candidates.sort((a,b)->{int c=Double.compare(a.total,b.total);if(c!=0)return c;c=Integer.compare(a.consumed,b.consumed);if(c!=0)return c;
                        for(int i=0;i<Math.min(a.ids.size(),b.ids.size());i++){c=Integer.compare(a.ids.get(i),b.ids.get(i));if(c!=0)return c;}return Integer.compare(a.ids.size(),b.ids.size());});
                    var winner=candidates.get(0);
                    assertEquals(winner.ids,actual.vertexIds());assertEquals(winner.total,actual.cost(),1e-11);
                    assertEquals(winner.q,actual.q(),1e-11);assertEquals(winner.c,actual.c(),1e-11);assertEquals(winner.t,actual.t(),1e-11);assertEquals(winner.k,actual.k(),1e-11);
                    assertTrue(physicalKDiffers,"Actual E changes the physical K input; native C/T formulas stay separate");
                }
            }
        });
    }
    /** Independent piecewise Simpson integration: exact quadratic rule per native bilinear cell, rounding tolerance explicit above. */
    static double mean(NativeScalarGridView grid,RasterPoint baseA,RasterPoint baseB){
        RasterPoint a=grid.toGrid(baseA),b=grid.toGrid(baseB);var cuts=new TreeSet<Double>();cuts.add(0.0);cuts.add(1.0);
        if(a.x()!=b.x())for(int x=(int)Math.floor(Math.min(a.x(),b.x()))+1;x<Math.max(a.x(),b.x());x++)cuts.add((x-a.x())/(b.x()-a.x()));
        if(a.y()!=b.y())for(int y=(int)Math.floor(Math.min(a.y(),b.y()))+1;y<Math.max(a.y(),b.y());y++)cuts.add((y-a.y())/(b.y()-a.y()));
        double integral=0,prior=0;
        for(double end:cuts){if(end>prior)integral+=(end-prior)/6*(sample(grid,a,b,prior)+4*sample(grid,a,b,(prior+end)/2)+sample(grid,a,b,end));prior=end;}
        return integral;
    }
    static double sample(NativeScalarGridView g,RasterPoint a,RasterPoint b,double t){
        double px=a.x()+(b.x()-a.x())*t,py=a.y()+(b.y()-a.y())*t;
        int x=Math.min(g.width()-2,(int)Math.floor(px)),y=Math.min(g.height()-2,(int)Math.floor(py));assertTrue(g.supportsCell(x,y));
        double fx=px-x,fy=py-y;return(g.nodeValue(x,y)*(1-fx)+g.nodeValue(x+1,y)*fx)*(1-fy)+(g.nodeValue(x,y+1)*(1-fx)+g.nodeValue(x+1,y+1)*fx)*fy;
    }
    static double physicalK(MetricPoint a,MetricPoint b,MetricPoint c,double pitch){return square(wrap(angle(b,c)-angle(a,b)))/Math.max(1,(a.distanceTo(b)+b.distanceTo(c))/(2*pitch));}
    static double angle(RasterPoint a,RasterPoint b){return Math.atan2(b.y()-a.y(),b.x()-a.x());}
    static double angle(MetricPoint a,MetricPoint b){return Math.atan2(b.yMeters()-a.yMeters(),b.xMeters()-a.xMeters());}
    static double wrap(double x){while(x>Math.PI)x-=2*Math.PI;while(x< -Math.PI)x+=2*Math.PI;return x;}
    static double square(double x){return x*x;}
    static RasterPoint reference(RenderedHeatmapSampler.CrossSectionProfile p,double offset){return new RasterPoint(p.anchorScreen().x+p.normalScreen().x*offset,p.anchorScreen().y+p.normalScreen().y*offset);}
    static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Object value(Object o,String name)throws Exception{var m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
    static int id(Object vertex)throws Exception{return (int)value(vertex,"id");}
    static RasterPoint raster(Object vertex)throws Exception{return (RasterPoint)value(vertex,"raster");}
    @SuppressWarnings("unchecked")static List<Arc> arcs(Object engine)throws Exception{return (List<Arc>)field(engine,"arcs");}
    static Arc arc(List<Arc> arcs,int from,int to){return arcs.stream().filter(a->a.fromId()==from&&a.toId()==to).findFirst().orElseThrow();}
}
