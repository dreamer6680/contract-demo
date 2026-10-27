package shop.shmj.backend.beans.dto.response;

import lombok.Getter;
import lombok.Setter;

/**
 * 商品上架时下拉框里的一行：只暴露必要字段。
 */
@Getter
@Setter
public class ContractTemplateOptionRes {

    private String id;

    private String templateName;

    /**
     * 已发布版本号；商品绑定的是这个版本
     */
    private Integer publishedVersionNo;

    /**
     * 是否平台默认模板
     */
    private Integer isDefault;

    private Integer ownerScope;
}
