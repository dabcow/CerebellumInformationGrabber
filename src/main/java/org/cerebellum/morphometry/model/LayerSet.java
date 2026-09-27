package org.cerebellum.morphometry.model;

import ij.gui.PolygonRoi;
import ij.gui.Roi;

import java.util.Collections;
import java.util.List;

/**
 * The five categories of user-supplied ROI, after {@link org.cerebellum.morphometry.geometry.ROIValidator}
 * has confirmed they exist, have the right ROI type, and are correctly nested.
 *
 * <p>{@link #fissures} are stored in anatomical order &mdash; sorted from the end of the
 * Purkinje line nearest the first subsection to the end nearest the last &mdash; which is
 * what lets {@link org.cerebellum.morphometry.geometry.FissurePartitioner} build the
 * subsections without needing any left/right or compass-direction assumptions.</p>
 *
 * <p>{@link #getWhiteMatter()} may be {@code null} and {@link #fissures} may be empty for any
 * instance traced without a White Matter ROI (see {@code ROIValidator}'s "White Matter and
 * fissures are optional" section) &mdash; downstream code already handles both: {@link
 * org.cerebellum.morphometry.geometry.BooleanROIProcessor#copy} treats
 * a null Roi as an empty shape, so Grey/Granular/Molecular degrade gracefully, and {@link
 * org.cerebellum.morphometry.measurement.MeasurementEngine} skips partitioning entirely when
 * there are no fissures rather than calling {@code FissurePartitioner} (which requires at
 * least one).</p>
 */
public final class LayerSet {

    private final Roi cerebellum;
    private final Roi granularWM;
    private final Roi whiteMatter;
    private final PolygonRoi purkinje;
    private final List<PolygonRoi> fissures; // size 7, anatomically ordered

    public LayerSet(Roi cerebellum, Roi granularWM, Roi whiteMatter,
                     PolygonRoi purkinje, List<PolygonRoi> fissures) {
        this.cerebellum = cerebellum;
        this.granularWM = granularWM;
        this.whiteMatter = whiteMatter;
        this.purkinje = purkinje;
        this.fissures = Collections.unmodifiableList(fissures);
    }

    public Roi getCerebellum() {
        return cerebellum;
    }

    public Roi getGranularWM() {
        return granularWM;
    }

    public Roi getWhiteMatter() {
        return whiteMatter;
    }

    public PolygonRoi getPurkinje() {
        return purkinje;
    }

    /** Fissure polylines (possibly empty — see class javadoc), ordered along the Purkinje line. */
    public List<PolygonRoi> getFissures() {
        return fissures;
    }
}
