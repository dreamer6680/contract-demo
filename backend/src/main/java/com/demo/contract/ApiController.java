package com.demo.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Path storage;
  private final Path templatePath;

  public ApiController(JdbcTemplate jdbc, ObjectMapper mapper,
      @Value("${app.storage-dir:/app/storage}") String storageDir) throws IOException {
    this.jdbc = jdbc; this.mapper = mapper;
    this.storage = Paths.get(storageDir).toAbsolutePath().normalize();
    Files.createDirectories(storage);
    this.templatePath = Paths.get("/app/templates/coal-sale-contract.docx");
  }

  @GetMapping("/health") public Map<String,Object> health() {
    return Map.of("status", "ok", "time", LocalDateTime.now().toString(), "templateExists", Files.exists(templatePath));
  }

  @GetMapping("/contract-template") public Map<String,Object> contractTemplate() {
    return Map.of("name", "煤炭买卖合同", "source", "contract-template-v2.docx", "fieldCount", fields().size(), "sections", List.of("合同封面", "合同填写说明", "买卖双方信息", "煤炭买卖数量、品种、质量", "合同价格", "交货时间、地点、方式及所有权的转移", "数量确认", "质量确认", "运输", "结算与支付", "违约责任", "免责条款", "争议解决", "保密义务", "其他约定事项", "签署页"));
  }

  @GetMapping("/contract-fields") public List<Map<String,Object>> contractFields() { return fields(); }

  private List<Map<String,Object>> fields() {
    String[][] rows = {
      {"seller.name","卖方企业名称","晋北能源贸易有限公司"}, {"seller.creditCode","卖方统一社会信用代码","91140000DEMO000001"},
      {"seller.address","卖方地址","山西省大同市示例地址"}, {"seller.legalPersonName","卖方法定代表人","张某"},
      {"seller.agentName","卖方委托代理人","李某"}, {"seller.agentMobile","卖方联系电话","13800000001"},
      {"seller.bank.bankFullName","卖方开户银行","中国银行山西省分行"}, {"seller.bank.accountName","卖方账户名称","晋北能源贸易有限公司"}, {"seller.bank.accountNo","卖方账户号码","6210000000000000000"},
      {"buyer.name","买方企业名称","山西宏坤千锦能源有限公司"}, {"buyer.creditCode","买方统一社会信用代码","91140000DEMO000002"},
      {"buyer.address","买方地址","山西省太原市示例地址"}, {"buyer.legalPersonName","买方法定代表人","王某"},
      {"buyer.agentName","买方委托代理人","赵某"}, {"buyer.agentMobile","买方联系电话","13800000002"},
      {"buyer.bank.bankFullName","买方开户银行","中国建设银行山西省分行"}, {"buyer.bank.accountName","买方账户名称","山西宏坤千锦能源有限公司"}, {"buyer.bank.accountNo","买方账户号码","6222000000000000000"},
      {"contractNo","合同编号","HKQJ-2026-001"}, {"contractBeginDate","合同开始日期","2026年11月1日"}, {"contractEndDate","合同结束日期","2026年12月31日"},
      {"product.quantity","合同数量（吨）","5000"}, {"product.type","煤炭品种","动力煤"}, {"product.specs","煤炭品质规格","Q5500，具体以双方确认指标为准"},
      {"product.priceInChinese","合计含税金额大写","人民币叁佰肆拾万元整"}, {"product.unitPrice","含税单价（元/吨）","680.00"},
      {"deliveryLocation","交货地点","山西省指定矿厂/站台"}, {"deliveryWay","交货方式","乙方自提，公路运输"}, {"product.guaWaterDesc","数量/水分补充约定","水分及相关指标按双方确认的检测结果执行。"}, {"signDate","签署日期","2026年10月26日"}
    };
    List<Map<String,Object>> out = new ArrayList<>();
    for (String[] row : rows) out.add(Map.of("key",row[0],"label",row[1],"defaultValue",row[2]));
    return out;
  }

  @PostMapping("/documents") public Map<String,Object> generate(@RequestBody Map<String,Object> body) throws Exception {
    Map<String,String> data = new LinkedHashMap<>();
    Object raw = body.get("data");
    if (raw instanceof Map<?,?> map) map.forEach((k,v) -> data.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    String id = UUID.randomUUID().toString();
    Path docx = storage.resolve("煤炭买卖合同-" + stamp + "-" + id.substring(0,8) + ".docx");
    Path pdf = storage.resolve("煤炭买卖合同-" + stamp + "-" + id.substring(0,8) + ".pdf");
    try (InputStream in = getTemplateStream(); XWPFDocument document = new XWPFDocument(in)) {
      replaceInDocument(document, data);
      try (OutputStream out = Files.newOutputStream(docx)) { document.write(out); }
    }
    Process process = new ProcessBuilder("libreoffice", "-env:UserInstallation=file:///tmp/lo-" + id, "--headless", "--convert-to", "pdf", "--outdir", storage.toString(), docx.toString()).redirectErrorStream(true).start();
    String output;
    try (InputStream is = process.getInputStream()) { output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
    if (!process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("DOCX 转 PDF 超时"); }
    if (process.exitValue() != 0 || !Files.exists(pdf)) {
      // LibreOffice uses the source basename for its output, which is the same basename as pdf above.
      throw new IllegalStateException("DOCX 转 PDF 失败：" + output);
    }
    jdbc.update("INSERT INTO contract_document_v2(template_name,docx_path,pdf_path,data_snapshot) VALUES(?,?,?,?)", "煤炭买卖合同", docx.toString(), pdf.toString(), mapper.writeValueAsString(data));
    Long docId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    return Map.of("id", docId, "templateName", "煤炭买卖合同", "pdfFileName", pdf.getFileName().toString(), "docxFileName", docx.getFileName().toString(), "downloadUrl", "/api/documents/" + docId + "/download", "docxUrl", "/api/documents/" + docId + "/download-docx", "message", "已按原始 DOCX 模板填充数据并生成 PDF。建议先核对分页、字体、表格及签署位置。", "conversionLog", output.strip());
  }

  private InputStream getTemplateStream() throws IOException {
    if (Files.exists(templatePath)) return Files.newInputStream(templatePath);
    InputStream in = getClass().getResourceAsStream("/templates/coal-sale-contract.docx");
    if (in == null) throw new FileNotFoundException("找不到原始 DOCX 模板");
    return in;
  }

  @GetMapping("/documents") public List<Map<String,Object>> documents() {
    return jdbc.query("SELECT id,template_name,pdf_path,docx_path,created_at FROM contract_document_v2 ORDER BY id DESC LIMIT 50", (rs,n) -> Map.of(
      "id",rs.getLong("id"), "templateName",rs.getString("template_name"), "pdfFileName",Paths.get(rs.getString("pdf_path")).getFileName().toString(),
      "docxFileName",Paths.get(rs.getString("docx_path")).getFileName().toString(), "createdAt",rs.getTimestamp("created_at").toString(),
      "downloadUrl","/api/documents/"+rs.getLong("id")+"/download", "docxUrl","/api/documents/"+rs.getLong("id")+"/download-docx"));
  }

  @GetMapping("/documents/{id}/download") public ResponseEntity<Resource> downloadPdf(@PathVariable long id) throws Exception { return download(id, false); }
  @GetMapping("/documents/{id}/download-docx") public ResponseEntity<Resource> downloadDocx(@PathVariable long id) throws Exception { return download(id, true); }
  private ResponseEntity<Resource> download(long id, boolean word) throws Exception {
    Map<String,Object> row = jdbc.queryForMap("SELECT pdf_path,docx_path FROM contract_document_v2 WHERE id=?", id);
    Path p = Paths.get(String.valueOf(row.get(word ? "docx_path" : "pdf_path"))).toAbsolutePath().normalize();
    if (!p.startsWith(storage) || !Files.isRegularFile(p)) return ResponseEntity.notFound().build();
    String filename = p.getFileName().toString();
    MediaType type = word ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document") : MediaType.APPLICATION_PDF;
    return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(p));
  }

  private void replaceInDocument(XWPFDocument doc, Map<String,String> data) {
    for (XWPFParagraph p : doc.getParagraphs()) replaceParagraph(p, data);
    for (XWPFTable t : doc.getTables()) replaceTable(t, data);
    for (XWPFHeader h : doc.getHeaderList()) { for (XWPFParagraph p : h.getParagraphs()) replaceParagraph(p,data); for (XWPFTable t : h.getTables()) replaceTable(t,data); }
    for (XWPFFooter f : doc.getFooterList()) { for (XWPFParagraph p : f.getParagraphs()) replaceParagraph(p,data); for (XWPFTable t : f.getTables()) replaceTable(t,data); }
  }
  private void replaceTable(XWPFTable table, Map<String,String> data) {
    for (XWPFTableRow row : table.getRows()) for (XWPFTableCell cell : row.getTableCells()) {
      for (XWPFParagraph p : cell.getParagraphs()) replaceParagraph(p,data);
      for (XWPFTable nested : cell.getTables()) replaceTable(nested,data);
    }
  }
  private void replaceParagraph(XWPFParagraph p, Map<String,String> data) {
    List<XWPFRun> runs = p.getRuns(); if (runs == null || runs.isEmpty()) return;
    StringBuilder joined = new StringBuilder(); for (XWPFRun r : runs) joined.append(r.text() == null ? "" : r.text());
    String original = joined.toString();
    Matcher m = Pattern.compile("\\{<\\$\\.([A-Za-z0-9_.]+)>}").matcher(original);
    StringBuffer sb = new StringBuffer();
    while (m.find()) { String value = data.getOrDefault(m.group(1), ""); m.appendReplacement(sb, Matcher.quoteReplacement(value)); }
    m.appendTail(sb); String replaced = sb.toString();
    if (!original.equals(replaced)) { runs.get(0).setText(replaced, 0); for (int i=1;i<runs.size();i++) runs.get(i).setText("",0); }
  }
}
