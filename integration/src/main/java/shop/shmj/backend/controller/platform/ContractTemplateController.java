package shop.shmj.backend.controller.platform;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import shop.shmj.backend.beans.dict.PlatformRole;
import shop.shmj.backend.beans.dto.response.BaseRes;
import shop.shmj.backend.beans.dto.response.ContractTemplateOptionRes;
import shop.shmj.backend.configurations.auth.AuthedPlatformUser;
import shop.shmj.backend.configurations.auth.annotations.CurrentUser;
import shop.shmj.backend.configurations.auth.annotations.PlatformAuthRequired;
import shop.shmj.backend.configurations.limiter.RateLimiterNames;
import shop.shmj.backend.service.contract.ContractTemplateService;

import java.util.List;

/**
 * 企业侧：商品上架时的合同模板选择。
 *
 * <p>企业<b>不能</b>编辑模板（法大大签署发起方是平台），只能从平台发布过的模板里选。
 * 一期先把「选」做出来；二期若要支持企业私有模板，把 ownerScope=2 的模板纳入本接口即可，
 * 但必须配套企业模板的审核流程。
 */
@RestController
@RequestMapping("product/contract-template")
@RateLimiter(name = RateLimiterNames.DEFAULT_NAME)
public class ContractTemplateController {

    @Autowired
    private ContractTemplateService contractTemplateService;

    /**
     * 商品上架可选模板列表。
     *
     * @apiNote 只返回 status=已发布 的模板；平台公共模板 + 本企业私有模板。
     * @ignoreParams currentUser
     */
    @PlatformAuthRequired({PlatformRole.Salesman, PlatformRole.Manager})
    @GetMapping("list")
    public BaseRes<List<ContractTemplateOptionRes>> list(@CurrentUser AuthedPlatformUser currentUser) {
        return BaseRes.withSuccess(
                contractTemplateService.listSelectable(currentUser.getCurrentEnterpriseId()));
    }
}
