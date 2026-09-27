package org.cerebellum.morphometry.visualization;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.TextRoi;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.PluginOutput;
import org.cerebellum.morphometry.geometry.BooleanROIProcessor;
import org.cerebellum.morphometry.geometry.PurkinjeLengthCalculator;
import org.cerebellum.morphometry.geometry.ROIValidator;
import org.cerebellum.morphometry.measurement.MeasurementEngine;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.MorphometryResults.SubsectionResult;
import org.cerebellum.morphometry.testing.SyntheticSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualizationTest {

    private SyntheticSection section;
    private ImagePlus imp;
    private Roi[] inputs;
    private List<InstanceResult> results;

    @BeforeEach
    void analyze() throws Exception {
        section = SyntheticSection.builder().build();
        imp = section.image();
        inputs = section.rois();
        SortedMap<Integer, LayerSet> instances = ROIValidator.validate(inputs, imp, Diagnostics.silent());
        results = MeasurementEngine.analyze(instances, imp, Diagnostics.silent());
    }

    @Test
    void measurementRoisAreNamedMarkedAndMatchTheTable() {
        List<Roi> rois = RoiManagerExporter.measurementRois(results);
        List<String> names = new ArrayList<>();
        for (Roi r : rois) {
            names.add(r.getName());
            assertTrue(PluginOutput.isOutput(r), r.getName() + " should be marked as plugin output");
        }
        assertEquals(List.of("Grey Matter", "Granular Layer", "Molecular Layer",
                "2Cb_Granular", "2Cb_Molecular", "2Cb_Purkinje"), names.subList(0, 6));
        assertEquals(3 + 8 * 3, rois.size());

        for (SubsectionResult sub : results.get(0).getResults().getSubsections()) {
            Roi purkinje = rois.get(names.indexOf(sub.getLabel() + "_Purkinje"));
            assertEquals(Roi.POLYLINE, purkinje.getType());
            assertEquals(sub.getPurkinjeLength(),
                    PurkinjeLengthCalculator.totalLength((PolygonRoi) purkinje, imp), 0.05,
                    sub.getLabel() + ": exported segment must measure what the table reports");
        }
    }

    @Test
    void exportedAreaRoisMeasureExactlyWhatTheTableReports() {
        List<Roi> rois = RoiManagerExporter.measurementRois(results);
        MorphometryResults r = results.get(0).getResults();
        assertEquals(r.getGranularLayerArea(), BooleanROIProcessor.area(byName(rois, "Granular Layer"), imp), 0);
        assertEquals(r.getMolecularLayerArea(), BooleanROIProcessor.area(byName(rois, "Molecular Layer"), imp), 0);
        assertEquals(r.getGreyMatterArea(), BooleanROIProcessor.area(byName(rois, "Grey Matter"), imp), 0);
        for (SubsectionResult sub : r.getSubsections()) {
            assertEquals(sub.getGranularArea(), BooleanROIProcessor.area(byName(rois, sub.getLabel() + "_Granular"), imp), 0);
            assertEquals(sub.getMolecularArea(), BooleanROIProcessor.area(byName(rois, sub.getLabel() + "_Molecular"), imp), 0);
        }
    }

    private static Roi byName(List<Roi> rois, String name) {
        return rois.stream().filter(x -> name.equals(x.getName())).findFirst().orElseThrow();
    }

    @Test
    void aSecondRunOnTheSameRoiManagerStillValidates() throws Exception {
        List<Roi> roiManager = new ArrayList<>(Arrays.asList(inputs));
        roiManager.addAll(RoiManagerExporter.measurementRois(results));
        SortedMap<Integer, LayerSet> again =
                ROIValidator.validate(roiManager.toArray(new Roi[0]), imp, Diagnostics.silent());
        assertEquals(1, again.size());
        assertEquals(7, again.get(1).getFissures().size());
    }

    @Test
    void markingCopiesNeverMarksTheUsersRois() {
        PolygonRoi purkinje = results.get(0).getLayers().getPurkinje();
        purkinje.setProperty("lab-note", "traced by hand"); // a ROI that already has a property table
        OverlayRenderer.buildOverlay(null, results, true, 20);
        RoiManagerExporter.measurementRois(results);
        assertNull(purkinje.getProperty(PluginOutput.PROPERTY));
        assertEquals("traced by hand", purkinje.getProperty("lab-note"));
    }

    @Test
    void overlayDrawsThePurkinjeLineAsALineAndLabelsSections() {
        Overlay overlay = OverlayRenderer.buildOverlay(null, results, true, 20);
        Roi purkinje = overlay.get(overlay.getIndex("Purkinje"));
        assertEquals(Roi.POLYLINE, purkinje.getType(), "v1.0.0 drew a 1-px area outline instead of the line");

        long labels = Arrays.stream(overlay.toArray()).filter(r -> r instanceof TextRoi).count();
        assertEquals(8, labels);
    }

    @Test
    void rerunningReplacesOnlyThePluginsOwnOverlayItems() {
        Overlay existing = new Overlay();
        Roi scaleBar = new Roi(10, 10, 100, 5);
        scaleBar.setName("scale bar");
        existing.add(scaleBar);

        Overlay first = OverlayRenderer.buildOverlay(existing, results, true, 20);
        Overlay second = OverlayRenderer.buildOverlay(first, results, true, 20);
        assertEquals(first.size(), second.size(), "a rerun must not stack a second copy of the overlay");
        assertTrue(second.contains(scaleBar), "the user's own overlay items are kept");
    }
}
