package shop.shmj.backend.service.contract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import shop.shmj.backend.service.template.V1ContractParam;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 合同模板可用的占位符字典。
 *
 * <p><b>为什么要有一份注册表</b>：用户在线编辑模板时，如果手敲一个
 * {@code {<$.product.untiPrice>}}（拼错一个字母），渲染时该字段会静默变空，
 * 一直到合同签完都不会有人发现。所以「模板里出现的 tag 必须在这份注册表里」
 * 是发布前的硬性校验。
 *
 * <p><b>为什么用反射而不是手写清单</b>：占位符的真实来源就是
 * {@link V1ContractParam} 的字段路径。手写清单一定会和类定义漂移；反射生成后，
 * 新增字段自动出现在「插入字段」面板里，删字段也会立刻让旧模板校验失败（这是想要的行为）。
 */
@Slf4j
@Component
public class TemplateFieldRegistry {

    /**
     * 已知字段的中文标签与示例值；没配置的走默认（标签=路径，示例=路径）。
     * 只影响运营体验，不影响渲染。
     */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    private static final Map<String, String> EXAMPLES = new LinkedHashMap<>();

    static {
        LABELS.put("contractNo", "合同编号");
        LABELS.put("contractBeginDate", "合同开始日期");
        LABELS.put("contractEndDate", "合同结束日期");
        LABELS.put("signDate", "签署日期");
        LABELS.put("deliveryLocation", "交货地点");
        LABELS.put("deliveryWay", "交货方式");

        LABELS.put("buyer.name", "买方企业名称");
        LABELS.put("buyer.legalPersonName", "买方法定代表人");
        LABELS.put("buyer.creditCode", "买方统一社会信用代码");
        LABELS.put("buyer.address", "买方地址");
        LABELS.put("buyer.agentName", "买方委托代理人");
        LABELS.put("buyer.agentMobile", "买方联系电话");
        LABELS.put("buyer.bank.bankFullName", "买方开户银行");
        LABELS.put("buyer.bank.accountName", "买方账户名称");
        LABELS.put("buyer.bank.accountNo", "买方账户号码");

        LABELS.put("seller.name", "卖方企业名称");
        LABELS.put("seller.legalPersonName", "卖方法定代表人");
        LABELS.put("seller.creditCode", "卖方统一社会信用代码");
        LABELS.put("seller.address", "卖方地址");
        LABELS.put("seller.agentName", "卖方委托代理人");
        LABELS.put("seller.agentMobile", "卖方联系电话");
        LABELS.put("seller.bank.bankFullName", "卖方开户银行");
        LABELS.put("seller.bank.accountName", "卖方账户名称");
        LABELS.put("seller.bank.accountNo", "卖方账户号码");

        LABELS.put("product.type", "煤炭品种");
        LABELS.put("product.specs", "煤炭品质规格");
        LABELS.put("product.quantity", "合同数量(吨)");
        LABELS.put("product.unitPrice", "含税单价(元/吨)");
        LABELS.put("product.priceInChinese", "合计金额大写");
        LABELS.put("product.guaWaterDesc", "水分约定");

        EXAMPLES.put("contractNo", "HT-20260101ABCD");
        EXAMPLES.put("signDate", "2026年01月01日");
        EXAMPLES.put("contractBeginDate", "2026年01月01日");
        EXAMPLES.put("contractEndDate", "2026年01月10日");
        EXAMPLES.put("deliveryWay", "乙方自提");
        EXAMPLES.put("deliveryLocation", "山西省大同市示例矿区");
        EXAMPLES.put("product.type", "动力煤");
        EXAMPLES.put("product.specs", "发热量 >= 5500 大卡");
        EXAMPLES.put("product.quantity", "5000.00");
        EXAMPLES.put("product.unitPrice", "680.00");
        EXAMPLES.put("product.priceInChinese", "人民币叁佰肆拾万元整");
        EXAMPLES.put("product.guaWaterDesc", "水分<=9.00%，超出部分结算时扣除相应吨数。");
    }

    /**
     * 全部合法 tag（= JsonPath 相对路径），如 {@code seller.name}。
     */
    private final Set<String> supportedTags;

    public TemplateFieldRegistry() {
        this.supportedTags = new TreeSet<>(collectPaths(V1ContractParam.class, "", 0));
        log.info("合同模板字段注册表初始化完成，共 {} 个字段", supportedTags.size());
    }

    public Set<String> supportedTags() {
        return supportedTags;
    }

    public boolean isSupported(String tag) {
        if (tag == null) {
            return false;
        }
        String normalized = tag.startsWith("$.") ? tag.substring(2) : tag;
        return supportedTags.contains(normalized);
    }

    /**
     * 校验模板里的 tag 集合，返回不认识的那些（空集合表示通过）。
     */
    public Set<String> unknownTags(Collection<String> tags) {
        Set<String> unknown = new TreeSet<>();
        for (String tag : tags) {
            if (!isSupported(tag)) {
                unknown.add(tag);
            }
        }
        return unknown;
    }

    /**
     * 给前端「插入字段」面板用的清单。
     */
    public List<FieldDescriptor> describe() {
        List<FieldDescriptor> list = new ArrayList<>(supportedTags.size());
        for (String tag : supportedTags) {
            String label = LABELS.getOrDefault(tag, tag);
            String example = EXAMPLES.getOrDefault(tag, label);
            list.add(new FieldDescriptor(tag, label, example));
        }
        return list;
    }

    /**
     * 生成一份「示例数据」，用于：
     * 发布前试渲染（确认每个字段都填得上）、以及用渲染后的文档去法大大反查签署锚点坐标。
     */
    public Map<String, Object> sampleData() {
        Map<String, Object> buyer = new LinkedHashMap<>();
        buyer.put("name", "山西买方示例能源有限公司");
        buyer.put("legalPersonName", "王示例");
        buyer.put("creditCode", "91140000EXAMPLE0001");
        buyer.put("address", "山西省太原市示例区示例路 1 号");
        buyer.put("agentName", "赵示例");
        buyer.put("agentMobile", "13800000001");
        buyer.put("bank", Map.of("bankFullName", "中国建设银行山西省分行",
                "accountName", "山西买方示例能源有限公司",
                "accountNo", "6222000000000000000"));

        Map<String, Object> seller = new LinkedHashMap<>();
        seller.put("name", "山西卖方示例能源有限公司");
        seller.put("legalPersonName", "张示例");
        seller.put("creditCode", "91140000EXAMPLE0002");
        seller.put("address", "山西省大同市示例区示例路 2 号");
        seller.put("agentName", "李示例");
        seller.put("agentMobile", "13800000002");
        seller.put("bank", Map.of("bankFullName", "中国银行山西省分行",
                "accountName", "山西卖方示例能源有限公司",
                "accountNo", "6210000000000000000"));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("type", "动力煤");
        product.put("specs", "发热量 >= 5500 大卡");
        product.put("quantity", "5000.00");
        product.put("unitPrice", "680.00");
        product.put("priceInChinese", "人民币叁佰肆拾万元整");
        product.put("guaWaterDesc", "水分<=9.00%，超出部分结算时扣除相应吨数。");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contractNo", "HT-20260101SAMPLE");
        data.put("contractBeginDate", "2026年01月01日");
        data.put("contractEndDate", "2026年01月10日");
        data.put("signDate", "2026年01月01日");
        data.put("deliveryLocation", "山西省大同市示例矿区");
        data.put("deliveryWay", "乙方自提");
        data.put("buyer", buyer);
        data.put("seller", seller);
        data.put("product", product);
        return data;
    }

    /**
     * 反射收集 POJO 的叶子字段路径。{@code Collection / Map} 不展开（合同参数里没有集合）。
     */
    private Set<String> collectPaths(Class<?> type, String prefix, int depth) {
        Set<String> paths = new LinkedHashSet<>();
        if (depth > 6) {
            return paths;
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            Class<?> fieldType = field.getType();
            String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
            if (isSimple(fieldType)) {
                paths.add(path);
            } else if (!Collection.class.isAssignableFrom(fieldType) && !Map.class.isAssignableFrom(fieldType)) {
                paths.addAll(collectPaths(fieldType, path, depth + 1));
            }
        }
        return paths;
    }

    private boolean isSimple(Class<?> type) {
        return type.isPrimitive()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || Boolean.class.equals(type)
                || Character.class.equals(type)
                || type.isEnum();
    }

    /**
     * 字段描述。
     */
    public record FieldDescriptor(String tag, String label, String example) {
    }
}
