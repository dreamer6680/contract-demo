package shop.shmj.backend.beans.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import shop.shmj.backend.beans.dto.contract.SigningAnchorConfig;

import java.util.List;

/**
 * 发布模板版本。
 *
 * <p>发布是本流程唯一的「闸门」：只有通过占位符校验与签署锚点校验的版本，
 * 才能被商品选中。设计上故意不让草稿可被选中，避免上线一半的模板被真实订单用到。
 */
@Getter
@Setter
public class ContractTemplatePublishReq {

    /**
     * 显式指定要发布的版本号；为空时发布当前草稿
     */
    private Integer versionNo;

    /**
     * 签署锚点配置；为空时沿用版本里已有配置（首次发布用默认值）
     */
    @Valid
    @Size(max = 20, message = "锚点数量过多")
    private List<SigningAnchorConfig> anchors;

    /**
     * 是否设为平台默认模板
     */
    private Boolean asDefault;

    /**
     * 发布说明
     */
    @Size(max = 500, message = "发布说明最大长度要小于 500")
    private String remark;
}
