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
  private static final Pattern TOKEN = Pattern.compile("\\{<\\$\\.[^}]+>}");
  private static final String DOCUMENT_XML = "word/document.xml";

  public void createProtectedCopy(Path source, Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), "protected-", ".docx");
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source));
         ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
      ZipEntry entry;
      boolean foundDocument = false;
      while ((entry = in.getNextEntry()) != null) {
        byte[] bytes = in.readAllBytes();
        if (DOCUMENT_XML.equals(entry.getName())) {
          bytes = protectDocumentXml(bytes);
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
      throw new IOException("无法保护合同占位符：" + e.getMessage(), e);
    }
    try {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
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
    Document doc = parse(xml);
    NodeList paragraphs = doc.getElementsByTagNameNS(W, "p");
    for (int i = paragraphs.getLength() - 1; i >= 0; i--) {
      Element paragraph = (Element) paragraphs.item(i);
      protectParagraph(paragraph, doc);
    }
    return serialize(doc);
  }

  private void protectParagraph(Element paragraph, Document doc) throws IOException {
    List<Element> textNodes = new ArrayList<>();
    collectTextNodes(paragraph, textNodes);
    StringBuilder full = new StringBuilder();
    for (Element t : textNodes) full.append(t.getTextContent());
    Matcher matcher = TOKEN.matcher(full);
    List<String> tokens = new ArrayList<>();
    while (matcher.find()) tokens.add(matcher.group());
    for (String token : tokens) {
      Element targetText = null;
      int found = 0;
      for (Element t : textNodes) {
        String value = t.getTextContent();
        if (value.contains(token)) { targetText = t; found++; }
      }
      if (found == 0) throw new IOException("未能定位占位符：" + token);
      if (found > 1) throw new IOException("占位符跨多个 Word 文本节点，无法安全锁定，请先在模板中将占位符设为连续文本：" + token);
      Element run = (Element) targetText.getParentNode();
      if (!"r".equals(run.getLocalName()) || run.getParentNode() != paragraph) {
        throw new IOException("占位符不在普通段落文本中，无法安全锁定：" + token);
      }
      NodeList runTexts = run.getElementsByTagNameNS(W, "t");
      if (runTexts.getLength() != 1 || runTexts.item(0) != targetText) {
        throw new IOException("占位符所在 Word 文本格式复杂，无法安全锁定：" + token);
      }
      Matcher inText = TOKEN.matcher(targetText.getTextContent());
      int tokenCount = 0;
      while (inText.find()) tokenCount++;
      if (tokenCount > 1) throw new IOException("同一 Word 文本节点包含多个占位符，无法安全锁定，请先在模板中拆分占位符：" + targetText.getTextContent());
      String value = targetText.getTextContent();
      int start = value.indexOf(token);
      String before = value.substring(0, start);
      String after = value.substring(start + token.length());
      Element parent = paragraph;
      if (!before.isEmpty()) parent.insertBefore(makeRun(doc, run, before), run);
      parent.insertBefore(makeSdt(doc, run, token), run);
      if (!after.isEmpty()) parent.insertBefore(makeRun(doc, run, after), run);
      parent.removeChild(run);
      // Refreshing text nodes is unnecessary: tokens are unique and the original run is replaced.
    }
  }

  private Element makeRun(Document doc, Element originalRun, String text) {
    Element run = (Element) originalRun.cloneNode(true);
    NodeList texts = run.getElementsByTagNameNS(W, "t");
    Element first = (Element) texts.item(0);
    first.setTextContent(text);
    if (text.startsWith(" ") || text.endsWith(" ")) first.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
    for (int i = texts.getLength() - 1; i >= 1; i--) texts.item(i).getParentNode().removeChild(texts.item(i));
    return run;
  }

  private Element makeSdt(Document doc, Element originalRun, String token) {
    Element sdt = doc.createElementNS(W, "w:sdt");
    Element pr = doc.createElementNS(W, "w:sdtPr");
    Element alias = doc.createElementNS(W, "w:alias");
    alias.setAttributeNS(W, "w:val", "Protected placeholder");
    Element tag = doc.createElementNS(W, "w:tag");
    tag.setAttributeNS(W, "w:val", "protected:" + token);
    Element lock = doc.createElementNS(W, "w:lock");
    lock.setAttributeNS(W, "w:val", "sdtContentLocked");
    pr.appendChild(alias); pr.appendChild(lock); pr.appendChild(tag);
    Element content = doc.createElementNS(W, "w:sdtContent");
    content.appendChild(makeRun(doc, originalRun, token));
    sdt.appendChild(pr); sdt.appendChild(content);
    return sdt;
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
