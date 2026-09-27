package org.cerebellum.morphometry.model;

import ij.gui.PolygonRoi;
import ij.gui.ShapeRoi;

import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.List;

/**
 * Output of {@link org.cerebellum.morphometry.geometry.FissurePartitioner}: the grey matter cut
 * into lobule-sized subsections by the traced fissure lines, plus the (extended) cutting lines
 * themselves so {@link org.cerebellum.morphometry.visualization.OverlayRenderer} can draw the
 * partition boundaries.
 *
 * <p>The subsections tile the grey matter: every grey-matter pixel belongs to exactly one of
 * them, so per-subsection areas add up to the whole-cerebellum totals.</p>
 */
public final class PartitionSet {

    /** One fissure-bounded subsection (e.g. "2Cb", or "Section 3" for a non-standard count). */
    public static final class Partition {
        private final String label;
        private final ShapeRoi region;
        private final Point2D.Double labelAnchor;

        public Partition(String label, ShapeRoi region, Point2D.Double labelAnchor) {
            this.label = label;
            this.region = region;
            this.labelAnchor = labelAnchor;
        }

        public String getLabel() {
            return label;
        }

        /** This subsection's footprint within the grey matter, in image coordinates. */
        public ShapeRoi getRegion() {
            return region;
        }

        /** A point guaranteed to lie inside {@link #getRegion()}, for placing a text label. */
        public Point2D.Double getLabelAnchor() {
            return labelAnchor;
        }
    }

    private static final PartitionSet EMPTY =
            new PartitionSet(Collections.emptyList(), Collections.emptyList(), false, false);

    private final List<Partition> partitions;
    private final List<PolygonRoi> cutLines;
    private final boolean ring;
    private final boolean endsJoined;

    public PartitionSet(List<Partition> partitions, List<PolygonRoi> cutLines, boolean ring, boolean endsJoined) {
        this.partitions = Collections.unmodifiableList(partitions);
        this.cutLines = Collections.unmodifiableList(cutLines);
        this.ring = ring;
        this.endsJoined = endsJoined;
    }

    /** No subsections and no cut lines: used for an instance that isn't partitioned at all. */
    public static PartitionSet empty() {
        return EMPTY;
    }

    /** One per resolved subsection, in anatomical (Purkinje-line) order. */
    public List<Partition> getPartitions() {
        return partitions;
    }

    /** Each fissure's spanning cut (the traced line, extended to cross the whole grey matter). */
    public List<PolygonRoi> getCutLines() {
        return cutLines;
    }

    /** True if the grey matter was a closed ring (not pinched open at the peduncle). */
    public boolean isRing() {
        return ring;
    }

    /** True if the first and last lobules ended up in the same subsection. */
    public boolean areEndsJoined() {
        return endsJoined;
    }
}
