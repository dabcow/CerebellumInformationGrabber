package org.cerebellum.morphometry.model;

import ij.gui.PolygonRoi;
import ij.gui.Roi;

import java.util.Collections;
import java.util.List;

/**
 * The five categories of user-supplied ROI for one instance, after {@link
 * org.cerebellum.morphometry.geometry.ROIValidator} has confirmed they exist and have the right
 * ROI type.
 *
 * <p>{@link #getFissures()} are in ROI Manager order; {@link
 * org.cerebellum.morphometry.geometry.FissurePartitioner} sorts them along the Purkinje line
 * itself, so no left/right or compass-direction assumptions are needed.</p>
 *
 * <p>{@link #getWhiteMatter()} may be {@code null}, and {@link #getFissures()} is then empty (see
 * {@code ROIValidator}'s "White Matter and fissures are optional" section). Downstream code handles
 * both: {@link org.cerebellum.morphometry.geometry.BooleanROIProcessor#copy} treats a null Roi as an
 * empty shape, so Grey/Granular/Molecular degrade gracefully, and {@link
 * org.cerebellum.morphometry.measurement.MeasurementEngine} skips partitioning when there are no
 * fissures.</p>
 */
public final class LayerSet {

    private final Roi cerebellum;
    private final Roi granularWM;
    private final Roi whiteMatter;
    private final PolygonRoi purkinje;
    private final List<PolygonRoi> fissures;

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

    /** Fissure polylines (possibly empty — see class javadoc), in ROI Manager order. */
    public List<PolygonRoi> getFissures() {
        return fissures;
    }
}
