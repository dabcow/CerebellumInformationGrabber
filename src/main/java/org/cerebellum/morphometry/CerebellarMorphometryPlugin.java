package org.cerebellum.morphometry;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import org.cerebellum.morphometry.export.SpreadsheetExporter;
import org.cerebellum.morphometry.geometry.ROIValidator;
import org.cerebellum.morphometry.measurement.MeasurementEngine;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.ValidationException;
import org.cerebellum.morphometry.visualization.OverlayRenderer;
import org.cerebellum.morphometry.visualization.RoiManagerExporter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Main FIJI/ImageJ entry point for the Cerebellar Layer Quantification plugin.
 *
 * <p>Registered in {@code plugins.config} under
 * <em>Plugins &gt; Cerebellar Morphometry &gt; Quantify Layers...</em></p>
 *
 * <h2>Execution flow</h2>
 * <ol>
 *   <li><b>Options dialog</b> &mdash; ask about overlay, ROI export, and export preferences.</li>
 *   <li><b>Validation</b> &mdash; {@link ROIValidator} checks the ROI Manager and produces one
 *       {@link LayerSet} per detected instance (see its "Multiple instances" section); a
 *       combined error dialog is shown if anything is wrong.</li>
 *   <li><b>Geometry &amp; measurement</b> &mdash; {@link MeasurementEngine} runs the Boolean
 *       construction, partitioning, and calibrated measurements, once per instance.</li>
 *   <li><b>Overlay</b> &mdash; {@link OverlayRenderer} adds one combined colour-coded vector
 *       overlay (covering every instance) to the active image.</li>
 *   <li><b>ROI Manager export</b> &mdash; {@link RoiManagerExporter} adds a named ROI for
 *       every measured region in every instance, so each can be independently re-selected or
 *       re-exported.</li>
 *   <li><b>Results table</b> &mdash; every instance's results, displayed together in one table.</li>
 *   <li><b>File export</b> &mdash; CSV and/or XLSX written to user-chosen directory.</li>
 * </ol>
 */
public final class CerebellarMorphometryPlugin implements PlugIn {

    @Override
    public void run(String arg) {
        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.error("Cerebellar Morphometry",
                    "No image is open.\n"
                    + "Open the Nissl-stained section and add the required ROIs to the ROI Manager first.");
            return;
        }

        RoiManager rm = RoiManager.getInstance();
        if (rm == null) {
            rm = new RoiManager();
        }

        // ---------------------------------------------------------------
        // Step 1: Options dialog
        // ---------------------------------------------------------------
        GenericDialog dlg = new GenericDialog("Cerebellar Morphometry Options");
        dlg.addCheckbox("Show colour-coded layer overlay",        true);
        dlg.addCheckbox("Show per-lobule transparent fills",      true);
        dlg.addCheckbox("Add measurement ROIs to ROI Manager",    true);
        dlg.addCheckbox("Show ImageJ Results Table",              true);
        dlg.addCheckbox("Export CSV",                             true);
        dlg.addCheckbox("Export Excel (.xlsx)",                   true);
        dlg.addMessage("\nIf exporting, you will be asked for a save directory.");
        dlg.showDialog();

        if (dlg.wasCanceled()) {
            return;
        }

        boolean doOverlay        = dlg.getNextBoolean();
        boolean doSubsectionFill = dlg.getNextBoolean();
        boolean doRoiExport      = dlg.getNextBoolean();
        boolean doResultsTable   = dlg.getNextBoolean();
        boolean doCSV            = dlg.getNextBoolean();
        boolean doXLSX           = dlg.getNextBoolean();

        // ---------------------------------------------------------------
        // Step 2: Validation — one LayerSet per detected instance
        // ---------------------------------------------------------------
        IJ.showStatus("Cerebellar Morphometry: validating ROIs...");
        SortedMap<Integer, LayerSet> instances;
        try {
            instances = ROIValidator.validate(rm, imp);
        } catch (ValidationException e) {
            IJ.error("Cerebellar Morphometry — ROI Validation Failed", e.getMessage());
            return;
        }

        boolean multiInstance = instances.size() > 1;
        if (multiInstance) {
            IJ.log("[Cerebellar Morphometry] Found " + instances.size() + " separately-traced pieces: "
                    + instances.keySet() + ". These are treated as parts of ONE cerebellum — measured "
                    + "separately, then summed into a single set of results.");
        }

        // ---------------------------------------------------------------
        // Steps 3–4: Geometry and measurement, once per instance
        // ---------------------------------------------------------------
        Map<Integer, MeasurementEngine.IntermediateGeometry> geometryByInstance = new HashMap<>();
        List<InstanceResult> results = new ArrayList<>(instances.size());
        for (Map.Entry<Integer, LayerSet> entry : instances.entrySet()) {
            int instance = entry.getKey();
            LayerSet layers = entry.getValue();
            String label = multiInstance ? " (instance " + instance + ")" : "";

            IJ.showStatus("Cerebellar Morphometry: building layer geometry" + label + "...");
            MeasurementEngine.IntermediateGeometry geo;
            try {
                geo = MeasurementEngine.buildGeometry(layers);
            } catch (Exception e) {
                IJ.error("Cerebellar Morphometry — Geometry Error",
                        "An error occurred while computing the layer geometry" + label + ":\n" + e.getMessage()
                        + "\n\nCheck that your ROIs don't have self-intersections, and that the fissure lines"
                        + " actually reach the Purkinje line.");
                return;
            }
            geometryByInstance.put(instance, geo);

            IJ.showStatus("Cerebellar Morphometry: measuring" + label + "...");
            MorphometryResults measured;
            try {
                measured = MeasurementEngine.measure(layers, imp);
            } catch (Exception e) {
                IJ.error("Cerebellar Morphometry — Measurement Error",
                        "An error occurred while measuring" + label + ":\n" + e.getMessage());
                return;
            }
            results.add(new InstanceResult(instance, measured));
        }

        // ---------------------------------------------------------------
        // Step 5: Overlay — one combined overlay covering every instance
        // ---------------------------------------------------------------
        if (doOverlay) {
            IJ.showStatus("Cerebellar Morphometry: rendering overlay...");
            try {
                OverlayRenderer.renderAll(imp, instances, geometryByInstance, doSubsectionFill);
            } catch (Exception e) {
                // Non-fatal: warn but don't abort the measurement output.
                IJ.log("[Cerebellar Morphometry] Warning: overlay rendering failed: " + e.getMessage());
            }
        }

        // ---------------------------------------------------------------
        // Step 6: Add measurement ROIs to the ROI Manager, per instance
        // ---------------------------------------------------------------
        if (doRoiExport) {
            IJ.showStatus("Cerebellar Morphometry: adding measurement ROIs...");
            try {
                for (Map.Entry<Integer, LayerSet> entry : instances.entrySet()) {
                    int instance = entry.getKey();
                    MeasurementEngine.IntermediateGeometry geo = geometryByInstance.get(instance);
                    if (multiInstance) {
                        RoiManagerExporter.addMeasurementRois(rm, entry.getValue(), geo, instance);
                    } else {
                        RoiManagerExporter.addMeasurementRois(rm, entry.getValue(), geo);
                    }
                }
            } catch (Exception e) {
                // Non-fatal: warn but don't abort the measurement output.
                IJ.log("[Cerebellar Morphometry] Warning: adding ROIs to the ROI Manager failed: " + e.getMessage());
            }
        }

        // ---------------------------------------------------------------
        // Step 7: Combine every instance into one cerebellum's worth of results.
        // The instances are pieces of the same cerebellum that couldn't be traced as one
        // outline — not separate specimens — so their totals are summed and their sections
        // pooled into one table. See MeasurementEngine.combine().
        // ---------------------------------------------------------------
        MorphometryResults combined = MeasurementEngine.combine(results);
        if (multiInstance) {
            IJ.log("[Cerebellar Morphometry] Combined " + results.size() + " traced pieces into one set of "
                    + "results: totals are summed across all pieces, and their sections pooled into "
                    + combined.getSubsections().size() + " subsection row(s).");
        }

        if (doResultsTable) {
            SpreadsheetExporter.showResultsTable(combined);
        }

        // ---------------------------------------------------------------
        // Step 8: File export
        // ---------------------------------------------------------------
        if (doCSV || doXLSX) {
            // Ask the user for a save directory.
            String saveDirPath = IJ.getDirectory("Choose a folder to save the results");
            if (saveDirPath == null) {
                IJ.log("[Cerebellar Morphometry] File export cancelled.");
            } else {
                File saveDir = new File(saveDirPath);
                String base = imp.getShortTitle().replaceAll("[^A-Za-z0-9_\\-]", "_");
                if (base.isEmpty()) {
                    base = "cerebellar_morphometry";
                }

                if (doCSV) {
                    File csv = new File(saveDir, base + ".csv");
                    try {
                        SpreadsheetExporter.exportCSV(combined, csv);
                        IJ.log("[Cerebellar Morphometry] CSV saved: " + csv.getAbsolutePath());
                    } catch (IOException e) {
                        IJ.error("Cerebellar Morphometry — Export Error",
                                "Could not write CSV:\n" + csv.getAbsolutePath()
                                + "\n" + e.getMessage());
                    }
                }

                if (doXLSX) {
                    File xlsx = new File(saveDir, base + ".xlsx");
                    try {
                        SpreadsheetExporter.exportXLSX(combined, xlsx);
                        IJ.log("[Cerebellar Morphometry] XLSX saved: " + xlsx.getAbsolutePath());
                    } catch (IOException e) {
                        IJ.error("Cerebellar Morphometry — Export Error",
                                "Could not write Excel file:\n" + xlsx.getAbsolutePath()
                                + "\n" + e.getMessage());
                    }
                }
            }
        }

        IJ.showStatus("Cerebellar Morphometry: done.");
        IJ.showProgress(1.0);
    }
}
