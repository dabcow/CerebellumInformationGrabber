package org.cerebellum.morphometry.geometry;

import ij.gui.ShapeRoi;
import org.cerebellum.morphometry.model.ConstructedLayers;
import org.cerebellum.morphometry.model.LayerSet;

/**
 * Implements the three Boolean constructions described in the spec:
 *
 * <pre>
 *   Grey      = Cerebellum  NOT  WhiteMatter
 *   Granular  = GranularWM  NOT  WhiteMatter
 *   Molecular = Grey        NOT  Granular
 * </pre>
 */
public final class LayerConstructor {

    private LayerConstructor() {
    }

    public static ConstructedLayers construct(LayerSet layers) {
        ShapeRoi grey = BooleanROIProcessor.subtract(layers.getCerebellum(), layers.getWhiteMatter());
        ShapeRoi granular = BooleanROIProcessor.subtract(layers.getGranularWM(), layers.getWhiteMatter());
        ShapeRoi molecular = BooleanROIProcessor.subtract(grey, granular);
        return new ConstructedLayers(grey, granular, molecular);
    }
}
