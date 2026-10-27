package shop.shmj.backend.beans.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 合同模板（逻辑模板）。对应表 platform_contract_template。
 *
 * <p>「逻辑模板」持有版本链：{@code current_version_no} 指向编辑中的版本，
 * {@code published_version_no} 指向商品可选的版本。商品只能绑定已发布版本。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PlatformContractTemplate {

    @NotBlank(message = "id不能为空")
    @Size(max = 64, message = "id最大长度要小于 64")
    private String id;

    /**
     * 业务编码，便于前端/配置引用，例如 COAL_SALE_STANDARD
     */
    @Size(max = 64, message = "templateCode最大长度要小于 64")
    private String templateCode;

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 255, message = "模板名称最大长度要小于 255")
    private String templateName;

    /**
     * 归属范围 1 平台公共 2 企业私有（ContractTemplateOwnerScope）
     */
    private Integer ownerScope;

    /**
     * 企业私有模板的归属企业
     */
    @Size(max = 64, message = "ownerEnterpriseId最大长度要小于 64")
    private String ownerEnterpriseId;

    /**
     * 状态 ContractTemplateStatus
     */
    private Integer status;

    /**
     * 当前编辑中的版本号
     */
    private Integer currentVersionNo;

    /**
     * 已发布版本号；为空表示从未发布
     */
    private Integer publishedVersionNo;

    /**
     * 签署锚点定位方式 1 关键字定位 2 像素坐标（历史兼容）
     */
    private Integer signAnchorMode;

    /**
     * 是否平台默认模板 1 是 0 否
     */
    private Integer isDefault;

    @Size(max = 255, message = "备注最大长度要小于 255")
    private String remark;

    @Size(max = 64, message = "createUserId最大长度要小于 64")
    private String createUserId;

    @Size(max = 64, message = "createUserName最大长度要小于 64")
    private String createUserName;

    private Date createTime;

    private Date updateTime;
}
