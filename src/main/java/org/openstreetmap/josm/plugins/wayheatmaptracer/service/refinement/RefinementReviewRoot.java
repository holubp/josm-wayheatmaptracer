package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.List;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Original review ownership shared by factual Legacy and native starting results. */
public interface RefinementReviewRoot extends AutoCloseable {
    TrackerMode engine();
    AlignmentConfig originalConfig();
    DataSet dataSet();
    SelectionContext selection();
    AlignmentResult originalResult();
    List<LegacyReviewOwner.Choice> choices();
    CenterlineCandidate selectedCandidate();
    default List<org.openstreetmap.josm.data.coor.EastNorth> selectedFinalGeometry(){return selectedCandidate().finalPreviewPoints();}
    void select(CenterlineCandidate candidate);
    String choiceId(CenterlineCandidate candidate);
    CenterlineCandidate candidateForChoice(String id);
    void requireOriginalAttemptCurrent();
    void requireAuthorityCurrent();
    boolean invalidated();
    String sourceIdentity();
    /** Projection recorded by the original review owner, including before lazy preparation. */
    String projectionCode();
    @Override void close();
}
