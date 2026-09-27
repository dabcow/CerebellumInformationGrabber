package org.cerebellum.morphometry.geometry;

import ij.gui.ShapeRoi;
import org.cerebellum.morphometry.model.ConstructedLayers;
import org.cerebellum.morphometry.model.PartitionSet;

import java.util.ArrayList;
import java.util.List;

/**
 * For each fissure-defined partition, clips the whole-cerebellum granular and molecular
 * layers down to that partition's footprint, via {@code AND}. Purkinje length is handled
 * separately by {@link PurkinjeLengthCalculator}, since (as noted on {@link PartitionSet})
 * it falls straight out of the arc-length bounds already stored on each partition.
 */
public final class PartitionClipper {

    private PartitionClipper() {
    }

    /** One partition's clipped granular and molecular shapes, in the same order as the input partitions. */
    public static final class ClippedPartition {
        public final String label;
        public final ShapeRoi granular;
        public final ShapeRoi molecular;

        public ClippedPartition(String label, ShapeRoi granular, ShapeRoi molecular) {
            this.label = label;
            this.granular = granular;
            this.molecular = molecular;
        }
    }

    public static List<ClippedPartition> clip(ConstructedLayers layers, PartitionSet partitionSet) {
        List<ClippedPartition> result = new ArrayList<>(partitionSet.getPartitions().size());
        for (PartitionSet.Partition p : partitionSet.getPartitions()) {
            ShapeRoi granularSection = BooleanROIProcessor.and(layers.getGranular(), p.getRegion());
            ShapeRoi molecularSection = BooleanROIProcessor.and(layers.getMolecular(), p.getRegion());
            result.add(new ClippedPartition(p.getLabel(), granularSection, molecularSection));
        }
        return result;
    }
}
