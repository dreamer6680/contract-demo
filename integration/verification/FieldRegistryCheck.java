import shop.shmj.backend.service.contract.TemplateFieldRegistry;
import java.util.*;

public class FieldRegistryCheck {
    public static void main(String[] args) {
        TemplateFieldRegistry registry = new TemplateFieldRegistry();
        Set<String> tags = registry.supportedTags();
        System.out.println("count=" + tags.size());
        System.out.println("tags=" + tags);
        boolean ok = tags.contains("seller.name")
                && tags.contains("buyer.bank.accountNo")
                && tags.contains("product.unitPrice")
                && tags.contains("deliveryLocation")
                && tags.contains("signDate")
                && !tags.contains("buyer.class");
        Set<String> unknown = registry.unknownTags(List.of("seller.name", "product.untiPrice"));
        System.out.println("unknown=" + unknown);
        Map<String,Object> sample = registry.sampleData();
        System.out.println("sample keys=" + sample.keySet());
        System.out.println(ok && unknown.equals(Set.of("product.untiPrice")) ? "RESULT: PASS" : "RESULT: FAIL");
    }
}
