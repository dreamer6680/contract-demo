package shop.shmj.backend.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内容控件「牌子」还原工具 —— 与 {@link SdtTemplateUtil} 严格配对使用。
 *
 * <h3>为什么需要它</h3>
 * 模板的<b>规范形态</b>（canonical）是：占位符 {@code {<$.seller.name>}} 被包进锁定内容控件，
 * 控件内容仍是 {@code {<$.seller.name>}} 文本，控件 tag 是 {@code seller.name}。
 * 这样 poi-tl 和新链路都能填。
 *
 * <p>但把它直接丢给用户在 ONLYOFFICE 里编辑会很难看（一片 {@code {<$.xxx>}}）。
 * 所以打开编辑器前先做一份<b>展示副本</b>：用 {@link SdtTemplateUtil#fillSdt} 把控件内容换成
 * 业务值（tag 不变，控件依旧锁死不可编辑）。用户保存时，必须把展示副本<b>还原</b>成规范形态，
 * 否则业务值就被永久写进模板，下一份合同的所有字段就都变成这家公司的名字了。
 *
 * <p>本类负责这个「还原」：逐个内容控件把内容改回 tag 对应的 {@code {<$.tag>}}。
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>还原后必须再跑一次 {@link SdtTemplateUtil#normalize} 做完整性校验（占位符集合与基线一致），
 *       <b>校验不过就拒绝保存</b>；</li>
 *   <li>控件被删除/改名时，还原后的 tag 集合会变化，因此能被上面的校验发现；</li>
 *   <li>控件内若被塞入多段文字，只保留第一段（其余丢弃），因为规范形态里一个控件就是一个占位符。</li>
 * </ul>
 */
@Slf4j
public final class SdtTokenRestoreUtil {

    /** 与 {@link SdtTemplateUtil} 的 grammar 保持一致 */
    public static final String TOKEN_PREFIX = "{<$.";
    public static final String TOKEN_SUFFIX = ">}";

    private SdtTokenRestoreUtil() {
    }

    public static String tokenOf(String tag) {
        return TOKEN_PREFIX + tag + TOKEN_SUFFIX;
    }

    /**
     * 还原结果：还原后的 docx + 本次见到的 tag 集合。
     */
    public static class RestoreResult {

        private final byte[] docx;

        /** 本次还原涉及的全部 tag（已去重） */
        private final Set<String> tags = new LinkedHashSet<>();

        /** 没有 tag 的控件（无法还原，必须拒绝保存） */
        private final List<String> untagged = new java.util.ArrayList<>();

        /** 实际被改写的控件数量 */
        private int restored;

        RestoreResult(byte[] docx) {
            this.docx = docx;
        }

        public byte[] getDocx() {
            return docx;
        }

        public Set<String> getTags() {
            return tags;
        }

        public List<String> getUntagged() {
            return untagged;
        }

        public int getRestored() {
            return restored;
        }

        public boolean isValid() {
            return untagged.isEmpty();
        }

        @Override
        public String toString() {
            return "RestoreResult{restored=" + restored + ", tags=" + tags.size()
                    + ", untagged=" + untagged.size() + "}";
        }
    }

    /**
     * 把展示副本还原成规范形态。
     *
     * @param template 展示副本（控件内容 = 业务值）
     * @return 还原结果；调用方必须检查 {@link RestoreResult#isValid()} 并做占位符集合校验
     */
    public static RestoreResult restore(InputStream template) throws IOException {
        RestoreResult result;
        try (XWPFDocument doc = new XWPFDocument(template)) {
            int[] stat = new int[1];
            Set<String> tags = new LinkedHashSet<>();
            List<String> untagged = new java.util.ArrayList<>();

            CTBody body = doc.getDocument().getBody();
            if (body != null) {
                for (CTSdtBlock block : body.getSdtList()) {
                    restoreBlockSdt(block, tags, untagged, stat);
                }
            }
            for (XWPFParagraph p : doc.getParagraphs()) {
                restoreParagraph(p, tags, untagged, stat);
            }
            for (XWPFTable t : doc.getTables()) {
                restoreTable(t, tags, untagged, stat);
            }
            for (XWPFHeader h : doc.getHeaderList()) {
                for (XWPFParagraph p : h.getParagraphs()) {
                    restoreParagraph(p, tags, untagged, stat);
                }
                for (XWPFTable t : h.getTables()) {
                    restoreTable(t, tags, untagged, stat);
                }
            }
            for (XWPFFooter f : doc.getFooterList()) {
                for (XWPFParagraph p : f.getParagraphs()) {
                    restoreParagraph(p, tags, untagged, stat);
                }
                for (XWPFTable t : f.getTables()) {
                    restoreTable(t, tags, untagged, stat);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            result = new RestoreResult(out.toByteArray());
            result.tags.addAll(tags);
            result.untagged.addAll(untagged);
            result.restored = stat[0];
        }
        log.info("模板控件还原: {}, untagged={}", result, result.getUntagged().size());
        return result;
    }

    // ------------------------------------------------------------------
    // 遍历
    // ------------------------------------------------------------------

    private static void restoreTable(XWPFTable table, Set<String> tags, List<String> untagged, int[] stat) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph p : cell.getParagraphs()) {
                    restoreParagraph(p, tags, untagged, stat);
                }
                for (XWPFTable nested : cell.getTables()) {
                    restoreTable(nested, tags, untagged, stat);
                }
            }
        }
    }

    private static void restoreParagraph(XWPFParagraph paragraph, Set<String> tags,
                                         List<String> untagged, int[] stat) {
        for (CTSdtRun sdt : paragraph.getCTP().getSdtList()) {
            restoreRunSdt(sdt, tags, untagged, stat);
        }
    }

    // ------------------------------------------------------------------
    // 还原
    // ------------------------------------------------------------------

    private static void restoreRunSdt(CTSdtRun sdt, Set<String> tags, List<String> untagged, int[] stat) {
        String tag = tagOf(sdt.isSetSdtPr() ? sdt.getSdtPr() : null);
        if (tag == null) {
            untagged.add("(run 级控件缺少 tag)");
        } else {
            tags.add(tag);
            if (setRunContentToken(sdt.isSetSdtContent() ? sdt.getSdtContent() : null, tag)) {
                stat[0]++;
            }
        }
        // 递归：控件里还嵌着控件（normalize 不会产生，但用户可能手工插入）
        if (sdt.isSetSdtContent()) {
            for (CTSdtRun nested : sdt.getSdtContent().getSdtList()) {
                restoreRunSdt(nested, tags, untagged, stat);
            }
        }
    }

    private static void restoreBlockSdt(CTSdtBlock block, Set<String> tags, List<String> untagged, int[] stat) {
        String tag = tagOf(block.isSetSdtPr() ? block.getSdtPr() : null);
        if (tag == null) {
            untagged.add("(block 级控件缺少 tag)");
        } else {
            tags.add(tag);
            if (block.isSetSdtContent() && setBlockContentToken(block.getSdtContent(), tag)) {
                stat[0]++;
            }
        }
    }

    /** 把 run 级控件的内容整体替换成 {<$.tag>}；返回是否真的改写了 */
    private static boolean setRunContentToken(CTSdtContentRun content, String tag) {
        if (content == null) {
            return false;
        }
        String token = tokenOf(tag);

        CTR target = null;
        for (CTR r : content.getRArray()) {
            if (!r.getTList().isEmpty()) {
                target = r;
                break;
            }
        }
        boolean fresh = target == null;
        if (fresh) {
            target = content.addNewR();
        }
        for (CTR r : content.getRArray()) {
            if (fresh && r == target) {
                continue;
            }
            for (CTText t : r.getTList()) {
                t.setStringValue("");
            }
        }
        if (target.getTList().isEmpty()) {
            target.addNewT().setStringValue(token);
        } else {
            target.getTList().get(0).setStringValue(token);
            for (int i = 1; i < target.getTList().size(); i++) {
                target.getTList().get(i).setStringValue("");
            }
        }
        return true;
    }

    /** 把 block 级控件的内容整体替换成 {<$.tag>}：保留第一段第一行 */
    private static boolean setBlockContentToken(CTSdtContentBlock content, String tag) {
        if (content == null) {
            return false;
        }
        String token = tokenOf(tag);
        List<CTP> paragraphs = content.getPList();
        if (paragraphs.isEmpty()) {
            CTP p = content.addNewP();
            p.addNewR().addNewT().setStringValue(token);
            return true;
        }
        CTP first = paragraphs.get(0);
        List<CTR> runs = first.getRList();
        if (runs.isEmpty()) {
            first.addNewR().addNewT().setStringValue(token);
        } else {
            CTR run = runs.get(0);
            if (run.getTList().isEmpty()) {
                run.addNewT().setStringValue(token);
            } else {
                run.getTList().get(0).setStringValue(token);
                for (int i = 1; i < run.getTList().size(); i++) {
                    run.getTList().get(i).setStringValue("");
                }
            }
            for (int i = 1; i < runs.size(); i++) {
                for (CTText t : runs.get(i).getTList()) {
                    t.setStringValue("");
                }
            }
        }
        // 其余段落清空，避免业务值残留在模板里
        for (int i = 1; i < paragraphs.size(); i++) {
            for (CTR run : paragraphs.get(i).getRList()) {
                for (CTText t : run.getTList()) {
                    t.setStringValue("");
                }
            }
        }
        return true;
    }

    private static String tagOf(CTSdtPr pr) {
        if (pr == null || !pr.isSetTag() || pr.getTag().getVal() == null) {
            return null;
        }
        String tag = pr.getTag().getVal().trim();
        return tag.isEmpty() ? null : tag;
    }

    /** 便捷方法：从字节数组还原 */
    public static RestoreResult restore(byte[] template) throws IOException {
        return restore(new ByteArrayInputStream(template));
    }
}
