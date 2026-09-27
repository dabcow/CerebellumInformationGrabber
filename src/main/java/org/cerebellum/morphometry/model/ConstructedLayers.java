package org.cerebellum.morphometry.model;

import ij.gui.ShapeRoi;

/**
 * Output of {@link org.cerebellum.morphometry.geometry.LayerConstructor}: the three
 * layers built from the raw input ROIs via Boolean (NOT) operations, covering the
 * <em>whole</em> cerebellum (not yet split into per-lobule subsections).
 *
 * <pre>
 *   grey     = cerebellum  NOT  whiteMatter
 *   granular = granularWM  NOT  whiteMatter
 *   molecular= grey        NOT  granular
 * </pre>
 */
public final class ConstructedLayers {

    private final ShapeRoi grey;
    private final ShapeRoi granular;
    private final ShapeRoi molecular;

    public ConstructedLayers(ShapeRoi grey, ShapeRoi granular, ShapeRoi molecular) {
        this.grey = grey;
        this.granular = granular;
        this.molecular = molecular;
    }

    public ShapeRoi getGrey() {
        return grey;
    }

    public ShapeRoi getGranular() {
        return granular;
    }

    public ShapeRoi getMolecular() {
        return molecular;
    }
}
