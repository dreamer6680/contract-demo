package shop.shmj.backend.beans.dict;

import lombok.Getter;

/**
 * 合同模板版本状态。
 *
 * <p>一个模板同时最多只有一个 {@link #DRAFT}，发布后转为 {@link #PUBLISHED}，
 * 下一次发布时旧版本转 {@link #SUPERSEDED}（文件永不覆盖，可历史追溯）。
 */
@Getter
public enum ContractTemplateVersionStatus implements BaseEnum {

    DRAFT(100, "草稿"),
    PUBLISHED(200, "已发布"),
    SUPERSEDED(300, "已被取代"),
    ;

    private final int code;

    private final String desc;

    ContractTemplateVersionStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
