package org.cerebellum.morphometry;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import org.cerebellum.morphometry.export.SpreadsheetExporter;
import org.cerebellum.morphometry.geometry.ROIValidator;
import org.cerebellum.morphometry.measurement.MeasurementEngine;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.MorphometryResults.SubsectionResult;
import org.cerebellum.morphometry.model.PartitionSet;
import org.cerebellum.morphometry.testing.SyntheticSection;
import org.cerebellum.morphometry.visualization.RoiManagerExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lab's workflow, as written in the "Nissl Staining ImageJ Analysis Protocol" SOP, expressed as
 * tests. Each test is named after the SOP section whose instructions it checks the plugin honours.
 */
class SopScenariosTest {

    private static final List<String> STANDARD = List.of("2Cb", "3Cb", "4/5Cb", "6Cb", "7Cb", "8Cb", "9Cb", "10Cb");

    /** Runs validation → measurement → pooling, like the plugin does. */
    private static final class Run {
        final Diagnostics diag = Diagnostics.silent();
        final List<InstanceResult> instances;
        final MorphometryResults results;

        Run(Roi[] rois, ImagePlus imp) throws Exception {
            SortedMap<Integer, LayerSet> validated = ROIValidator.validate(rois, imp, diag);
            instances = MeasurementEngine.analyze(validated, imp, diag);
            results = MeasurementEngine.combine(instances, diag);
        }

        List<String> labels() {
            List<String> out = new ArrayList<>();
            for (SubsectionResult s : results.getSubsections()) {
                out.add(s.getLabel());
            }
            return out;
        }

        boolean warned(String fragment) {
            return diag.getWarnings().stream().anyMatch(w -> w.contains(fragment));
        }
    }

    private static Run run(SyntheticSection s) throws Exception {
        return new Run(s.rois(), s.image());
    }

    private static Roi renamed(Roi roi, String name) {
        roi.setName(name);
        return roi;
    }

    // -----------------------------------------------------------------------
    // SOP 4: ROI naming table
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" is read as {1}")
    @CsvSource({
            "Cerebellum, CB", "cerebellum, CB", "CB, CB", "cb, CB", "CB_left, CB",
            "Granular+WM, GLWM", "granular, GLWM", "GL+WM, GLWM", "GLWM, GLWM", "GL_WM, GLWM",
            "GL-WM, GLWM", "GL WM, GLWM",
            "WhiteMatter, WM", "white matter, WM", "WM, WM", "wm, WM",
            "Purkinje, PL", "purkinje, PL", "PL, PL", "pl, PL",
            "Fissure1, FL", "fissure1, FL", "FL1, FL", "FL_1, FL", "FL-1, FL", "fl1, FL", "Fissure2, FL", "FL2, FL"
    })
    void sop4_everyNameInTheNamingTableIsAccepted(String name, String layer) throws Exception {
        SyntheticSection s = SyntheticSection.builder().build();
        Roi[] rois = s.rois(); // CB, GL+WM, WM, PL, FL1…FL7
        int index = Arrays.asList("CB", "GLWM", "WM", "PL", "FL").indexOf(layer);
        rois[index].setName(name);
        LayerSet layers = ROIValidator.validate(rois, s.image(), Diagnostics.silent()).get(1);
        Roi found = index == 0 ? layers.getCerebellum() : index == 1 ? layers.getGranularWM()
                : index == 2 ? layers.getWhiteMatter() : index == 3 ? layers.getPurkinje()
                : layers.getFissures().get(0);
        assertEquals(name, found.getName());
    }

    // -----------------------------------------------------------------------
    // SOP 4.1: detached cerebellar tissue (2CB, 3CB, …) pooled into one result
    // -----------------------------------------------------------------------

    @Test
    void sop4_1_detachedPiecesArePooledLikeTheExampleInTheSop() throws Exception {
        // The ROI Manager list shown in the SOP: CB, GL+WM, WM, 2CB, 2GL+WM, 2WM, 3CB, 3GL+WM, 3WM,
        // 4CB, 4GL+WM, 4PL, 3PL, 2PL, PL, fl1 — piece 1 split by one fissure, pieces 2 and 3 with
        // White Matter but no fissures, piece 4 without White Matter. The SOP's example output has
        // five rows, "Section 1" … "Section 5".
        SyntheticSection main = SyntheticSection.builder().fissures(1).build();
        SyntheticSection piece = SyntheticSection.builder().fissures(0).build();
        ImagePlus imp = main.image();
        List<Roi> rois = new ArrayList<>(List.of(
                main.cerebellum(), main.granularWM(), main.whiteMatter(),
                renamed(piece.cerebellum(), "2CB"), renamed(piece.granularWM(), "2GL+WM"), renamed(piece.whiteMatter(), "2WM"),
                renamed(piece.cerebellum(), "3CB"), renamed(piece.granularWM(), "3GL+WM"), renamed(piece.whiteMatter(), "3WM"),
                renamed(piece.cerebellum(), "4CB"), renamed(piece.granularWM(), "4GL+WM"), renamed(piece.purkinje(), "4PL"),
                renamed(piece.purkinje(), "3PL"), renamed(piece.purkinje(), "2PL"), main.purkinje(),
                renamed(main.fissures().get(0), "fl1")));

        Run run = new Run(rois.toArray(new Roi[0]), imp);
        assertEquals(4, run.instances.size());
        assertEquals(List.of("Section 1", "Section 2", "Section 3", "Section 4", "Section 5"), run.labels());

        double cerebellum = 0, purkinje = 0;
        for (InstanceResult i : run.instances) {
            cerebellum += i.getResults().getCerebellumArea();
            purkinje += i.getResults().getTotalPurkinjeLength();
        }
        assertEquals(cerebellum, run.results.getCerebellumArea(), 1e-6, "totals are summed across pieces");
        assertEquals(purkinje, run.results.getTotalPurkinjeLength(), 1e-6);

        String[][] grid = SpreadsheetExporter.buildDataGrid(run.results);
        assertEquals("Area", grid[1][0]);
        assertEquals("Length", grid[2][0]);
        assertEquals("Section 5", grid[7][0]);
        assertEquals(8, grid.length, "header, Area, Length and five section rows");
    }

    // -----------------------------------------------------------------------
    // SOP 5: Purkinje line traced clockwise
    // -----------------------------------------------------------------------

    @Test
    void sop5_clockwisePurkinjeGivesLobulesInAnatomicalOrder() throws Exception {
        // The phantom is oriented like the SOP images: rostral to the left, lobule 2 at the lower
        // left, running over the top to lobule 10 at the lower right. Its Purkinje line is traced
        // clockwise, starting at lobule 2.
        Run run = run(SyntheticSection.builder().build());
        assertEquals(STANDARD, run.labels());
        PartitionSet ps = run.instances.get(0).getGeometry().partitionSet;
        assertTrue(ps.getPartitions().get(0).getLabelAnchor().x < SyntheticSection.CX, "2Cb is on the left");
        assertTrue(ps.getPartitions().get(7).getLabelAnchor().x > SyntheticSection.CX, "10Cb is on the right");
        assertFalse(run.warned("counterclockwise"));
    }

    @Test
    void sop10_counterclockwisePurkinjeIsFlagged() throws Exception {
        // SOP 10, "Section labels reversed — cause: Purkinje traced counterclockwise". The numbering
        // still follows the trace (so it is reversed), but the plugin now says so.
        Run run = run(SyntheticSection.builder().purkinjeCounterclockwise(true).build());
        assertTrue(run.warned("traced counterclockwise"), () -> "warnings: " + run.diag.getWarnings());
        PartitionSet ps = run.instances.get(0).getGeometry().partitionSet;
        assertTrue(ps.getPartitions().get(0).getLabelAnchor().x > SyntheticSection.CX, "numbering starts on the right");
    }

    // -----------------------------------------------------------------------
    // SOP 10 "Missing section": closing the sections at the base of the cerebellum
    // -----------------------------------------------------------------------

    @Test
    void sop10_honoraryFissureAtTheBaseSeparatesTheFirstAndLastSections() throws Exception {
        // As traced in the SOP images, the outlines cut straight across the base, so the grey
        // matter is a closed ring. Seven fissures plus the "honorary" line across the base give
        // eight sections with the standard names.
        Run run = run(SyntheticSection.builder().pinched(false).baseFissure(true).build());
        assertEquals(STANDARD, run.labels());
        PartitionSet ps = run.instances.get(0).getGeometry().partitionSet;
        assertTrue(ps.isRing());
        assertFalse(ps.areEndsJoined());
        assertFalse(run.diag.hasWarnings(), () -> "unexpected warnings: " + run.diag.getWarnings());

        MorphometryResults r = run.results;
        double sections = r.getSubsections().stream().mapToDouble(x -> x.getGranularArea() + x.getMolecularArea()).sum();
        assertEquals(r.getGreyMatterArea(), sections, r.getGreyMatterArea() * 0.002);
    }

    @Test
    void sop10_forgettingTheBaseFissureIsFlaggedAsAMissingSection() throws Exception {
        Run run = run(SyntheticSection.builder().pinched(false).build());
        assertEquals(7, run.results.getSubsections().size());
        assertTrue(run.warned("first and last lobules are joined"), () -> "warnings: " + run.diag.getWarnings());
        assertTrue(run.warned("fissure line across the base"), "the warning names the SOP's fix");
    }

    // -----------------------------------------------------------------------
    // SOP 6 "Important advice": outlines made with ROI Manager XOR and Alt-trimming
    // -----------------------------------------------------------------------

    @Test
    void sop6_compositeOutlinesFromXorAndTrimmingAreAccepted() throws Exception {
        // XOR + Alt-trim leaves a composite (ShapeRoi) selection rather than a plain polygon.
        SyntheticSection s = SyntheticSection.builder().build();
        Roi[] rois = s.rois();
        Roi notch = new PolygonRoi(new float[] {540, 580, 580, 540}, new float[] {100, 100, 150, 150}, 4, Roi.POLYGON);
        ShapeRoi composite = new ShapeRoi(rois[0]).not(new ShapeRoi(notch));
        composite.setName("CB");
        assertEquals(Roi.COMPOSITE, composite.getType());
        rois[0] = composite;

        Run run = new Run(rois, s.image());
        assertEquals(STANDARD, run.labels());
        MorphometryResults r = run.results;
        double sections = r.getSubsections().stream().mapToDouble(x -> x.getGranularArea() + x.getMolecularArea()).sum();
        assertEquals(r.getGreyMatterArea(), sections, r.getGreyMatterArea() * 0.002);
    }

    // -----------------------------------------------------------------------
    // SOP 9.2: every lobule has exactly one Purkinje segment
    // -----------------------------------------------------------------------

    @Test
    void sop9_2_everySectionGetsExactlyOnePurkinjeRoi() throws Exception {
        for (SyntheticSection s : new SyntheticSection[] {
                SyntheticSection.builder().build(),
                SyntheticSection.builder().pinched(false).baseFissure(true).build()}) {
            Run run = run(s);
            List<Roi> rois = RoiManagerExporter.measurementRois(run.instances);
            for (String label : run.labels()) {
                long count = rois.stream().filter(r -> r.getName().startsWith(label + "_Purkinje")).count();
                assertEquals(1, count, label + " should have exactly one Purkinje segment");
            }
        }
    }

    // -----------------------------------------------------------------------
    // SOP 7: saving the ROI set after a run, then re-running on it
    // -----------------------------------------------------------------------

    @Test
    void sop7_measurementRoisStayMarkedThroughASavedRoiSet() throws Exception {
        Run run = run(SyntheticSection.builder().build());
        for (Roi output : RoiManagerExporter.measurementRois(run.instances)) {
            byte[] saved = RoiEncoder.saveAsByteArray(output);
            Roi reloaded = RoiDecoder.openFromByteArray(saved);
            assertNotNull(reloaded);
            assertTrue(PluginOutput.isOutput(reloaded), output.getName() + " lost its marker when saved");
        }
    }

    // -----------------------------------------------------------------------
    // SOP 11: output files are Output.csv and Output.xlsx
    // -----------------------------------------------------------------------

    @Test
    void sop11_resultFilesAreNamedOutputByDefault() {
        assertEquals("Output", CerebellarMorphometryPlugin.DEFAULT_FILE_NAME);
        assertEquals("Output", CerebellarMorphometryPlugin.resultFileBase(null));
        assertEquals("Output", CerebellarMorphometryPlugin.resultFileBase("   "));
        assertEquals("Output", CerebellarMorphometryPlugin.resultFileBase("Output.csv"));
        assertEquals("Mouse_001 Section_02", CerebellarMorphometryPlugin.resultFileBase(" Mouse_001 Section_02 "));
        assertEquals("a_b_c", CerebellarMorphometryPlugin.resultFileBase("a/b:c"));
        assertEquals("results", CerebellarMorphometryPlugin.resultFileBase("results.XLSX"));
    }

    // -----------------------------------------------------------------------
    // SOP "Image Conversion": the converter macro calibrates in micrometers
    // -----------------------------------------------------------------------

    @Test
    void micrometerCalibrationFromTheConverterMacroGivesTheSopColumnHeaders() throws Exception {
        // 1.4 pixels per micrometer, unit "micrometer", as set by the CZI/ZVI converter macro.
        Run run = run(SyntheticSection.builder().calibration(1 / 1.4, "micrometer").build());
        assertArrayEquals(new String[] {"Measurement", "Cerebellum (micrometer²)", "Grey Matter (micrometer²)",
                        "Granular Layer (micrometer²)", "Molecular Layer (micrometer²)", "Purkinje (micrometer)"},
                SpreadsheetExporter.buildDataGrid(run.results)[0]);
    }
}
