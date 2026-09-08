package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;

/**
 * Immutable confirmation state for one exact all-way preview.
 *
 * <p>The plan hash covers every modified way and relation. Confirmation is invalidated by a
 * candidate, permission, source, parameter, or incident-way change before Apply is considered.</p>
 *
 * @param candidateId selected candidate identity
 * @param exactEditPlanHash canonical hash of the complete previewed edit plan
 * @param permissionHash canonical effective permission hash
 * @param sourceHash canonical source/evidence identity hash
 * @param disposition final validation disposition
 * @param confirmationHash exact reviewed identity, or empty before confirmation
 */
public record PreviewReviewState(
    String candidateId,
    String exactEditPlanHash,
    String permissionHash,
    String sourceHash,
    ValidationReport.Disposition disposition,
    String confirmationHash
) {
    /** Validates immutable identifiers and uses an empty token for an unreviewed candidate. */
    public PreviewReviewState {
        require(candidateId, "candidateId");
        require(exactEditPlanHash, "exactEditPlanHash");
        require(permissionHash, "permissionHash");
        require(sourceHash, "sourceHash");
        if (disposition == null || confirmationHash == null) {
            throw new IllegalArgumentException("Preview review state is incomplete");
        }
    }

    /** Creates an unconfirmed state for the exact final all-way preview. */
    public static PreviewReviewState create(String candidateId, String exactEditPlanHash,
        String permissionHash, String sourceHash, ValidationReport.Disposition disposition) {
        return new PreviewReviewState(candidateId, exactEditPlanHash, permissionHash, sourceHash,
            disposition, "");
    }

    /**
     * Creates an unconfirmed review state from one immutable complete edit plan.
     *
     * @param candidateId selected candidate identity
     * @param plan full proposed selected and incident-way edit plan
     * @return review state bound to the plan's exact all-way canonical hash
     */
    public static PreviewReviewState fromEditPlan(String candidateId, AlignmentEditPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Edit plan must not be null");
        }
        String permissions = sha256("permissions-v1", plan.permissions().toString());
        String source = sha256("source-v1", plan.before().datasetIdentity(),
            Long.toString(plan.before().sourceGeneration()), plan.evidenceHash(), plan.settingsHash(),
            plan.parameterHash());
        return create(candidateId, plan.canonicalHash(), permissions, source, plan.validation().disposition());
    }

    /** Returns a state confirmed for this exact candidate and all-way edit plan. */
    public PreviewReviewState confirm() {
        if (disposition == ValidationReport.Disposition.HARD_BLOCKED) {
            throw new IllegalStateException("Blocked previews cannot be confirmed");
        }
        return new PreviewReviewState(candidateId, exactEditPlanHash, permissionHash, sourceHash,
            disposition, reviewHash());
    }

    /** Returns a candidate switch with its previous confirmation intentionally cleared. */
    public PreviewReviewState withCandidate(String changedCandidateId) {
        return create(changedCandidateId, exactEditPlanHash, permissionHash, sourceHash, disposition);
    }

    /** Returns whether this state contains an exact current confirmation. */
    public boolean confirmed() {
        return !confirmationHash.isEmpty() && confirmationHash.equals(reviewHash());
    }

    /** Returns whether the preview can be applied at the review boundary. */
    public boolean canApply() {
        return disposition == ValidationReport.Disposition.APPLICABLE
            || disposition == ValidationReport.Disposition.REVIEW_REQUIRED && confirmed();
    }

    /** Checks whether a confirmation remains valid for a current preview state. */
    public boolean matches(PreviewReviewState current) {
        return current != null && confirmed() && reviewHash().equals(current.reviewHash())
            && disposition != ValidationReport.Disposition.HARD_BLOCKED;
    }

    /** Returns the stable exact identity signed by review confirmation. */
    public String reviewHash() {
        return sha256("preview-review-v1", candidateId, exactEditPlanHash, permissionHash, sourceHash,
            disposition.name());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String sha256(String... fields) {
        try {
            StringBuilder encoded = new StringBuilder();
            for (String field : fields) {
                String value = field == null ? "" : field;
                encoded.append(value.length()).append(':').append(value);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(encoded.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime has no SHA-256 implementation", exception);
        }
    }
}
