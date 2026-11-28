package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Post-frame same-ledger settlement. Acquisition/currentness owners are never retired here. */
final class CompletedGraphRetention {
    enum Status { NOT_ATTEMPTED, SETTLED, UNAVAILABLE }
    // 512 bytes per traversal slot covers identity indexes, record metadata and preflight staging.
    // This is transient metadata capacity, charged to the unchanged operation ledger, not a new work pool.
    private static final long METADATA_BYTES=64L*1024*1024;
    private static final int METADATA_ENTRIES=(int)((METADATA_BYTES-4096)/512);
    private static final class InventoryCapacity {
        private final AttemptMemoryLedger.Owner owner;
        private long admitted;
        private boolean exchanging;
        InventoryCapacity(AttemptMemoryLedger.Owner owner){this.owner=owner;owner.reserve(4096);}
        void admit(long needed) {
            if(needed>METADATA_ENTRIES)throw new RefinementSession.Refused(RefinementModels.RefusalReason.PAYLOAD_LIMIT);
            if(needed<=admitted)return;
            if(exchanging)throw new RefinementSession.Refused(RefinementModels.RefusalReason.PAYLOAD_LIMIT);
            long rounded=Math.min(METADATA_ENTRIES,((needed+63)/64)*64);
            owner.reserve(Math.multiplyExact(512L,rounded-admitted));
            admitted=rounded;
        }
        void prepareExchange(RefinementRootAuthority.Inventory.Traversal traversal,
                AttemptMemoryLedger.Owner working,AttemptMemoryLedger.Owner destination) {
            long exchangeSlots=working.retainedExchangeEntries(destination);
            // One additional reservation made by this step is in the metadata owner
            // outside the retiring subtree, and does not add a ledger charge identity.
            admit(Math.addExact(traversal.entries(),exchangeSlots));
            exchanging=true;
        }
    }
    private final RefinementRootAuthority root;
    private final AttemptMemoryLedger.Owner operation;
    private record RetainedGroup(long bytes,Set<Object> members) { }
    private final IdentityHashMap<RefinementEngineAdapter.Run,RetainedGroup> settled=new IdentityHashMap<>();
    private Status status=Status.NOT_ATTEMPTED;
    private boolean headerInCapacity;
    private CompletedGraphRetention(RefinementRootAuthority root,AttemptMemoryLedger.Owner operation){this.root=root;this.operation=operation;}
    static CompletedGraphRetention open(RefinementRootAuthority root,AttemptMemoryLedger.Owner operation) {
        try(var header=operation.reserve(4096)) {
            var value=new CompletedGraphRetention(Objects.requireNonNull(root),operation);header.adopt(value);return value;
        }
    }
    Status status(){return status;}
    void unavailable(){status=Status.UNAVAILABLE;}
    static CompletedGraphRetention forPipeline(RefinementRootAuthority root,AttemptMemoryLedger.Owner operation,
            AttemptMemoryLedger.Owner capacity,AttemptMemoryLedger.Reservation reservation) {
        if(!reservation.isActiveFor(capacity)||reservation.bytes()<1_048_576L||!capacity.sharesLedgerWith(operation))
            throw new IllegalArgumentException("Pipeline metadata requires its live full assembled capacity");
        var value=new CompletedGraphRetention(root,operation);value.headerInCapacity=true;return value;
    }
    private void pin(Map<Object,Long> wanted,AttemptMemoryLedger.Owner destination,IdentityHashMap<Object,Long> borrowed,RefinementRootAuthority.Inventory.Traversal traversal,boolean existingOnly) {
        for(var entry:wanted.entrySet()) {
            traversal.allocate(1);AttemptMemoryLedger.MemoryLease lease;
            try{lease=destination.retainIfAbsent(entry.getKey());}
            catch(IllegalArgumentException missing) {
                if(existingOnly)throw missing;
                // Cached producer-owned root inventory is complete, including grouped scalar identities.
                // Detached callers may not yet have admitted those original inputs in this ledger.
                try(var admitted=destination.reserve(entry.getValue())){lease=admitted.adopt(entry.getKey());}
            }
            if(lease.bytes()<entry.getValue())throw new IllegalArgumentException("Original retained group is undercosted");
            borrowed.put(entry.getKey(),lease.bytes());
        }
    }
    private IdentityHashMap<Object,Long> inputs(AttemptMemoryLedger.Owner destination,RefinementRootAuthority.Inventory.Traversal traversal) {
        var borrowed=new IdentityHashMap<Object,Long>();pin(root.charges(),destination,borrowed,traversal,false);return borrowed;
    }
    private void source(RetainedSmoothingSource source,AttemptMemoryLedger.Owner destination,IdentityHashMap<Object,Long> borrowed,RefinementRootAuthority.Inventory.Traversal traversal) {
        source.retainInto(destination);pin(source.charges(),destination,borrowed,traversal,true);
    }
    void settleRun(RefinementEngineAdapter.CompletedRun completed,Optional<RetainedSmoothingSource> source,CancellationProbe probe) {
        var working=completed.completedOwner();var destination=operation.child("completed-native-retained");boolean exchanged=false;
        try(var metadata=operation.child("completed-native-inventory")) {
            var capacity=new InventoryCapacity(metadata);
            var traversal=new RefinementRootAuthority.Inventory.Traversal(probe,METADATA_ENTRIES,true,capacity::admit);
            var borrowed=inputs(destination,traversal);source.ifPresent(value->source(value,destination,borrowed,traversal));
            var run=completed.run();var charges=RefinementRootAuthority.completedCharges(borrowed,traversal,run);
            // Preserve the producer-computed identity membership so Result aliases to provenance,
            // basis and receipt payload borrow the same Run group rather than charging it again.
            // Presize from the already-measured identity union: 64 bytes/member plus the
            // 256-byte header conservatively covers the actual table, set and registry record.
            traversal.allocate(charges.size());
            var members=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>(charges.size()));
            for(Object member:charges.keySet()){probe.checkpoint();members.add(member);}
            long bytes=Math.addExact(256L,Math.multiplyExact(64L,members.size()));
            for(long value:charges.values()){probe.checkpoint();bytes=Math.addExact(bytes,value);}
            var group=new RetainedGroup(bytes,members);
            capacity.prepareExchange(traversal,working,destination);
            working.replaceWithRetainedGroup(destination,run,bytes,()->{traversal.allocate(1);return false;});exchanged=true;
            settled.put(run,group);status=Status.SETTLED;
        } finally {if(!exchanged){destination.close();status=Status.UNAVAILABLE;}}
    }
    void settleResult(RefinementPipeline.Result result,AttemptMemoryLedger.Owner capacity,CancellationProbe probe) {
        var destination=operation.child("completed-pipeline-retained");boolean exchanged=false;
        try(var metadata=operation.child("completed-pipeline-inventory")) {
            var inventoryCapacity=new InventoryCapacity(metadata);
            var traversal=new RefinementRootAuthority.Inventory.Traversal(probe,METADATA_ENTRIES,true,inventoryCapacity::admit);
            var borrowed=inputs(destination,traversal);
            for(var entry:settled.entrySet()) {
                traversal.allocate(1);var lease=destination.retainIfAbsent(entry.getKey());
                var group=entry.getValue();
                if(lease.bytes()!=group.bytes())throw new IllegalArgumentException("Completed native retained group changed");
                borrowed.put(entry.getKey(),lease.bytes());
                for(Object member:group.members()){traversal.allocate(1);borrowed.put(member,group.bytes());}
            }
            for(var candidate:result.candidates())for(var source:candidate.retainedSources().values())source(source,destination,borrowed,traversal);
            var charges=RefinementRootAuthority.completedCharges(borrowed,traversal,result);
            long bytes=headerInCapacity?4096:0;for(long value:charges.values()){probe.checkpoint();bytes=Math.addExact(bytes,value);}
            inventoryCapacity.prepareExchange(traversal,capacity,destination);
            capacity.replaceWithRetainedGroup(destination,result,bytes,()->{traversal.allocate(1);return false;});exchanged=true;status=Status.SETTLED;
        } finally {if(!exchanged){destination.close();status=Status.UNAVAILABLE;}}
    }
}
