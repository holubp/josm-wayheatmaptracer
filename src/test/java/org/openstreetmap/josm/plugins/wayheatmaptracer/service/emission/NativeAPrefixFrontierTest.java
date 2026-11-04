package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Algebra/index/ownership controls; actual native reachability is tested separately. */
class NativeAPrefixFrontierTest {
    record P(CanonicalKey key,double cost,int consumed,int depth,P parent) implements NativeAPrefixFrontier.Path { }
    static final CanonicalKey KEY=new CanonicalKey(6,4,5,0);
    static P path(CanonicalKey key,double cost,int consumed,int... ids) {
        P parent=null;for(int i=0;i<ids.length;i++)parent=new P(i==ids.length-1?key:new CanonicalKey(ids[i],-1,ids[i],0),
            i==ids.length-1?cost:0,i==ids.length-1?consumed:0,i+1,parent);
        assertEquals(ids[ids.length-1],key.cursorId());return parent;
    }
    @Test void finiteCostAloneCannotDropTheWinningConsumedOrLexOrder() {
        try(var f=new F()) {
            var cheap=path(KEY,0,0,0,2,6);var preferred=path(KEY,0x1p-54,0,0,1,6);
            f.install(cheap);assertNotNull(f.frontier.prepare(preferred));assertTrue(f.frontier.active(cheap));
            var lexBestButMoreConsumed=path(KEY,0,1,0,1,6);
            long allocations=f.work.operationUnits(EmissionWorkBudget.Operation.ALLOCATION);
            assertNull(f.frontier.prepare(lexBestButMoreConsumed));
            assertEquals(allocations,f.work.operationUnits(EmissionWorkBudget.Operation.ALLOCATION),"Strict consumed preference needs no lex scratch");
        }
    }
    @Test void replacementIsPreparedBeforeRetirementAndDoesNotRetireExistingDescendants() {
        try(var f=new F()) {
            var old=path(KEY,2,0,0,2,6);f.install(old);
            var descendant=new P(new CanonicalKey(7,6,7,0),3,0,4,old);f.install(descendant);
            var replacement=path(KEY,1,0,0,1,3,6);var installation=f.frontier.prepare(replacement);
            assertNotNull(installation);assertTrue(f.frontier.active(old));assertFalse(f.frontier.active(replacement));
            f.frontier.publish(installation);assertFalse(f.frontier.active(old));assertTrue(f.frontier.active(replacement));assertTrue(f.frontier.active(descendant));
            assertEquals(1,f.census.retiredPrefixes());assertEquals(2,f.census.maximumActivePrefixesPerKey());
        }
    }
    @Test void aReplacementCanBeReplacedAgainWithoutResurrectingItsCoveredAncestors() {
        try(var f=new F()) {
            var first=path(KEY,3,0,0,3,6);var second=path(KEY,2,0,0,2,6);var third=path(KEY,1,0,0,1,6);
            f.install(first);f.install(second);assertFalse(f.frontier.active(first));assertTrue(f.frontier.active(second));
            f.install(third);assertFalse(f.frontier.active(first));assertFalse(f.frontier.active(second));assertTrue(f.frontier.active(third));
            assertEquals(2,f.census.retiredPrefixes());assertNull(f.frontier.prepare(path(KEY,4,0,0,4,6)));
        }
    }
    @Test void equalityRetainsTheInstalledIdentityAndSignedZeroDoesNotClaimDominance() {
        try(var f=new F()) {
            var old=path(KEY,1,0,0,1,6);var equal=path(KEY,1,0,0,1,6);f.install(old);
            assertNull(f.frontier.prepare(equal));assertTrue(f.frontier.active(old));assertFalse(f.frontier.active(equal));assertEquals(0,f.census.retiredPrefixes());
        }
        try(var f=new F()) {
            var negative=path(KEY,-0.,0,0,1,6);var positive=path(KEY,0.,0,0,2,6);f.install(negative);
            assertNotNull(f.frontier.prepare(positive));assertTrue(f.frontier.active(negative));
        }
    }
    @Test void terminalEmptySuffixUsesDoubleCompareAndTheNativeZeroSeedKeepsPositiveZero() {
        try(var f=new F()) {
            var terminal=new CanonicalKey(7,6,7,0);
            var positiveLexPreferred=path(terminal,+0.,0,0,1,7);var negativeLexWorse=path(terminal,-0.,0,0,2,7);
            f.install(positiveLexPreferred);f.install(negativeLexWorse);
            assertTrue(f.frontier.active(positiveLexPreferred));assertTrue(f.frontier.active(negativeLexWorse));
            assertTrue(Double.compare(negativeLexWorse.cost(),positiveLexPreferred.cost())<0);
            assertTrue(positiveLexPreferred.cost()<=negativeLexWorse.cost(),"Java <= alone is insufficient at an empty suffix");
        }
        for(double q:new double[]{+0.,-0.,Double.MIN_VALUE,1})for(double c:new double[]{+0.,-0.,Double.MIN_VALUE,1})
            for(double t:new double[]{+0.,-0.,Double.MIN_VALUE,1})for(double k:new double[]{+0.,-0.,Double.MIN_VALUE,1}) {
                double nativeFold=+0.+q+c+t+k;
                if(nativeFold==0)assertEquals(Double.doubleToLongBits(+0.),Double.doubleToLongBits(nativeFold));
            }
    }
    @Test void everyFieldOfTheFullKeyKeepsItsOwnContinuationFamily() {
        try(var f=new F()) {
            var old=path(KEY,2,0,0,2,6);f.install(old);
            for(var key:List.of(new CanonicalKey(7,4,5,0),new CanonicalKey(6,3,5,0),new CanonicalKey(6,4,4,0),new CanonicalKey(6,4,5,1))) {
                var candidate=path(key,0,0,0,1,key.cursorId());f.install(candidate);assertTrue(f.frontier.active(old));assertTrue(f.frontier.active(candidate));
            }
            assertEquals(0,f.census.dominatedPrefixes());assertEquals(0,f.census.retiredPrefixes());
        }
    }
    @Test void malformedDepthAndNonfiniteFactsCannotSupplyAnExtensionStableProof() {
        try(var f=new F()) {
            var old=path(KEY,0,0,0,2,6);f.install(old);
            var malformed=new P(KEY,1,0,5,path(new CanonicalKey(3,-1,3,0),0,0,0,3));
            assertNotNull(f.frontier.prepare(malformed));assertNotNull(f.frontier.prepare(path(KEY,Double.NaN,0,0,3,6)));
            assertNotNull(f.frontier.prepare(path(KEY,Double.POSITIVE_INFINITY,0,0,3,6)));assertTrue(f.frontier.active(old));
        }
    }
    @Test void scratchFailureAfterFullInstallationKeepsOldVersionCoveredAndClosesAllOwners() {
        try(var f=new F()) {
            var old=path(KEY,2,0,0,2,6);f.install(old);var replacement=path(KEY,1,0,0,1,6);
            var installation=f.frontier.prepare(replacement);assertNotNull(installation);assertTrue(f.frontier.active(old));
            try(var pressure=f.fixture.owner().reserve(f.fixture.ledger().limitBytes()-f.fixture.ledger().currentBytes())) {
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->f.frontier.publish(installation));
                assertTrue(f.frontier.active(old));assertTrue(f.frontier.active(replacement));assertEquals(0,f.census.retiredPrefixes());
                assertEquals(2,f.census.maximumActivePrefixesPerKey());
            }
        }
    }
    static final class F implements AutoCloseable {
        final LegacyEmissionProgressTest.Fixture fixture=LegacyEmissionProgressTest.fixture();
        final EmissionWorkBudget work=fixture.input().workBudget();
        final AttemptMemoryLedger.Owner owner=fixture.input().openExecutionOwner("prefix-algebra-control");
        final NativeAExecutionCensus census=NativeAExecutionCensus.open(fixture.input());
        final NativeAPrefixFrontier frontier;
        F(){
            census.begin(fixture.input());var vertices=new ArrayList<Vertex>();var arcs=new ArrayList<Arc>();
            for(int i=0;i<8;i++){vertices.add(new Vertex(i,i,0,true,i==0,i==7,i));if(i>0)arcs.add(new Arc(i-1,i-1,i,1,0,false,false,i-1));}
            var graph=Graph.create(vertices,arcs,Set.of(),1,work,owner);frontier=NativeAPrefixFrontier.create(graph,work,owner,census);assertNotNull(frontier);
        }
        void install(P p){var entry=frontier.prepare(p);assertNotNull(entry);frontier.publish(entry);}
        @Override public void close(){owner.close();census.close();fixture.close();assertEquals(0,fixture.ledger().currentBytes());}
    }
}
