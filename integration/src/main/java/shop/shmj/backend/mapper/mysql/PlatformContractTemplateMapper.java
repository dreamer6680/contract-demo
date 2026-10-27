package shop.shmj.backend.mapper.mysql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import shop.shmj.backend.beans.entity.PlatformContractTemplate;

import java.util.Date;
import java.util.List;

/**
 * 合同模板主表 mapper。
 */
@Mapper
public interface PlatformContractTemplateMapper {

    int insert(PlatformContractTemplate record);

    PlatformContractTemplate selectByPrimaryKey(String id);

    PlatformContractTemplate selectByTemplateCode(@Param("templateCode") String templateCode);

    /**
     * 平台默认模板（必须已发布才能被商品兜底使用）
     */
    PlatformContractTemplate selectDefaultPublished();

    /**
     * 模板列表（管理端分页；PageHelper 拦截）
     */
    List<PlatformContractTemplate> selectTemplates(@Param("keyword") String keyword,
                                                   @Param("status") Integer status,
                                                   @Param("ownerScope") Integer ownerScope,
                                                   @Param("enterpriseId") String enterpriseId);

    /**
     * 商品可选模板：平台公共已发布 + 该企业私有已发布
     */
    List<PlatformContractTemplate> selectSelectableTemplates(@Param("enterpriseId") String enterpriseId);

    int updateByPrimaryKeySelective(PlatformContractTemplate record);

    /**
     * 置为默认模板，同时清掉其它模板的默认标记（同事务内调用）
     */
    int clearDefaultFlag();

    int updateEditVersion(@Param("id") String id,
                          @Param("currentVersionNo") Integer currentVersionNo,
                          @Param("updateTime") Date updateTime);

    int updatePublished(@Param("id") String id,
                        @Param("publishedVersionNo") Integer publishedVersionNo,
                        @Param("status") Integer status,
                        @Param("signAnchorMode") Integer signAnchorMode,
                        @Param("updateTime") Date updateTime);

    int updateStatus(@Param("id") String id,
                     @Param("status") Integer status,
                     @Param("updateTime") Date updateTime);
}
