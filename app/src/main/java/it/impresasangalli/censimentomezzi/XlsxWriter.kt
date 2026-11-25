package it.impresasangalli.censimentomezzi

import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Cella del foglio Excel. */
sealed class Cell {
    data class Text(val v: String) : Cell()
    data class Date(val v: LocalDate) : Cell()
    data class Time(val v: LocalTime) : Cell()
    /** Cella vuota con formato data (es. data di uscita non ancora inserita). */
    object EmptyDate : Cell()
}

/**
 * Generatore minimale di file .xlsx (Office Open XML), senza librerie esterne.
 * Intestazione in grassetto, riga bloccata, filtri automatici,
 * date e ore come veri valori Excel (ordinabili e filtrabili).
 * Se [tableName] è indicato, i dati vengono definiti come Tabella Excel
 * (necessario per aggiungere righe online tramite Microsoft Graph).
 */
object XlsxWriter {

    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** Numero seriale Excel di una data. */
    fun serial(d: LocalDate): Long = ChronoUnit.DAYS.between(EPOCH, d)

    /** Frazione di giorno Excel di un orario. */
    fun fraction(t: LocalTime): Double = t.toSecondOfDay() / 86400.0

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> if (ch.code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    /** Scrive l'xlsx su [out]. Lo stream NON viene chiuso. */
    fun write(
        out: OutputStream, sheetName: String, header: List<String>, widths: List<Double>,
        rows: List<List<Cell>>, tableName: String? = null
    ) {
        val zip = ZipOutputStream(out)
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val table = tableName != null && rows.isNotEmpty()
        val lastCol = col(header.size - 1)
        val lastRow = rows.size + 1
        val sheet = esc(sheetName)

        // Stringhe condivise
        val sst = LinkedHashMap<String, Int>()
        var sstCount = 0
        fun si(s: String): Int { sstCount++; return sst.getOrPut(s) { sst.size } }

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<dimension ref="A1:$lastCol$lastRow"/>
<sheetViews><sheetView tabSelected="1" workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="15"/>
<cols>""")
        widths.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        sb.append("</cols><sheetData>")
        sb.append("<row r=\"1\">")
        header.forEachIndexed { i, h -> sb.append("<c r=\"${col(i)}1\" t=\"s\" s=\"1\"><v>${si(h)}</v></c>") }
        sb.append("</row>")
        rows.forEachIndexed { ri, row ->
            val r = ri + 2
            sb.append("<row r=\"$r\">")
            row.forEachIndexed { ci, cell ->
                val ref = "${col(ci)}$r"
                when (cell) {
                    is Cell.Text -> sb.append("<c r=\"$ref\" t=\"s\" s=\"0\"><v>${si(cell.v)}</v></c>")
                    is Cell.Date -> sb.append("<c r=\"$ref\" s=\"2\"><v>${serial(cell.v)}</v></c>")
                    is Cell.Time -> sb.append("<c r=\"$ref\" s=\"3\"><v>${fraction(cell.v)}</v></c>")
                    Cell.EmptyDate -> sb.append("<c r=\"$ref\" s=\"2\"/>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (!table) sb.append("<autoFilter ref=\"A1:$lastCol$lastRow\"/>")
        sb.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        if (table) sb.append("<tableParts count=\"1\"><tablePart r:id=\"rId1\"/></tableParts>")
        sb.append("</worksheet>")

        put("[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
${if (table) "<Override PartName=\"/xl/tables/table1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/>" else ""}
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>""")

        put("_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>""")

        put("docProps/core.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>Censimento Mezzi</dc:title><dc:creator>Impresa Sangalli - Censimento Mezzi</dc:creator>
</cp:coreProperties>""")

        put("docProps/app.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Censimento Mezzi</Application></Properties>""")

        val defined = if (table) "" else
            "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">'$sheet'!\$A\$1:\$$lastCol\$$lastRow</definedName></definedNames>"
        put("xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<bookViews><workbookView/></bookViews>
<sheets><sheet name="$sheet" sheetId="1" r:id="rId1"/></sheets>
$defined
</workbook>""")

        put("xl/_rels/workbook.xml.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>""")

        // Stili: 0 normale, 1 intestazione, 2 data, 3 ora
        put("xl/styles.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/><numFmt numFmtId="165" formatCode="hh:mm:ss"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/><family val="2"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1B7F3B"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>""")

        put("xl/worksheets/sheet1.xml", sb.toString())

        val ss = StringBuilder()
        ss.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        ss.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"$sstCount\" uniqueCount=\"${sst.size}\">")
        sst.keys.forEach { ss.append("<si><t xml:space=\"preserve\">${esc(it)}</t></si>") }
        ss.append("</sst>")
        put("xl/sharedStrings.xml", ss.toString())

        if (table) {
            put("xl/worksheets/_rels/sheet1.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table1.xml"/>
</Relationships>""")
            val tn = esc(tableName!!)
            val tc = StringBuilder()
            header.forEachIndexed { i, h -> tc.append("<tableColumn id=\"${i + 1}\" name=\"${esc(h)}\"/>") }
            put("xl/tables/table1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="1" name="$tn" displayName="$tn" ref="A1:$lastCol$lastRow" totalsRowShown="0"><autoFilter ref="A1:$lastCol$lastRow"/><tableColumns count="${header.size}">$tc</tableColumns><tableStyleInfo name="TableStyleLight8" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/></table>""")
        }
        zip.finish()
        zip.flush()
    }
}
