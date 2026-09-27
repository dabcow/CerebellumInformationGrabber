package org.cerebellum.morphometry.export;

import ij.measure.ResultsTable;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.RunInfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Exports a {@link MorphometryResults} to three targets:
 * <ol>
 *   <li>An ImageJ {@link ResultsTable} (displayed interactively via {@link #showResultsTable}).</li>
 *   <li>A plain UTF-8 CSV file.</li>
 *   <li>An {@code .xlsx} workbook, with a second "Run Info" sheet recording provenance.</li>
 * </ol>
 *
 * <p>All three use the same layout (shown for the standard 8-subsection case; any other count
 * produces the same layout with that many subsection rows, labelled "Section 1", "Section 2",
 * &hellip;):</p>
 * <pre>
 * Measurement | Cerebellum | Grey Matter | Granular Layer | Molecular Layer | Purkinje
 * Area        | Total      | Grey        | Granular       | Molecular       | Total "area"
 * Length      |            |             |                |                 | Total length
 * 2Cb         |            |             | Area           | Area            | Length
 * ...
 * 10Cb        |            |             | Area           | Area            | Length
 * </pre>
 *
 * <p>Values are in the image's calibrated units. Numbers are always written with a {@code .}
 * decimal separator, whatever the computer's language settings: formatting with the default
 * locale (as earlier versions did) produced {@code 1234,5678} on e.g. German or French systems,
 * which broke the CSV for downstream tools and turned every value in the Excel file into text.</p>
 */
public final class SpreadsheetExporter {

    // Column indices
    private static final int COL_MEASUREMENT = 0;
    private static final int COL_CEREBELLUM  = 1;
    private static final int COL_GREY        = 2;
    private static final int COL_GRANULAR    = 3;
    private static final int COL_MOLECULAR   = 4;
    private static final int COL_PURKINJE    = 5;
    private static final int COLUMN_COUNT    = 6;

    // Row indices for the fixed part of the table
    private static final int ROW_HEADER            = 0;
    private static final int ROW_AREA              = 1;
    private static final int ROW_LENGTH            = 2;
    private static final int ROW_SUBSECTIONS_START = 3;

    private static final String SHEET_RESULTS  = "Cerebellar Morphometry";
    private static final String SHEET_RUN_INFO = "Run Info";

    private SpreadsheetExporter() {
    }

    // -----------------------------------------------------------------------
    // ImageJ ResultsTable
    // -----------------------------------------------------------------------

    /**
     * Builds a {@link ResultsTable}. The ResultsTable API is column-oriented, so the row-oriented
     * layout is produced by using the "Label" column as the first (Measurement) column.
     */
    public static ResultsTable buildResultsTable(MorphometryResults r) {
        ResultsTable rt = new ResultsTable();
        rt.setPrecision(4);
        String[] headers = headers(r);

        rt.incrementCounter();
        rt.addLabel("Area");
        rt.addValue(headers[COL_CEREBELLUM], r.getCerebellumArea());
        rt.addValue(headers[COL_GREY],       r.getGreyMatterArea());
        rt.addValue(headers[COL_GRANULAR],   r.getGranularLayerArea());
        rt.addValue(headers[COL_MOLECULAR],  r.getMolecularLayerArea());
        rt.addValue(headers[COL_PURKINJE],   r.getTotalPurkinjeArea());

        rt.incrementCounter();
        rt.addLabel("Length");
        rt.addValue(headers[COL_CEREBELLUM], Double.NaN);
        rt.addValue(headers[COL_GREY],       Double.NaN);
        rt.addValue(headers[COL_GRANULAR],   Double.NaN);
        rt.addValue(headers[COL_MOLECULAR],  Double.NaN);
        rt.addValue(headers[COL_PURKINJE],   r.getTotalPurkinjeLength());

        for (MorphometryResults.SubsectionResult sub : r.getSubsections()) {
            rt.incrementCounter();
            rt.addLabel(sub.getLabel());
            rt.addValue(headers[COL_CEREBELLUM], Double.NaN);
            rt.addValue(headers[COL_GREY],       Double.NaN);
            rt.addValue(headers[COL_GRANULAR],   sub.getGranularArea());
            rt.addValue(headers[COL_MOLECULAR],  sub.getMolecularArea());
            rt.addValue(headers[COL_PURKINJE],   sub.getPurkinjeLength());
        }
        return rt;
    }

    /** Builds and shows the ResultsTable in the ImageJ UI. */
    public static void showResultsTable(MorphometryResults r) {
        buildResultsTable(r).show("Cerebellar Morphometry");
    }

    // -----------------------------------------------------------------------
    // CSV
    // -----------------------------------------------------------------------

    /** Writes the results table as UTF-8 CSV. The file is replaced only once fully written. */
    public static void exportCSV(MorphometryResults r, File file) throws IOException {
        writeAtomically(file, out -> {
            Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            for (String[] row : buildDataGrid(r)) {
                w.write(csvRow(row));
                w.write(System.lineSeparator());
            }
            w.flush();
        });
    }

    private static String csvRow(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String cell = cells[i] == null ? "" : cells[i];
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
                sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(cell);
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // XLSX
    // -----------------------------------------------------------------------

    /**
     * Writes the results table, plus a "Run Info" sheet when {@code runInfo} is given, as an
     * {@code .xlsx} workbook. Values are stored at full precision and displayed with four decimal
     * places. The file is replaced only once fully written.
     */
    public static void exportXLSX(MorphometryResults r, RunInfo runInfo, File file) throws IOException {
        XlsxWriter wb = new XlsxWriter();

        XlsxWriter.Sheet sheet = wb.addSheet(SHEET_RESULTS).freeze(1, 1);
        String[] headers = headers(r);
        for (int c = 0; c < COLUMN_COUNT; c++) {
            sheet.text(ROW_HEADER, c, headers[c], XlsxWriter.Style.HEADER);
        }

        sheet.text(ROW_AREA, COL_MEASUREMENT, "Area", XlsxWriter.Style.BOLD);
        sheet.number(ROW_AREA, COL_CEREBELLUM, r.getCerebellumArea(),     XlsxWriter.Style.BOLD_NUMBER);
        sheet.number(ROW_AREA, COL_GREY,       r.getGreyMatterArea(),     XlsxWriter.Style.BOLD_NUMBER);
        sheet.number(ROW_AREA, COL_GRANULAR,   r.getGranularLayerArea(),  XlsxWriter.Style.BOLD_NUMBER);
        sheet.number(ROW_AREA, COL_MOLECULAR,  r.getMolecularLayerArea(), XlsxWriter.Style.BOLD_NUMBER);
        sheet.number(ROW_AREA, COL_PURKINJE,   r.getTotalPurkinjeArea(),  XlsxWriter.Style.BOLD_NUMBER);

        sheet.text(ROW_LENGTH, COL_MEASUREMENT, "Length", XlsxWriter.Style.BOLD);
        for (int c = COL_CEREBELLUM; c < COL_PURKINJE; c++) {
            sheet.blank(ROW_LENGTH, c, XlsxWriter.Style.BOLD);
        }
        sheet.number(ROW_LENGTH, COL_PURKINJE, r.getTotalPurkinjeLength(), XlsxWriter.Style.BOLD_NUMBER);

        List<MorphometryResults.SubsectionResult> subs = r.getSubsections();
        for (int i = 0; i < subs.size(); i++) {
            MorphometryResults.SubsectionResult s = subs.get(i);
            int row = ROW_SUBSECTIONS_START + i;
            sheet.text(row, COL_MEASUREMENT, s.getLabel(), XlsxWriter.Style.ITALIC);
            sheet.number(row, COL_GRANULAR,  s.getGranularArea(),   XlsxWriter.Style.NUMBER);
            sheet.number(row, COL_MOLECULAR, s.getMolecularArea(),  XlsxWriter.Style.NUMBER);
            sheet.number(row, COL_PURKINJE,  s.getPurkinjeLength(), XlsxWriter.Style.NUMBER);
        }

        if (runInfo != null) {
            XlsxWriter.Sheet info = wb.addSheet(SHEET_RUN_INFO).freeze(0, 1);
            info.text(0, 0, "Field", XlsxWriter.Style.HEADER);
            info.text(0, 1, "Value", XlsxWriter.Style.HEADER);
            int row = 1;
            for (String[] field : runInfo.getFields()) {
                info.text(row, 0, field[0], XlsxWriter.Style.BOLD);
                info.text(row, 1, field[1], XlsxWriter.Style.WRAP);
                row++;
            }
            for (Diagnostics.Entry e : runInfo.getMessages()) {
                info.text(row, 0, e.getLevel() == Diagnostics.Level.WARNING ? "Warning" : "Note", XlsxWriter.Style.BOLD);
                info.text(row, 1, e.getMessage(), XlsxWriter.Style.WRAP);
                row++;
            }
        }

        writeAtomically(file, wb::write);
    }

    // -----------------------------------------------------------------------
    // Shared grid builder
    // -----------------------------------------------------------------------

    /**
     * Returns the table as a 2D grid of strings (null == empty cell), as written to CSV. Numbers
     * are formatted to 4 decimal places with a {@code .} separator; a value that could not be
     * measured (NaN) is left empty.
     */
    public static String[][] buildDataGrid(MorphometryResults r) {
        List<MorphometryResults.SubsectionResult> subs = r.getSubsections();
        String[][] g = new String[ROW_SUBSECTIONS_START + subs.size()][COLUMN_COUNT];

        g[ROW_HEADER] = headers(r);

        g[ROW_AREA][COL_MEASUREMENT] = "Area";
        g[ROW_AREA][COL_CEREBELLUM]  = fmt(r.getCerebellumArea());
        g[ROW_AREA][COL_GREY]        = fmt(r.getGreyMatterArea());
        g[ROW_AREA][COL_GRANULAR]    = fmt(r.getGranularLayerArea());
        g[ROW_AREA][COL_MOLECULAR]   = fmt(r.getMolecularLayerArea());
        g[ROW_AREA][COL_PURKINJE]    = fmt(r.getTotalPurkinjeArea());

        g[ROW_LENGTH][COL_MEASUREMENT] = "Length";
        g[ROW_LENGTH][COL_PURKINJE]    = fmt(r.getTotalPurkinjeLength());

        for (int i = 0; i < subs.size(); i++) {
            MorphometryResults.SubsectionResult s = subs.get(i);
            int row = ROW_SUBSECTIONS_START + i;
            g[row][COL_MEASUREMENT] = s.getLabel();
            g[row][COL_GRANULAR]    = fmt(s.getGranularArea());
            g[row][COL_MOLECULAR]   = fmt(s.getMolecularArea());
            g[row][COL_PURKINJE]    = fmt(s.getPurkinjeLength());
        }
        return g;
    }

    private static String[] headers(MorphometryResults r) {
        String[] h = new String[COLUMN_COUNT];
        h[COL_MEASUREMENT] = "Measurement";
        h[COL_CEREBELLUM]  = "Cerebellum (" + r.getAreaUnit() + ")";
        h[COL_GREY]        = "Grey Matter (" + r.getAreaUnit() + ")";
        h[COL_GRANULAR]    = "Granular Layer (" + r.getAreaUnit() + ")";
        h[COL_MOLECULAR]   = "Molecular Layer (" + r.getAreaUnit() + ")";
        h[COL_PURKINJE]    = "Purkinje (" + r.getLengthUnit() + ")";
        return h;
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : null;
    }

    // -----------------------------------------------------------------------
    // File handling
    // -----------------------------------------------------------------------

    private interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * Writes to a temporary file next to {@code target} and then moves it into place, so a failed
     * or interrupted export never leaves a truncated file behind (or destroys the previous one).
     */
    private static void writeAtomically(File target, Body body) throws IOException {
        Path dest = target.toPath().toAbsolutePath();
        Path tmp = Files.createTempFile(dest.getParent(), "." + dest.getFileName(), ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                body.writeTo(out);
            }
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
