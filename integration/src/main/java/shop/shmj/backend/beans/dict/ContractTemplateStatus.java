package shop.shmj.backend.beans.dict;

import lombok.Getter;

/**
 * 合同模板状态。
 *
 * <p>与 {@link ProductStatus} 同构：草稿可编辑，发布后只增不改，停用不回溯。
 */
@Getter
public enum ContractTemplateStatus implements BaseEnum {

    /** 草稿：可以继续在线编辑，但商品不可选 */
    DRAFT(100, "草稿"),
    /** 已发布：商品可选；对应版本不可再编辑 */
    PUBLISHED(200, "已发布"),
    /** 已停用：不再允许新的商品选择，已选它的商品不受影响 */
    OFFLINE(300, "已停用"),
    ;

    private final int code;

    private final String desc;

    ContractTemplateStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
