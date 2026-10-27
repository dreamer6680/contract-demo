package shop.shmj.backend.beans.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 新建合同模板。
 */
@Getter
@Setter
public class ContractTemplateCreateReq {

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 255, message = "模板名称最大长度要小于 255")
    private String templateName;

    /**
     * 业务编码，可空；为空时按名称生成
     */
    @Size(max = 64, message = "业务编码最大长度要小于 64")
    private String templateCode;

    /**
     * 归属范围 1 平台公共 2 企业私有；默认 1
     */
    private Integer ownerScope = 1;

    /**
     * 企业私有模板的归属企业（ownerScope=2 时必填）
     */
    @Size(max = 64, message = "归属企业最大长度要小于 64")
    private String ownerEnterpriseId;

    @Size(max = 255, message = "备注最大长度要小于 255")
    private String remark;
}
