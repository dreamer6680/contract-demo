package shop.shmj.backend.beans.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 合同模板版本（不可变）。对应表 platform_contract_template_version。
 *
 * <p>{@code fileKey} 指向 OSS 上的「占位符版」DOCX —— 即占位符已归一化为
 * {@code w:tag} 内容控件、控件内容仍是 {@code {<$.xxx>}} 文本的那种文件。
 * 该文件永远不被就地覆盖：改模板 = 生成新版本号。
 *
 * <p>{@code signingAnchorJson} 记录本版本如何定位签章，格式见
 * {@code SigningAnchorConfig}。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PlatformContractTemplateVersion {

    @NotBlank(message = "id不能为空")
    @Size(max = 64, message = "id最大长度要小于 64")
    private String id;

    @NotBlank(message = "模板id不能为空")
    @Size(max = 64, message = "templateId最大长度要小于 64")
    private String templateId;

    /**
     * 版本号，从 1 递增
     */
    @NotNull(message = "版本号不能为空")
    private Integer versionNo;

    /**
     * 占位符版 DOCX 的 OSS key
     */
    @NotBlank(message = "fileKey不能为空")
    @Size(max = 255, message = "fileKey最大长度要小于 255")
    private String fileKey;

    /**
     * 预览 PDF 的 OSS key（二期）
     */
    @Size(max = 255, message = "previewPdfKey最大长度要小于 255")
    private String previewPdfKey;

    /**
     * 内容指纹（SHA-256），用于去重与识别「无改动重复保存」
     */
    @NotBlank(message = "fileSha256不能为空")
    @Size(max = 64, message = "fileSha256最大长度要小于 64")
    private String fileSha256;

    /**
     * 占位符快照 JSON
     */
    private String placeholderSnapshot;

    /**
     * 签署锚点配置 JSON
     */
    private String signingAnchorJson;

    @Size(max = 500, message = "变更说明最大长度要小于 500")
    private String changeSummary;

    /**
     * 状态 ContractTemplateVersionStatus
     */
    private Integer status;

    @Size(max = 64, message = "createUserId最大长度要小于 64")
    private String createUserId;

    @Size(max = 64, message = "createUserName最大长度要小于 64")
    private String createUserName;

    private Date createTime;
}
