package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

class RefinementOperationReceiptTest {
    @Test void unknownObjectsRemainRefused() {
        assertThrows(IllegalArgumentException.class,()->RefinementRootAuthority.detachedCharges(new Object()));
    }
    @Test void actualOpaqueTicketIdentityIsAChargedImmutableReceiptLeaf() {
        var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,new StageId(UUID.randomUUID()),
            new CandidateId("actual-parent-choice"),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
        var receipt=new RefinementOperationReceipt(ticket,"parent-identity","original-network-hash",Outcome.FAILED,List.of(),Optional.empty(),false);
        var inventory=assertDoesNotThrow(()->RefinementRootAuthority.detachedCharges(receipt));
        assertTrue(inventory.containsKey(ticket.session().value()));
        assertTrue(inventory.containsKey(ticket.parent().value()));
        assertFalse(receipt.actualWork().isPresent(),"Missing historical work remains unavailable");
    }
}
