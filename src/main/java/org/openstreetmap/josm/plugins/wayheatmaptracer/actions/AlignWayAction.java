package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.DefaultListCellRenderer;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.help.HelpUtil;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.LastSlideDebugBundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.AggregateIntensityLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.HeatmapLayerResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateAssessment;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateReviewConfirmation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateRating;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionIntegrity;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewOverlay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.MoveNodesCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ReplaceWaySegmentCommand;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * JOSM action that samples the selected way against the heatmap, opens candidate preview, and applies the chosen result.
 */
public class AlignWayAction extends JosmAction {
    private static final String[] RATING_VALUES = {"", "++", "+", "0", "-", "--"};
    /** Largest half-width offered by ordinary search-edge recovery. */
    private static final double MAX_ORDINARY_SEARCH_HALF_WIDTH_METERS = 14.0;
    private static final String FEATURE_OFF_THE_LINE = "off-the-line";
    private static final String FEATURE_JUMPING = "jumping";
    private static final String FEATURE_UNNECESSARY_KINKS = "unnecessary-kinks";
    private static final String FEATURE_BAD_JUNCTION_SHAPES = "bad-junction-shapes";

    /** Stateless alignment orchestrator shared by action invocations. */
    private final AlignmentService alignmentService = new AlignmentService();
    /** Map overlay used for candidate preview. */
    private final PreviewOverlay overlay = PreviewOverlay.getInstance();
    /** Optional shortcut-specific mode override, or null for configured behavior. */
    private final AlignmentMode forcedAlignmentMode;
    /** Current modeless preview dialog, if one is open. */
    private JDialog activePreviewDialog;

    /**
     * Creates the default alignment action using the mode configured in plugin settings.
     */
    public AlignWayAction() {
        this(null);
    }

    /**
     * Creates an alignment action with an optional one-shot mode override.
     *
     * @param forcedAlignmentMode alignment mode to force for this action, or {@code null} to use settings
     */
    public AlignWayAction(AlignmentMode forcedAlignmentMode) {
        super(
            actionName(forcedAlignmentMode),
            null,
            actionTooltip(forcedAlignmentMode),
            shortcut(forcedAlignmentMode),
            true
        );
        this.forcedAlignmentMode = forcedAlignmentMode;
        putValue("help", HelpUtil.ht("/Plugin/WayHeatmapTracer"));
    }

    /**
     * Returns the one-shot mode override used by this action.
     *
     * @return forced mode, or {@code null} when settings control the mode
     */
    public AlignmentMode forcedAlignmentMode() {
        return forcedAlignmentMode;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (activePreviewDialog != null && activePreviewDialog.isDisplayable()) {
            activePreviewDialog.toFront();
            return;
        }
        ManagedHeatmapConfig config = null;
        PluginLog.beginSlideSession();
        try {
            PluginLog.verbose("Align Way to Heatmap invoked.");
            DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
            if (dataSet == null) {
                showError(tr("No editable data layer is active."));
                return;
            }
            if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
                showError(tr("No map view is available."));
                return;
            }

            config = effectiveConfig(PluginPreferences.load());
            SelectionContext selection = SelectionResolver.resolve(dataSet, config.adjustJunctionNodes());
            if (!config.allowUndownloadedAlignment()) {
                requireDownloadedAreaCoverage(selection, dataSet);
            } else {
                PluginLog.verbose("Downloaded-area coverage checks are disabled by settings.");
            }
            ImageryLayer imageryLayer = config.hasManagedAccessValues()
                ? HeatmapLayerResolver.resolveOptional().orElse(null)
                : HeatmapLayerResolver.resolve();
            MapView mapView = MainApplication.getMap().mapView;

            GeometryCleanupConfig cleanupConfig = PluginPreferences.loadGeometryCleanup();
            AlignmentConfig slideConfig = new AlignmentConfig(config, cleanupConfig);
            AlignmentResult result = alignmentService.align(selection, imageryLayer, mapView, slideConfig);
            updateAggregateIntensityLayer(result, config);
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                result, initialCandidate(result), initialCandidate(result), "preview-open", PluginLog.currentSlideLog(), Map.of()));

            showCandidatePreview(dataSet, selection, result, slideConfig, imageryLayer, mapView, new LinkedHashMap<>());
        } catch (AlignmentService.AlignmentFailureException ex) {
            overlay.hide();
            if (config != null) {
                updateAggregateIntensityLayer(ex.partialResult(), config);
            }
            Logging.warn("WayHeatmapTracer alignment failed without applying geometry: " + ex.getMessage());
            PluginLog.verbose("Alignment failed without applying geometry: %s", ex.toString());
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                ex.partialResult(),
                ex.partialResult().candidates().isEmpty() ? null : ex.partialResult().candidates().get(0),
                "failed",
                PluginLog.currentSlideLog()
            ));
            PluginLog.endSlideSession();
            showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
        } catch (Exception ex) {
            overlay.hide();
            Logging.error(ex);
            PluginLog.verbose("Alignment failed with exception: %s", ex.toString());
            PluginLog.endSlideSession();
            showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
        }
    }

    private ManagedHeatmapConfig effectiveConfig(ManagedHeatmapConfig config) {
        if (forcedAlignmentMode == null) {
            return config;
        }
        PluginLog.verbose("Using one-shot alignment mode override: %s.", forcedAlignmentMode);
        return config.withAlignmentMode(forcedAlignmentMode);
    }

    private void updateAggregateIntensityLayer(AlignmentResult result, ManagedHeatmapConfig config) {
        if (!config.showAggregateIntensityLayer() || !config.hasManagedAccessValues()) {
            AggregateIntensityLayer.removeExisting();
        }
    }

    private static String actionName(AlignmentMode forcedAlignmentMode) {
        if (forcedAlignmentMode == AlignmentMode.PRECISE_SHAPE) {
            return tr("Align Way to Heatmap Precisely");
        }
        if (forcedAlignmentMode == AlignmentMode.MOVE_EXISTING_NODES) {
            return tr("Align Way to Heatmap by Moving Nodes");
        }
        return tr("Align Way to Heatmap");
    }

    private static String actionTooltip(AlignmentMode forcedAlignmentMode) {
        if (forcedAlignmentMode == AlignmentMode.PRECISE_SHAPE) {
            return tr("Align the selected way to a heatmap and rebuild the selected segment precisely");
        }
        if (forcedAlignmentMode == AlignmentMode.MOVE_EXISTING_NODES) {
            return tr("Align the selected way to a heatmap by moving the existing selected nodes");
        }
        return tr("Align the selected way geometry to a heatmap imagery layer");
    }

    private static Shortcut shortcut(AlignmentMode forcedAlignmentMode) {
        if (forcedAlignmentMode == AlignmentMode.PRECISE_SHAPE) {
            return Shortcut.registerShortcut(
                "wayheatmaptracer:align-precise",
                tr("WayHeatmapTracer: Align Way to Heatmap Precisely"),
                KeyEvent.VK_S,
                Shortcut.ALT_CTRL_SHIFT
            );
        }
        if (forcedAlignmentMode == AlignmentMode.MOVE_EXISTING_NODES) {
            return Shortcut.registerShortcut(
                "wayheatmaptracer:align-move-nodes",
                tr("WayHeatmapTracer: Align Way to Heatmap by Moving Nodes"),
                KeyEvent.VK_M,
                Shortcut.ALT_CTRL_SHIFT
            );
        }
        return Shortcut.registerShortcut(
            "wayheatmaptracer:align",
            tr("WayHeatmapTracer: Align Way to Heatmap"),
            KeyEvent.VK_Y,
            Shortcut.CTRL_SHIFT
        );
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }

    private void showCandidatePreview(
        DataSet dataSet,
        SelectionContext selection,
        AlignmentResult result,
        AlignmentConfig slideConfig,
        ImageryLayer imageryLayer,
        MapView mapView,
        Map<String, CandidateRating> candidateRatings
    ) {
        ManagedHeatmapConfig config = slideConfig.heatmap();
        GeometryCleanupConfig cleanupConfig = slideConfig.cleanup();
        if (result.candidates().isEmpty()) {
            throw new IllegalStateException(tr("No centerline candidate could be extracted from the heatmap."));
        }
        CenterlineCandidate initial = initialCandidate(result);
        boolean ratingMode = config.candidateRatingEnabled();
        boolean[] loadingRating = {false};
        CandidateReviewConfirmation[] reviewConfirmation = {null};
        PreviewSelection[] current = {buildPreviewSelection(dataSet, result, initial, initial, config)};
        CandidateAssessment initialAssessment = AlignmentService.assessCandidate(initial);
        overlay.show(selection, current[0].result(), initial, initialAssessment.disposition(), false,
            PluginPreferences.isDebugEnabled());
        JComboBox<CenterlineCandidate> comboBox = new JComboBox<>();
        comboBox.setModel(new DefaultComboBoxModel<>(result.candidates().toArray(CenterlineCandidate[]::new)));
        comboBox.setSelectedItem(initial);
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focused
            ) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof CenterlineCandidate candidate) {
                    CandidateAssessment assessment = AlignmentService.assessCandidate(candidate);
                    setText(candidateListLabel(candidate, assessment,
                        confirmationMatches(reviewConfirmation[0], candidate, current[0])));
                }
                return this;
            }
        });
        JComboBox<String> ratingBox = new JComboBox<>(RATING_VALUES);
        JCheckBox offTheLine = new JCheckBox(tr("off-the-line"));
        JCheckBox jumping = new JCheckBox(tr("jumping"));
        JCheckBox unnecessaryKinks = new JCheckBox(tr("unnecessary kinks"));
        JCheckBox badJunctionShapes = new JCheckBox(tr("bad junction shapes"));
        JButton apply = new JButton(tr("Apply"));
        apply.setEnabled(initialAssessment.automaticallyApplicable());
        JButton confirm = new JButton(tr("Confirm reviewed candidate"));
        confirm.setVisible(canConfirmCandidate(initialAssessment));
        confirm.setEnabled(canConfirmCandidate(initialAssessment));
        JButton retry = new JButton(tr("Retry with wider search..."));
        configureRetryButton(retry, initial, slideConfig, result);

        JPanel panel = buildSummaryPanel(
            current[0].result(),
            initial,
            config,
            cleanupConfig,
            result.candidates().size() > 1 ? comboBox : null,
            ratingMode ? ratingBox : null,
            offTheLine,
            jumping,
            unnecessaryKinks,
            badJunctionShapes
        );
        double currentSearchHalfWidth = retrySearchBounds(slideConfig, result).currentMeters();
        JLabel selectedCoverageStatus = new JLabel(coverageStatus(
            initial, currentSearchHalfWidth, initialAssessment, false));
        panel.add(selectedCoverageStatus, GBC.eol());
        JLabel selectedCleanupStatus = new JLabel(cleanupStatus(initial));
        panel.add(selectedCleanupStatus, GBC.eol());
        JLabel selectedCandidateDetail = new JLabel(cleanupDetail(initial));
        panel.add(selectedCandidateDetail, GBC.eol());
        comboBox.addActionListener(event -> {
            CenterlineCandidate selected = (CenterlineCandidate) comboBox.getSelectedItem();
            if (selected == null) {
                return;
            }
            try {
                reviewConfirmation[0] = null;
                current[0] = buildPreviewSelection(dataSet, result, current[0].initialCandidate(), selected, config);
                CandidateAssessment assessment = AlignmentService.assessCandidate(selected);
                overlay.show(selection, current[0].result(), selected, assessment.disposition(), false,
                    PluginPreferences.isDebugEnabled());
                selectedCoverageStatus.setText(coverageStatus(
                    selected, currentSearchHalfWidth, assessment, false));
                selectedCleanupStatus.setText(cleanupStatus(selected));
                apply.setEnabled(assessment.automaticallyApplicable());
                confirm.setVisible(canConfirmCandidate(assessment));
                confirm.setEnabled(canConfirmCandidate(assessment));
                confirm.setText(tr("Confirm reviewed candidate"));
                java.awt.Window previewWindow = SwingUtilities.getWindowAncestor(confirm);
                if (previewWindow != null) {
                    previewWindow.pack();
                }
                selectedCandidateDetail.setText(cleanupDetail(selected));
                configureRetryButton(retry, selected, slideConfig, result);
                comboBox.repaint();
                loadingRating[0] = true;
                loadCandidateRating(candidateRatings.get(selected.id()), ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
                loadingRating[0] = false;
                updatePreviewBundle(current[0], candidateRatings, "preview-open");
            } catch (Exception ex) {
                Logging.warn("WayHeatmapTracer rejected preview candidate: " + ex.getMessage());
                showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
                comboBox.setSelectedItem(current[0].candidate());
            }
        });
        ratingBox.addActionListener(event -> {
            if (!loadingRating[0]) {
                saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
            }
        });
        offTheLine.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));
        jumping.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));
        unnecessaryKinks.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));
        badJunctionShapes.addActionListener(event -> saveCandidateRating(current[0], candidateRatings, ratingBox, offTheLine, jumping, unnecessaryKinks, badJunctionShapes));

        JButton cancel = new JButton(tr("Cancel"));
        JPanel buttons = new JPanel();
        buttons.add(confirm);
        buttons.add(apply);
        buttons.add(retry);
        buttons.add(cancel);
        panel.add(buttons, GBC.eol());

        JDialog dialog = new JDialog(MainApplication.getMainFrame(), tr("Preview Heatmap Alignment"), false);
        activePreviewDialog = dialog;
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        confirm.addActionListener(event -> {
            try {
                CandidateAssessment assessment = AlignmentService.assessCandidate(current[0].candidate());
                if (!canConfirmCandidate(assessment)) {
                    throw new IllegalStateException(tr("This candidate cannot be enabled by review."));
                }
                int answer = JOptionPane.showConfirmDialog(
                    dialog,
                    tr("Confirm that you reviewed the complete displayed geometry. Evidence uncertainty will be "
                        + "accepted, but all geometry and topology safety checks will remain mandatory."),
                    tr("Confirm reviewed candidate"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
                SelectionIntegrity.requirePreviewSourceUnchanged(
                    dataSet, selection, current[0].result().sourcePolyline());
                requireCandidateAssignmentPlan(selection, current[0].result(),
                    current[0].candidate(), config);
                alignmentService.requireCurrentTopologySafe(current[0].candidate(), selection);
                if (!config.allowUndownloadedAlignment()) {
                    requirePreviewWithinDownloadedArea(current[0].result().previewPolyline(), dataSet);
                }
                reviewConfirmation[0] = CandidateReviewConfirmation.capture(
                    current[0].candidate(), current[0].result().previewPolyline());
                apply.setEnabled(true);
                confirm.setEnabled(false);
                confirm.setText(tr("Review confirmed"));
                selectedCoverageStatus.setText(coverageStatus(current[0].candidate(),
                    currentSearchHalfWidth, assessment, true));
                overlay.show(selection, current[0].result(), current[0].candidate(),
                    assessment.disposition(), true, PluginPreferences.isDebugEnabled());
                comboBox.repaint();
                PluginLog.verbose("Candidate review confirmed: candidate=%s previewPoints=%d.",
                    current[0].candidate().id(), current[0].result().previewPolyline().size());
                updatePreviewBundle(current[0], candidateRatings, "review-confirmed");
            } catch (Exception ex) {
                PluginLog.verbose("Candidate review confirmation failed: %s", ex.toString());
                updatePreviewBundle(current[0], candidateRatings, "review-confirmation-failed");
                showError(tr("WayHeatmapTracer review confirmation failed: {0}", ex.getMessage()));
            }
        });
        retry.addActionListener(event -> retryWithWiderSearch(
            dialog, dataSet, selection, current[0], slideConfig, imageryLayer, mapView, candidateRatings
        ));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                activePreviewDialog = null;
            }

            @Override
            public void windowClosing(WindowEvent e) {
                cancelPreview(current[0], candidateRatings);
            }
        });
        apply.addActionListener(event -> {
            try {
                applyPreview(dataSet, selection, current[0], config, candidateRatings,
                    reviewConfirmation[0]);
                dialog.dispose();
                overlay.hide();
                PluginLog.endSlideSession();
            } catch (Exception ex) {
                Logging.error(ex);
                PluginLog.verbose("Alignment apply failed with exception: %s", ex.toString());
                DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                    current[0].result(),
                    current[0].candidate(),
                    current[0].initialCandidate(),
                    reviewConfirmation[0] == null ? "apply-failed" : "review-apply-failed",
                    PluginLog.currentSlideLog(),
                    candidateRatings
                ));
                reviewConfirmation[0] = null;
                overlay.hide();
                PluginLog.endSlideSession();
                dialog.dispose();
                showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
            }
        });
        cancel.addActionListener(event -> {
            cancelPreview(current[0], candidateRatings);
            dialog.dispose();
        });
        dialog.setVisible(true);
    }

    /**
     * Selects the candidate initially shown by preview and recorded by diagnostics.
     *
     * @param result completed alignment result
     * @return first applicable candidate, then the first review-required candidate, then the first blocked candidate
     * @throws IllegalStateException when the result contains no candidates
     */
    static CenterlineCandidate initialCandidate(AlignmentResult result) {
        List<CenterlineCandidate> preferredCandidates = result.applicableCandidates().isEmpty()
            ? result.candidates().stream()
                .filter(candidate -> AlignmentService.assessCandidate(candidate).reviewRequired())
                .toList()
            : result.applicableCandidates();
        CenterlineCandidate selected = InitialPreviewCandidatePolicy.select(
            result.candidates(), preferredCandidates);
        if (selected != null) {
            return selected;
        }
        throw new IllegalStateException(tr("No centerline candidate could be extracted from the heatmap."));
    }

    private PreviewSelection buildPreviewSelection(
        DataSet dataSet,
        AlignmentResult base,
        CenterlineCandidate initialCandidate,
        CenterlineCandidate candidate,
        ManagedHeatmapConfig config
    ) {
        SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, base.selection(), base.sourcePolyline());
        CandidateAssessment assessment = AlignmentService.assessCandidate(candidate);
        if (assessment.disposition() == CandidateAssessment.Disposition.HARD_BLOCKED) {
            List<EastNorth> geometry = candidate.finalPreviewPoints().size() >= 2
                ? candidate.finalPreviewPoints()
                : candidate.eastNorthPoints().size() >= 2 ? candidate.eastNorthPoints() : base.sourcePolyline();
            AlignmentResult diagnostic = new AlignmentResult(base.selection(), base.capturedHeatmap(),
                base.candidates(), base.sourcePolyline(), geometry, List.of(), base.diagnostics(), base.tileMosaics(),
                base.detectorAttempts(), base.applicableCandidates());
            return new PreviewSelection(initialCandidate, candidate, diagnostic);
        }
        AlignmentResult candidateResult = assessment.automaticallyApplicable()
            ? alignmentService.applyCandidate(base, candidate, config)
            : alignmentService.previewCandidate(base, candidate, config);
        if (!config.allowUndownloadedAlignment()) {
            requirePreviewWithinDownloadedArea(candidateResult.previewPolyline(), dataSet);
        }
        return new PreviewSelection(initialCandidate, candidate, candidateResult);
    }


    /**
     * Validates the complete candidate-owned assignment plan before review or Apply.
     *
     * @param selection slide-time selected segment
     * @param preview exact candidate preview
     * @param candidate candidate owning existing-node targets
     * @param config slide-time heatmap configuration
     */
    private static void requireCandidateAssignmentPlan(
        SelectionContext selection,
        AlignmentResult preview,
        CenterlineCandidate candidate,
        ManagedHeatmapConfig config
    ) {
        if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE
            && AlignmentService.effectiveAlignmentMode(selection, config) == AlignmentMode.PRECISE_SHAPE) {
            ReplaceWaySegmentCommand.validateProposedNodePositions(
                selection,
                preview.sourcePolyline(),
                preview.previewPolyline(),
                candidate.proposedNodePositions()
            );
        }
    }

    private static boolean confirmationMatches(
        CandidateReviewConfirmation confirmation,
        CenterlineCandidate candidate,
        PreviewSelection preview
    ) {
        return confirmation != null && preview != null && preview.candidate().id().equals(candidate.id())
            && confirmation.matches(candidate, preview.result().previewPolyline());
    }

    /**
     * Builds a candidate label from its typed preview disposition.
     *
     * @param candidate candidate represented by the list row
     * @param assessment current typed disposition
     * @param reviewConfirmed whether this exact preview was explicitly confirmed
     * @return user-facing candidate label
     */
    static String candidateListLabel(
        CenterlineCandidate candidate,
        CandidateAssessment assessment,
        boolean reviewConfirmed
    ) {
        String disposition = switch (assessment.disposition()) {
            case APPLICABLE -> tr("applicable");
            case REVIEW_REQUIRED -> reviewConfirmed ? tr("review confirmed") : tr("review required");
            case HARD_BLOCKED -> tr("blocked");
        };
        String reason = candidate.evidence().corridorCoverage().reason();
        if ("complete-with-search-edge-bridge".equals(reason)) {
            return tr("{0} - {1} - search-edge gaps bridged", candidate.displayName(), disposition);
        }
        if ("unresolved-search-edge-censoring".equals(reason)) {
            return tr("{0} - {1} - incomplete search-edge evidence", candidate.displayName(), disposition);
        }
        return tr("{0} - {1}", candidate.displayName(), disposition);
    }

    /**
     * Builds a selected-candidate coverage message without treating reviewable uncertainty as a hard stop.
     *
     * @param candidate selected preview candidate
     * @param searchHalfWidthMeters factual slide-time half-width
     * @param assessment current typed disposition
     * @param reviewConfirmed whether this exact preview was explicitly confirmed
     * @return user-facing coverage status
     */
    static String coverageStatus(
        CenterlineCandidate candidate,
        double searchHalfWidthMeters,
        CandidateAssessment assessment,
        boolean reviewConfirmed
    ) {
        var coverage = candidate.evidence().corridorCoverage();
        if (!coverage.measured()) {
            return tr("Corridor coverage: not measured for this detector.");
        }
        if ("complete-with-search-edge-bridge".equals(coverage.reason())) {
            String message = tr("Search-edge gaps were interpolated from surrounding evidence.");
            return assessment.automaticallyApplicable() || reviewConfirmed
                ? message
                : tr("{0} Another safety finding blocks this candidate.", message);
        }
        if (assessment.reviewRequired()) {
            if (reviewConfirmed) {
                return tr("Incomplete corridor evidence was reviewed and confirmed for this preview.");
            }
            if ("unresolved-search-edge-censoring".equals(coverage.reason())) {
                return tr("Review required: heatmap evidence reaches the configured {0} m search boundary; review the complete "
                        + "preview before confirming it.",
                    String.format(Locale.ROOT, "%.1f", searchHalfWidthMeters));
            }
            return tr("Review required: corridor evidence is incomplete; review the complete preview before confirming it.");
        }
        if (coverage.complete()) {
            return assessment.automaticallyApplicable()
                ? tr("Corridor coverage: complete.")
                : tr("Corridor coverage is complete, but a structural safety finding blocks this candidate.");
        }
        return tr("Corridor evidence is incomplete and another safety finding blocks this candidate.");
    }

    /** Returns whether explicit review can promote this candidate. */
    static boolean canConfirmCandidate(CandidateAssessment assessment) {
        return assessment != null && assessment.reviewRequired();
    }


    /**
     * Returns whether a candidate has bridge or unresolved search-edge coverage that warrants an
     * explicit larger acquisition. This deliberately excludes generic no-signal failures.
     *
     * @param candidate selected preview candidate
     * @return whether the preview should offer wider-search retry
     */
    static boolean canRetryWithWiderSearch(CenterlineCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        var coverage = candidate.evidence().corridorCoverage();
        return coverage.measured() && ("complete-with-search-edge-bridge".equals(coverage.reason())
            || "unresolved-search-edge-censoring".equals(coverage.reason()));
    }

    private void configureRetryButton(
        JButton retry,
        CenterlineCandidate candidate,
        AlignmentConfig slideConfig,
        AlignmentResult result
    ) {
        boolean offer = canRetryWithWiderSearch(candidate);
        retry.setVisible(offer);
        if (!offer) {
            return;
        }
        RetrySearchBounds bounds = retrySearchBounds(slideConfig, result);
        boolean canExpand = bounds.maximumMeters() > bounds.currentMeters() + 1e-6;
        retry.setEnabled(canExpand);
        retry.setToolTipText(canExpand
            ? tr("Re-run the complete selected segment with a larger search width")
            : tr("No wider ordinary retry is available within the 14 m and sampler limits"));
    }

    private RetrySearchBounds retrySearchBounds(AlignmentConfig slideConfig, AlignmentResult result) {
        ManagedHeatmapConfig source = slideConfig.effectiveHeatmap();
        if (source.hasManagedAccessValues()) {
            double current = source.searchHalfWidthMeters();
            return new RetrySearchBounds(current, ordinaryRetryMaximumMeters(current,
                TileHeatmapSampler.maximumSearchHalfWidthMeters(source, result.sourcePolyline())));
        }
        double current = AlignmentService.visibleSearchHalfWidthMeters(slideConfig, result.sourcePolyline());
        return new RetrySearchBounds(current, ordinaryRetryMaximumMeters(current,
            AlignmentService.maximumVisibleSearchHalfWidthMeters(result.sourcePolyline())));
    }

    private void retryWithWiderSearch(
        JDialog dialog,
        DataSet dataSet,
        SelectionContext selection,
        PreviewSelection current,
        AlignmentConfig slideConfig,
        ImageryLayer imageryLayer,
        MapView mapView,
        Map<String, CandidateRating> candidateRatings
    ) {
        if (!canRetryWithWiderSearch(current.candidate())) {
            return;
        }
        RetrySearchBounds bounds = retrySearchBounds(slideConfig, current.result());
        Double requested = promptRetryWidth(dialog, bounds);
        if (requested == null) {
            return;
        }
        try {
            SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, selection, current.result().sourcePolyline());
            requireRetrySourceUnchanged(slideConfig.heatmap(), imageryLayer);
            AlignmentConfig retryConfig = slideConfig.withSearchHalfWidthMetersOverride(requested);
            PluginLog.verbose("Wider-search retry requested: priorHalfWidth=%.3f m, newHalfWidth=%.3f m, "
                + "candidate=%s, coverageReason=%s.", bounds.currentMeters(), requested,
                current.candidate().id(), current.candidate().evidence().corridorCoverage().reason());
            AlignmentResult retried = alignmentService.align(selection, imageryLayer, mapView, retryConfig);
            updateAggregateIntensityLayer(retried, retryConfig.effectiveHeatmap());
            CenterlineCandidate retryInitial = initialCandidate(retried);
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                retried, retryInitial, retryInitial, "preview-open", PluginLog.currentSlideLog(), candidateRatings));
            overlay.hide();
            activePreviewDialog = null;
            dialog.dispose();
            showCandidatePreview(dataSet, selection, retried, retryConfig, imageryLayer, mapView, candidateRatings);
        } catch (AlignmentService.AlignmentFailureException exception) {
            AlignmentResult failed = exception.partialResult();
            CenterlineCandidate failedCandidate = failed.candidates().isEmpty()
                ? null : failed.candidates().get(0);
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                failed, failedCandidate, failedCandidate, "wider-search-failed",
                PluginLog.currentSlideLog(), candidateRatings));
            PluginLog.verbose("Wider-search retry failed without changing the current preview: %s", exception.getMessage());
            showError(tr("Wider-search retry failed: {0}. The existing preview and ratings were kept.",
                exception.getMessage()));
        } catch (Exception exception) {
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
                current.result(), current.candidate(), current.candidate(), "wider-search-rejected",
                PluginLog.currentSlideLog(), candidateRatings));
            PluginLog.verbose("Wider-search retry was rejected without changing the current preview: %s",
                exception.getMessage());
            showError(tr("Wider-search retry was not run: {0}. The existing preview and ratings were kept.",
                exception.getMessage()));
        }
    }

    /** Returns the bounded ordinary retry limit without shrinking an explicit existing width. */
    static double ordinaryRetryMaximumMeters(double currentMeters, double samplerMaximumMeters) {
        return Math.max(currentMeters,
            Math.min(MAX_ORDINARY_SEARCH_HALF_WIDTH_METERS, samplerMaximumMeters));
    }

    /** Returns the default one-shot retry width within the already bounded range. */
    static double defaultRetryWidthMeters(double currentMeters, double maximumMeters) {
        return Math.min(maximumMeters, currentMeters * 2.0);
    }

    private Double promptRetryWidth(JDialog dialog, RetrySearchBounds bounds) {
        if (bounds.maximumMeters() <= bounds.currentMeters() + 1e-6) {
            showError(tr("No wider ordinary retry is available within the 14 m and sampler limits."));
            return null;
        }
        double defaultWidth = defaultRetryWidthMeters(bounds.currentMeters(), bounds.maximumMeters());
        String answer = JOptionPane.showInputDialog(
            dialog,
            tr("Search half-width in metres ({0} to {1})",
                String.format(Locale.ROOT, "%.2f", bounds.currentMeters()),
                String.format(Locale.ROOT, "%.2f", bounds.maximumMeters())),
            String.format(Locale.ROOT, "%.2f", defaultWidth)
        );
        if (answer == null) {
            return null;
        }
        try {
            double requested = Double.parseDouble(answer.trim().replace(",", "."));
            if (!Double.isFinite(requested) || requested <= bounds.currentMeters() + 1e-6
                || requested > bounds.maximumMeters() + 1e-6) {
                throw new IllegalArgumentException(tr("Enter a width larger than {0} and no greater than {1} metres.",
                    String.format(Locale.ROOT, "%.2f", bounds.currentMeters()),
                    String.format(Locale.ROOT, "%.2f", bounds.maximumMeters())));
            }
            return requested;
        } catch (NumberFormatException exception) {
            showError(tr("Enter a finite number of metres."));
            return null;
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return null;
        }
    }

    private void requireRetrySourceUnchanged(ManagedHeatmapConfig sourceConfig, ImageryLayer sourceLayer) {
        ManagedHeatmapConfig currentConfig = effectiveConfig(PluginPreferences.load());
        if (HeatmapLayerResolver.resolveOptional().orElse(null) != sourceLayer) {
            throw new IllegalStateException(
                "The heatmap layer changed after the preview opened. Run a new slide.");
        }
        if (sourceConfig.hasManagedAccessValues()) {
            if (!sourceConfig.hasSameManagedSource(currentConfig)) {
                throw new IllegalStateException("Heatmap source settings changed after the preview opened. Run a new slide.");
            }
            return;
        }
        if (currentConfig.hasManagedAccessValues()
            || !java.util.Objects.equals(sourceConfig.color(), currentConfig.color())
            || !java.util.Objects.equals(sourceConfig.manualLayerName(), currentConfig.manualLayerName())
            || !java.util.Objects.equals(sourceConfig.layerRegex(), currentConfig.layerRegex())
            || HeatmapLayerResolver.resolve() != sourceLayer) {
            throw new IllegalStateException("The rendered heatmap layer changed after the preview opened. Run a new slide.");
        }
    }

    private record RetrySearchBounds(double currentMeters, double maximumMeters) {
        private RetrySearchBounds {
            if (!Double.isFinite(currentMeters) || !Double.isFinite(maximumMeters)
                || currentMeters <= 0.0 || maximumMeters < 0.0) {
                throw new IllegalArgumentException("Retry search bounds must be finite and non-negative");
            }
        }
    }

    private void loadCandidateRating(
        CandidateRating rating,
        JComboBox<String> ratingBox,
        JCheckBox offTheLine,
        JCheckBox jumping,
        JCheckBox unnecessaryKinks,
        JCheckBox badJunctionShapes
    ) {
        ratingBox.setSelectedItem(rating == null ? "" : rating.rating());
        List<String> features = rating == null ? List.of() : rating.negativeFeatures();
        offTheLine.setSelected(features.contains(FEATURE_OFF_THE_LINE));
        jumping.setSelected(features.contains(FEATURE_JUMPING));
        unnecessaryKinks.setSelected(features.contains(FEATURE_UNNECESSARY_KINKS));
        badJunctionShapes.setSelected(features.contains(FEATURE_BAD_JUNCTION_SHAPES));
    }

    private void saveCandidateRating(
        PreviewSelection preview,
        Map<String, CandidateRating> candidateRatings,
        JComboBox<String> ratingBox,
        JCheckBox offTheLine,
        JCheckBox jumping,
        JCheckBox unnecessaryKinks,
        JCheckBox badJunctionShapes
    ) {
        String rating = (String) ratingBox.getSelectedItem();
        List<String> features = negativeFeatures(offTheLine, jumping, unnecessaryKinks, badJunctionShapes);
        CandidateRating candidateRating = new CandidateRating(rating, features);
        if (candidateRating.isEmpty()) {
            candidateRatings.remove(preview.candidate().id());
        } else {
            candidateRatings.put(preview.candidate().id(), candidateRating);
        }
        PluginLog.verbose("CandidateRating candidate=%s rating='%s' negativeFeatures=%s.",
            preview.candidate().id(), rating == null ? "" : rating, features);
        updatePreviewBundle(preview, candidateRatings, "preview-open");
    }

    private List<String> negativeFeatures(JCheckBox offTheLine, JCheckBox jumping, JCheckBox unnecessaryKinks, JCheckBox badJunctionShapes) {
        List<String> features = new java.util.ArrayList<>();
        if (offTheLine.isSelected()) {
            features.add(FEATURE_OFF_THE_LINE);
        }
        if (jumping.isSelected()) {
            features.add(FEATURE_JUMPING);
        }
        if (unnecessaryKinks.isSelected()) {
            features.add(FEATURE_UNNECESSARY_KINKS);
        }
        if (badJunctionShapes.isSelected()) {
            features.add(FEATURE_BAD_JUNCTION_SHAPES);
        }
        return features;
    }

    private void updatePreviewBundle(PreviewSelection preview, Map<String, CandidateRating> candidateRatings, String status) {
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
            preview.result(),
            preview.candidate(),
            preview.initialCandidate(),
            status,
            PluginLog.currentSlideLog(),
            candidateRatings
        ));
    }
    /**
     * Builds the prominent candidate-specific cleanup notification shown above technical details.
     *
     * @param candidate selected preview candidate
     * @return concise human-readable cleanup status
     */
    static String cleanupStatus(CenterlineCandidate candidate) {
        CandidateGeometryCleanup cleanup = candidate.geometryCleanup();
        return switch (cleanup.outcome()) {
            case NOT_REQUESTED -> tr("Cleanup status: not requested.");
            case SKIPPED -> tr("Cleanup status: skipped; no safe interval was changed ({0}).",
                cleanupReasonLabel(cleanup.reasonCode()));
            case UNCHANGED -> cleanup.frozenIntervalCount() > 0
                ? tr("Cleanup status: {0} safe interval(s) were evaluated; {1} protected neighborhood(s) "
                    + "stayed unchanged and no geometric change was accepted.",
                    cleanup.eligibleIntervalCount(), cleanup.frozenIntervalCount())
                : tr("Cleanup status: evaluated safely, but no geometric change was accepted.");
            case CLEANED_ALTERNATIVE_AVAILABLE ->
                tr("Cleanup status: a separate cleaned result is available in the candidate list.");
            case CLEANED -> tr("Cleanup status: fully cleaned ({0} to {1} points).",
                cleanup.beforePointCount(), cleanup.afterPointCount());
            case PARTIALLY_CLEANED -> partialCleanupStatus(cleanup);
            case REJECTED -> tr("Cleanup status: rejected for safety; the raw traced result is shown.");
        };
    }


    private static String partialCleanupStatus(CandidateGeometryCleanup cleanup) {
        if (cleanup.frozenIntervalCount() > 0) {
            return tr(
                "Cleanup status: partially cleaned in {0} interval(s); "
                    + "{1} protected neighborhood(s) stayed unchanged.",
                cleanup.changedIntervalCount(), cleanup.frozenIntervalCount());
        }
        return tr(
            "Cleanup status: partially cleaned in {0} interval(s); "
                + "other eligible geometry stayed unchanged for safety.",
            cleanup.changedIntervalCount());
    }
    private static String cleanupReasonLabel(String reasonCode) {
        return switch (reasonCode) {
            case "no-eligible-cleanup-interval" ->
                tr("no independently safe cleanup interval exists outside the protected neighborhood");
            case "alignment-mode-ineligible" -> tr("cleanup requires Precise Shape mode");
            case "tracker-mode-ineligible" -> tr("cleanup requires Corridor Aware tracking");
            default -> reasonCode;
        };
    }


    private void cancelPreview(PreviewSelection preview, Map<String, CandidateRating> candidateRatings) {
        PluginLog.verbose("Alignment cancelled at preview dialog.");
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
            preview.result(),
            preview.candidate(),
            preview.initialCandidate(),
            "cancelled",
            PluginLog.currentSlideLog(),
            candidateRatings
        ));
        overlay.hide();
        PluginLog.endSlideSession();
    }

    private void applyPreview(
        DataSet dataSet,
        SelectionContext selection,
        PreviewSelection preview,
        ManagedHeatmapConfig config,
        Map<String, CandidateRating> candidateRatings,
        CandidateReviewConfirmation reviewConfirmation
    ) {
        CenterlineCandidate chosen = preview.candidate();
        AlignmentResult chosenResult = preview.result();
        CandidateAssessment assessment = AlignmentService.assessCandidate(chosen);
        if (assessment.disposition() == CandidateAssessment.Disposition.HARD_BLOCKED) {
            throw new IllegalStateException(tr("This candidate is blocked by a structural or signal-safety finding."));
        }
        if (assessment.reviewRequired()
            && !confirmationMatches(reviewConfirmation, chosen, preview)) {
            throw new IllegalStateException(tr("Review and confirm this exact candidate preview before applying it."));
        }

        SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, selection, chosenResult.sourcePolyline());
        requireCandidateAssignmentPlan(selection, chosenResult, chosen, config);
        if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE) {
            alignmentService.requireCurrentTopologySafe(chosen, selection);
        }
        if (!config.allowUndownloadedAlignment()) {
            requirePreviewWithinDownloadedArea(chosenResult.previewPolyline(), dataSet);
        }

        AlignmentMode effectiveMode = AlignmentService.effectiveAlignmentMode(selection, config);
        if (effectiveMode == AlignmentMode.MOVE_EXISTING_NODES
            && chosenResult.nodeMoves().isEmpty()) {
            throw new IllegalStateException(tr("No movable interior nodes were found in the selected segment."));
        }

        if (effectiveMode == AlignmentMode.MOVE_EXISTING_NODES) {
            PluginLog.verbose("Applying move-existing-nodes alignment for candidate %s with %d node moves.", chosen.id(), chosenResult.nodeMoves().size());
            UndoRedoHandler.getInstance().add(new MoveNodesCommand(
                dataSet,
                chosenResult.nodeMoves(),
                tr("Align way to heatmap")
            ));
        } else {
            PluginLog.verbose("Applying precise-shape alignment for candidate %s with %d preview points.", chosen.id(), chosenResult.previewPolyline().size());
            Map<Long, EastNorth> proposedNodePositions = config.trackerMode() == TrackerMode.CORRIDOR_AWARE
                ? chosen.proposedNodePositions() : null;
            if (config.trackerMode() == TrackerMode.CORRIDOR_AWARE && proposedNodePositions.isEmpty()) {
                throw new IllegalStateException(
                    "The corridor-aware preview has no existing-node assignment plan. Run the slide again.");
            }
            UndoRedoHandler.getInstance().add(new ReplaceWaySegmentCommand(
                dataSet,
                selection.way(),
                selection,
                chosenResult.previewPolyline(),
                proposedNodePositions,
                tr("Align way to heatmap precisely")
            ));
        }
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(
            chosenResult, chosen, preview.initialCandidate(),
            reviewConfirmation == null ? "applied" : "applied-after-review",
            PluginLog.currentSlideLog(), candidateRatings));
    }

    private JPanel buildSummaryPanel(
        AlignmentResult result,
        CenterlineCandidate chosen,
        ManagedHeatmapConfig config,
        GeometryCleanupConfig cleanupConfig,
        JComboBox<CenterlineCandidate> candidates,
        JComboBox<String> ratingBox,
        JCheckBox offTheLine,
        JCheckBox jumping,
        JCheckBox unnecessaryKinks,
        JCheckBox badJunctionShapes
    ) {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        if (candidates != null) {
            panel.add(new JLabel(tr("Detected ridge")), GBC.std());
            panel.add(candidates, GBC.eol().fill(GBC.HORIZONTAL));
            panel.add(new JLabel(tr("Changing the ridge updates the map preview immediately.")), GBC.eol());
        } else {
            panel.add(new JLabel(tr("Candidate: {0}", chosen.toString())), GBC.eol());
        }
        if (ratingBox != null) {
            panel.add(new JLabel(tr("Visual rating")), GBC.std());
            panel.add(ratingBox, GBC.eol());
            panel.add(offTheLine, GBC.std());
            panel.add(jumping, GBC.eol());
            panel.add(unnecessaryKinks, GBC.std());
            panel.add(badJunctionShapes, GBC.eol());
            panel.add(new JLabel(tr("Ratings and negative feature tags are saved in the debug export.")), GBC.eol());
        }
        AlignmentMode effectiveMode = AlignmentService.effectiveAlignmentMode(result.selection(), config);
        String modeLabel = effectiveMode == config.alignmentMode()
            ? config.alignmentMode().displayName()
            : tr("{0} (automatic for rough sketch)", effectiveMode.displayName());
        panel.add(new JLabel(tr("Mode: {0}", modeLabel)), GBC.eol());
        panel.add(new JLabel(tr("Junction/end nodes: {0}", config.adjustJunctionNodes() ? "adjustable" : "fixed")), GBC.eol());
        panel.add(new JLabel(cleanupSummary(result, cleanupConfig)), GBC.eol());
        panel.add(new JLabel(tr("Legacy post-slide simplification: {0}",
            cleanupConfig.isDisabled()
                ? (config.simplifyEnabled() ? "enabled" : "disabled")
                : "inactive while geometry cleanup is enabled")), GBC.eol());
        panel.add(new JLabel(tr("Sampling: {0}", result.diagnostics().samplingSummary())), GBC.eol());
        panel.add(new JLabel(tr("Diagnostics file can be exported from More tools.")), GBC.eol());
        if (PluginPreferences.isDebugEnabled()) {
            panel.add(new JLabel(tr("Debug overlay is enabled.")), GBC.eol());
        }
        panel.add(new JLabel(tr("Preview legend: solid blue = selected result; orange dashed = original; dashed labeled lines = other detected ridges.")), GBC.eol());
        return panel;
    }

    /** Returns slide-time cleanup configuration and generated-sibling count for the preview. */
    private String cleanupSummary(AlignmentResult result, GeometryCleanupConfig cleanupConfig) {
        if (cleanupConfig.isDisabled()) {
            return tr("Geometry cleanup: off");
        }
        long alternatives = result.candidates().stream()
            .filter(candidate -> candidate.geometryCleanup().cleanedCandidate())
            .count();
        return tr("Geometry cleanup: {0}, {1} ({2} m); cleaned alternatives: {3}",
            cleanupConfig.mode(), cleanupConfig.preset(), cleanupConfig.rippleScaleMeters(), alternatives);
    }

    /**
     * Builds the cleanup status line for the candidate currently shown in the modeless preview.
     *
     * <p>The wording intentionally reports only candidate-owned cleanup facts. It does not infer
     * a successful cleanup from the current settings, nor does it imply that an inspection-only
     * candidate can be applied.</p>
     *
     * @param candidate selected preview candidate
     * @return user-readable cleanup outcome, reason, and point/operation counts
     */
    static String cleanupDetail(CenterlineCandidate candidate) {
        var cleanup = candidate.geometryCleanup();
        return tr("Selected cleanup: {0}; reason: {1}; points: before {2}, smoothed {3}, after {4}; "
                + "smoothing: accepted {5}, backtracks {6}; reduction: accepted {7}/{8}; containment failures: {9}",
            cleanupOutcomeLabel(cleanup.outcome()), cleanup.reasonCode(), cleanup.beforePointCount(),
            cleanup.smoothedPointCount(),
            cleanup.afterPointCount(), cleanup.acceptedSmoothingPasses(), cleanup.smoothingBacktrackCount(),
            cleanup.acceptedChordCount(), cleanup.attemptedChordCount(), cleanup.containmentFailureCount());
    }

    private static String cleanupOutcomeLabel(
        CandidateGeometryCleanup.Outcome outcome
    ) {
        return switch (outcome) {
            case NOT_REQUESTED -> tr("not requested");
            case SKIPPED -> tr("skipped");
            case UNCHANGED -> tr("unchanged");
            case CLEANED_ALTERNATIVE_AVAILABLE -> tr("cleaned alternative available");
            case CLEANED -> tr("fully applied");
            case PARTIALLY_CLEANED -> tr("partially applied");
            case REJECTED -> tr("rejected");
        };
    }

    private record PreviewSelection(
        CenterlineCandidate initialCandidate,
        CenterlineCandidate candidate,
        AlignmentResult result
    ) {
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(
            MainApplication.getMainFrame(),
            message,
            tr("WayHeatmapTracer"),
            JOptionPane.ERROR_MESSAGE
        );
    }

    private void requireDownloadedAreaCoverage(SelectionContext selection, DataSet dataSet) {
        List<Bounds> bounds = dataSet.getDataSourceBounds();
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalStateException("This data layer has no downloaded area metadata. Download the area in JOSM before aligning ways.");
        }
        for (org.openstreetmap.josm.data.osm.Node node : selection.segmentNodes()) {
            LatLon point = node.getCoor();
            if (point == null || !isWithinDownloadedBounds(point, bounds)) {
                throw new IllegalStateException("Selected segment extends outside the downloaded area. Download a larger area first.");
            }
        }
    }

    private void requirePreviewWithinDownloadedArea(List<EastNorth> preview, DataSet dataSet) {
        List<Bounds> bounds = dataSet.getDataSourceBounds();
        if (bounds == null || bounds.isEmpty()) {
            return;
        }
        for (EastNorth point : preview) {
            LatLon latLon = org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection().eastNorth2latlon(point);
            if (!isWithinDownloadedBounds(latLon, bounds)) {
                throw new IllegalStateException("Aligned geometry would extend outside the downloaded area. Download a larger area first.");
            }
        }
    }

    private boolean isWithinDownloadedBounds(LatLon point, List<Bounds> bounds) {
        for (Bounds bound : bounds) {
            if (bound.contains(point)) {
                return true;
            }
        }
        return false;
    }
}
