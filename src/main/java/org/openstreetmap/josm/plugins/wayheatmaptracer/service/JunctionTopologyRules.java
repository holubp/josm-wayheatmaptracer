package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import java.util.function.IntPredicate;

/** Pure occurrence-based topology rules shared by live selection and detached refinement. */
public final class JunctionTopologyRules {
    private JunctionTopologyRules() { }
    public record Referrer(boolean selected,boolean sameDataset,boolean completeNondegenerate,boolean uniqueEndpoint) { }
    public record Range(int originalFirst,int originalLast,int first,int last) { }
    public static boolean safeEndpoint(List<Referrer> liveReferrers) {
        if(liveReferrers.size()==1)return liveReferrers.get(0).selected();
        return liveReferrers.size()==2&&liveReferrers.stream().filter(Referrer::selected).count()==1
            &&liveReferrers.stream().allMatch(r->r.sameDataset()&&r.completeNondegenerate()&&r.uniqueEndpoint());
    }
    public static List<Range> ranges(int first,int last,IntPredicate boundary,IntPredicate safeEndpoint) {
        if(first<0||last<=first)throw new IllegalArgumentException("A selected range needs two ordered occurrences");
        var anchors=new ArrayList<Integer>();anchors.add(first);
        for(int i=first+1;i<last;i++)if(boundary.test(i))anchors.add(i);
        anchors.add(last);var result=new ArrayList<Range>();
        for(int i=1;i<anchors.size();i++) {
            int originalFirst=anchors.get(i-1),originalLast=anchors.get(i),start=originalFirst,end=originalLast;
            while(start<end&&!safeEndpoint.test(start))start++;
            while(end>start&&!safeEndpoint.test(end))end--;
            result.add(new Range(originalFirst,originalLast,start,end));
        }
        return List.copyOf(result);
    }
}
