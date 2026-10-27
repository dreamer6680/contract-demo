package shop.shmj.backend.beans.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 合同模板版本详情。
 */
@Getter
@Setter
public class ContractTemplateVersionRes {

    private String id;

    private String templateId;

    private Integer versionNo;

    /**
     * 占位符版 DOCX 的 OSS key
     */
    private String fileKey;

    /**
     * 下载地址（5 分钟有效预签名）
     */
    private String fileUrl;

    private String fileSha256;

    /**
     * 占位符快照（JSON 字符串，前端可直接渲染标签列表）
     */
    private String placeholderSnapshot;

    /**
     * 占位符个数
     */
    private Integer placeholderCount;

    /**
     * 锚点包（configs + resolutions）
     */
    private String signingAnchorJson;

    private String changeSummary;

    private Integer status;

    private String statusDesc;

    private String createUserId;

    private String createUserName;

    private Date createTime;
}
