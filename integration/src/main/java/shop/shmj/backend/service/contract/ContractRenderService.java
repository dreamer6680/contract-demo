package shop.shmj.backend.service.contract;

import com.fasc.open.api.exception.ApiException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import shop.shmj.backend.beans.dict.SealType;
import shop.shmj.backend.beans.dict.SigningAnchor;
import shop.shmj.backend.beans.dto.contract.SigningAnchorBundle;
import shop.shmj.backend.beans.dto.contract.SigningAnchorConfig;
import shop.shmj.backend.beans.entity.PlatformContract;
import shop.shmj.backend.beans.entity.PlatformOrder;
import shop.shmj.backend.configurations.SealUsageConfigProperties;
import shop.shmj.backend.external.FileStorage;
import shop.shmj.backend.external.impl.FascOpenApi;
import shop.shmj.backend.service.template.TemplateComponent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 合同渲染的唯一入口。
 *
 * <p>改造前 {@code ContractTask} 直接调用
 * {@code TemplateComponent.fillContractTemplate(order, contract)}，而后者内部固定读取
 * {@code fasc.templates.contractTemplatePath} —— 单模板、无版本、改模板要改配置发版。
 *
 * <p>改造后由本服务负责「用哪一版模板」：
 * <pre>
 * 订单.contract_template_id / version_no
 *    └─(缺失)→ 平台默认已发布模板
 *          └─(缺失)→ 老配置路径（历史兼容，保证上线期间不会因没模板而生成失败）
 * </pre>
 *
 * <p>它同时把签署字段一起算好返回，让 {@code ContractTask} 不再持有写死的页码坐标。
 */
@Slf4j
@Service
public class ContractRenderService {

    /** 骑缝章纵向位置：沿用现有实现，保证升级前后观感一致 */
    private static final long CROSS_SEAL_Y_PARTY_A = 400L;

    private static final long CROSS_SEAL_Y_PARTY_B = 800L;

    @Autowired
    private ContractTemplateService contractTemplateService;

    @Autowired
    private TemplateComponent templateComponent;

    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private SigningAnchorService signingAnchorService;

    @Autowired
    private SealUsageConfigProperties sealUsageConfigProperties;

    /**
     * 渲染合同 DOCX，并给出双方签署字段。
     */
    public RenderedContract render(PlatformOrder order, PlatformContract contract)
            throws IOException, ExecutionException, ApiException {

        ContractTemplateService.ResolvedTemplate resolved = contractTemplateService.resolveForOrder(
                order.getContractTemplateId(), order.getContractTemplateVersionNo());

        if (resolved == null) {
            log.warn("订单没有可用模板版本，回退历史单模板配置 orderId={}", order.getId());
            ByteArrayOutputStream legacy = templateComponent.fillContractTemplate(order, contract);
            return RenderedContract.legacy(legacy.toByteArray());
        }

        byte[] templateBytes;
        try (var in = fileStorage.getFile(resolved.getFileKey())) {
            templateBytes = in.readAllBytes();
        }

        ByteArrayOutputStream rendered = templateComponent.fillContractTemplate(
                order, contract, new ByteArrayInputStream(templateBytes));

        SealType sealType = sealUsageConfigProperties.getContractSealType();
        SigningAnchorBundle anchors = resolved.getAnchors();
        List<SigningAnchorConfig> configs = anchors.getConfigs();

        List<FascOpenApi.FieldHolder> buyerFields = signingAnchorService.buildFields(
                SigningAnchor.SigningParty.PARTY_A, configs, sealType, CROSS_SEAL_Y_PARTY_A);
        List<FascOpenApi.FieldHolder> sellerFields = signingAnchorService.buildFields(
                SigningAnchor.SigningParty.PARTY_B, configs, sealType, CROSS_SEAL_Y_PARTY_B);

        log.info("合同渲染完成 orderId={}, templateId={}, version={}, buyerFields={}, sellerFields={}",
                order.getId(), resolved.getTemplateId(), resolved.getVersionNo(),
                buyerFields.size(), sellerFields.size());

        return RenderedContract.builder()
                .docx(rendered.toByteArray())
                .templateId(resolved.getTemplateId())
                .templateVersionNo(resolved.getVersionNo())
                .anchors(anchors)
                .buyerFields(buyerFields)
                .sellerFields(sellerFields)
                .legacy(false)
                .build();
    }

    /**
     * 渲染结果。
     */
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RenderedContract {

        /** 渲染后的 DOCX（未签） */
        private byte[] docx;

        /** 使用的模板；为 null 表示走了历史单模板路径 */
        private String templateId;

        private Integer templateVersionNo;

        private SigningAnchorBundle anchors;

        /** 甲方（买方）签署字段 */
        private List<FascOpenApi.FieldHolder> buyerFields;

        /** 乙方（卖方）签署字段 */
        private List<FascOpenApi.FieldHolder> sellerFields;

        /** 是否回退到历史单模板路径 */
        private boolean legacy;

        /**
         * 历史兼容：没有模板版本时只返回 DOCX，签署字段由调用方沿用旧的像素坐标。
         */
        public static RenderedContract legacy(byte[] docx) {
            return RenderedContract.builder()
                    .docx(docx)
                    .legacy(true)
                    .buyerFields(List.of())
                    .sellerFields(List.of())
                    .build();
        }
    }
}
