package org.cerebellum.morphometry.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XlsxWriterTest {

    @Test
    void cellReferences() {
        assertEquals("A1", XlsxWriter.cellRef(0, 0));
        assertEquals("Z1", XlsxWriter.cellRef(0, 25));
        assertEquals("AA1", XlsxWriter.cellRef(0, 26));
        assertEquals("AB10", XlsxWriter.cellRef(9, 27));
        assertEquals("ZZ3", XlsxWriter.cellRef(2, 701));
        assertEquals("AAA1", XlsxWriter.cellRef(0, 702));
    }

    @Test
    void escapesMarkupAndDropsCharactersXmlCannotHold() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot; &apos;d&apos;", XlsxWriter.escape("a <b> & \"c\" 'd'"));
        assertEquals("ok", XlsxWriter.escape("o\u0001k\u0000"));
        assertEquals("µm² line1\nline2", XlsxWriter.escape("µm² line1\nline2"));
    }

    @Test
    void rejectsSheetNamesExcelWouldRefuse() {
        XlsxWriter wb = new XlsxWriter();
        assertThrows(IllegalArgumentException.class, () -> wb.addSheet("a/b"));
        assertThrows(IllegalArgumentException.class, () -> wb.addSheet("x".repeat(32)));
        assertThrows(IllegalArgumentException.class, () -> wb.addSheet(""));
    }
}
