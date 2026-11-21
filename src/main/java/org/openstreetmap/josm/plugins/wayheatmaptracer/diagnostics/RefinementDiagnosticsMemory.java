package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Same-ledger lifetime boundary for workflow inputs and immutable encoded export bytes. */
public final class RefinementDiagnosticsMemory {
    private final AttemptMemoryLedger ledger;
    private final AttemptMemoryLedger.Owner root;
    private final AttemptMemoryLedger.Owner workflow;
    private boolean workflowClosed;
    private int exports;
    private long registryBytes;

    public RefinementDiagnosticsMemory(AttemptMemoryLedger ledger) {
        this.ledger=Objects.requireNonNull(ledger);
        root=ledger.rootOwner();workflow=root.child("workflow");
    }
    public AttemptMemoryLedger.Owner workflowOwner(){return workflow;}
    public long totalBytes(){return ledger.currentBytes();}
    public synchronized long workflowOwnedBytes(){return ledger.currentBytes()-registryBytes;}
    public synchronized long registryOwnedBytes(){return registryBytes;}

    /** Reserves before serialization; unused capacity remains workflow-owned until released. */
    public synchronized Admission reserveExport(long bytes) {
        if(workflowClosed)throw new IllegalStateException("Diagnostic workflow is closed");
        if(bytes<128)throw new IllegalArgumentException("Encoded export allowance is too small");
        var owner=workflow.child("export-encoding");
        try{return new Admission(owner,owner.reserve(bytes),bytes);}
        catch(RuntimeException refused){owner.close();throw refused;}
    }

    /** Called only after physical work and queued publication have settled. */
    public synchronized void closeWorkflow() {
        if(workflowClosed)return;
        workflowClosed=true;workflow.close();retireRootIfUnused();
    }
    private void retireRootIfUnused(){if(workflowClosed&&exports==0)root.close();}

    public final class Admission implements AutoCloseable {
        private final AttemptMemoryLedger.Owner owner;
        private final AttemptMemoryLedger.Reservation reservation;
        private final long allowance;
        private boolean closed;
        private Admission(AttemptMemoryLedger.Owner owner,AttemptMemoryLedger.Reservation reservation,long allowance) {
            this.owner=owner;this.reservation=reservation;this.allowance=allowance;
        }
        /** Reads only a bounded already-encoded file; no workflow/input references enter the snapshot. */
        public Snapshot read(Path encoded) throws IOException {
            synchronized(RefinementDiagnosticsMemory.this) {
                if(closed||workflowClosed)throw new IllegalStateException("Diagnostic admission is closed");
                long length=Files.size(encoded);
                long charge=Math.addExact(AttemptMemoryLedger.arrayBytes(length,1),128);
                if(length>Integer.MAX_VALUE||charge>allowance)throw new IllegalArgumentException("Encoded diagnostics exceed their admitted budget");
                // The existing reservation covers this allocation before the immutable bytes exist.
                byte[] bytes=new byte[(int)length];
                try(var input=Files.newInputStream(encoded)) {
                    if(input.readNBytes(bytes,0,bytes.length)!=bytes.length||input.read()!=-1)throw new IOException("Encoded diagnostic size changed");
                }
                var retained=root.child("registry-export");
                try {
                    synchronized(ledger) {
                        reservation.close();
                        retained.reserve(charge).adopt(bytes);
                    }
                    closed=true;owner.close();exports++;registryBytes=Math.addExact(registryBytes,charge);
                    return new Snapshot(RefinementDiagnosticsMemory.this,retained,bytes,charge);
                } catch(RuntimeException failure){retained.close();close();throw failure;}
            }
        }
        @Override public void close(){synchronized(RefinementDiagnosticsMemory.this){if(!closed){closed=true;owner.close();}}}
    }

    /** Immutable encoded ZIP plus its same-ledger lease; readers pin replacement/clear races. */
    public static final class Snapshot implements AutoCloseable {
        private final RefinementDiagnosticsMemory memory;
        private final AttemptMemoryLedger.Owner owner;
        private final byte[] bytes;
        private final long charge;
        private int readers;
        private boolean retired,released;
        private Snapshot(RefinementDiagnosticsMemory memory,AttemptMemoryLedger.Owner owner,byte[] bytes,long charge) {
            this.memory=memory;this.owner=owner;this.bytes=bytes;this.charge=charge;
        }
        public Reader pin(){synchronized(memory){if(retired)throw new IllegalStateException("Diagnostic snapshot is retired");readers++;return new Reader(this);}}
        @Override public void close(){synchronized(memory){retired=true;releaseIfUnused();}}
        private void releaseIfUnused(){
            if(retired&&readers==0&&!released){released=true;owner.close();memory.registryBytes-=charge;memory.exports--;memory.retireRootIfUnused();}
        }
    }
    public static final class Reader implements AutoCloseable {
        private Snapshot snapshot;
        private Reader(Snapshot snapshot){this.snapshot=snapshot;}
        public synchronized void writeTo(Path target) throws IOException {
            if(snapshot==null)throw new IllegalStateException("Diagnostic reader is closed");
            Path absolute=target.toAbsolutePath();Path parent=absolute.getParent();
            Files.createDirectories(parent);Path temporary=Files.createTempFile(parent,".refinement-debug-",".tmp");
            try {
                Files.write(temporary,snapshot.bytes);
                try{Files.move(temporary,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unavailable){Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING);}
            } finally{Files.deleteIfExists(temporary);}
        }
        @Override public synchronized void close(){if(snapshot!=null){var old=snapshot;snapshot=null;synchronized(old.memory){old.readers--;old.releaseIfUnused();}}}
    }
}
