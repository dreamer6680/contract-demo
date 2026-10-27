package shop.shmj.backend.beans.dto.response;

import lombok.Getter;
import lombok.Setter;
import shop.shmj.backend.beans.dto.contract.SigningAnchorBundle;

import java.util.Date;

/**
 * 合同模板详情。
 */
@Getter
@Setter
public class ContractTemplateRes {

    private String id;

    private String templateCode;

    private String templateName;

    /**
     * 归属范围 1 平台公共 2 企业私有
     */
    private Integer ownerScope;

    private String ownerEnterpriseId;

    private Integer status;

    /**
     * 状态中文，便于前端直接展示（字典由后端翻译，避免前端硬编码）
     */
    private String statusDesc;

    private Integer currentVersionNo;

    private Integer publishedVersionNo;

    /**
     * 1 关键字定位 2 像素坐标
     */
    private Integer signAnchorMode;

    private Integer isDefault;

    private String remark;

    private Date createTime;

    private Date updateTime;

    /**
     * 已发布版本的占位符数量
     */
    private Integer placeholderCount;

    /**
     * 已发布版本的锚点配置（发布页回显）
     */
    private SigningAnchorBundle signingAnchors;

    /**
     * 有多少商品版本正在使用该模板的某个版本
     */
    private Integer productUsageCount;
}
