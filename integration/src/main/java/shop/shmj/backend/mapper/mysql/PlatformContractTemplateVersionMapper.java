package shop.shmj.backend.mapper.mysql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.shmj.backend.beans.entity.PlatformContractTemplateVersion;

import java.util.List;

/**
 * 合同模板版本 mapper。版本只增不改，因此只有 insert 与状态流转。
 */
@Mapper
public interface PlatformContractTemplateVersionMapper {

    int insert(PlatformContractTemplateVersion record);

    PlatformContractTemplateVersion selectByTemplateIdAndVersion(@Param("templateId") String templateId,
                                                                 @Param("versionNo") Integer versionNo);

    /**
     * 编辑中的草稿：同一模板最多一个
     */
    PlatformContractTemplateVersion selectDraft(@Param("templateId") String templateId);

    PlatformContractTemplateVersion selectPublished(@Param("templateId") String templateId);

    List<PlatformContractTemplateVersion> selectByTemplateId(@Param("templateId") String templateId);

    int selectMaxVersionNo(@Param("templateId") String templateId);

    int updateStatus(@Param("id") String id, @Param("status") Integer status);

    /**
     * 发布时写入锚点配置与定位结果。
     *
     * <p>这是模板版本字段里<b>唯一允许变更</b>的一项：它只描述「怎么签署」，
     * 不改变合同正文，因此不破坏版本不可变语义；且发布后即冻结。
     */
    int updateAnchors(@Param("id") String id, @Param("signingAnchorJson") String signingAnchorJson);

    /**
     * 把该模板下除指定版本外的已发布版本全部置为「已被取代」
     */
    int supersedeOtherPublished(@Param("templateId") String templateId,
                                @Param("keepVersionNo") Integer keepVersionNo);

    /**
     * 统计有多少商品版本正在使用某模板版本（删除保护）
     */
    int countProductUsage(@Param("templateId") String templateId,
                          @Param("versionNo") Integer versionNo);
}
