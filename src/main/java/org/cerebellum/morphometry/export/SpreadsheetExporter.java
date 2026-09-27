package org.cerebellum.morphometry.export;

import ij.measure.ResultsTable;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.cerebellum.morphometry.model.MorphometryResults;

import java.io.*;
import java.util.List;

/**
 * Exports a {@link MorphometryResults} to three targets:
 * <ol>
 *   <li>An ImageJ {@link ResultsTable} (displayed interactively via {@link #showResultsTable}).</li>
 *   <li>A plain UTF-8 CSV file.</li>
 *   <li>A native {@code .xlsx} workbook via Apache POI.</li>
 * </ol>
 *
 * <p>All three reproduce the exact table layout described in the spec (shown here for the
 * standard 8-subsection case; any other count produces the same layout with that many
 * subsection rows, labeled "Section 1", "Section 2", … instead of anatomical names — see
 * {@link org.cerebellum.morphometry.geometry.FissurePartitioner#labelsForSubsectionCount}). The
 * Purkinje column's Area row, always empty before, now holds the whole line's total "area"
 * (see {@link org.cerebellum.morphometry.geometry.PurkinjeLengthCalculator} for what that
 * means):</p>
 * <pre>
 * Measurement | Cerebellum | Grey Matter | Granular Layer | Molecular Layer | Purkinje
 * Area        | Total      | Grey        | Granular       | Molecular       | Total area
 * Length      |            |             |                |                 | Total length
 * 2Cb         |            |             | Area           | Area            | Length
 * ...
 * 10Cb        |            |             | Area           | Area            | Length
 * </pre>
 *
 * <p>Separately-traced pieces of one cerebellum (see {@link
 * org.cerebellum.morphometry.geometry.ROIValidator}'s "Multiple instances" section) are pooled
 * into a single {@code MorphometryResults} upstream, by {@link
 * org.cerebellum.morphometry.measurement.MeasurementEngine#combine} — so there is always
 * exactly one table, whether the cerebellum was traced as one outline or several.</p>
 *
 * <p>All values are in the calibrated units reported by {@link MorphometryResults#getAreaUnit()} and
 * {@link MorphometryResults#getLengthUnit()}, so the numbers match whatever unit the image's
 * pixel calibration was set to (microns, millimetres, etc.).</p>
 */
public final class SpreadsheetExporter {

    // Column indices
    private static final int COL_MEASUREMENT = 0;
    private static final int COL_CEREBELLUM  = 1;
    private static final int COL_GREY        = 2;
    private static final int COL_GRANULAR    = 3;
    private static final int COL_MOLECULAR   = 4;
    private static final int COL_PURKINJE    = 5;

    // Row indices for the fixed part of the table
    private static final int ROW_HEADER            = 0;
    private static final int ROW_AREA              = 1;
    private static final int ROW_LENGTH            = 2;
    private static final int ROW_SUBSECTIONS_START = 3;

    private SpreadsheetExporter() {
    }

    // -----------------------------------------------------------------------
    // ImageJ ResultsTable
    // -----------------------------------------------------------------------

    /**
     * Builds a {@link ResultsTable}. The ResultsTable API is column-oriented, so we fake the
     * required row-oriented layout by using the "Label" column as the first (Measurement)
     * column, and making each logical column a ResultsTable column.
     */
    public static ResultsTable buildResultsTable(MorphometryResults r) {
        ResultsTable rt = new ResultsTable();
        rt.setPrecision(4);

        String cbCol   = "Cerebellum (" + r.getAreaUnit() + ")";
        String greyCol = "Grey Matter (" + r.getAreaUnit() + ")";
        String granCol = "Granular Layer (" + r.getAreaUnit() + ")";
        String molCol  = "Molecular Layer (" + r.getAreaUnit() + ")";
        String pkCol   = "Purkinje (" + r.getLengthUnit() + ")";

        // Area row (Purkinje's "area" — see class javadoc — goes here too)
        rt.incrementCounter();
        rt.addLabel("Area");
        rt.addValue(cbCol,   r.getCerebellumArea());
        rt.addValue(greyCol, r.getGreyMatterArea());
        rt.addValue(granCol, r.getGranularLayerArea());
        rt.addValue(molCol,  r.getMolecularLayerArea());
        rt.addValue(pkCol,   r.getTotalPurkinjeArea());

        // Length row
        rt.incrementCounter();
        rt.addLabel("Length");
        rt.addValue(cbCol,   Double.NaN);
        rt.addValue(greyCol, Double.NaN);
        rt.addValue(granCol, Double.NaN);
        rt.addValue(molCol,  Double.NaN);
        rt.addValue(pkCol,   r.getTotalPurkinjeLength());

        // Per-subsection rows
        for (MorphometryResults.SubsectionResult sub : r.getSubsections()) {
            rt.incrementCounter();
            rt.addLabel(sub.getLabel());
            rt.addValue(cbCol,   Double.NaN);
            rt.addValue(greyCol, Double.NaN);
            rt.addValue(granCol, sub.getGranularArea());
            rt.addValue(molCol,  sub.getMolecularArea());
            rt.addValue(pkCol,   sub.getPurkinjeLength());
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

    public static void exportCSV(MorphometryResults r, File file) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
            for (String[] row : buildDataGrid(r)) {
                pw.println(escapeCSVRow(row));
            }
        }
    }

    private static String escapeCSVRow(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String cell = cells[i] == null ? "" : cells[i];
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
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

    public static void exportXLSX(MorphometryResults r, File file) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Cerebellar Morphometry");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle numberStyle = wb.createCellStyle();
            DataFormat df = wb.createDataFormat();
            numberStyle.setDataFormat(df.getFormat("0.0000"));

            CellStyle sectionStyle = wb.createCellStyle();
            Font sectionFont = wb.createFont();
            sectionFont.setItalic(true);
            sectionStyle.setFont(sectionFont);

            CellStyle boldStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            boldStyle.setFont(boldFont);

            CellStyle boldNumberStyle = wb.createCellStyle();
            boldNumberStyle.cloneStyleFrom(numberStyle);
            boldNumberStyle.setFont(boldFont);

            String[][] data = buildDataGrid(r);

            Row header = sheet.createRow(ROW_HEADER);
            for (int c = 0; c < data[ROW_HEADER].length; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(data[ROW_HEADER][c] == null ? "" : data[ROW_HEADER][c]);
                cell.setCellStyle(headerStyle);
            }

            for (int rowIdx = 1; rowIdx < data.length; rowIdx++) {
                Row row = sheet.createRow(rowIdx);
                String[] rowData = data[rowIdx];
                boolean bold = rowIdx == ROW_AREA || rowIdx == ROW_LENGTH;
                boolean isSubsection = rowIdx >= ROW_SUBSECTIONS_START;

                for (int c = 0; c < rowData.length; c++) {
                    Cell cell = row.createCell(c);
                    String val = rowData[c];
                    boolean isLabelCol = c == COL_MEASUREMENT;
                    if (val == null || val.isEmpty()) {
                        cell.setCellValue("");
                        if (bold) {
                            cell.setCellStyle(boldStyle);
                        }
                    } else if (isLabelCol) {
                        cell.setCellValue(val);
                        if (isSubsection) {
                            cell.setCellStyle(sectionStyle);
                        } else if (bold) {
                            cell.setCellStyle(boldStyle);
                        }
                    } else {
                        try {
                            cell.setCellValue(Double.parseDouble(val));
                            cell.setCellStyle(bold ? boldNumberStyle : numberStyle);
                        } catch (NumberFormatException e) {
                            cell.setCellValue(val);
                            if (bold) {
                                cell.setCellStyle(boldStyle);
                            }
                        }
                    }
                }
            }

            for (int c = 0; c < data[ROW_HEADER].length; c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.createFreezePane(1, 1);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                wb.write(fos);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Shared grid builder (used by both CSV and XLSX paths)
    // -----------------------------------------------------------------------

    /**
     * Returns a 2D grid of String values (null == empty cell) matching the layout required by
     * the spec. Numeric values are formatted to 4 decimal places.
     */
    public static String[][] buildDataGrid(MorphometryResults r) {
        List<MorphometryResults.SubsectionResult> subs = r.getSubsections();
        int nRows = ROW_SUBSECTIONS_START + subs.size(); // header + area + length + N subsections
        int nCols = 6;

        String[][] g = new String[nRows][nCols];

        // Header row
        g[ROW_HEADER][COL_MEASUREMENT] = "Measurement";
        g[ROW_HEADER][COL_CEREBELLUM]  = "Cerebellum (" + r.getAreaUnit() + ")";
        g[ROW_HEADER][COL_GREY]        = "Grey Matter (" + r.getAreaUnit() + ")";
        g[ROW_HEADER][COL_GRANULAR]    = "Granular Layer (" + r.getAreaUnit() + ")";
        g[ROW_HEADER][COL_MOLECULAR]   = "Molecular Layer (" + r.getAreaUnit() + ")";
        g[ROW_HEADER][COL_PURKINJE]    = "Purkinje (" + r.getLengthUnit() + ")";

        // Area row (Purkinje's "area" — see class javadoc — goes in this row too)
        g[ROW_AREA][COL_MEASUREMENT] = "Area";
        g[ROW_AREA][COL_CEREBELLUM]  = fmt(r.getCerebellumArea());
        g[ROW_AREA][COL_GREY]        = fmt(r.getGreyMatterArea());
        g[ROW_AREA][COL_GRANULAR]    = fmt(r.getGranularLayerArea());
        g[ROW_AREA][COL_MOLECULAR]   = fmt(r.getMolecularLayerArea());
        g[ROW_AREA][COL_PURKINJE]    = fmt(r.getTotalPurkinjeArea());

        // Length row
        g[ROW_LENGTH][COL_MEASUREMENT] = "Length";
        g[ROW_LENGTH][COL_PURKINJE]    = fmt(r.getTotalPurkinjeLength());

        // Per-subsection rows
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

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
