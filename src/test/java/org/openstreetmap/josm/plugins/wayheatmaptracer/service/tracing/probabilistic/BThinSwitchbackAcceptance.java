package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Truth-only ordered full-edge geometry; never an inference input. */
final class BThinSwitchbackAcceptance {
    static final String ORACLE_ID="thin-hairpin-ordered-frechet-one-metre-v1";
    record Result(boolean available,boolean anchors,boolean orderedWholePath,long cells,long checkpoints) {
        boolean passed(){return available&&anchors&&orderedWholePath;}
        Map<String,Object> receipt(){return Map.of("oracle",ORACLE_ID,"available",available,"exactAnchors",anchors,
                "orderedWholePath",orderedWholePath,"passed",passed(),"freeSpaceCells",cells,"checkpoints",checkpoints,
                "tubeMeters",1.0,"worldXMonotonicityRequired",false);}
    }
    static Result evaluate(List<MetricPoint> truth,List<MetricPoint> path,double nativePitch,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(cancellation);Objects.requireNonNull(owner);
        if(truth==null||truth.size()<2||truth.size()>128||path==null||path.size()<2||path.size()>4096||nativePitch!=1)
            return new Result(false,false,false,0,0);
        // Fund reference points/list, result and all reusable primitive boundary scratch
        // before constructing them. No objects are allocated per free-space cell.
        long bytes=8192+2*AttemptMemoryLedger.arrayBytes(truth.size()-1,8)+3*AttemptMemoryLedger.arrayBytes(2,8);
        try(var reservation=owner.reserve(bytes)) {
            var work=new Work(cancellation);
            for(int i=0;i<truth.size();i++){work.tick();if(truth.get(i)==null)return new Result(false,false,false,0,work.checkpoints);}
            for(int i=0;i<path.size();i++){work.tick();if(path.get(i)==null)return new Result(false,false,false,0,work.checkpoints);}
            boolean anchors=path.get(0).equals(truth.get(0))&&path.get(path.size()-1).equals(truth.get(truth.size()-1));
            if(!anchors)return new Result(true,false,false,0,work.checkpoints);
            int m=truth.size()-1;double[] rightLo=new double[m],rightHi=new double[m];Arrays.fill(rightLo,Double.NaN);
            double[] top=new double[2],right=new double[2],axis=new double[2];
            boolean bottomAxisOpen=true,leftAxisOpen=true;long cells=0;
            for(int i=0;i<path.size()-1;i++) {
                work.tick();var a=path.get(i);var b=path.get(i+1);
                interval(a,b,truth.get(0),axis);
                double bottomLo=Double.NaN;
                if(bottomAxisOpen&&valid(axis)&&axis[0]<=0)bottomLo=0;
                bottomAxisOpen=!Double.isNaN(bottomLo)&&axis[1]>=1;
                for(int j=0;j<m;j++) {
                    work.tick();cells++;
                    double leftLo=rightLo[j];
                    if(i==0) {
                        interval(truth.get(j),truth.get(j+1),a,axis);
                        leftLo=leftAxisOpen&&valid(axis)&&axis[0]<=0?0:Double.NaN;
                        leftAxisOpen=!Double.isNaN(leftLo)&&axis[1]>=1;
                    }
                    interval(a,b,truth.get(j+1),top);
                    interval(truth.get(j),truth.get(j+1),b,right);
                    // Each cell is a convex free-space ellipse. A reachable left boundary
                    // admits every free top point; reachable bottom admits top t>=bottomLo.
                    double nextTop=valid(top)?(!Double.isNaN(leftLo)?top[0]:
                            !Double.isNaN(bottomLo)?Math.max(top[0],bottomLo):Double.NaN):Double.NaN;
                    if(!Double.isNaN(nextTop)&&nextTop>top[1])nextTop=Double.NaN;
                    double nextRight=valid(right)?(!Double.isNaN(bottomLo)?right[0]:
                            !Double.isNaN(leftLo)?Math.max(right[0],leftLo):Double.NaN):Double.NaN;
                    if(!Double.isNaN(nextRight)&&nextRight>right[1])nextRight=Double.NaN;
                    rightLo[j]=nextRight;rightHi[j]=right[1];bottomLo=nextTop;
                }
            }
            boolean end=!Double.isNaN(rightLo[m-1])&&rightHi[m-1]>=1;
            return new Result(true,true,end,cells,work.checkpoints);
        }
    }
    private static boolean valid(double[] interval){return !Double.isNaN(interval[0]);}
    /** Closed parameter interval where an entire segment is within1m of a fixed point. */
    private static void interval(MetricPoint a,MetricPoint b,MetricPoint center,double[] out) {
        double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters();
        double ax=a.xMeters()-center.xMeters(),ay=a.yMeters()-center.yMeters();
        double norm=dx*dx+dy*dy;
        if(norm==0){out[0]=ax*ax+ay*ay<=1?0:Double.NaN;out[1]=1;return;}
        double projection=-(ax*dx+ay*dy)/norm;
        double cross=ax*dy-ay*dx,remaining=1-cross*cross/norm;
        if(remaining<0){out[0]=Double.NaN;out[1]=Double.NaN;return;}
        double half=StrictMath.sqrt(remaining/norm),lo=Math.max(0,projection-half),hi=Math.min(1,projection+half);
        out[0]=lo<=hi?lo:Double.NaN;out[1]=hi;
    }
    private static final class Work {
        final CancellationProbe cancellation;long checkpoints;
        Work(CancellationProbe cancellation){this.cancellation=cancellation;}
        void tick(){if(++checkpoints>1_000_000)throw new IllegalArgumentException("Fixed hairpin oracle work bound");
            if(cancellation.cancelled())throw new java.util.concurrent.CancellationException("Hairpin oracle cancelled");}
    }
}
