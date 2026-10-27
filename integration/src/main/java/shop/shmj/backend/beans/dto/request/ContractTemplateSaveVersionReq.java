package shop.shmj.backend.beans.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 保存一个新版本（把编辑器当前内容固化为不可变版本）。
 */
@Getter
@Setter
public class ContractTemplateSaveVersionReq {

    /**
     * 变更说明，会显示在版本历史里
     */
    @Size(max = 500, message = "变更说明最大长度要小于 500")
    private String changeSummary;

    /**
     * 是否在保存后立即发布；默认 false，保存与发布分开，给运营一次复核机会
     */
    private Boolean publishImmediately;
}
