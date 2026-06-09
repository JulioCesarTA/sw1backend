package com.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class CollabExportService {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Xlsx → JSON grid ([[row1],[row2],...]) ────────────────────────────────

    public String xlsxToGridJson(byte[] data) throws IOException {
        List<List<String>> grid = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return "[[]]";
            int lastRow = sheet.getLastRowNum();
            int maxCol  = 0;
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
            }
            maxCol = Math.min(maxCol, 50);
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                List<String> rowData = new ArrayList<>();
                for (int c = 0; c < maxCol; c++) {
                    Cell cell = row != null ? row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL) : null;
                    rowData.add(getCellValue(wb, cell));
                }
                grid.add(rowData);
            }
        }
        return mapper.writeValueAsString(grid);
    }

    // ── JSON grid → Xlsx bytes ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public byte[] gridJsonToXlsxBytes(String gridJson) throws IOException {
        List<List<String>> grid = mapper.readValue(gridJson, new TypeReference<>() {});
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Hoja1");
            for (int r = 0; r < grid.size(); r++) {
                var row = sheet.createRow(r);
                List<String> rowData = grid.get(r);
                for (int c = 0; c < rowData.size(); c++) {
                    row.createCell(c).setCellValue(rowData.get(c) != null ? rowData.get(c) : "");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── HTML → Docx bytes ────────────────────────────────────────────────────

    public byte[] htmlToDocxBytes(String html) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            Document jsoupDoc = Jsoup.parse(html != null ? html : "");
            for (Element el : jsoupDoc.body().children()) {
                renderElement(doc, el);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private void renderElement(XWPFDocument doc, Element el) {
        switch (el.tagName().toLowerCase()) {
            case "h1" -> createStyledParagraph(doc, el, true, 28);
            case "h2" -> createStyledParagraph(doc, el, true, 24);
            case "h3" -> createStyledParagraph(doc, el, true, 20);
            case "p", "blockquote", "div" -> createParagraph(doc, el);
            case "ul" -> el.select("> li").forEach(li -> createBullet(doc, "• " + li.text()));
            case "ol" -> {
                int[] idx = {1};
                el.select("> li").forEach(li -> createBullet(doc, idx[0]++ + ". " + li.text()));
            }
            case "table" -> createTable(doc, el);
            default -> { if (!el.text().isBlank()) createParagraph(doc, el); }
        }
    }

    private void createStyledParagraph(XWPFDocument doc, Element el, boolean bold, int sizePt) {
        XWPFParagraph para = doc.createParagraph();
        XWPFRun run = para.createRun();
        run.setText(el.text());
        run.setBold(bold);
        run.setFontSize(sizePt);
    }

    private void createParagraph(XWPFDocument doc, Element el) {
        XWPFParagraph para = doc.createParagraph();
        addInlineRuns(para, el);
    }

    private void createBullet(XWPFDocument doc, String text) {
        XWPFParagraph para = doc.createParagraph();
        XWPFRun run = para.createRun();
        run.setText(text);
    }

    private void addInlineRuns(XWPFParagraph para, Element el) {
        for (org.jsoup.nodes.Node node : el.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode tn) {
                String t = tn.text();
                if (!t.isEmpty()) { XWPFRun r = para.createRun(); r.setText(t); }
            } else if (node instanceof Element child) {
                XWPFRun r = para.createRun();
                r.setText(child.text());
                String ct = child.tagName().toLowerCase();
                if (ct.equals("strong") || ct.equals("b")) r.setBold(true);
                if (ct.equals("em")     || ct.equals("i")) r.setItalic(true);
                if (ct.equals("code")) r.setFontFamily("Courier New");
            }
        }
        if (para.getRuns().isEmpty() && !el.text().isBlank()) {
            para.createRun().setText(el.text());
        }
    }

    private void createTable(XWPFDocument doc, Element el) {
        var rows = el.select("tr");
        if (rows.isEmpty()) return;
        int cols = rows.stream().mapToInt(r -> r.select("td,th").size()).max().orElse(1);
        XWPFTable table = doc.createTable(rows.size(), cols);
        for (int r = 0; r < rows.size(); r++) {
            var cells = rows.get(r).select("td,th");
            XWPFTableRow tableRow = table.getRow(r);
            for (int c = 0; c < cells.size() && c < cols; c++) {
                tableRow.getCell(c).setText(cells.get(c).text());
            }
        }
    }

    // ── Text extraction (for audit diff) ─────────────────────────────────────

    public String extractText(String storedName, byte[] data) {
        try {
            String lower = storedName.toLowerCase();
            if (lower.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
                    StringBuilder sb = new StringBuilder();
                    doc.getParagraphs().forEach(p -> { if (!p.getText().isBlank()) sb.append(p.getText()).append("\n"); });
                    return sb.toString().trim();
                }
            } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
                    StringBuilder sb = new StringBuilder();
                    Sheet sheet = wb.getSheetAt(0);
                    if (sheet != null) {
                        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                            Row row = sheet.getRow(r);
                            if (row == null) continue;
                            for (Cell cell : row) { sb.append(getCellValue(wb, cell)).append("\t"); }
                            sb.append("\n");
                        }
                    }
                    return sb.toString().trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public String textFromGridJson(String gridJson) {
        try {
            List<List<String>> grid = mapper.readValue(gridJson, new TypeReference<>() {});
            StringBuilder sb = new StringBuilder();
            for (List<String> row : grid) {
                sb.append(row.stream()
                        .map(c -> c == null ? "" : c)
                        .collect(java.util.stream.Collectors.joining("\t")));
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception ignored) {
            return gridJson;
        }
    }

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
