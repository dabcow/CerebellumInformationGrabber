package org.cerebellum.morphometry;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import org.cerebellum.morphometry.export.SpreadsheetExporter;
import org.cerebellum.morphometry.geometry.ROIValidator;
import org.cerebellum.morphometry.measurement.MeasurementEngine;
import org.cerebellum.morphometry.model.InstanceResult;
import org.cerebellum.morphometry.model.LayerSet;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.RunInfo;
import org.cerebellum.morphometry.model.ValidationException;
import org.cerebellum.morphometry.visualization.OverlayRenderer;
import org.cerebellum.morphometry.visualization.RoiManagerExporter;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.SortedMap;

/**
 * Main FIJI/ImageJ entry point for the Cerebellar Layer Quantification plugin.
 *
 * <p>Registered in {@code plugins.config} under
 * <em>Plugins &gt; Cerebellar Morphometry &gt; Quantify Layers...</em></p>
 *
 * <h2>Execution flow</h2>
 * <ol>
 *   <li><b>Options dialog</b> &mdash; overlay, ROI export, results table and file export.</li>
 *   <li><b>Validation</b> &mdash; {@link ROIValidator} checks the ROI Manager and produces one
 *       {@link LayerSet} per traced piece; all problems are shown together.</li>
 *   <li><b>Geometry &amp; measurement</b> &mdash; {@link MeasurementEngine#analyze} builds each
 *       piece's layers and lobule partition once and measures them.</li>
 *   <li><b>Overlay</b>, <b>ROI Manager export</b>, <b>results table</b> and <b>file export</b>
 *       all use that same geometry and the pooled results.</li>
 *   <li><b>Summary</b> &mdash; if anything needs attention, a dialog lists the warnings (they are
 *       also in the Log window and the workbook's "Run Info" sheet).</li>
 * </ol>
 *
 * <h2>Macros and batch processing</h2>
 * <p>Every option has its own macro keyword, so a run can be recorded and replayed with
 * <em>Plugins &gt; Macros &gt; Record&hellip;</em>, e.g.</p>
 * <pre>
 * run("Quantify Layers...", "layer_overlay section_fills results_table save_csv save_excel output_folder=[/data/results]");
 * </pre>
 * <p>When run from a macro, existing result files are overwritten without asking and no summary
 * dialog is shown.</p>
 */
public final class CerebellarMorphometryPlugin implements PlugIn {

    private static final String TITLE = "Cerebellar Morphometry";
    private static final String PREFS = "cerebellar_morphometry.";

    @Override
    public void run(String arg) {
        // WindowManager, not IJ.getImage(): the latter shows its own error and aborts the plugin
        // with an exception when no image is open, so the message below would never be seen.
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            IJ.error(TITLE, "No image is open.\n"
                    + "Open the Nissl-stained section and add the required ROIs to the ROI Manager first.");
            return;
        }

        Options opt = Options.ask(imp);
        if (opt == null) {
            return;
        }

        Diagnostics diag = new Diagnostics();
        IJ.log(Diagnostics.LOG_PREFIX + BuildInfo.NAME + " " + BuildInfo.version() + " — measuring \""
                + imp.getTitle() + "\"");

        // ---------------------------------------------------------------
        // Validation — one LayerSet per traced piece
        // ---------------------------------------------------------------
        IJ.showStatus(TITLE + ": validating ROIs...");
        RoiManager rm = RoiManager.getInstance();
        SortedMap<Integer, LayerSet> instances;
        try {
            instances = ROIValidator.validate(rm, imp, diag);
        } catch (ValidationException e) {
            IJ.error(TITLE + " — ROI Validation Failed", e.getMessage());
            return;
        }
        if (instances.size() > 1) {
            diag.note("Found " + instances.size() + " separately traced pieces " + instances.keySet()
                    + ". They are treated as parts of ONE cerebellum: measured separately, then pooled.");
        }

        // ---------------------------------------------------------------
        // Geometry and measurement, once per piece
        // ---------------------------------------------------------------
        IJ.showStatus(TITLE + ": building layer geometry and measuring...");
        List<InstanceResult> results;
        try {
            results = MeasurementEngine.analyze(instances, imp, diag);
        } catch (OutOfMemoryError e) {
            IJ.outOfMemory(TITLE);
            return;
        } catch (RuntimeException e) {
            logStackTrace(e);
            IJ.error(TITLE + " — Geometry Error",
                    "An error occurred while computing the layer geometry:\n" + e
                    + "\n\nCheck that the ROIs don't self-intersect and that each fissure line crosses the "
                    + "grey matter. Technical details are in the Log window.");
            return;
        }
        MorphometryResults combined = MeasurementEngine.combine(results, diag);
        if (results.size() > 1) {
            diag.note("Pooled " + results.size() + " traced pieces into one set of results with "
                    + combined.getSubsections().size() + " section row(s).");
        }

        // ---------------------------------------------------------------
        // Overlay, ROI Manager, results table
        // ---------------------------------------------------------------
        if (opt.overlay) {
            IJ.showStatus(TITLE + ": rendering overlay...");
            try {
                OverlayRenderer.render(imp, results, opt.sectionFills);
            } catch (RuntimeException e) {
                logStackTrace(e);
                diag.warn("The overlay could not be drawn (" + e + "); the measurements are unaffected.");
            }
        }
        if (opt.addRois && rm != null) {
            try {
                RoiManagerExporter.addMeasurementRois(rm, results);
            } catch (RuntimeException e) {
                logStackTrace(e);
                diag.warn("Adding measurement ROIs to the ROI Manager failed (" + e + "); the measurements "
                        + "are unaffected.");
            }
        }
        if (opt.resultsTable) {
            SpreadsheetExporter.showResultsTable(combined);
        }

        // ---------------------------------------------------------------
        // File export
        // ---------------------------------------------------------------
        if (opt.saveCsv || opt.saveXlsx) {
            export(imp, opt, combined, results, diag);
        }

        IJ.showStatus(TITLE + ": done.");
        IJ.showProgress(1.0);

        List<String> warnings = diag.getWarnings();
        if (!warnings.isEmpty() && !IJ.isMacro()) {
            StringBuilder sb = new StringBuilder("Finished with " + warnings.size() + " warning"
                    + (warnings.size() == 1 ? "" : "s") + ":\n");
            for (String w : warnings) {
                sb.append("\n• ").append(w);
            }
            sb.append("\n\nThese are also listed in the Log window");
            sb.append(opt.saveXlsx ? " and in the workbook's \"Run Info\" sheet." : ".");
            IJ.showMessage(TITLE, sb.toString());
        }
    }

    private static void export(ImagePlus imp, Options opt, MorphometryResults combined,
                               List<InstanceResult> results, Diagnostics diag) {
        File dir = opt.outputFolder.isEmpty() ? null : new File(opt.outputFolder);
        if (dir == null || !dir.isDirectory()) {
            if (IJ.isMacro()) {
                IJ.error(TITLE, "Output folder not found: \"" + opt.outputFolder + "\". No files were saved.");
                return;
            }
            String chosen = IJ.getDirectory("Choose a folder to save the results");
            if (chosen == null) {
                diag.note("File export cancelled.");
                return;
            }
            dir = new File(chosen);
            Prefs.set(PREFS + "folder", dir.getAbsolutePath());
        }

        String base = imp.getShortTitle().replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (base.isEmpty()) {
            base = "cerebellar_morphometry";
        }
        File csv = new File(dir, base + ".csv");
        File xlsx = new File(dir, base + ".xlsx");

        if (!IJ.isMacro()) {
            StringBuilder existing = new StringBuilder();
            if (opt.saveCsv && csv.exists()) {
                existing.append("\n").append(csv.getName());
            }
            if (opt.saveXlsx && xlsx.exists()) {
                existing.append("\n").append(xlsx.getName());
            }
            if (existing.length() > 0 && !IJ.showMessageWithCancel(TITLE,
                    "These files already exist in\n" + dir.getAbsolutePath() + ":\n" + existing
                    + "\n\nReplace them?")) {
                diag.note("File export cancelled (existing files kept).");
                return;
            }
        }

        if (opt.saveCsv) {
            try {
                SpreadsheetExporter.exportCSV(combined, csv);
                diag.note("CSV saved: " + csv.getAbsolutePath());
            } catch (IOException e) {
                IJ.error(TITLE + " — Export Error", "Could not write CSV:\n" + csv.getAbsolutePath() + "\n" + e);
            }
        }
        if (opt.saveXlsx) {
            try {
                // Described before writing, so the saved Run Info lists every message up to here.
                SpreadsheetExporter.exportXLSX(combined, RunInfo.describe(imp, results, diag), xlsx);
                diag.note("Excel workbook saved: " + xlsx.getAbsolutePath());
            } catch (IOException e) {
                IJ.error(TITLE + " — Export Error", "Could not write Excel file:\n" + xlsx.getAbsolutePath() + "\n" + e);
            }
        }
    }

    private static void logStackTrace(Throwable t) {
        IJ.log(Diagnostics.LOG_PREFIX + t);
        for (StackTraceElement el : t.getStackTrace()) {
            IJ.log("    at " + el);
        }
    }

    /**
     * The options dialog. Each checkbox label's first word (up to the first space, with
     * underscores joining words) is its macro keyword, so every keyword must be unique: in
     * version 1.0.0 three options started with "Show" and two with "Export", which made them
     * impossible to set independently from a macro.
     */
    private static final class Options {
        boolean overlay;
        boolean sectionFills;
        boolean addRois;
        boolean resultsTable;
        boolean saveCsv;
        boolean saveXlsx;
        String outputFolder;

        static Options ask(ImagePlus imp) {
            GenericDialog gd = new GenericDialog(TITLE + " Options");
            gd.addMessage("Display");
            gd.addCheckbox("Layer_overlay (colour-coded)", Prefs.get(PREFS + "overlay", true));
            gd.addCheckbox("Section_fills and labels", Prefs.get(PREFS + "fills", true));
            gd.addCheckbox("Add_ROIs to ROI Manager", Prefs.get(PREFS + "rois", true));
            gd.addCheckbox("Results_table", Prefs.get(PREFS + "table", true));
            gd.addMessage("Save results");
            gd.addCheckbox("Save_CSV", Prefs.get(PREFS + "csv", true));
            gd.addCheckbox("Save_Excel workbook (.xlsx)", Prefs.get(PREFS + "xlsx", true));
            gd.addDirectoryField("Output_folder", defaultFolder(imp), 30);
            gd.addMessage("Files are named after the image and saved next to it unless you pick\n"
                    + "another folder. Leave the folder empty to be asked.");
            gd.addHelp("https://github.com/dabcow/CerebellumInformationGrabber#readme");
            gd.showDialog();
            if (gd.wasCanceled()) {
                return null;
            }

            Options o = new Options();
            o.overlay      = gd.getNextBoolean();
            o.sectionFills = gd.getNextBoolean();
            o.addRois      = gd.getNextBoolean();
            o.resultsTable = gd.getNextBoolean();
            o.saveCsv      = gd.getNextBoolean();
            o.saveXlsx     = gd.getNextBoolean();
            o.outputFolder = gd.getNextString().trim();

            if (Macro.getOptions() == null) { // remember interactive choices, not macro ones
                Prefs.set(PREFS + "overlay", o.overlay);
                Prefs.set(PREFS + "fills", o.sectionFills);
                Prefs.set(PREFS + "rois", o.addRois);
                Prefs.set(PREFS + "table", o.resultsTable);
                Prefs.set(PREFS + "csv", o.saveCsv);
                Prefs.set(PREFS + "xlsx", o.saveXlsx);
                if (!o.outputFolder.isEmpty()) {
                    Prefs.set(PREFS + "folder", o.outputFolder);
                }
            }
            return o;
        }

        /**
         * The folder the image was opened from, so each section's results land next to its image
         * (one folder per section, all images named alike, e.g. Montage.tif). Deliberately not the
         * last folder used: with identically named images, that would put the next section's
         * Montage.csv on top of the previous section's. Falls back to the last folder used only
         * for an image that has never been saved.
         */
        private static String defaultFolder(ImagePlus imp) {
            FileInfo fi = imp.getOriginalFileInfo();
            if (fi != null && fi.directory != null && !fi.directory.isEmpty()) {
                return fi.directory;
            }
            return Prefs.get(PREFS + "folder", "");
        }
    }
}
