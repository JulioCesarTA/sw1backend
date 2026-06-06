package com.workflow.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Service
public class CollabExportService {

    // ── Xlsx/Xls → HTML ──────────────────────────────────────────────────────

    public String readXlsxAsHtml(byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        String hdrCell = "style=\"background:#e2e8f0;font-weight:700;text-align:center;padding:3px 8px;" +
                "border:1px solid #94a3b8;font-size:0.72rem;color:#475569;user-select:none;white-space:nowrap\"";
        String rowNum  = "style=\"background:#f1f5f9;font-weight:600;text-align:right;padding:3px 8px;" +
                "border:1px solid #94a3b8;font-size:0.72rem;color:#64748b;user-select:none\"";
        String dataCell = "style=\"padding:4px 8px;border:1px solid #cbd5e1;white-space:pre;min-width:72px\"";

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                if (wb.getNumberOfSheets() > 1) {
                    sb.append("<h2 style=\"margin:1rem 0 0.5rem;font-weight:700\">")
                      .append(escape(sheet.getSheetName())).append("</h2>");
                }

                int lastRow = sheet.getLastRowNum();
                int maxCol = 0;
                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row != null && row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
                }
                if (maxCol == 0) continue;
                maxCol = Math.min(maxCol, 26); // cap en Z

                sb.append("<table style=\"border-collapse:collapse;font-size:0.875rem\">");

                // Fila de encabezado: esquina vacía + A B C ...
                sb.append("<thead><tr><th ").append(hdrCell).append("></th>");
                for (int c = 0; c < maxCol; c++) {
                    sb.append("<th ").append(hdrCell).append(">").append(colName(c)).append("</th>");
                }
                sb.append("</tr></thead><tbody>");

                // Siempre desde fila 1 hasta la última con datos
                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    sb.append("<tr><td ").append(rowNum).append(">").append(r + 1).append("</td>");
                    for (int c = 0; c < maxCol; c++) {
                        Cell cell = row != null
                                ? row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL) : null;
                        sb.append("<td ").append(dataCell).append(">")
                          .append(escape(getCellValue(wb, cell))).append("</td>");
                    }
                    sb.append("</tr>");
                }
                sb.append("</tbody></table>");
                if (si < wb.getNumberOfSheets() - 1) sb.append("<p><br></p>");
            }
        }
        return sb.toString();
    }

    private String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) { n--; sb.insert(0, (char) ('A' + n % 26)); n /= 26; }
        return sb.toString();
    }

    private String getCellValue(Workbook wb, Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) && !Double.isInfinite(d)
                        ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default      -> "";
        };
    }

    // ── Docx → HTML ───────────────────────────────────────────────────────────

    public String readDocxAsHtml(byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    sb.append(paragraphToHtml(para));
                } else if (element instanceof XWPFTable table) {
                    sb.append("<table style=\"border-collapse:collapse;width:100%\">");
                    for (XWPFTableRow row : table.getRows()) {
                        sb.append("<tr>");
                        for (XWPFTableCell cell : row.getTableCells()) {
                            sb.append("<td style=\"border:1px solid #ccc;padding:4px 8px\">");
                            for (XWPFParagraph cp : cell.getParagraphs()) {
                                sb.append(paragraphToHtml(cp));
                            }
                            sb.append("</td>");
                        }
                        sb.append("</tr>");
                    }
                    sb.append("</table>");
                }
            }
        }
        return sb.toString();
    }

    private String paragraphToHtml(XWPFParagraph para) {
        String text = para.getText();
        if (text == null || text.isBlank()) return "<p><br></p>";

        String style = para.getStyle();
        String tag = "p";
        if (style != null) {
            if (style.matches("(?i)heading.?1|title")) tag = "h1";
            else if (style.matches("(?i)heading.?2")) tag = "h2";
            else if (style.matches("(?i)heading.?3")) tag = "h3";
        }

        // List detection
        if (para.getNumIlvl() != null) {
            String bullet = para.getNumIlvl().intValue() >= 0 ? "• " : "";
            return "<p>" + escape(bullet + text) + "</p>";
        }

        StringBuilder inner = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            String runText = run.getText(0);
            if (runText == null || runText.isEmpty()) continue;
            String escaped = escape(runText);
            if (run.isBold())   escaped = "<strong>" + escaped + "</strong>";
            if (run.isItalic()) escaped = "<em>" + escaped + "</em>";
            if ("Courier New".equalsIgnoreCase(run.getFontFamily())) escaped = "<code>" + escaped + "</code>";
            inner.append(escaped);
        }
        String content = inner.isEmpty() ? escape(text) : inner.toString();
        return "<" + tag + ">" + content + "</" + tag + ">";
    }

    private String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
