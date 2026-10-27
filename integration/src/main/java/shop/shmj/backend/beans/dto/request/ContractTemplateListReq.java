package shop.shmj.backend.beans.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 查询合同模板列表。
 */
@Getter
@Setter
public class ContractTemplateListReq extends BasePageReq {

    /**
     * 名称或编码模糊搜索
     */
    @Size(max = 100, message = "关键字最大长度要小于 100")
    private String keyword;

    /**
     * 状态过滤 ContractTemplateStatus
     */
    private Integer status;

    /**
     * 归属范围过滤 1 平台公共 2 企业私有
     */
    private Integer ownerScope;

    /**
     * 按企业视角过滤（运营查看某企业可用模板时使用）
     */
    @Size(max = 64, message = "企业ID最大长度要小于 64")
    private String enterpriseId;
}
