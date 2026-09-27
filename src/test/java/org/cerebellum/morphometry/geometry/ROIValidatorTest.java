package org.cerebellum.morphometry.geometry;

import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.PluginOutput;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.ValidationException;
import org.cerebellum.morphometry.testing.SyntheticSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ROIValidatorTest {

    private final SyntheticSection section = SyntheticSection.builder().build();
    private final ImagePlus imp = section.image();

    /** The phantom's ROIs renamed with {@code names} in order: CB, GL+WM, WM, PL, fissures… */
    private Roi[] renamed(String... names) {
        Roi[] rois = section.rois();
        for (int i = 0; i < names.length; i++) {
            rois[i].setName(names[i]);
        }
        return rois;
    }

    private static List<Roi> list(Roi... rois) {
        return new ArrayList<>(Arrays.asList(rois));
    }

    private LayerSet validSingle(Roi[] rois) throws ValidationException {
        SortedMap<Integer, LayerSet> result = ROIValidator.validate(rois, imp, Diagnostics.silent());
        assertEquals(1, result.size(), "expected a single instance");
        return result.get(1);
    }

    @Test
    void acceptsAbbreviatedNames() throws Exception {
        LayerSet layers = validSingle(section.rois());
        assertEquals("CB", layers.getCerebellum().getName());
        assertEquals("GL+WM", layers.getGranularWM().getName());
        assertEquals("WM", layers.getWhiteMatter().getName());
        assertEquals("PL", layers.getPurkinje().getName());
        assertEquals(7, layers.getFissures().size());
    }

    @Test
    void acceptsFullNames() throws Exception {
        Roi[] rois = renamed("Cerebellum", "Granular+WM", "WhiteMatter", "Purkinje",
                "Fissure1", "Fissure2", "Fissure3", "Fissure4", "Fissure5", "Fissure6", "Fissure7");
        assertEquals(7, validSingle(rois).getFissures().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GL+WM", "GLWM", "GL_WM", "GL-WM", "GL WM", "gl+wm", "Granular", "granular layer + wm"})
    void granularWhiteMatterVariantsAreNeverMistakenForWhiteMatter(String name) throws Exception {
        LayerSet layers = validSingle(renamed("CB", name, "WM", "PL"));
        assertEquals(name, layers.getGranularWM().getName());
        assertEquals("WM", layers.getWhiteMatter().getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FL1", "fl_1", "FL-1", "fissure 1", "Fissure_1"})
    void fissureNameVariants(String name) throws Exception {
        Roi[] rois = section.rois();
        rois[4].setName(name);
        assertEquals(7, validSingle(rois).getFissures().size());
    }

    @Test
    void wordsThatMerelyContainAnAbbreviationAreIgnored() throws Exception {
        List<Roi> rois = list(section.rois());
        Roi flat = (Roi) section.cerebellum().clone();
        flat.setName("flat region");
        rois.add(flat);
        Diagnostics diag = Diagnostics.silent();
        ROIValidator.validate(rois.toArray(new Roi[0]), imp, diag);
        assertTrue(diag.getEntries().stream().anyMatch(e -> e.getMessage().contains("flat region")),
                "ignored names should be reported in a note");
    }

    @Test
    void leadingNumberSelectsAnInstance() throws Exception {
        List<Roi> rois = list(section.rois());
        rois.add(renamedCopy(section.cerebellum(), "2CB"));
        rois.add(renamedCopy(section.granularWM(), "2GL+WM"));
        rois.add(renamedCopy(section.purkinje(), "2PL"));
        SortedMap<Integer, LayerSet> result = ROIValidator.validate(rois.toArray(new Roi[0]), imp, Diagnostics.silent());
        assertEquals(List.of(1, 2), new ArrayList<>(result.keySet()));
        assertNull(result.get(2).getWhiteMatter(), "White Matter is optional for a secondary instance");
        assertTrue(result.get(2).getFissures().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260715_CB notes", "0512-1024", "123456789012345 cb"})
    void longOrDefaultNumericNamesDoNotCrashOrCreateInstances(String name) throws Exception {
        List<Roi> rois = list(section.rois());
        rois.add(renamedCopy(section.cerebellum(), name));
        // v1.0.0 threw NumberFormatException for leading digit runs longer than an int.
        assertEquals(1, ROIValidator.validate(rois.toArray(new Roi[0]), imp, Diagnostics.silent()).size());
    }

    @Test
    void straightLineFissuresAreAccepted() throws Exception {
        List<Roi> rois = list(section.rois());
        rois.removeIf(r -> "FL7".equals(r.getName()));
        Line straight = new Line(600, 100, 600, 340);
        straight.setName("FL7");
        rois.add(straight);

        LayerSet layers = validSingle(rois.toArray(new Roi[0]));
        PolygonRoi converted = layers.getFissures().get(6);
        assertEquals(Roi.POLYLINE, converted.getType());
        assertEquals(2, converted.getNCoordinates());
        assertEquals("FL7", converted.getName());
    }

    @Test
    void reportsEveryProblemAtOnce() {
        Roi[] rois = {renamedCopy(section.cerebellum(), "CB"), renamedCopy(section.cerebellum(), "Cerebellum"),
                renamedCopy(section.whiteMatter(), "WM")};
        ValidationException e = assertThrows(ValidationException.class,
                () -> ROIValidator.validate(rois, imp, Diagnostics.silent()));
        String msg = e.getMessage();
        assertTrue(msg.contains("More than one ROI matches Cerebellum"), msg);
        assertTrue(msg.contains("Missing the Granular+WM ROI"), msg);
        assertTrue(msg.contains("Missing the Purkinje ROI"), msg);
        assertTrue(msg.contains("No fissure ROIs found"), msg);
    }

    @Test
    void closedPurkinjeIsRejected() {
        Roi[] rois = section.rois();
        rois[3] = renamedCopy(section.cerebellum(), "PL");
        ValidationException e = assertThrows(ValidationException.class,
                () -> ROIValidator.validate(rois, imp, Diagnostics.silent()));
        assertTrue(e.getMessage().contains("is a closed area, not a line"), e.getMessage());
    }

    @Test
    void whiteMatterIsOptionalAndThenFissuresAreIgnored() throws Exception {
        List<Roi> rois = list(section.rois());
        rois.removeIf(r -> "WM".equals(r.getName()));
        Diagnostics diag = Diagnostics.silent();
        LayerSet layers = ROIValidator.validate(rois.toArray(new Roi[0]), imp, diag).get(1);
        assertNull(layers.getWhiteMatter());
        assertTrue(layers.getFissures().isEmpty());
        assertTrue(diag.getEntries().stream().anyMatch(e -> e.getMessage().contains("fissure ROI(s) found but ignored")));
    }

    @Test
    void emptyInputIsRejected() {
        assertThrows(ValidationException.class, () -> ROIValidator.validate(new Roi[0], imp, Diagnostics.silent()));
    }

    // -----------------------------------------------------------------------
    // Re-running on a ROI Manager that already holds this plugin's output
    // -----------------------------------------------------------------------

    @Test
    void markedPluginOutputIsSkipped() throws Exception {
        List<Roi> rois = list(section.rois());
        for (String name : new String[] {"Grey Matter", "Granular Layer", "Molecular Layer",
                "2Cb_Granular", "4/5Cb_Molecular", "10Cb_Purkinje"}) {
            rois.add(PluginOutput.mark(renamedCopy(section.cerebellum(), name)));
        }
        // In v1.0.0 this failed with 7 problems ("More than one ROI matches Granular+WM",
        // spurious instances 2, 4 and 10, ...).
        LayerSet layers = validSingle(rois.toArray(new Roi[0]));
        assertEquals("GL+WM", layers.getGranularWM().getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2Cb_Granular", "4/5Cb_Molecular", "10Cb_Purkinje", "Section 3_Granular",
            "[2] Grey Matter", "[2] 2Cb_Granular"})
    void untaggedOutputNamesFromVersion1AreSkipped(String name) throws Exception {
        List<Roi> rois = list(section.rois());
        rois.add(renamedCopy(section.cerebellum(), name));
        validSingle(rois.toArray(new Roi[0]));
    }

    @Test
    void untaggedWholeLayerOutputGetsAHelpfulDuplicateMessage() {
        List<Roi> rois = list(section.rois());
        rois.add(renamedCopy(section.cerebellum(), "Granular Layer"));
        ValidationException e = assertThrows(ValidationException.class,
                () -> ROIValidator.validate(rois.toArray(new Roi[0]), imp, Diagnostics.silent()));
        assertTrue(e.getMessage().contains("earlier run of this plugin"), e.getMessage());
    }

    @Test
    void validationUsesTheInputRoisWithoutModifyingThem() throws Exception {
        Roi[] rois = section.rois();
        LayerSet layers = validSingle(rois);
        assertSame(rois[0], layers.getCerebellum());
        for (Roi r : rois) {
            assertNull(r.getProperty(PluginOutput.PROPERTY));
        }
    }

    private static Roi renamedCopy(Roi roi, String name) {
        Roi copy = (Roi) roi.clone();
        copy.setName(name);
        return copy;
    }
}
