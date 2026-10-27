package shop.shmj.backend.utils;

import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「展示副本 → 占位符版」往返测试。
 *
 * <p>这是整个模板编辑能力最关键的安全属性：用户在编辑器里看到的是真实业务值，
 * 保存出来的模板里必须是占位符。一旦这条链路有洞，业务值会被永久写进模板，
 * 之后每一份合同都会带着第一个客户的名称和账号，而且很难发现。
 */
class SdtTokenRestoreUtilTest {

    /**
     * 归一化 → 填示例值 → 还原，占位符必须原样回来。
     */
    @Test
    void restoreReturnsPlaceholdersAfterFill() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText("卖方：{<$.seller.name>}，编号：{<$.contractNo>}");

        // 1) 上传时归一化：占位符变成锁定内容控件，tag = JsonPath
        SdtTemplateUtil.NormalizeResult normalized =
                SdtTemplateUtil.normalize(new ByteArrayInputStream(write(doc)));
        Assertions.assertTrue(normalized.isValid());
        Assertions.assertEquals(Set.of("seller.name", "contractNo"), normalized.getTags());

        // 2) 打开编辑器时填示例值（控件内容变成业务值，tag 不变）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contractNo", "HT-2026");
        data.put("seller", Map.of("name", "山西示例能源有限公司"));
        byte[] display = SdtTemplateUtil.fillSdt(new ByteArrayInputStream(normalized.getDocx()), data);
        String displayText = paragraphText(display);
        Assertions.assertTrue(displayText.contains("山西示例能源有限公司"), "展示副本应包含业务值");

        // 3) 保存回调里还原
        SdtTokenRestoreUtil.RestoreResult restored = SdtTokenRestoreUtil.restore(display);
        Assertions.assertTrue(restored.isValid());
        Assertions.assertEquals(Set.of("seller.name", "contractNo"), restored.getTags());

        String restoredText = paragraphText(restored.getDocx());
        Assertions.assertTrue(restoredText.contains("{<$.seller.name>}"), restoredText);
        Assertions.assertTrue(restoredText.contains("{<$.contractNo>}"), restoredText);
        Assertions.assertFalse(restoredText.contains("山西示例能源有限公司"), "模板里不能残留业务值");

        // 4) 还原结果再归一化必须幂等（发布/保存前的兜底校验）
        SdtTemplateUtil.NormalizeResult again =
                SdtTemplateUtil.normalize(new ByteArrayInputStream(restored.getDocx()));
        Assertions.assertTrue(again.isValid());
        Assertions.assertEquals(0, again.getConverted(), "已是控件，不应再转换");
        Assertions.assertEquals(normalized.getTags(), again.getTags());
    }

    /**
     * 页眉里的控件同样要还原 —— 只看正文的实现会漏掉页眉。
     */
    @Test
    void restoreCoversHeaders() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        doc.createParagraph().createRun().setText("正文");
        XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
        header.createParagraph().createRun().setText("合同编号：{<$.contractNo>}");

        SdtTemplateUtil.NormalizeResult normalized =
                SdtTemplateUtil.normalize(new ByteArrayInputStream(write(doc)));
        Assertions.assertTrue(normalized.getTags().contains("contractNo"));

        Map<String, Object> data = Map.of("contractNo", "HT-2026");
        byte[] display = SdtTemplateUtil.fillSdt(new ByteArrayInputStream(normalized.getDocx()), data);
        SdtTokenRestoreUtil.RestoreResult restored = SdtTokenRestoreUtil.restore(display);

        Assertions.assertTrue(restored.isValid());
        Assertions.assertTrue(restored.getTags().contains("contractNo"));
        Assertions.assertFalse(xml(restored.getDocx()).contains("HT-2026"),
                "页眉的业务值也必须被还原成占位符");
    }

    /**
     * 控件没有 tag 时必须报告，让调用方拒绝保存，而不是静默放过。
     */
    @Test
    void restoreReportsUntaggedControl() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        doc.createParagraph().createRun().setText("正常：{<$.contractNo>}");
        SdtTemplateUtil.NormalizeResult normalized =
                SdtTemplateUtil.normalize(new ByteArrayInputStream(write(doc)));

        // 模拟：用户把 w:tag 删了
        byte[] broken = xml(normalized.getDocx())
                .replaceAll("<w:tag w:val=\"contractNo\"/>", "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] repaired = rewriteDocx(normalized.getDocx(), broken);

        SdtTokenRestoreUtil.RestoreResult restored = SdtTokenRestoreUtil.restore(repaired);
        Assertions.assertFalse(restored.isValid(), "缺 tag 的控件必须被判为不合法");
        Assertions.assertFalse(restored.getUntagged().isEmpty());
    }

    // ------------------------------------------------------------------

    private String paragraphText(byte[] docx) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText());
            }
            return sb.toString();
        }
    }

    private String xml(byte[] docx) throws Exception {
        try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(docx));
             var in = new ByteArrayInputStream(docx);
             var zip = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zip.transferTo(out);
                    return out.toString(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("没有 document.xml");
    }

    private byte[] rewriteDocx(byte[] original, byte[] newDocumentXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(original));
             var zipOut = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                zipOut.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if ("word/document.xml".equals(entry.getName())) {
                    zipOut.write(newDocumentXml);
                } else {
                    zip.transferTo(zipOut);
                }
                zipOut.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private byte[] write(XWPFDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        return out.toByteArray();
    }
}
