package org.cerebellum.morphometry.measurement;

import ij.ImagePlus;
import ij.gui.Roi;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.geometry.ROIValidator;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.MorphometryResults.SubsectionResult;
import org.cerebellum.morphometry.model.PartitionSet;
import org.cerebellum.morphometry.testing.SyntheticSection;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the geometry pipeline against {@link SyntheticSection}, whose answers are
 * known in closed form.
 */
class MeasurementEngineTest {

    private static final List<String> STANDARD = List.of("2Cb", "3Cb", "4/5Cb", "6Cb", "7Cb", "8Cb", "9Cb", "10Cb");

    /** Result of running the whole pipeline on one phantom. */
    private static final class Run {
        final SyntheticSection section;
        final InstanceResult instance;
        final MorphometryResults results;
        final Diagnostics diag;

        Run(SyntheticSection section) throws Exception {
            this.section = section;
            this.diag = Diagnostics.silent();
            ImagePlus imp = section.image();
            SortedMap<Integer, LayerSet> instances = ROIValidator.validate(section.rois(), imp, diag);
            List<InstanceResult> all = MeasurementEngine.analyze(instances, imp, diag);
            this.instance = all.get(0);
            this.results = MeasurementEngine.combine(all);
        }

        List<String> labels() {
            List<String> out = new ArrayList<>();
            for (SubsectionResult s : results.getSubsections()) {
                out.add(s.getLabel());
            }
            return out;
        }

        double sum(java.util.function.ToDoubleFunction<SubsectionResult> f) {
            return results.getSubsections().stream().mapToDouble(f).sum();
        }

        PartitionSet partitions() {
            return instance.getGeometry().partitionSet;
        }
    }

    private static void assertClose(double expected, double actual, double relTol, String what) {
        double rel = Math.abs(actual - expected) / expected;
        assertTrue(rel <= relTol, String.format("%s: expected %.2f, got %.2f (%.2f%% off, tolerance %.2f%%)",
                what, expected, actual, 100 * rel, 100 * relTol));
    }

    // -----------------------------------------------------------------------
    // Standard case: 7 fissures, grey matter pinched open at the peduncle
    // -----------------------------------------------------------------------

    @Test
    void pinchedSectionGivesEightStandardLobulesInOrder() throws Exception {
        Run run = new Run(SyntheticSection.builder().build());
        assertEquals(STANDARD, run.labels());
        assertFalse(run.partitions().isRing());
        assertFalse(run.partitions().areEndsJoined());
        assertFalse(run.diag.hasWarnings(), () -> "unexpected warnings: " + run.diag.getWarnings());
    }

    @Test
    void sectionsAreOrderedAlongThePurkinjeLine() throws Exception {
        Run run = new Run(SyntheticSection.builder().build());
        double previous = -Double.MAX_VALUE;
        for (PartitionSet.Partition p : run.partitions().getPartitions()) {
            Point2D.Double a = p.getLabelAnchor();
            double deg = Math.toDegrees(Math.atan2(a.y - SyntheticSection.CY, a.x - SyntheticSection.CX));
            // The Purkinje line runs clockwise from 120° to 420°; unwrap into that range.
            while (deg < SyntheticSection.PURKINJE_START_DEG - 30) {
                deg += 360;
            }
            assertTrue(deg > previous, "section " + p.getLabel() + " is out of order");
            previous = deg;
        }
    }

    @Test
    void sectionsAddUpToTheWholeLayers() throws Exception {
        // v1.0.0 lost the cutting strips: sections summed to ~95% (granular) and ~97% (molecular)
        // of the totals on this phantom, and ~96% of the Purkinje length.
        Run run = new Run(SyntheticSection.builder().build());
        MorphometryResults r = run.results;
        assertClose(r.getGreyMatterArea(), run.sum(s -> s.getGranularArea() + s.getMolecularArea()), 0.002, "grey matter");
        assertClose(r.getMolecularLayerArea(), run.sum(SubsectionResult::getMolecularArea), 0.002, "molecular");
        assertClose(r.getTotalPurkinjeLength(), run.sum(SubsectionResult::getPurkinjeLength), 0.002, "Purkinje");
    }

    @Test
    void interiorSectionsMatchClosedFormValues() throws Exception {
        // v1.0.0: granular -6%, molecular -4%, Purkinje -4..-7% on these same sections.
        SyntheticSection s = SyntheticSection.builder().build();
        Run run = new Run(s);
        double[] f = s.fissureAnglesDeg();
        for (int k = 0; k + 1 < f.length; k++) {
            SubsectionResult sub = run.results.getSubsections().get(k + 1);
            assertClose(s.granularSectorArea(f[k], f[k + 1]), sub.getGranularArea(), 0.01, sub.getLabel() + " granular");
            assertClose(s.molecularSectorArea(f[k], f[k + 1]), sub.getMolecularArea(), 0.01, sub.getLabel() + " molecular");
            assertClose(s.purkinjeLength(f[k], f[k + 1]), sub.getPurkinjeLength(), 0.01, sub.getLabel() + " Purkinje");
        }
    }

    @Test
    void coarselyTracedPurkinjeLineIsClippedAccurately() throws Exception {
        // A Segmented Line trace with ~73 px segments. v1.0.0 credited whole segments by their
        // midpoint and got every interior section 20% short on this phantom.
        SyntheticSection s = SyntheticSection.builder().purkinjeStepDeg(15).build();
        Run run = new Run(s);
        double[] f = s.fissureAnglesDeg();
        for (int k = 0; k + 1 < f.length; k++) {
            SubsectionResult sub = run.results.getSubsections().get(k + 1);
            assertClose(s.purkinjeLength(f[k], f[k + 1]), sub.getPurkinjeLength(), 0.01, sub.getLabel() + " Purkinje");
        }
        assertClose(s.totalPurkinjeLength(), run.results.getTotalPurkinjeLength(), 1e-6, "total Purkinje");
    }

    @Test
    void fissuresTracedOnlyToThePurkinjeLineAreExtended() throws Exception {
        SyntheticSection s = SyntheticSection.builder()
                .fissureRadii(SyntheticSection.R_CEREBELLUM - 4, SyntheticSection.R_PURKINJE - 5).build();
        Run run = new Run(s);
        assertEquals(STANDARD, run.labels());
        assertClose(run.results.getGreyMatterArea(),
                run.sum(x -> x.getGranularArea() + x.getMolecularArea()), 0.002, "grey matter");
    }

    @Test
    void calibrationIsApplied() throws Exception {
        Run px = new Run(SyntheticSection.builder().build());
        Run um = new Run(SyntheticSection.builder().calibration(0.5, "um").build());
        assertEquals("µm²", um.results.getAreaUnit());
        assertEquals("µm", um.results.getLengthUnit());
        assertEquals(px.results.getCerebellumArea() * 0.25, um.results.getCerebellumArea(), 1e-6);
        assertEquals(px.results.getTotalPurkinjeLength() * 0.5, um.results.getTotalPurkinjeLength(), 1e-6);
        SubsectionResult a = px.results.getSubsections().get(3);
        SubsectionResult b = um.results.getSubsections().get(3);
        assertEquals(a.getGranularArea() * 0.25, b.getGranularArea(), 1e-6);
        assertEquals(a.getPurkinjeLength() * 0.5, b.getPurkinjeLength(), 1e-6);
    }

    // -----------------------------------------------------------------------
    // Closed ring: first and last lobules joined
    // -----------------------------------------------------------------------

    @Test
    void closedRingGivesNSectionsWithGenericLabels() throws Exception {
        Run run = new Run(SyntheticSection.builder().pinched(false).build());
        assertEquals(7, run.results.getSubsections().size());
        assertEquals("Section 1", run.labels().get(0));
        assertTrue(run.partitions().isRing());
        assertTrue(run.partitions().areEndsJoined());
        assertTrue(run.diag.getEntries().stream().anyMatch(e -> e.getMessage().contains("closed ring")));
    }

    @Test
    void standardNamesAreWithheldWhenTheEndsAreJoined() throws Exception {
        // Eight sections, but one of them is 2Cb and 10Cb fused: v1.0.0 still named them 2Cb…10Cb.
        Run run = new Run(SyntheticSection.builder().pinched(false).fissures(8).build());
        assertEquals(8, run.results.getSubsections().size());
        assertEquals("Section 1", run.labels().get(0));
        assertTrue(run.diag.getWarnings().stream().anyMatch(w -> w.contains("first and last lobules are still joined")));
    }

    // -----------------------------------------------------------------------
    // Several traced pieces pooled into one cerebellum
    // -----------------------------------------------------------------------

    @Test
    void separatelyTracedPiecesArePooled() throws Exception {
        SyntheticSection s = SyntheticSection.builder().build();
        ImagePlus imp = s.image();
        List<Roi> rois = new ArrayList<>(Arrays.asList(s.rois()));
        // A second piece, traced without White Matter or fissures: it contributes one section.
        for (Roi r : new Roi[] {s.cerebellum(), s.granularWM(), s.purkinje()}) {
            r.setName("2" + r.getName());
            rois.add(r);
        }
        Diagnostics diag = Diagnostics.silent();
        SortedMap<Integer, LayerSet> instances = ROIValidator.validate(rois.toArray(new Roi[0]), imp, diag);
        List<InstanceResult> all = MeasurementEngine.analyze(instances, imp, diag);
        MorphometryResults pooled = MeasurementEngine.combine(all);

        MorphometryResults one = all.get(0).getResults();
        MorphometryResults two = all.get(1).getResults();
        assertEquals(one.getCerebellumArea() + two.getCerebellumArea(), pooled.getCerebellumArea(), 1e-6);
        assertEquals(one.getTotalPurkinjeLength() + two.getTotalPurkinjeLength(), pooled.getTotalPurkinjeLength(), 1e-6);
        assertEquals(9, pooled.getSubsections().size());
        assertEquals("Section 9", pooled.getSubsections().get(8).getLabel());
        assertEquals(two.getGranularLayerArea(), pooled.getSubsections().get(8).getGranularArea(), 1e-6);
        assertTrue(diag.getEntries().stream().anyMatch(e -> e.getMessage().startsWith("[Instance 2] ")),
                "messages from the second piece should be prefixed with its number");
    }
}
