package com.workflow.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CollabExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // ── Word ─────────────────────────────────────────────────────────────────

    public byte[] exportWord(String title, String html) throws IOException {
        XWPFDocument doc = new XWPFDocument();

        // Title
        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText(title);
        titleRun.setBold(true);
        titleRun.setFontSize(20);
        titleRun.setColor("1F4E79");

        // Date
        XWPFParagraph datePara = doc.createParagraph();
        datePara.setAlignment(ParagraphAlignment.RIGHT);
        XWPFRun dateRun = datePara.createRun();
        dateRun.setText("Exportado: " + LocalDateTime.now().format(FMT));
        dateRun.setFontSize(9);
        dateRun.setColor("888888");
        dateRun.setItalic(true);

        // Empty line
        doc.createParagraph();

        // Parse HTML and write content
        Document jsoup = Jsoup.parse(html != null ? html : "");
        for (Element el : jsoup.body().children()) {
            writeElement(doc, el);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        doc.close();
        return out.toByteArray();
    }

    private void writeElement(XWPFDocument doc, Element el) {
        String tag = el.tagName().toLowerCase();

        if (tag.matches("h[1-6]")) {
            int level = tag.charAt(1) - '0';
            XWPFParagraph para = doc.createParagraph();
            XWPFRun run = para.createRun();
            run.setText(el.text());
            run.setBold(true);
            run.setFontSize(level == 1 ? 18 : level == 2 ? 15 : 13);
            run.setColor("1F4E79");
            return;
        }

        if (tag.equals("p") || tag.equals("div")) {
            XWPFParagraph para = doc.createParagraph();
            writeInlineNodes(para, el.childNodes());
            return;
        }

        if (tag.equals("ul") || tag.equals("ol")) {
            int index = 1;
            for (Element li : el.select("> li")) {
                XWPFParagraph para = doc.createParagraph();
                para.setIndentationLeft(400);
                XWPFRun run = para.createRun();
                String bullet = tag.equals("ul") ? "• " : (index++) + ". ";
                run.setText(bullet);
                writeInlineNodes(para, li.childNodes());
            }
            return;
        }

        if (tag.equals("blockquote")) {
            XWPFParagraph para = doc.createParagraph();
            para.setIndentationLeft(600);
            XWPFRun run = para.createRun();
            run.setItalic(true);
            run.setColor("64748b");
            run.setText(el.text());
            return;
        }

        // Fallback: treat as paragraph if it has text
        String text = el.text().trim();
        if (!text.isEmpty()) {
            XWPFParagraph para = doc.createParagraph();
            XWPFRun run = para.createRun();
            run.setText(text);
        }
    }

    private void writeInlineNodes(XWPFParagraph para, List<Node> nodes) {
        for (Node node : nodes) {
            if (node instanceof TextNode textNode) {
                String text = textNode.text();
                if (!text.isEmpty()) {
                    para.createRun().setText(text);
                }
            } else if (node instanceof Element el) {
                String tag = el.tagName().toLowerCase();
                XWPFRun run = para.createRun();
                run.setText(el.text());
                switch (tag) {
                    case "strong", "b" -> run.setBold(true);
                    case "em", "i"     -> run.setItalic(true);
                    case "code"        -> { run.setFontFamily("Courier New"); run.setFontSize(9); }
                    default            -> {}
                }
            }
        }
    }

    // ── Excel ─────────────────────────────────────────────────────────────────

    public byte[] exportExcel(String title, String html) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet(title.length() > 31 ? title.substring(0, 31) : title);

        // Title style
        CellStyle titleStyle = wb.createCellStyle();
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleFont.setColor(IndexedColors.DARK_BLUE.getIndex());
        titleStyle.setFont(titleFont);
        titleStyle.setAlignment(HorizontalAlignment.CENTER);

        // Date style
        CellStyle dateStyle = wb.createCellStyle();
        Font dateFont = wb.createFont();
        dateFont.setItalic(true);
        dateFont.setFontHeightInPoints((short) 9);
        dateFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        dateStyle.setFont(dateFont);
        dateStyle.setAlignment(HorizontalAlignment.RIGHT);

        // Alt row fill
        CellStyle altStyle = wb.createCellStyle();
        altStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        altStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        altStyle.setWrapText(true);

        CellStyle plainStyle = wb.createCellStyle();
        plainStyle.setWrapText(true);

        // Row 0: title
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(24);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        // Row 1: date
        Row dateRow = sheet.createRow(1);
        Cell dateCell = dateRow.createCell(0);
        dateCell.setCellValue("Exportado: " + LocalDateTime.now().format(FMT));
        dateCell.setCellStyle(dateStyle);

        // Si el HTML tiene tablas (exportado desde Excel importado), reconstruir la grilla
        Document jsoupDoc = Jsoup.parse(html != null ? html : "");
        Elements tables = jsoupDoc.select("table");
        if (!tables.isEmpty()) {
            // Encabezado de columnas (fila 3): skip la primera <th> que es el número de fila
            int excelRow = 3;
            for (Element table : tables) {
                Elements rows = table.select("tr");
                for (Element tr : rows) {
                    Elements cells = tr.select("td, th");
                    if (cells.isEmpty()) continue;
                    // La primera celda es el número de fila (esquina/rowNum) — omitirla
                    Row row = sheet.createRow(excelRow++);
                    row.setHeightInPoints(18);
                    for (int ci = 1; ci < cells.size(); ci++) {
                        String val = cells.get(ci).text().trim();
                        Cell cell = row.createCell(ci - 1);
                        // Intentar como número
                        try { cell.setCellValue(Double.parseDouble(val)); }
                        catch (NumberFormatException e) { cell.setCellValue(val); }
                        cell.setCellStyle((excelRow % 2 == 0) ? altStyle : plainStyle);
                    }
                }
                // Fila vacía entre hojas
                excelRow++;
            }
            for (int c = 0; c < 26; c++) sheet.setColumnWidth(c, 256 * 14);
        } else {
            // Documento de texto plano
            List<String> lines = extractLines(html);
            for (int i = 0; i < lines.size(); i++) {
                Row row = sheet.createRow(i + 3);
                row.setHeightInPoints(18);
                Cell cell = row.createCell(0);
                cell.setCellValue(lines.get(i));
                cell.setCellStyle((i % 2 == 0) ? altStyle : plainStyle);
            }
            sheet.setColumnWidth(0, 256 * 100);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private List<String> extractLines(String html) {
        Document doc = Jsoup.parse(html != null ? html : "");
        return doc.body().children().stream()
                .map(el -> {
                    String tag = el.tagName().toLowerCase();
                    if (tag.matches("h[1-6]")) return el.text().toUpperCase();
                    if (tag.equals("blockquote")) return "  > " + el.text();
                    if (tag.equals("ul")) {
                        return el.select("> li").stream()
                                .map(li -> "  • " + li.text())
                                .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);
                    }
                    if (tag.equals("ol")) {
                        final int[] idx = {1};
                        return el.select("> li").stream()
                                .map(li -> "  " + idx[0]++ + ". " + li.text())
                                .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);
                    }
                    return el.text().trim();
                })
                .filter(line -> !line.isBlank())
                .toList();
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
