package org.cerebellum.morphometry.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal writer for Office Open XML spreadsheets ({@code .xlsx}): text and number cells, a
 * handful of fixed cell styles, frozen panes and sized columns &mdash; exactly what the results
 * table needs, and nothing more.
 *
 * <p>This replaces Apache POI, which the plugin previously bundled (with its dependencies) into
 * a 23&nbsp;MB jar. Those libraries &mdash; commons-io, commons-compress, commons-codec, log4j and
 * others &mdash; are also shipped by FIJI itself, usually at different versions, and two copies of
 * the same classes on one classpath is a well-known source of hard-to-diagnose FIJI errors.
 * Only the parts every spreadsheet reader requires are written (content types, relationships,
 * workbook, styles, one XML part per sheet, with inline strings); the test suite reads the
 * output back with Apache POI, as a test-only dependency, to check it.</p>
 */
final class XlsxWriter {

    /** Cell styles; the ordinal is the index into the {@code cellXfs} table written in styles.xml. */
    enum Style {
        /** Default: no formatting. */
        PLAIN,
        /** Bold on grey, with a bottom border: column headers. */
        HEADER,
        /** Four decimal places. */
        NUMBER,
        /** Italic: subsection labels. */
        ITALIC,
        /** Bold: summary-row labels. */
        BOLD,
        /** Bold, four decimal places: summary-row values. */
        BOLD_NUMBER,
        /** Wrapped text, top-aligned: long free-text values. */
        WRAP
    }

    private static final double MIN_COLUMN_WIDTH = 8;
    private static final double MAX_COLUMN_WIDTH = 90;

    private final List<Sheet> sheets = new ArrayList<>();

    /** Adds a sheet. Names must be unique, at most 31 characters, and free of {@code []:*?/\}. */
    Sheet addSheet(String name) {
        if (name.isEmpty() || name.length() > 31 || name.matches(".*[\\[\\]:*?/\\\\].*")) {
            throw new IllegalArgumentException("Invalid sheet name: " + name);
        }
        Sheet s = new Sheet(name);
        sheets.add(s);
        return s;
    }

    /** One worksheet. Rows and columns are zero-based. */
    static final class Sheet {
        private final String name;
        private final List<List<Cell>> rows = new ArrayList<>();
        private int frozenCols;
        private int frozenRows;

        private Sheet(String name) {
            this.name = name;
        }

        /** Keeps the first {@code cols} columns and {@code rows} rows in view while scrolling. */
        Sheet freeze(int cols, int rows) {
            this.frozenCols = cols;
            this.frozenRows = rows;
            return this;
        }

        Sheet text(int row, int col, String value, Style style) {
            if (value != null) {
                put(row, col, new Cell(value, Double.NaN, style));
            }
            return this;
        }

        /** Writes a number; NaN and infinities are left as an empty cell (Excel has no NaN). */
        Sheet number(int row, int col, double value, Style style) {
            if (Double.isFinite(value)) {
                put(row, col, new Cell(null, value, style));
            }
            return this;
        }

        /** Applies {@code style} to an empty cell, so e.g. a bold row stays bold across its gaps. */
        Sheet blank(int row, int col, Style style) {
            put(row, col, new Cell(null, Double.NaN, style));
            return this;
        }

        private void put(int row, int col, Cell cell) {
            while (rows.size() <= row) {
                rows.add(new ArrayList<>());
            }
            List<Cell> r = rows.get(row);
            while (r.size() <= col) {
                r.add(null);
            }
            r.set(col, cell);
        }
    }

    private static final class Cell {
        final String text;   // non-null for a text cell
        final double number; // finite for a number cell
        final Style style;

        Cell(String text, double number, Style style) {
            this.text = text;
            this.number = number;
            this.style = style;
        }

        boolean isNumber() {
            return text == null && Double.isFinite(number);
        }

        /** Approximate displayed width in characters, for column sizing. */
        int displayLength() {
            if (text != null) {
                int longestLine = 0;
                for (String line : text.split("\n", -1)) {
                    longestLine = Math.max(longestLine, line.length());
                }
                return longestLine;
            }
            if (isNumber()) {
                boolean fixed = style == Style.NUMBER || style == Style.BOLD_NUMBER;
                return (fixed ? String.format(Locale.ROOT, "%.4f", number) : Double.toString(number)).length();
            }
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Serialisation
    // -----------------------------------------------------------------------

    void write(OutputStream out) throws IOException {
        if (sheets.isEmpty()) {
            throw new IllegalStateException("A workbook needs at least one sheet");
        }
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        entry(zip, "[Content_Types].xml", contentTypes());
        entry(zip, "_rels/.rels", rootRels());
        entry(zip, "xl/workbook.xml", workbook());
        entry(zip, "xl/_rels/workbook.xml.rels", workbookRels());
        entry(zip, "xl/styles.xml", STYLES);
        for (int i = 0; i < sheets.size(); i++) {
            entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", worksheet(sheets.get(i), i == 0));
        }
        zip.finish();
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private String contentTypes() {
        StringBuilder sb = new StringBuilder(XML_DECL);
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
          .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
          .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
          .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
          .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets.size(); i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
              .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static String rootRels() {
        return XML_DECL
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private String workbook() {
        StringBuilder sb = new StringBuilder(XML_DECL);
        sb.append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">");
        sb.append("<sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(escape(sheets.get(i).name)).append("\" sheetId=\"").append(i + 1)
              .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return sb.append("</sheets></workbook>").toString();
    }

    private String workbookRels() {
        StringBuilder sb = new StringBuilder(XML_DECL);
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(NS_REL)
              .append("/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"").append(NS_REL)
          .append("/styles\" Target=\"styles.xml\"/>");
        return sb.append("</Relationships>").toString();
    }

    /**
     * Fonts: 0 regular, 1 bold, 2 italic. Fills: 0 and 1 are the two Excel requires, 2 is the
     * header grey. Borders: 0 none, 1 thin bottom. The {@code cellXfs} entries are in {@link Style}
     * order; custom number format 164 is {@code 0.0000}.
     */
    private static final String STYLES = XML_DECL
            + "<styleSheet xmlns=\"" + NS_MAIN + "\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.0000\"/></numFmts>"
            + "<fonts count=\"3\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><i/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "</fonts>"
            + "<fills count=\"3\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFC0C0C0\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"2\">"
            + "<border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left/><right/><top/><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border>"
            + "</borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"7\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
            + "<alignment vertical=\"top\" wrapText=\"1\"/></xf>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";

    private static String worksheet(Sheet sheet, boolean selected) {
        StringBuilder sb = new StringBuilder(XML_DECL);
        sb.append("<worksheet xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">");

        // Element order is fixed by the schema: sheetViews, sheetFormatPr, cols, sheetData.
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"").append(selected ? " tabSelected=\"1\"" : "").append(">");
        if (sheet.frozenCols > 0 || sheet.frozenRows > 0) {
            String topLeft = cellRef(sheet.frozenRows, sheet.frozenCols);
            String pane = sheet.frozenCols > 0 && sheet.frozenRows > 0 ? "bottomRight"
                    : sheet.frozenRows > 0 ? "bottomLeft" : "topRight";
            sb.append("<pane");
            if (sheet.frozenCols > 0) {
                sb.append(" xSplit=\"").append(sheet.frozenCols).append('"');
            }
            if (sheet.frozenRows > 0) {
                sb.append(" ySplit=\"").append(sheet.frozenRows).append('"');
            }
            sb.append(" topLeftCell=\"").append(topLeft).append("\" activePane=\"").append(pane)
              .append("\" state=\"frozen\"/>");
            sb.append("<selection pane=\"").append(pane).append("\" activeCell=\"").append(topLeft)
              .append("\" sqref=\"").append(topLeft).append("\"/>");
        }
        sb.append("</sheetView></sheetViews>");
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>");

        double[] widths = columnWidths(sheet);
        if (widths.length > 0) {
            sb.append("<cols>");
            for (int c = 0; c < widths.length; c++) {
                sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                  .append(String.format(Locale.ROOT, "%.2f", widths[c])).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }

        sb.append("<sheetData>");
        for (int r = 0; r < sheet.rows.size(); r++) {
            List<Cell> row = sheet.rows.get(r);
            sb.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < row.size(); c++) {
                Cell cell = row.get(c);
                if (cell == null) {
                    continue;
                }
                sb.append("<c r=\"").append(cellRef(r, c)).append('"');
                if (cell.style != Style.PLAIN) {
                    sb.append(" s=\"").append(cell.style.ordinal()).append('"');
                }
                if (cell.text != null) {
                    sb.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(cell.text))
                      .append("</t></is></c>");
                } else if (cell.isNumber()) {
                    sb.append("><v>").append(Double.toString(cell.number)).append("</v></c>");
                } else {
                    sb.append("/>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static double[] columnWidths(Sheet sheet) {
        int cols = 0;
        for (List<Cell> row : sheet.rows) {
            cols = Math.max(cols, row.size());
        }
        double[] widths = new double[cols];
        for (List<Cell> row : sheet.rows) {
            for (int c = 0; c < row.size(); c++) {
                Cell cell = row.get(c);
                if (cell != null) {
                    widths[c] = Math.max(widths[c], cell.displayLength() * 1.1 + 2);
                }
            }
        }
        for (int c = 0; c < cols; c++) {
            widths[c] = Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_COLUMN_WIDTH, widths[c]));
        }
        return widths;
    }

    /** A1-style reference for a zero-based row and column. */
    static String cellRef(int row, int col) {
        StringBuilder letters = new StringBuilder();
        int c = col + 1;
        while (c > 0) {
            int rem = (c - 1) % 26;
            letters.insert(0, (char) ('A' + rem));
            c = (c - 1) / 26;
        }
        return letters.append(row + 1).toString();
    }

    /** XML-escapes {@code s} and drops characters XML 1.0 cannot represent at all. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&':  sb.append("&amp;"); break;
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= 0x20 && ch != 0xFFFE && ch != 0xFFFF) {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}
