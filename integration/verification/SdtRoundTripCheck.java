import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import shop.shmj.backend.utils.SdtTemplateUtil;
import shop.shmj.backend.utils.SdtTokenRestoreUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class SdtRoundTripCheck {
    public static void main(String[] args) throws Exception {
        // 1) 模拟一份模板：正文 + 页眉各一个占位符
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph p = doc.createParagraph();
        // 故意拆成两个 run，模拟 Word 把占位符拆开的情况
        p.createRun().setText("卖方：{<$.seller");
        p.createRun().setText(".name>}，编号：{<$.contractNo>}");
        XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
        header.createParagraph().createRun().setText("页眉编号：{<$.contractNo>}");
        byte[] original = write(doc);

        // 2) 归一化
        SdtTemplateUtil.NormalizeResult nr = SdtTemplateUtil.normalize(new ByteArrayInputStream(original));
        System.out.println("[normalize] valid=" + nr.isValid() + " converted=" + nr.getConverted()
                + " sdt=" + nr.getSdtTotal() + " tags=" + nr.getTags());

        // 3) 填充展示值
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("contractNo", "HT-2026-0001");
        data.put("seller", Map.of("name", "山西示例能源有限公司"));
        byte[] display = SdtTemplateUtil.fillSdt(new ByteArrayInputStream(nr.getDocx()), data);
        System.out.println("[display] body=" + bodyText(display));
        System.out.println("[display] header=" + headerText(display));

        // 4) 还原成占位符
        SdtTokenRestoreUtil.RestoreResult rr = SdtTokenRestoreUtil.restore(display);
        System.out.println("[restore] valid=" + rr.isValid() + " restored=" + rr.getRestored()
                + " tags=" + rr.getTags() + " untagged=" + rr.getUntagged());
        String body = bodyText(rr.getDocx());
        String hdr = headerText(rr.getDocx());
        System.out.println("[restore] body=" + body);
        System.out.println("[restore] header=" + hdr);

        boolean ok = body.contains("{<$.seller.name>}") && body.contains("{<$.contractNo>}")
                && !body.contains("山西示例能源有限公司")
                && hdr.contains("{<$.contractNo>}") && !hdr.contains("HT-2026-0001");
        System.out.println("[assert] roundtrip=" + ok);

        // 5) 幂等：还原结果再归一化不应再转换
        SdtTemplateUtil.NormalizeResult nr2 = SdtTemplateUtil.normalize(new ByteArrayInputStream(rr.getDocx()));
        System.out.println("[idempotent] converted=" + nr2.getConverted() + " valid=" + nr2.isValid()
                + " sameTags=" + nr2.getTags().equals(nr.getTags()));
        boolean ok2 = nr2.getConverted() == 0 && nr2.isValid() && nr2.getTags().equals(nr.getTags());
        System.out.println("[assert] idempotent=" + ok2);

        // 6) 篡改：删掉 tag，必须被判为不合法
        String xml = documentXml(nr.getDocx()).replace("<w:tag w:val=\"contractNo\"/>", "");
        byte[] broken = rewrite(nr.getDocx(), xml.getBytes(StandardCharsets.UTF_8));
        SdtTokenRestoreUtil.RestoreResult bad = SdtTokenRestoreUtil.restore(broken);
        System.out.println("[assert] untaggedDetected=" + (!bad.isValid()) + " untagged=" + bad.getUntagged());

        System.out.println(ok && ok2 && !bad.isValid() ? "RESULT: PASS" : "RESULT: FAIL");
    }

    static String bodyText(byte[] docx) throws Exception {
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : d.getParagraphs()) sb.append(p.getText());
            return sb.toString();
        }
    }

    static String headerText(byte[] docx) throws Exception {
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFHeader h : d.getHeaderList()) for (XWPFParagraph p : h.getParagraphs()) sb.append(p.getText());
            return sb.toString();
        }
    }

    static String documentXml(byte[] docx) throws Exception {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    z.transferTo(out);
                    return out.toString(StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("no document.xml");
    }

    static byte[] rewrite(byte[] original, byte[] docXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(original));
             ZipOutputStream zo = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                zo.putNextEntry(new ZipEntry(e.getName()));
                if ("word/document.xml".equals(e.getName())) zo.write(docXml);
                else z.transferTo(zo);
                zo.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static byte[] write(XWPFDocument d) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        d.write(out);
        return out.toByteArray();
    }
}
