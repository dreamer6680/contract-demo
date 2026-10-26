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

  /** Return default clause text on every load. User edits are only applied to the current generation request. */
  @GetMapping("/contract-clauses") public List<Map<String,Object>> contractClauses() { return defaultClauses(); }

  private List<Map<String,Object>> defaultClauses() {
    List<Map<String,Object>> out = new ArrayList<>();
    out.add(clause("delivery", "交货与物权转移", "编辑交货时间、交货地点及物权转移约定。", List.of(
      "矿、厂、站台场地交货，以甲方矿厂装车时间为准。",
      "交货地点：    {<$.deliveryLocation>}",
      "交货方式：     {<$.deliveryWay>}")));
    out.add(clause("ownership", "物权转移", "编辑煤炭交付后的所有权和损益转移规则。", List.of(
      "乙方自提，甲方将煤炭交付乙方承运人，煤炭的所有权及损益同时转移给乙方。")));
    out.add(clause("quantity", "数量确认", "可修改计量规则和数量差异处理方式。", List.of(
      "数量以吨为计量单位，保留小数点后两位，四舍五入。",
      "公路运输以甲方出厂实际过磅煤炭数量为准。当期交货数量在合同约定数量±50 吨范围内，视为合同全部履行。")));
    out.add(clause("quality", "质量确认", "编辑取样、检测、复检及质量指标确认规则。", List.of(
      "公路运输的煤炭质量确认，由甲乙双方共同在交货现场（装车或卸车地点）按国家现行标准（如GB/T 475《商品煤样人工采取方法》）进行取样、制样和封存；双方共同委托具有资质的第三方检测机构进行化验，或以双方共同在场、各自按相同方法检测后共同确认的数据为准。",
      "同一批次煤炭的质量指标，按该批次内多次取样化验结果的加权平均值确定。加权平均值按每次取样所代表的煤炭数量（吨）进行加权计算；若无法按数量加权，双方可约定按车次等权重平均。各项指标计算结果保留至小数点后两位。",
      "主要质量指标标准:",
      "干燥基灰分（Ad），以%为计量单位，保留至小数点后两位数。",
      "干燥基全硫（St,d），以%为计量单位，保留至小数点后两位数。",
      "计量水（全水分Mt），以%为计量单位，保留至小数点后两位数。",
      "如一方对检测结果有异议，应在收到检测报告后3个工作日内书面通知对方，双方共同封存备查样品，并委托共同认可的检测机构进行复检；复检结果为最终结算依据。复检费用由过错方承担，若双方均无过错，则费用由双方平均分担。")));
    out.add(clause("transport", "运输条款", "编辑运输责任、承运方确定及运输过程约定。", List.of(
      "公路运输，由乙方确定承运方。")));
    out.add(clause("settlement", "结算与支付", "编辑结算方式、发票、预付款及支付方式。", List.of(
      "结算以人民币元为单位，小数点后保留两位。",
      "实行货款增值税专用发票和公路运杂费发票等分票结算。",
      "货款结构基差：乙方以非现汇支付货款时，结算价格在原现汇价格基础上上浮2%。产品基价及银行贴现率发生较大波动或卖方政策调整时，甲方有权对货款结构基差进行月度调整。",
      "执行全额预付货款。",
      "支付方式：货款以现汇或银行承兑方式支付")));
    out.add(clause("liability", "违约责任", "编辑双方责任、违约情形及责任承担方式。", List.of(
      "甲乙双方应保证本合同均衡平稳运行，如果因一方原因造成本合同不能履行，应承担违约责任。")));
    out.add(clause("force-majeure", "免责条款", "编辑不可抗力、政策变化等情形的处理规则。", List.of(
      "如在甲、乙双方履行合同期间及区域内因发生不可抗力，如战争、自然灾害、瘟疫等，使合同无法正常履行，受影响一方应立即通知对方，并积极采取补救措施，在受不可抗力影响的范围内，有关当事人可免除其责任。",
      "如在甲、乙双方履行合同期间及区域内因运输中断、重大安全生产事故、政策变化等，使合同无法正常履行，受影响一方应立即通知对方，并积极采取补救措施，有关当事人可免除其责任。")));
    out.add(clause("dispute", "争议解决", "编辑协商期限、管辖法院或其他争议处理方式。", List.of(
      "甲、乙双方在执行本合同过程中因对合同条款解释不一致、违约、中止而引起的争议、",
      "纠纷，应在60天内协商解决。逾期未能达成一致，可向合同签订所在地人民法院提起诉讼。")));
    out.add(clause("confidentiality", "保密义务", "编辑商业秘密范围和保密责任。", List.of(
      "本合同中所涉及的企业商业秘密，甲、乙双方必须严格履行保密义务，不得向第三方利害关系人提供。")));
    out.add(clause("other", "其他约定事项", "编辑补充约定和书面修改意见的效力。", List.of(
      "在合同执行过程中，甲、乙双方均有权对本合同提出书面修改意见。双方未就修改意见达成一致的，提出的修改意见不予认可。",
      "甲、乙双方就修改意见达成一致后，形成电子合同或书面文件签字盖章确认，视为本合同有效组成部分，如有不一致者以补充协议为准。")));
    return out;
  }

  private Map<String,Object> clause(String id, String title, String description, List<String> paragraphs) {
    return Map.of("id", id, "title", title, "description", description,
      "content", String.join("\n", paragraphs), "anchors", paragraphs);
  }

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
    Map<String,String> clauseEdits = new LinkedHashMap<>();
    Object rawClauses = body.get("clauses");
    if (rawClauses instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?,?> clause) {
          Object clauseId = clause.get("id");
          Object content = clause.get("content");
          if (clauseId != null && content != null) clauseEdits.put(String.valueOf(clauseId), String.valueOf(content));
        }
      }
    }
    List<Map<String,Object>> clauseDefaults = defaultClauses();
    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    String id = UUID.randomUUID().toString();
    Path docx = storage.resolve("煤炭买卖合同-" + stamp + "-" + id.substring(0,8) + ".docx");
    Path pdf = storage.resolve("煤炭买卖合同-" + stamp + "-" + id.substring(0,8) + ".pdf");
    try (InputStream in = getTemplateStream(); XWPFDocument document = new XWPFDocument(in)) {
      applyClauseEdits(document, clauseDefaults, clauseEdits, data);
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
    Map<String,Object> snapshot = new LinkedHashMap<>();
    snapshot.put("data", data);
    snapshot.put("clauses", clauseEdits);
    jdbc.update("INSERT INTO contract_document_v2(template_name,docx_path,pdf_path,data_snapshot) VALUES(?,?,?,?)", "煤炭买卖合同", docx.toString(), pdf.toString(), mapper.writeValueAsString(snapshot));
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

  private void applyClauseEdits(XWPFDocument doc, List<Map<String,Object>> defaults, Map<String,String> edits, Map<String,String> data) {
    if (edits.isEmpty()) return;
    Map<String,XWPFParagraph> paragraphsByText = new HashMap<>();
    for (XWPFParagraph p : doc.getParagraphs()) paragraphsByText.putIfAbsent(paragraphText(p).trim(), p);
    for (Map<String,Object> clause : defaults) {
      String id = String.valueOf(clause.get("id"));
      if (!edits.containsKey(id)) continue;
      if (Objects.equals(String.valueOf(clause.get("content")), edits.get(id))) continue;
      Object rawAnchors = clause.get("anchors");
      if (!(rawAnchors instanceof List<?> anchors) || anchors.isEmpty()) continue;
      List<XWPFParagraph> targets = new ArrayList<>();
      for (Object anchor : anchors) {
        XWPFParagraph p = paragraphsByText.get(String.valueOf(anchor).trim());
        if (p != null) targets.add(p);
      }
      if (targets.isEmpty()) continue;
      setParagraphLines(targets.get(0), replaceTokensInText(edits.get(id), data));
      for (int i = 1; i < targets.size(); i++) setParagraphLines(targets.get(i), "");
    }
  }

  private String paragraphText(XWPFParagraph paragraph) {
    StringBuilder text = new StringBuilder();
    for (XWPFRun run : paragraph.getRuns()) text.append(run.text() == null ? "" : run.text());
    return text.toString();
  }

  private String replaceTokensInText(String text, Map<String,String> data) {
    Matcher matcher = Pattern.compile("\\{<\\$\\.([A-Za-z0-9_.]+)>}").matcher(text == null ? "" : text);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(data.getOrDefault(matcher.group(1), "")));
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Replace text while retaining the anchor paragraph's paragraph properties and
   * the first run's character formatting. Reuse one run for line breaks so the
   * replacement does not discard the original font/size/bold settings.
   */
  private void setParagraphLines(XWPFParagraph paragraph, String value) {
    List<XWPFRun> runs = paragraph.getRuns();
    XWPFRun run;
    if (runs == null || runs.isEmpty()) {
      run = paragraph.createRun();
    } else {
      run = runs.get(0);
      // Keep the first run (and its rPr); remove only additional runs.
      for (int i = runs.size() - 1; i >= 1; i--) paragraph.removeRun(i);
    }

    String[] lines = (value == null ? "" : value).split("\\R", -1);
    run.setText(lines.length > 0 ? lines[0] : "", 0);
    for (int i = 1; i < lines.length; i++) {
      run.addBreak();
      run.setText(lines[i]);
    }
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
