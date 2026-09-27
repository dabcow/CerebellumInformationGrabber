package org.cerebellum.morphometry.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.cerebellum.morphometry.Diagnostics;
import org.cerebellum.morphometry.model.MorphometryResults;
import org.cerebellum.morphometry.model.MorphometryResults.SubsectionResult;
import org.cerebellum.morphometry.model.RunInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpreadsheetExporterTest {

    private final Locale originalLocale = Locale.getDefault();

    @TempDir
    Path dir;

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    private static MorphometryResults sample() {
        return new MorphometryResults(453660.123456, 337396.5, 126036.25, 211360.75, 1466.123456789, Double.NaN,
                List.of(new SubsectionResult("2Cb", 15000.5, 26000.25, 180.125),
                        new SubsectionResult("3Cb", 14000.0, 23000.0, 170.0)),
                "µm²", "µm");
    }

    @Test
    void gridHasTheDocumentedLayout() {
        String[][] g = SpreadsheetExporter.buildDataGrid(sample());
        assertArrayEquals(new String[] {"Measurement", "Cerebellum (µm²)", "Grey Matter (µm²)",
                "Granular Layer (µm²)", "Molecular Layer (µm²)", "Purkinje (µm)"}, g[0]);
        assertArrayEquals(new String[] {"Area", "453660.1235", "337396.5000", "126036.2500", "211360.7500", null}, g[1],
                "a value that could not be measured (NaN) is left empty");
        assertArrayEquals(new String[] {"Length", null, null, null, null, "1466.1235"}, g[2]);
        assertArrayEquals(new String[] {"2Cb", null, null, "15000.5000", "26000.2500", "180.1250"}, g[3]);
        assertEquals(5, g.length);
    }

    @Test
    void numbersUseADotWhateverTheLocale() {
        // v1.0.0 wrote "453660,1235" on e.g. German systems.
        Locale.setDefault(Locale.GERMANY);
        assertEquals("453660.1235", SpreadsheetExporter.buildDataGrid(sample())[1][1]);
    }

    @Test
    void writesCsv() throws Exception {
        File csv = dir.resolve("out.csv").toFile();
        Files.writeString(csv.toPath(), "previous contents that must be replaced");
        SpreadsheetExporter.exportCSV(sample(), csv);
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        assertEquals("Measurement,Cerebellum (µm²),Grey Matter (µm²),Granular Layer (µm²),Molecular Layer (µm²),Purkinje (µm)",
                lines.get(0));
        assertEquals("Area,453660.1235,337396.5000,126036.2500,211360.7500,", lines.get(1));
        assertEquals("2Cb,,,15000.5000,26000.2500,180.1250", lines.get(3));
        assertEquals(5, lines.size());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary files are left behind");
        }
    }

    @Test
    void writesAWorkbookThatExcelReadersAccept() throws Exception {
        Locale.setDefault(Locale.FRANCE); // v1.0.0 stored every value as text under a comma locale
        Diagnostics diag = Diagnostics.silent();
        diag.note("a note");
        diag.warn("something to check <&>");
        RunInfo info = new RunInfo(List.<String[]>of(new String[] {"Plugin", "test 1.2.3"}), diag.getEntries());

        File xlsx = dir.resolve("out.xlsx").toFile();
        SpreadsheetExporter.exportXLSX(sample(), info, xlsx);

        try (InputStream in = new FileInputStream(xlsx); Workbook wb = new XSSFWorkbook(in)) {
            assertEquals(2, wb.getNumberOfSheets());
            Sheet sheet = wb.getSheet("Cerebellar Morphometry");
            assertNotNull(sheet);

            Row header = sheet.getRow(0);
            assertEquals("Granular Layer (µm²)", header.getCell(3).getStringCellValue());
            assertTrue(wb.getFontAt(header.getCell(3).getCellStyle().getFontIndex()).getBold());

            Cell area = sheet.getRow(1).getCell(1);
            assertEquals(CellType.NUMERIC, area.getCellType());
            assertEquals(453660.123456, area.getNumericCellValue(), 0, "stored at full precision");
            assertEquals("0.0000", area.getCellStyle().getDataFormatString());
            assertTrue(wb.getFontAt(area.getCellStyle().getFontIndex()).getBold());

            assertNull(sheet.getRow(1).getCell(5), "NaN is written as an empty cell");
            assertEquals(1466.123456789, sheet.getRow(2).getCell(5).getNumericCellValue(), 0);

            Cell label = sheet.getRow(3).getCell(0);
            assertEquals("2Cb", label.getStringCellValue());
            assertTrue(wb.getFontAt(label.getCellStyle().getFontIndex()).getItalic());
            assertEquals(180.125, sheet.getRow(3).getCell(5).getNumericCellValue(), 0);

            PaneInformation pane = sheet.getPaneInformation();
            assertTrue(pane.isFreezePane());
            assertEquals(1, pane.getVerticalSplitPosition());
            assertEquals(1, pane.getHorizontalSplitPosition());

            Sheet runInfo = wb.getSheet("Run Info");
            assertEquals("Plugin", runInfo.getRow(1).getCell(0).getStringCellValue());
            assertEquals("test 1.2.3", runInfo.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Warning", runInfo.getRow(3).getCell(0).getStringCellValue());
            assertEquals("something to check <&>", runInfo.getRow(3).getCell(1).getStringCellValue());
        }
    }
}
