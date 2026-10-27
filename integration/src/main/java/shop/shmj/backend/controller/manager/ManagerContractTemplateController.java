package shop.shmj.backend.controller.manager;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import shop.shmj.backend.beans.dict.ManagerRole;
import shop.shmj.backend.beans.dto.request.ContractTemplateCreateReq;
import shop.shmj.backend.beans.dto.request.ContractTemplateListReq;
import shop.shmj.backend.beans.dto.request.ContractTemplatePublishReq;
import shop.shmj.backend.beans.dto.request.ContractTemplateSaveVersionReq;
import shop.shmj.backend.beans.dto.response.BasePageRes;
import shop.shmj.backend.beans.dto.response.BaseRes;
import shop.shmj.backend.beans.dto.response.ContractTemplateRes;
import shop.shmj.backend.beans.dto.response.ContractTemplateVersionRes;
import shop.shmj.backend.beans.dto.response.EditorConfigRes;
import shop.shmj.backend.configurations.auth.AuthedManagerUser;
import shop.shmj.backend.configurations.auth.annotations.CurrentUser;
import shop.shmj.backend.configurations.auth.annotations.ManagerUserAuthRequired;
import shop.shmj.backend.configurations.limiter.RateLimiterNames;
import shop.shmj.backend.service.contract.ContractTemplateService;
import shop.shmj.backend.service.contract.TemplateFieldRegistry;

import java.util.List;

/**
 * 合同模板管理（运营端）。
 *
 * <p>管理端是模板的唯一作者：法大大发起合同用的是<b>平台自身</b>的 openCorpId，
 * 签章类型也由 {@code shmj.seal-usage.contract-seal-type} 统一约束，
 * 因此模板属于平台级资产，不能让每个企业各改一套。
 *
 * <p>授权：暂用 {@link ManagerRole#SpotManager}（内容维护角色）。
 * 若后续要拆出「合同模板管理员」，替换注解取值即可，接口不变。
 */
@RestController
@RequestMapping("manager/contract/template")
@RateLimiter(name = RateLimiterNames.DEFAULT_NAME)
public class ManagerContractTemplateController {

    @Autowired
    private ContractTemplateService contractTemplateService;

    /**
     * 模板列表。
     *
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager, ManagerRole.Checker})
    @GetMapping("list")
    public BaseRes<BasePageRes<ContractTemplateRes>> list(@CurrentUser AuthedManagerUser user,
                                                          ContractTemplateListReq req) {
        try (Page<ContractTemplateRes> page = PageHelper.startPage(req.getPageNum(), req.getPageSize())) {
            List<ContractTemplateRes> list = contractTemplateService.listTemplates(
                    req.getKeyword(), req.getStatus(), req.getOwnerScope(), req.getEnterpriseId());
            return BaseRes.withSuccess(new BasePageRes<>(page, list));
        }
    }

    /**
     * 可选字段字典（编辑器右侧「插入字段」面板）。
     *
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager, ManagerRole.Checker})
    @GetMapping("fields")
    public BaseRes<List<TemplateFieldRegistry.FieldDescriptor>> fields(@CurrentUser AuthedManagerUser user) {
        return BaseRes.withSuccess(contractTemplateService.listFields());
    }

    /**
     * 模板详情。
     *
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager, ManagerRole.Checker})
    @GetMapping("{id}")
    public BaseRes<ContractTemplateRes> detail(@CurrentUser AuthedManagerUser user, @PathVariable String id) {
        return BaseRes.withSuccess(contractTemplateService.getTemplate(id));
    }

    /**
     * 新建模板（此时还没有任何版本）。
     *
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager})
    @PostMapping(value = "create", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BaseRes<String> create(@CurrentUser AuthedManagerUser user,
                                  @RequestBody @Valid ContractTemplateCreateReq req) {
        return BaseRes.withSuccess(contractTemplateService.createTemplate(
                req, user.getId(), user.getPersonName()));
    }

    /**
     * 上传 DOCX 作为新版本。
     *
     * @apiNote 上传时会把 {&lt;$.x&gt;} 占位符归一化为锁定内容控件；有残缺占位符时直接拒绝。
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager})
    @PostMapping(value = "{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseRes<ContractTemplateVersionRes> upload(@CurrentUser AuthedManagerUser user,
                                                      @PathVariable String id,
                                                      @RequestParam("file") MultipartFile file) throws Exception {
        return BaseRes.withSuccess(contractTemplateService.uploadTemplate(
                id, file.getBytes(), file.getOriginalFilename(), user.getId(), user.getPersonName()));
    }

    /**
     * 版本列表。
     *
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager, ManagerRole.Checker})
    @GetMapping("{id}/versions")
    public BaseRes<List<ContractTemplateVersionRes>> versions(@CurrentUser AuthedManagerUser user,
                                                              @PathVariable String id) {
        return BaseRes.withSuccess(contractTemplateService.listVersions(id));
    }

    /**
     * 打开 ONLYOFFICE 编辑器。
     *
     * @apiNote 返回 documentServerUrl 与已签名的 config，前端直接交给 DocsAPI，不参与签名。
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager})
    @PostMapping("{id}/edit-session")
    public BaseRes<EditorConfigRes> editSession(@CurrentUser AuthedManagerUser user,
                                                @PathVariable String id) throws Exception {
        return BaseRes.withSuccess(contractTemplateService.openEditSession(
                id, user.getId(), user.getPersonName()));
    }

    /**
     * 保存为新版本（会先触发编辑器强制保存）。
     *
     * @apiNote 版本只增不改；返回的 changeSummary 会说明是否收到强制保存回执。
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager})
    @PostMapping(value = "{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BaseRes<ContractTemplateVersionRes> saveVersion(@CurrentUser AuthedManagerUser user,
                                                           @PathVariable String id,
                                                           @RequestBody @Valid ContractTemplateSaveVersionReq req)
            throws Exception {
        return BaseRes.withSuccess(contractTemplateService.saveVersion(
                id, req, user.getId(), user.getPersonName()));
    }

    /**
     * 发布版本。
     *
     * @apiNote 发布要过两道闸门：占位符必须都是已知字段；签署锚点关键字必须唯一命中。
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager})
    @PostMapping(value = "{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BaseRes<ContractTemplateRes> publish(@CurrentUser AuthedManagerUser user,
                                                @PathVariable String id,
                                                @RequestBody @Valid ContractTemplatePublishReq req) throws Exception {
        return BaseRes.withSuccess(contractTemplateService.publish(id, req, user.getId()));
    }

    /**
     * 停用模板。
     *
     * @apiNote 已选择该模板的商品不受影响，仅禁止新的选择。
     * @ignoreParams user
     */
    @ManagerUserAuthRequired({ManagerRole.SpotManager})
    @PostMapping("{id}/offline")
    public BaseRes<?> offline(@CurrentUser AuthedManagerUser user, @PathVariable String id) {
        contractTemplateService.offline(id);
        return BaseRes.withSuccess();
    }
}
