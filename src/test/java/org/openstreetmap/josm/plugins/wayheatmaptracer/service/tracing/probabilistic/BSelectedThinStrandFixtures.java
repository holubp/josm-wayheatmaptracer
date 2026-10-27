package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Separate selected-only A_CAPTURE source; never activates the original eager cache. */
final class BSelectedThinStrandFixtures {
    static final String SOURCE_ID="selected-owned-thin-recipe-v1";
    private static final int WIDTH=81,HEIGHT=81;
    private static final double ORIGIN_X=-20.0,BACKGROUND=0.02;
    private BSelectedThinStrandFixtures() { }

    static boolean supported(String id) {
        return "thin-three-strands".equals(id)
            || "thin-three-strands-intermittent".equals(id)
            || "thin-strands-merge-separate".equals(id)
            || "drifting-strong-with-faint-original".equals(id)
            || "thin-strong-sharp-bend".equals(id)
            || "thin-faint-coherent-sharp-bend".equals(id)
            || "thin-local-overlap-noise".equals(id);
    }
    static Fixture selected(BSharedCaptureAdmission owner,String id) {
        AttemptMemoryLedger.Reservation reservation=owner.reserveSelectedThinRecipeUnit(id);
        try {
            Fixture fixture=create(id);
            reservation.adopt(fixture);
            return fixture;
        }catch(RuntimeException|Error primary){
            try{reservation.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            owner.abortOriginalScalarInputUnit(primary);
            throw primary;
        }
    }
    private static Fixture create(String id) {
        double[] pixels=new double[WIDTH*HEIGHT];
        Arrays.fill(pixels,BACKGROUND);
        boolean[] valid=new boolean[pixels.length];
        Arrays.fill(valid,true);
        boolean intermittent=id.equals("thin-three-strands-intermittent");
        boolean merge=id.equals("thin-strands-merge-separate");
        boolean drifting=id.equals("drifting-strong-with-faint-original");
        boolean sharp=id.equals("thin-strong-sharp-bend")||id.equals("thin-faint-coherent-sharp-bend");
        List<Strand> strands=new ArrayList<>();
        Strand strongCourse=null;
        if(drifting){
            strongCourse=new Strand("strong-course",new double[WIDTH],new boolean[WIDTH],0.70,3);
            for(int column=0;column<WIDTH;column++){
                double x=worldX(column);
                double t=clamp((x+8.0)/60.0,0.0,1.0);
                double e=Math.sin(Math.PI*t);
                strongCourse.centerY[column]=37.0+18.0*e;
                strongCourse.observed[column]=true;
                addRendered(pixels,column,strongCourse.centerY[column],0.70,3);
            }
            for(int strandIndex=0;strandIndex<3;strandIndex++){
                int j=strandIndex-1;
                double[] centers=new double[WIDTH];
                boolean[] observed=new boolean[WIDTH];
                for(int column=0;column<WIDTH;column++){
                    double x=worldX(column);
                    double t=clamp((x+8.0)/60.0,0.0,1.0);
                    double e=Math.sin(Math.PI*t);
                    centers[column]=37.0+2.0*j*e
                        +0.4*Math.sin(6.0*Math.PI*t+j*Math.PI/3.0)*e;
                    observed[column]=true;
                    addRendered(pixels,column,centers[column],0.04,1);
                }
                strands.add(new Strand("faint-"+j,centers,observed,0.04,1));
            }
            return new Fixture(id,"strong-course",pixels,valid,strands,strongCourse);
        }
        if(sharp){
            double amplitude=id.equals("thin-strong-sharp-bend")?0.70:0.08;
            double[] centers=new double[WIDTH];
            boolean[] observed=new boolean[WIDTH];
            for(int column=0;column<WIDTH;column++){
                double x=worldX(column);
                centers[column]=37.0+3.0*Math.max(0.0,1.0-Math.abs(x-22.0)/3.0);
                observed[column]=true;
                addRendered(pixels,column,centers[column],amplitude,1);
            }
            strands.add(new Strand("sharp-course",centers,observed,amplitude,1));
            return new Fixture(id,"sharp-course",pixels,valid,strands,null);
        }
        if(id.equals("thin-local-overlap-noise")){
            for(int strandIndex=0;strandIndex<3;strandIndex++){
                int j=strandIndex-1;
                double[] centers=new double[WIDTH];
                boolean[] observed=new boolean[WIDTH];
                for(int column=0;column<WIDTH;column++){
                    double x=worldX(column);
                    double t=clamp((x+8.0)/60.0,0.0,1.0);
                    double e=Math.sin(Math.PI*t);
                    centers[column]=37.0+e*(1.5*j
                        +0.6*Math.sin(11.0*Math.PI*t+strandIndex*2.0*Math.PI/3.0)
                        +0.3*Math.sin(17.0*Math.PI*t+strandIndex*2.0*Math.PI/3.0));
                    observed[column]=true;
                    addRendered(pixels,column,centers[column],0.06,1);
                }
                strands.add(new Strand("overlap-"+j,centers,observed,0.06,1));
            }
            return new Fixture(id,"LATENT_REFERENCE_ONE_METRE",pixels,valid,strands,null);
        }
        for(int strandIndex=0;strandIndex<3;strandIndex++){
            int j=strandIndex-1;
            double baseAmplitude=strandIndex==1?0.08:0.06;
            double[] centers=new double[WIDTH];
            boolean[] observed=new boolean[WIDTH];
            for(int column=0;column<WIDTH;column++){
                double x=worldX(column);
                double t=clamp((x+8.0)/60.0,0.0,1.0);
                double e=Math.sin(Math.PI*t);
                double separation=merge?clamp((Math.abs(x-22.0)-1.0)/4.0,0.0,1.0):1.0;
                centers[column]=37.0+2.0*e
                    +separation*(4.0*j*e+0.6*Math.sin(4.0*Math.PI*t+j*Math.PI/3.0)*e);
                observed[column]=!intermittent||(column+2*strandIndex)%7<5;
                if(observed[column]){
                    double modulation=0.75+0.25*Math.sin(2.0*Math.PI*t+strandIndex*2.0*Math.PI/3.0);
                    addRendered(pixels,column,centers[column],baseAmplitude*modulation,1);
                }
            }
            strands.add(new Strand("strand-"+j,centers,observed,baseAmplitude,1));
        }
        String routeKind=merge?"allowed-actual-merge":"single-coherent-strand";
        return new Fixture(id,routeKind,pixels,valid,strands,null);
    }
    private static void addRendered(double[] pixels,int column,double centerY,double amplitude,int width){
        int centerRow=(int)Math.floor(centerY+0.5);
        int first=centerRow-(width-1)/2;
        for(int row=first;row<first+width;row++)pixels[row*WIDTH+column]+=amplitude;
    }
    private static double worldX(int column){return ORIGIN_X+column;}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}

    static final class Fixture {
        private final String id,expectedRouteKind;
        private final double[] intensities;
        private final boolean[] validity;
        private final List<Strand> strands;
        private final Strand strongCourse;
        private Fixture(String id,String expectedRouteKind,double[] intensities,boolean[] validity,
                List<Strand> strands,Strand strongCourse){
            this.id=id;this.expectedRouteKind=expectedRouteKind;
            this.intensities=intensities.clone();this.validity=validity.clone();
            this.strands=List.copyOf(strands);this.strongCourse=strongCourse;
        }
        int width(){return WIDTH;}
        int height(){return HEIGHT;}
        double[] intensitiesOwned(BSharedCaptureAdmission owner){return owner.rawScalarCopyOriginalUnit(intensities);}
        boolean[] validityOwned(BSharedCaptureAdmission owner){return owner.rawValidityCopyOriginalUnit(validity);}
    }
    static final class Strand {
        private final String id;
        private final double[] centerY;
        private final boolean[] observed;
        private final double baseAmplitude;
        private final int nativePixelWidth;
        private Strand(String id,double[] centerY,boolean[] observed,double baseAmplitude,int nativePixelWidth){
            this.id=id;this.centerY=centerY.clone();this.observed=observed.clone();
            this.baseAmplitude=baseAmplitude;this.nativePixelWidth=nativePixelWidth;
        }
    }
}
