package org.cerebellum.morphometry.measurement;

import ij.ImagePlus;
import ij.measure.Calibration;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.geometry.BooleanROIProcessor;
import org.cerebellum.morphometry.geometry.FissurePartitioner;
import org.cerebellum.morphometry.geometry.LayerConstructor;
import org.cerebellum.morphometry.geometry.PartitionClipper;
import org.cerebellum.morphometry.geometry.PurkinjeLengthCalculator;
import org.cerebellum.morphometry.geometry.ROIValidator;
import org.cerebellum.morphometry.model.ConstructedLayers;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.PartitionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

/**
 * Ties together every geometry class and produces the {@link MorphometryResults}.
 *
 * <p>Execution order, per instance:</p>
 * <ol>
 *   <li>{@link LayerConstructor} &rarr; Grey, Granular, Molecular shapes for the whole cerebellum.</li>
 *   <li>{@link FissurePartitioner} &rarr; lobule regions that tile the grey matter.</li>
 *   <li>{@link PartitionClipper} &rarr; per-lobule Granular and Molecular clips.</li>
 *   <li>Area and length measurement using {@link BooleanROIProcessor} and
 *       {@link PurkinjeLengthCalculator}.</li>
 * </ol>
 *
 * <p>Steps 1&ndash;3 ({@link #buildGeometry}) are computed once and shared by the measurement,
 * the overlay and the ROI Manager export, so all three are guaranteed to describe the same
 * shapes.</p>
 */
public final class MeasurementEngine {

    /** Sections covering less of the grey matter than this trigger a warning. */
    private static final double MIN_SECTION_AREA_COVERAGE = 0.99;
    /** Sections holding less of the Purkinje line than this trigger a note. */
    private static final double MIN_SECTION_PURKINJE_COVERAGE = 0.99;

    private MeasurementEngine() {
    }

    /**
     * Validated instances in, one {@link InstanceResult} per instance out (in instance order).
     * Messages from each instance are prefixed with its number when there is more than one.
     */
    public static List<InstanceResult> analyze(SortedMap<Integer, LayerSet> instances, ImagePlus imp,
            Diagnostics diag) {
        boolean multi = instances.size() > 1;
        List<InstanceResult> results = new ArrayList<>(instances.size());
        for (Map.Entry<Integer, LayerSet> entry : instances.entrySet()) {
            int instance = entry.getKey();
            Diagnostics d = multi ? diag.withPrefix("[Instance " + instance + "] ") : diag;
            IntermediateGeometry geo = buildGeometry(entry.getValue(), d);
            MorphometryResults measured = measure(entry.getValue(), geo, imp, d);
            results.add(new InstanceResult(instance, entry.getValue(), geo, measured));
        }
        return results;
    }

    /**
     * Builds the whole-cerebellum layers, the lobule partition and the per-lobule clips for one
     * instance. No pixel content is read.
     */
    public static IntermediateGeometry buildGeometry(LayerSet layers, Diagnostics diag) {
        ConstructedLayers constructed = LayerConstructor.construct(layers);
        PartitionSet partitionSet = layers.getFissures().isEmpty()
                ? PartitionSet.empty()
                : FissurePartitioner.partition(layers, diag);
        List<PartitionClipper.ClippedPartition> clipped = PartitionClipper.clip(constructed, partitionSet);
        return new IntermediateGeometry(constructed, partitionSet, clipped);
    }

    /**
     * Measures one instance's already-built geometry.
     *
     * @param layers the validated input ROIs
     * @param geo    the geometry from {@link #buildGeometry} for the same {@code layers}
     * @param imp    the active image (needed for calibration; pixel content is never read)
     * @param diag   receives coverage warnings
     */
    public static MorphometryResults measure(LayerSet layers, IntermediateGeometry geo, ImagePlus imp,
            Diagnostics diag) {
        ConstructedLayers constructed = geo.constructed;

        double cerebellumArea    = BooleanROIProcessor.area(layers.getCerebellum(), imp);
        double greyMatterArea    = BooleanROIProcessor.area(constructed.getGrey(), imp);
        double granularArea      = BooleanROIProcessor.area(constructed.getGranular(), imp);
        double molecularArea     = BooleanROIProcessor.area(constructed.getMolecular(), imp);
        double totalPurkinje     = PurkinjeLengthCalculator.totalLength(layers.getPurkinje(), imp);
        double totalPurkinjeArea = PurkinjeLengthCalculator.totalArea(layers.getPurkinje(), imp);

        List<MorphometryResults.SubsectionResult> subsections = new ArrayList<>(geo.clipped.size());
        List<PartitionSet.Partition> partitions = geo.partitionSet.getPartitions();
        double sectionArea = 0;
        double sectionPurkinje = 0;
        for (int i = 0; i < geo.clipped.size(); i++) {
            PartitionClipper.ClippedPartition cp = geo.clipped.get(i);
            double subGranular  = BooleanROIProcessor.area(cp.granular, imp);
            double subMolecular = BooleanROIProcessor.area(cp.molecular, imp);
            double subPurkinje  = PurkinjeLengthCalculator.lengthInside(
                    layers.getPurkinje(), imp, partitions.get(i).getRegion());
            sectionArea += subGranular + subMolecular;
            sectionPurkinje += subPurkinje;
            subsections.add(new MorphometryResults.SubsectionResult(cp.label, subGranular, subMolecular, subPurkinje));
        }

        if (!subsections.isEmpty()) {
            if (greyMatterArea > 0 && sectionArea < greyMatterArea * MIN_SECTION_AREA_COVERAGE) {
                diag.warn(String.format(Locale.ROOT,
                        "The sections only cover %.1f%% of the grey matter area, so per-section areas will not "
                        + "add up to the totals. Check the overlay for grey matter left outside every section.",
                        100.0 * sectionArea / greyMatterArea));
            }
            if (totalPurkinje > 0 && sectionPurkinje < totalPurkinje * MIN_SECTION_PURKINJE_COVERAGE) {
                diag.note(String.format(Locale.ROOT,
                        "%.1f%% of the Purkinje line lies outside the grey matter, so it is counted in the "
                        + "total length but not in any section.",
                        100.0 * (totalPurkinje - sectionPurkinje) / totalPurkinje));
            }
        }

        Calibration cal = imp.getCalibration();
        String unit = (cal != null && cal.getUnit() != null) ? cal.getUnit() : "pixel";
        return new MorphometryResults(
                cerebellumArea, greyMatterArea, granularArea, molecularArea, totalPurkinje, totalPurkinjeArea,
                subsections, unit + "²", unit);
    }

    /**
     * Pools several separately-traced pieces of <b>one</b> cerebellum into a single
     * {@link MorphometryResults}, as if they had been traced as one object.
     *
     * <p>This is the normal case for multi-instance input (see {@link ROIValidator}'s
     * "Multiple instances" section): the pieces aren't different specimens, they're parts of
     * the same cerebellum that couldn't be outlined as one connected shape. So:</p>
     * <ul>
     *   <li><b>Whole-cerebellum totals are summed</b> across every piece.</li>
     *   <li><b>Subsections are concatenated</b> in instance order. A piece that was split by
     *       fissures contributes each of its own subsections; a piece traced without fissures
     *       contributes exactly one subsection &mdash; itself.</li>
     *   <li><b>Labels are re-assigned across the pooled list</b>, so an 8-subsection total gets
     *       the standard anatomical names regardless of how the sections were distributed across
     *       pieces &mdash; unless some piece's first and last lobules are joined, in which case
     *       those names would be wrong. Sections are ordered by instance number first, then along
     *       the Purkinje line within each piece.</li>
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
        boolean anyEndsJoined = false;

        List<MorphometryResults.SubsectionResult> pooled = new ArrayList<>();
        for (InstanceResult inst : instances) {
            MorphometryResults r = inst.getResults();
            cerebellumArea += r.getCerebellumArea();
            greyMatterArea += r.getGreyMatterArea();
            granularArea   += r.getGranularLayerArea();
            molecularArea  += r.getMolecularLayerArea();
            purkinjeLength += r.getTotalPurkinjeLength();
            purkinjeArea   += r.getTotalPurkinjeArea();
            if (inst.getGeometry() != null && inst.getGeometry().partitionSet.areEndsJoined()) {
                anyEndsJoined = true;
            }

            if (r.getSubsections().isEmpty()) {
                pooled.add(new MorphometryResults.SubsectionResult(
                        "", r.getGranularLayerArea(), r.getMolecularLayerArea(), r.getTotalPurkinjeLength()));
            } else {
                pooled.addAll(r.getSubsections());
            }
        }

        String[] labels = anyEndsJoined
                ? FissurePartitioner.genericLabels(pooled.size())
                : FissurePartitioner.labelsForSubsectionCount(pooled.size());
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

    /** The geometry built for one instance, shared by measurement, overlay and ROI export. */
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
