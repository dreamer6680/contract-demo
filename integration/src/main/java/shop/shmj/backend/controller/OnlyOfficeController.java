package shop.shmj.backend.controller;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import shop.shmj.backend.configurations.OnlyOfficeProperties;
import shop.shmj.backend.configurations.limiter.RateLimiterNames;
import shop.shmj.backend.external.impl.OnlyOfficeClient;
import shop.shmj.backend.service.contract.ContractTemplateService;

import java.util.Map;

/**
 * ONLYOFFICE Document Server 的对接端点（<b>无登录态</b>）。
 *
 * <p>这三个接口由 Document Server 调用，不能挂 {@code @PlatformAuthRequired}/
 * {@code @ManagerUserAuthRequired}（否则它的请求没有用户 JWT 会被拒）。安全性靠：
 * <ul>
 *   <li>{@code document}：URL 里的一次性签名 token，且只能取到会话对应的展示副本；</li>
 *   <li>{@code callback}：校验 Document Server 用 JWT 密钥签的回调 token；</li>
 *   <li>{@code ready}：只返回探活结果，不泄露任何配置。</li>
 * </ul>
 *
 * <p>与 {@code fadada/callback} 的处理方式保持一致：路径公开、控制器内自行验签。
 */
@Slf4j
@RestController
@RequestMapping("contract/onlyoffice")
@RateLimiter(name = RateLimiterNames.DEFAULT_NAME)
public class OnlyOfficeController {

    @Autowired
    private ContractTemplateService contractTemplateService;

    @Autowired
    private OnlyOfficeClient onlyOfficeClient;

    @Autowired
    private OnlyOfficeProperties onlyOfficeProperties;

    /**
     * 探活：Document Server 是否可达、JWT 是否一致。
     *
     * @apiNote 编辑器页加载前调用；返回 code=JWT_MISMATCH 时说明两边密钥不一致。
     */
    @GetMapping("ready")
    public Map<String, Object> ready() {
        if (!onlyOfficeProperties.isEnabled()) {
            return Map.of("ready", false, "code", "DISABLED",
                    "message", "ONLYOFFICE 编辑未启用（shmj.onlyoffice.enabled=false）");
        }
        OnlyOfficeClient.ReadyState state = onlyOfficeClient.ready();
        return Map.of("ready", state.ready(), "code", state.code(), "message", state.message());
    }

    /**
     * Document Server 拉取「展示副本」。
     *
     * @param documentKey 会话 key
     * @param token       后端签发的一次性 token
     */
    @GetMapping("document/{documentKey}")
    public ResponseEntity<byte[]> document(@PathVariable String documentKey,
                                           @RequestParam("token") String token) throws Exception {
        byte[] bytes = contractTemplateService.loadDocumentForEditor(documentKey, token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }

    /**
     * 保存回调。
     *
     * @apiNote status=2/6 时带文件地址；后端会把业务值还原为占位符、与基线比对，
     * 任何占位符缺失/改名都返回 error=1（编辑器会提示保存失败）。
     */
    @PostMapping(value = "callback/{templateId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> callback(@PathVariable String templateId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Map<String, Object> body) {
        try {
            return contractTemplateService.handleSaveCallback(templateId, authorization, body);
        } catch (Exception e) {
            log.error("onlyoffice 回调处理异常 templateId={}", templateId, e);
            return Map.of("error", 1);
        }
    }
}
