package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Immutable invalid-signature counts; never scalar or region authority. */
final class BPhysicalSignaturePrefix {
    //4refs+3ints+2flags=46 field bytes,O46=64. No retained caller arrays.
    private final BPhysicalWork work;
    private final BPhysicalResponse.SourceView view;
    private final AttemptMemoryLedger.Owner owner;
    private final int[] invalid;
    private final int width,height,stride;
    private boolean published,closed;

    private BPhysicalSignaturePrefix(BPhysicalWork work,BPhysicalResponse.SourceView view,
            AttemptMemoryLedger.Owner owner,int width,int height) {
        this.work=work;this.view=view;this.owner=owner;this.width=width;this.height=height;
        stride=width+1;invalid=new int[stride*(height+1)];
    }
    static long bytes(int w,int h) {
        if(w<2||h<2||(long)w*h>191401)throw new IllegalArgumentException("Original source shape");
        return AttemptMemoryLedger.arrayBytes((long)(w+1)*(h+1),4)+64;
    }
    static BPhysicalSignaturePrefix issue(BPhysicalPanelCertificate parent,
            BPhysicalWork work,BPhysicalResponse.SourceView view,AttemptMemoryLedger.Owner allocation,int w,int h) {
        work.prepayPrefix(parent,view,allocation,w,h);
        var child=allocation.child("physical-signature-prefix");
        try {
            work.poll();child.reserve(bytes(w,h));
            var p=new BPhysicalSignaturePrefix(work,view,child,w,h);
            work.poll();p.build();
            p.published=true;work.poll();p.queryLive();
            parent.requirePrefixConstruction(work,view,allocation);
            return p;
        } catch(RuntimeException|Error failure){child.close();throw failure;}
    }
    private void sourceLive() {
        //Private issuance binds view EXACTLY to Work's authenticated view.
        //Work.liveSource already rechecks that original Bound/authority.
        work.liveSource();
        if(closed||owner.isClosed())throw new IllegalStateException("Signature prefix closed");
    }
    private void queryLive(){sourceLive();if(!published)throw new IllegalStateException("Unpublished signature prefix");}
    private int plus(int a,int b){work.poll();return a+b;}
    private int minus(int a,int b){work.poll();return a-b;}
    private int times(int a,int b){work.poll();return a*b;}
    private int minimum(int a,int b){work.poll();return Math.min(a,b);}
    private int loadBuilding(int index){sourceLive();if(published)throw new IllegalStateException("Build permit consumed");return load(index);}
    private int loadQuery(int index){queryLive();return load(index);}
    private int load(int index) {
        if(index<0||index>=invalid.length)throw new IllegalArgumentException("Prefix index");
        int value=invalid[index];work.stat(8);return value;
    }
    private void build() {
        //Named producers required by the accepted R1 DAG;never implicit free
        //subtractions inside repeated owner/index expressions.
        int wm2=minus(width,2),hm2=minus(height,2),wm1=minus(width,1),hm1=minus(height,1);
        for(int y=0;y<height;y++) {
            work.poll();int rowInvalid=0;
            for(int x=0;x<width;x++) {
                work.poll();int ix=minimum(wm2,x),iy=minimum(hm2,y);
                int xp=plus(x,1),yp=plus(y,1),x1=minimum(wm1,xp),y1=minimum(hm1,yp);
                //ALL five accesses,including zero-weight/collapsed flags. No
                //short-circuit may fabricate5V completed reads on cancellation.
                boolean cell=view.mask(plus(times(iy,wm1),ix),true);
                boolean a=view.mask(plus(times(y,width),x),false);
                boolean b=view.mask(plus(times(y,width),x1),false);
                boolean c=view.mask(plus(times(y1,width),x),false);
                boolean d=view.mask(plus(times(y1,width),x1),false);
                int previous=plus(times(y,stride),xp),out=plus(times(yp,stride),xp);
                rowInvalid=plus(rowInvalid,cell&&a&&b&&c&&d?0:1);
                invalid[out]=plus(loadBuilding(previous),rowInvalid);
            }
        }
    }
    int rectangle(double lx,double hx,double ly,double hy) {
        queryLive();work.poll();
        //Only SourceBank.signatures calls this helper,after its finite guarded
        //inverse and original extent checks. Immutable private issuance binds
        //this view to Work's sole original source;no caller identity flags.
        work.poll();double flx=StrictMath.floor(lx);work.poll();int x0=(int)flx;
        work.poll();double fhx=StrictMath.floor(hx);work.poll();int x1=(int)fhx;
        work.poll();double fly=StrictMath.floor(ly);work.poll();int y0=(int)fly;
        work.poll();double fhy=StrictMath.floor(hy);work.poll();int y1=(int)fhy;
        if(hx<lx||hy<ly)return 1;
        if(lx<0||ly<0||hx>width-1||hy>height-1)throw new IllegalArgumentException("Original signature extent");
        int xp=plus(x1,1),yp=plus(y1,1),left=plus(x0,0),top=plus(y0,0);
        int a=loadQuery(plus(times(yp,stride),xp));
        int b=loadQuery(plus(times(top,stride),xp));
        int c=loadQuery(plus(times(yp,stride),left));
        int d=loadQuery(plus(times(top,stride),left));
        int area=times(plus(minus(x1,x0),1),plus(minus(y1,y0),1));
        int bad=plus(minus(minus(a,b),c),d);
        if(bad<0||bad>area)throw new IllegalStateException("Invalid signature count");
        int result=bad==0?2:bad==area?1:3;
        work.poll();queryLive();return result;
    }
}
