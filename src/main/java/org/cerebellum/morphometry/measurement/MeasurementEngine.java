package org.cerebellum.morphometry.measurement;

import ij.ImagePlus;
import ij.measure.Calibration;
import org.cerebellum.morphometry.geometry.*;
import org.cerebellum.morphometry.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Ties together every geometry class and produces the complete {@link MorphometryResults}
 * object. This is the single call the plugin controller makes after validation passes.
 *
 * <p>Execution order:
 * <ol>
 *   <li>{@link LayerConstructor} &rarr; Grey, Granular, Molecular shapes for the whole cerebellum.</li>
 *   <li>{@link FissurePartitioner} &rarr; N+1 lobule regions (from N traced fissures) plus their Purkinje arc-length bounds.</li>
 *   <li>{@link PartitionClipper} &rarr; per-partition Granular and Molecular clips.</li>
 *   <li>Area and length measurement using {@link BooleanROIProcessor} and
 *       {@link PurkinjeLengthCalculator}.</li>
 * </ol>
 * </p>
 */
public final class MeasurementEngine {

    private MeasurementEngine() {
    }

    /**
     * Full pipeline: geometry construction &rarr; subdivision &rarr; measurement.
     *
     * @param layers the validated, typed ROI bundle
     * @param imp    the active image (needed for calibrated area; pixel content is never read)
     * @return all numbers needed to populate the output table
     */
    public static MorphometryResults measure(LayerSet layers, ImagePlus imp) {

        // Step 1: build the three whole-cerebellum layers.
        ConstructedLayers constructed = LayerConstructor.construct(layers);

        // Step 2: cut into lobule partitions (N+1 from N traced fissures) — or skip entirely
        // if there are no fissures at all (see partitionOrEmpty: this happens for secondary
        // instances traced without White Matter, where partitioning isn't attempted).
        PartitionSet partitionSet = partitionOrEmpty(layers);

        // Step 3: clip each partition's granular and molecular shapes.
        List<PartitionClipper.ClippedPartition> clipped =
                PartitionClipper.clip(constructed, partitionSet);

        // Step 4: whole-cerebellum measurements.
        double cerebellumArea  = BooleanROIProcessor.area(layers.getCerebellum(), imp);
        double greyMatterArea  = BooleanROIProcessor.area(constructed.getGrey(), imp);
        double granularArea    = BooleanROIProcessor.area(constructed.getGranular(), imp);
        double molecularArea   = BooleanROIProcessor.area(constructed.getMolecular(), imp);
        double totalPurkinje   = PurkinjeLengthCalculator.totalLength(layers.getPurkinje(), imp);
        double totalPurkinjeArea = PurkinjeLengthCalculator.totalArea(layers.getPurkinje(), imp);

        // Step 5: per-subsection measurements.
        List<MorphometryResults.SubsectionResult> subsections = new ArrayList<>(clipped.size());
        List<PartitionSet.Partition> partitions = partitionSet.getPartitions();
        for (int i = 0; i < clipped.size(); i++) {
            PartitionClipper.ClippedPartition cp = clipped.get(i);
            PartitionSet.Partition p = partitions.get(i);

            double subGranular  = BooleanROIProcessor.area(cp.granular, imp);
            double subMolecular = BooleanROIProcessor.area(cp.molecular, imp);
            // Measured by containment, not arc range: a ring-shaped layer has one section that
            // wraps past the end of the Purkinje line and resumes at its start, owning two
            // disjoint stretches of it. See PurkinjeLengthCalculator.lengthInside.
            double subPurkinje  = PurkinjeLengthCalculator.lengthInside(
                    layers.getPurkinje(), imp, p.getRegion());

            subsections.add(new MorphometryResults.SubsectionResult(
                    cp.label, subGranular, subMolecular, subPurkinje));
        }

        // Determine unit strings from the image calibration.
        Calibration cal = imp.getCalibration();
        String unit      = (cal != null && cal.getUnit() != null) ? cal.getUnit() : "px";
        String areaUnit  = unit + "\u00B2"; // e.g. "µm²"
        String lengthUnit = unit;

        return new MorphometryResults(
                cerebellumArea, greyMatterArea, granularArea, molecularArea, totalPurkinje, totalPurkinjeArea,
                subsections, areaUnit, lengthUnit);
    }

    /**
     * Exposes the intermediate geometry for the overlay renderer without re-running the
     * full measurement pipeline. Returns an object carrying constructed layers,
     * partitions, and clips so {@link org.cerebellum.morphometry.visualization.OverlayRenderer}
     * can use them directly.
     */
    public static IntermediateGeometry buildGeometry(LayerSet layers) {
        ConstructedLayers constructed = LayerConstructor.construct(layers);
        PartitionSet partitionSet     = partitionOrEmpty(layers);
        List<PartitionClipper.ClippedPartition> clipped =
                PartitionClipper.clip(constructed, partitionSet);
        return new IntermediateGeometry(constructed, partitionSet, clipped);
    }

    /**
     * Runs {@link FissurePartitioner#partition}, or returns an empty {@link PartitionSet}
     * (no subsections, no cut lines) without calling it at all when there are no fissures —
     * which {@code FissurePartitioner} itself doesn't accept (it requires at least one).
     * Zero fissures is a legitimate state for any instance traced without White Matter (see
     * {@link ROIValidator}): a section with no white-matter core isn't split into lobules, so
     * partitioning isn't attempted. It's also legitimate for a secondary instance traced only
     * for its overall extent.
     */
    private static PartitionSet partitionOrEmpty(LayerSet layers) {
        if (layers.getFissures().isEmpty()) {
            return new PartitionSet(java.util.Collections.emptyList(), java.util.Collections.emptyList());
        }
        return FissurePartitioner.partition(layers);
    }

    /**
     * Pools several separately-traced pieces of <b>one</b> cerebellum into a single
     * {@link MorphometryResults}, as if they had been traced as one object.
     *
     * <p>This is the normal case for multi-instance input (see {@link ROIValidator}'s
     * "Multiple instances" section): the pieces aren't different specimens, they're parts of
     * the same cerebellum that couldn't be outlined as one connected shape (broken during
     * sectioning, disconnected islands in the cut plane, etc.). So:</p>
     * <ul>
     *   <li><b>Whole-cerebellum totals are summed</b> across every piece — one Cerebellum
     *       area, one Grey Matter area, one Purkinje length, and so on, covering all of them
     *       together.</li>
     *   <li><b>Subsections are concatenated</b> in instance order. A piece that was split by
     *       fissures contributes each of its own subsections; a piece traced without fissures
     *       contributes exactly one subsection — itself — since it is anatomically one more
     *       section of the same cerebellum, just one that had to be outlined separately.</li>
     *   <li><b>Labels are re-assigned across the pooled list</b>, so an 8-subsection total
     *       gets the standard anatomical names (2Cb … 10Cb) regardless of how the sections
     *       were distributed across pieces. Sections are ordered by instance number first,
     *       then by arc order within each piece — so name the pieces in anatomical order
     *       (1, 2, 3, …) to get the labels lined up correctly.</li>
     * </ul>
     */
    public static MorphometryResults combine(List<InstanceResult> instances) {
        if (instances.isEmpty()) {
            throw new IllegalArgumentException("combine() needs at least one instance");
        }
        if (instances.size() == 1) {
            return instances.get(0).getResults();
        }

        double cerebellumArea = 0, greyMatterArea = 0, granularArea = 0, molecularArea = 0;
        double purkinjeLength = 0, purkinjeArea = 0;

        // Collect each piece's sections, in instance order. A piece with no fissures counts as
        // one section in its own right (its whole-cerebellum numbers ARE that section's numbers).
        List<MorphometryResults.SubsectionResult> pooled = new ArrayList<>();
        for (InstanceResult inst : instances) {
            MorphometryResults r = inst.getResults();
            cerebellumArea += r.getCerebellumArea();
            greyMatterArea += r.getGreyMatterArea();
            granularArea   += r.getGranularLayerArea();
            molecularArea  += r.getMolecularLayerArea();
            purkinjeLength += r.getTotalPurkinjeLength();
            purkinjeArea   += r.getTotalPurkinjeArea();

            if (r.getSubsections().isEmpty()) {
                pooled.add(new MorphometryResults.SubsectionResult(
                        "", r.getGranularLayerArea(), r.getMolecularLayerArea(), r.getTotalPurkinjeLength()));
            } else {
                pooled.addAll(r.getSubsections());
            }
        }

        // Re-label across the pooled list, so the standard names apply when the pooled count
        // matches the standard scheme, no matter how the sections were split across pieces.
        String[] labels = FissurePartitioner.labelsForSubsectionCount(pooled.size());
        List<MorphometryResults.SubsectionResult> relabeled = new ArrayList<>(pooled.size());
        for (int i = 0; i < pooled.size(); i++) {
            MorphometryResults.SubsectionResult s = pooled.get(i);
            relabeled.add(new MorphometryResults.SubsectionResult(
                    labels[i], s.getGranularArea(), s.getMolecularArea(), s.getPurkinjeLength()));
        }

        MorphometryResults first = instances.get(0).getResults();
        return new MorphometryResults(
                cerebellumArea, greyMatterArea, granularArea, molecularArea,
                purkinjeLength, purkinjeArea,
                relabeled, first.getAreaUnit(), first.getLengthUnit());
    }

    /** Carries the intermediate geometry objects so they can be passed to the overlay renderer. */
    public static final class IntermediateGeometry {
        public final ConstructedLayers constructed;
        public final PartitionSet partitionSet;
        public final List<PartitionClipper.ClippedPartition> clipped;

        public IntermediateGeometry(ConstructedLayers constructed, PartitionSet partitionSet,
                                     List<PartitionClipper.ClippedPartition> clipped) {
            this.constructed  = constructed;
            this.partitionSet = partitionSet;
            this.clipped      = clipped;
        }
    }
}
