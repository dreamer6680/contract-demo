package com.demo.contract;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Protects business placeholders in DOCX using OOXML inline content controls.
 * Fails closed if a placeholder is split across Word text nodes, rather than silently serving it editable.
 */
public final class PlaceholderProtectionService {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final Pattern TOKEN = Pattern.compile("\\{<\\$\\.([^}]+)>}");
  private static final String DOCUMENT_XML = "word/document.xml";

  public void createProtectedCopy(Path source, Path target) throws IOException {
    writeTransformed(source, target, this::protectDocumentXml);
  }

  /**
   * Create an editing copy in which every placeholder is replaced by the current
   * business value. The token is kept in the content-control tag, so the value can
   * be replaced by the original placeholder again when the editor saves.
   */
  public void createDisplayCopy(Path source, Path target, Map<String, String> values) throws IOException {
    writeTransformed(source, target, xml -> protectDocumentXml(xml, values == null ? Map.of() : values));
  }

  /** Replace every placeholder content control with its original placeholder token. */
  public void restorePlaceholders(Path source, Path target) throws IOException {
    writeTransformed(source, target, this::restoreDocumentXml);
  }

  public boolean hasPlaceholderControls(Path path) throws IOException {
    Document doc = readDocument(path);
    NodeList tags = doc.getElementsByTagNameNS(W, "tag");
    for (int i = 0; i < tags.getLength(); i++) {
      String value = ((Element) tags.item(i)).getAttributeNS(W, "val");
      if (value.startsWith("placeholder:") || value.startsWith("protected:")) return true;
    }
    NodeList aliases = doc.getElementsByTagNameNS(W, "alias");
    for (int i = 0; i < aliases.getLength(); i++) {
      if (TOKEN.matcher(((Element) aliases.item(i)).getAttributeNS(W, "val")).find()) return true;
    }
    return false;
  }

  private void writeTransformed(Path source, Path target, DocumentTransform transform) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), "docx-", ".docx");
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source));
         ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
      ZipEntry entry;
      boolean foundDocument = false;
      while ((entry = in.getNextEntry()) != null) {
        byte[] bytes = in.readAllBytes();
        if (DOCUMENT_XML.equals(entry.getName())) {
          bytes = transform.apply(bytes);
          foundDocument = true;
        }
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTime(entry.getTime());
        out.putNextEntry(copy);
        out.write(bytes);
        out.closeEntry();
      }
      if (!foundDocument) throw new IOException("DOCX 缺少 word/document.xml");
    } catch (Exception e) {
      Files.deleteIfExists(temp);
      if (e instanceof IOException io) throw io;
      throw new IOException("处理合同 DOCX 失败：" + e.getMessage(), e);
    }
    try {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @FunctionalInterface
  private interface DocumentTransform {
    byte[] apply(byte[] xml) throws Exception;
  }

  private Document readDocument(Path path) throws IOException {
    try (ZipFile zip = new ZipFile(path.toFile())) {
      ZipEntry entry = zip.getEntry(DOCUMENT_XML);
      if (entry == null) throw new IOException("DOCX 缺少 word/document.xml: " + path.getFileName());
      try (InputStream in = zip.getInputStream(entry)) { return parse(in.readAllBytes()); }
    } catch (IOException e) { throw e; }
    catch (Exception e) { throw new IOException("无法读取 DOCX：" + path.getFileName(), e); }
  }


  public boolean hasProtectedControls(Path path) throws IOException {
    try (ZipFile zip = new ZipFile(path.toFile())) {
      ZipEntry entry = zip.getEntry(DOCUMENT_XML);
      if (entry == null) return false;
      try (InputStream in = zip.getInputStream(entry)) {
        Document doc = parse(in.readAllBytes());
        NodeList locks = doc.getElementsByTagNameNS(W, "lock");
        for (int i = 0; i < locks.getLength(); i++) {
          Element lock = (Element) locks.item(i);
          if ("sdtContentLocked".equals(lock.getAttributeNS(W, "val"))) return true;
        }
        return false;
      }
    } catch (IOException e) { throw e; }
    catch (Exception e) { throw new IOException("无法检查 DOCX 占位符锁定状态", e); }
  }

  public void assertIntegrity(Path baseline, Path candidate) throws IOException {
    List<String> expected = extractTokens(baseline);
    List<String> actual = extractTokens(candidate);
    Map<String, Integer> expectedCounts = counts(expected);
    Map<String, Integer> actualCounts = counts(actual);
    if (!expectedCounts.equals(actualCounts)) {
      Map<String, Integer> missing = new LinkedHashMap<>(expectedCounts);
      actualCounts.forEach((token, count) -> missing.merge(token, -count, Integer::sum));
      throw new IOException("占位符完整性校验失败（缺失或被修改）。差异：" +
          missing.entrySet().stream().filter(e -> e.getValue() != 0).toList());
    }
  }

  private byte[] protectDocumentXml(byte[] xml) throws Exception {
    return protectDocumentXml(xml, null);
  }

  private byte[] protectDocumentXml(byte[] xml, Map<String, String> values) throws Exception {
    Document doc = parse(xml);
    NodeList paragraphs = doc.getElementsByTagNameNS(W, "p");
    int[] nextSdtId = {1};
    for (int i = paragraphs.getLength() - 1; i >= 0; i--) {
      Element paragraph = (Element) paragraphs.item(i);
      protectParagraph(paragraph, doc, nextSdtId, values);
    }
    return serialize(doc);
  }

  private void protectParagraph(Element paragraph, Document doc, int[] nextSdtId, Map<String, String> values) throws IOException {
    List<TokenSpan> tokens = findTokens(paragraph);
    if (tokens.isEmpty()) return;

    // Replace from right to left. A replacement preserves the paragraph's concatenated
    // text exactly, so the offsets found in the original text remain valid; the w:t/w:r
    // nodes, however, are split and must be re-read for every token.
    tokens.sort(Comparator.comparingInt(TokenSpan::start).reversed());
    for (TokenSpan token : tokens) {
      List<Element> textNodes = new ArrayList<>();
      collectTextNodes(paragraph, textNodes);
      StringBuilder fullBuilder = new StringBuilder();
      for (Element t : textNodes) fullBuilder.append(t.getTextContent());
      String full = fullBuilder.toString();
      if (token.start() < 0 || token.end() > full.length()
          || !full.substring(token.start(), token.end()).equals(token.value())) {
        throw new IOException("占位符位置已变化，无法安全锁定：" + token.value());
      }

      int position = 0;
      Element startText = null;
      Element endText = null;
      int startOffset = -1;
      int endOffset = -1;
      for (Element t : textNodes) {
        int next = position + t.getTextContent().length();
        if (startText == null && token.start() >= position && token.start() < next) {
          startText = t;
          startOffset = token.start() - position;
        }
        if (token.end() > position && token.end() <= next) {
          endText = t;
          endOffset = token.end() - position;
          break;
        }
        position = next;
      }
      if (startText == null || endText == null) {
        throw new IOException("未能定位占位符：" + token.value());
      }

      Element startRun = directRun(startText, token.value());
      Element endRun = directRun(endText, token.value());
      Node parent = startRun.getParentNode();
      if (!(parent instanceof Element) || endRun.getParentNode() != parent) {
        throw new IOException("占位符跨越多个 Word 结构容器，无法安全锁定：" + token.value());
      }
      if (startRun != endRun) {
        Node check = startRun.getNextSibling();
        while (check != null && check != endRun) {
          if (!(check instanceof Element element)
              || !"r".equals(element.getLocalName())
              || !W.equals(element.getNamespaceURI())) {
            throw new IOException("占位符跨越非文本 Word 结构，无法安全锁定：" + token.value());
          }
          check = check.getNextSibling();
        }
        if (check != endRun) {
          throw new IOException("占位符起止文本不在同一 Word 容器中，无法安全锁定：" + token.value());
        }
      }

      assertSimpleTextRun(startRun, token.value());
      if (endRun != startRun) assertSimpleTextRun(endRun, token.value());

      String before = startText.getTextContent().substring(0, startOffset);
      String after = endText.getTextContent().substring(endOffset);

      // Remove runs that are wholly inside the placeholder. Their text is part of the
      // token and must not leak into the surrounding text.
      if (startRun != endRun) {
        Node current = startRun.getNextSibling();
        while (current != null && current != endRun) {
          Node next = current.getNextSibling();
          parent.removeChild(current);
          current = next;
        }
      }

      String content = values == null ? token.value() : displayValue(values, token.value());
      Element tokenRun = makeRun(doc, startRun, content);
      Element sdt = createSdt(doc, tokenRun, token.value(), nextSdtId[0]++);
      if (!before.isEmpty()) parent.insertBefore(makeRun(doc, startRun, before), startRun);
      parent.insertBefore(sdt, startRun);
      parent.removeChild(startRun);
      if (endRun != startRun && endRun.getParentNode() == parent) parent.removeChild(endRun);
      if (!after.isEmpty()) parent.insertBefore(makeRun(doc, endRun, after), sdt.getNextSibling());
    }
  }

  private List<TokenSpan> findTokens(Element paragraph) {
    List<Element> textNodes = new ArrayList<>();
    collectTextNodes(paragraph, textNodes);
    StringBuilder full = new StringBuilder();
    for (Element t : textNodes) full.append(t.getTextContent());
    Matcher matcher = TOKEN.matcher(full);
    List<TokenSpan> tokens = new ArrayList<>();
    while (matcher.find()) tokens.add(new TokenSpan(matcher.group(), matcher.start(), matcher.end()));
    return tokens;
  }

  private Element directRun(Element text, String token) throws IOException {
    Node parent = text.getParentNode();
    if (!(parent instanceof Element run)
        || !"r".equals(run.getLocalName())
        || !W.equals(run.getNamespaceURI())) {
      throw new IOException("占位符文本不属于普通 Word Run，无法安全锁定：" + token);
    }
    NodeList runTexts = run.getElementsByTagNameNS(W, "t");
    if (runTexts.getLength() != 1 || runTexts.item(0) != text) {
      throw new IOException("占位符所在 Word Run 包含复杂文本结构，无法安全锁定：" + token);
    }
    return run;
  }

  /** makeRun clones a run, so reject runs whose other children would be duplicated. */
  private void assertSimpleTextRun(Element run, String token) throws IOException {
    for (Node child = run.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.COMMENT_NODE) continue;
      if (child instanceof Element element
          && W.equals(element.getNamespaceURI())
          && ("rPr".equals(element.getLocalName())
              || "t".equals(element.getLocalName())
              || "lastRenderedPageBreak".equals(element.getLocalName()))) {
        continue;
      }
      throw new IOException("占位符所在 Word Run 包含非文本结构，无法安全锁定：" + token);
    }
  }

  private Element createSdt(Document doc, Element run, String token, int id) {
    Element sdt = doc.createElementNS(W, "w:sdt");
    Element pr = doc.createElementNS(W, "w:sdtPr");
    Element alias = doc.createElementNS(W, "w:alias");
    alias.setAttributeNS(W, "w:val", token);
    Element tag = doc.createElementNS(W, "w:tag");
    tag.setAttributeNS(W, "w:val", "placeholder:" + token);
    Element idElement = doc.createElementNS(W, "w:id");
    idElement.setAttributeNS(W, "w:val", String.valueOf(id));
    Element lock = doc.createElementNS(W, "w:lock");
    lock.setAttributeNS(W, "w:val", "sdtContentLocked");
    // CT_SdtPr is an ordered sequence: alias, tag, id, lock. Keep this order for Word.
    pr.appendChild(alias);
    pr.appendChild(tag);
    pr.appendChild(idElement);
    pr.appendChild(lock);
    Element content = doc.createElementNS(W, "w:sdtContent");
    content.appendChild(run);
    sdt.appendChild(pr);
    sdt.appendChild(content);
    return sdt;
  }

  private record TokenSpan(String value, int start, int end) {}

  private Element makeRun(Document doc, Element originalRun, String text) {
    Element run = (Element) originalRun.cloneNode(true);
    // Word's cached pagination hint is not text and must not be duplicated into
    // every fragment created when a run is split.
    NodeList pageBreaks = run.getElementsByTagNameNS(W, "lastRenderedPageBreak");
    for (int i = pageBreaks.getLength() - 1; i >= 0; i--) {
      Node pageBreak = pageBreaks.item(i);
      if (pageBreak.getParentNode() != null) pageBreak.getParentNode().removeChild(pageBreak);
    }
    NodeList texts = run.getElementsByTagNameNS(W, "t");
    Element first = (Element) texts.item(0);
    first.setTextContent(text);
    if (text.startsWith(" ") || text.endsWith(" ")) first.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
    for (int i = texts.getLength() - 1; i >= 1; i--) texts.item(i).getParentNode().removeChild(texts.item(i));
    return run;
  }

  private String displayValue(Map<String, String> values, String token) {
    String key = tokenKey(token);
    String value = key.isEmpty() ? "" : values.getOrDefault(key, "");
    if (value == null) return "";
    // A content control's w:t is a single line; keep the editor stable for multi-line data.
    return value.replaceAll("[\\r\\n\\t]", " ");
  }

  private String tokenKey(String token) {
    Matcher matcher = TOKEN.matcher(token == null ? "" : token);
    return matcher.matches() ? matcher.group(1) : "";
  }

  private byte[] restoreDocumentXml(byte[] xml) throws Exception {
    Document doc = parse(xml);
    NodeList sdts = doc.getElementsByTagNameNS(W, "sdt");
    for (int i = sdts.getLength() - 1; i >= 0; i--) {
      Element sdt = (Element) sdts.item(i);
      String token = placeholderToken(sdt);
      if (token == null) continue;
      Node parent = sdt.getParentNode();
      if (!(parent instanceof Element parentElement) || !isInlineContainer(parentElement)) {
        throw new IOException("字段内容控件不在段落内，无法还原占位符：" + token);
      }
      parent.replaceChild(tokenRun(doc, sdt, token), sdt);
    }
    return serialize(doc);
  }

  private String placeholderToken(Element sdt) {
    Element pr = firstChildElement(sdt, "sdtPr");
    if (pr != null) {
      Element tag = firstChildElement(pr, "tag");
      if (tag != null) {
        String token = tokenFromTag(tag.getAttributeNS(W, "val"));
        if (token != null) return token;
      }
      Element alias = firstChildElement(pr, "alias");
      if (alias != null) {
        String token = firstToken(alias.getAttributeNS(W, "val"));
        if (token != null) return token;
      }
    }
    // Legacy protected copies stored the token as the content of the control.
    NodeList texts = sdt.getElementsByTagNameNS(W, "t");
    StringBuilder content = new StringBuilder();
    for (int i = 0; i < texts.getLength(); i++) content.append(texts.item(i).getTextContent());
    Matcher matcher = TOKEN.matcher(content.toString());
    String found = null;
    while (matcher.find()) {
      if (found != null) return null;
      found = matcher.group();
    }
    return found;
  }

  private String tokenFromTag(String raw) {
    if (raw == null) return null;
    String candidate = raw.startsWith("placeholder:") ? raw.substring("placeholder:".length())
        : raw.startsWith("protected:") ? raw.substring("protected:".length())
        : raw;
    return TOKEN.matcher(candidate).matches() ? candidate : null;
  }

  private String firstToken(String raw) {
    Matcher matcher = TOKEN.matcher(raw == null ? "" : raw);
    return matcher.find() ? matcher.group() : null;
  }

  private boolean isInlineContainer(Element element) {
    if (!W.equals(element.getNamespaceURI())) return false;
    return switch (element.getLocalName()) {
      case "p", "hyperlink", "smartTag", "ins", "del", "moveTo", "moveFrom",
          "sdtContent", "customXml", "fldSimple" -> true;
      default -> false;
    };
  }

  private Element tokenRun(Document doc, Element sdt, String token) {
    NodeList runs = sdt.getElementsByTagNameNS(W, "r");
    Element run = runs.getLength() > 0
        ? (Element) runs.item(0).cloneNode(true)
        : doc.createElementNS(W, "w:r");
    Element text = null;
    for (Node child = run.getFirstChild(); child != null; ) {
      Node next = child.getNextSibling();
      boolean keep = false;
      if (child instanceof Element element && W.equals(element.getNamespaceURI())) {
        keep = "rPr".equals(element.getLocalName());
        if ("t".equals(element.getLocalName()) && text == null) {
          text = element;
          keep = true;
        }
      }
      if (!keep) run.removeChild(child);
      child = next;
    }
    if (text == null) {
      text = doc.createElementNS(W, "w:t");
      run.appendChild(text);
    }
    text.setTextContent(token);
    text.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
    return run;
  }

  private Element firstChildElement(Element parent, String localName) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element
          && W.equals(element.getNamespaceURI())
          && localName.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private List<String> extractTokens(Path path) throws IOException {
    try (ZipFile zip = new ZipFile(path.toFile())) {
      ZipEntry entry = zip.getEntry(DOCUMENT_XML);
      if (entry == null) throw new IOException("DOCX 缺少 word/document.xml: " + path.getFileName());
      Document doc;
      try (InputStream in = zip.getInputStream(entry)) { doc = parse(in.readAllBytes()); }
      NodeList paragraphs = doc.getElementsByTagNameNS(W, "p");
      List<String> all = new ArrayList<>();
      for (int i = 0; i < paragraphs.getLength(); i++) {
        List<Element> texts = new ArrayList<>();
        collectTextNodes((Element) paragraphs.item(i), texts);
        StringBuilder joined = new StringBuilder();
        texts.forEach(t -> joined.append(t.getTextContent()));
        Matcher m = TOKEN.matcher(joined);
        while (m.find()) all.add(m.group());
      }
      return all;
    } catch (IOException e) { throw e; }
    catch (Exception e) { throw new IOException("无法读取 DOCX 占位符", e); }
  }

  private Map<String, Integer> counts(List<String> values) {
    Map<String, Integer> result = new LinkedHashMap<>();
    values.forEach(v -> result.merge(v, 1, Integer::sum));
    return result;
  }

  private void collectTextNodes(Element root, List<Element> result) {
    NodeList nodes = root.getElementsByTagNameNS(W, "t");
    for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
  }

  private Document parse(byte[] xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
  }

  private byte[] serialize(Document doc) throws Exception {
    TransformerFactory tf = TransformerFactory.newInstance();
    tf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(doc), new StreamResult(out));
    return out.toByteArray();
  }
}
