package shop.shmj.backend.external.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import shop.shmj.backend.configurations.OnlyOfficeProperties;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONLYOFFICE Document Server 客户端：签发/校验 JWT、拼编辑器配置、强制保存、下载回传文件。
 *
 * <p>与 Demo 的差异（都是生产必需）：
 * <ul>
 *   <li>JWT 由后端签发，密钥不下发前端；回调必须验签，否则任何人都能伪造保存请求覆盖模板；</li>
 *   <li>文档与回调地址都用 {@code backendUrlForDocServer}，不能用 {@code localhost}；</li>
 *   <li>Document Server 回传的下载地址是它自己的公网域名，后端要用 {@code internalUrl} 去取。</li>
 * </ul>
 */
@Slf4j
@Component
public class OnlyOfficeClient {

    private static final String DOC_TYPE_WORD = "word";
    private static final String DOCX = "docx";

    @Autowired
    private OnlyOfficeProperties properties;

    private final RestClient restClient = RestClient.create();

    private SecretKeySpec key() {
        return new SecretKeySpec(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    // ------------------------------------------------------------------
    // JWT
    // ------------------------------------------------------------------

    /**
     * 把一个任意 Map 整体塞进 JWT 载荷（ONLYOFFICE 的约定）。
     */
    public String sign(Map<String, Object> payload) {
        return Jwts.builder()
                .setClaims(payload)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000L))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 校验 Document Server 回调里的 token。
     */
    public boolean verify(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Claims ignored = Jwts.parserBuilder()
                    .setSigningKey(key())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return true;
        } catch (Exception e) {
            log.warn("onlyoffice token 校验失败: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 编辑器配置
    // ------------------------------------------------------------------

    /**
     * 构造前端 {@code DocsAPI.DocEditor} 所需配置（已签名）。
     *
     * @param templateId     模板 ID（回调路径里用它定位模板）
     * @param versionNo      正在编辑的版本号
     * @param documentKey    本次会话唯一的 document key；内容变了必须换 key，否则编辑器会命中旧缓存
     * @param documentUrl    Document Server 拉取文件的地址（绝对地址）
     * @param title          显示标题
     * @param userId         操作人（回调里校验必须同一个人）
     * @param userName       操作人姓名
     */
    public Map<String, Object> buildEditorConfig(String templateId,
                                                 int versionNo,
                                                 String documentKey,
                                                 String documentUrl,
                                                 String title,
                                                 String userId,
                                                 String userName) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", DOCX);
        document.put("key", documentKey);
        document.put("title", title + ".docx");
        document.put("url", documentUrl);
        document.put("permissions", Map.of("edit", true, "download", true, "print", true));

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", "edit");
        editorConfig.put("lang", "zh-CN");
        editorConfig.put("callbackUrl", properties.normalizedBackendUrl()
                + "/contract/onlyoffice/callback/" + templateId);
        // 自定义字段随回调原样带回，用于「谁在上一次会话里改了哪一版」
        editorConfig.put("customizer", Map.of("customer", Map.of("templateId", templateId,
                "versionNo", versionNo, "operatorId", userId)));
        editorConfig.put("user", Map.of("id", userId, "name", userName == null ? "运营" : userName));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("documentType", DOC_TYPE_WORD);
        config.put("document", document);
        config.put("editorConfig", editorConfig);
        config.put("width", "100%");
        config.put("height", "100%");
        config.put("token", sign(config));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentServerUrl", properties.normalizedPublicUrl());
        result.put("config", config);
        return result;
    }

    /**
     * 让 Document Server 把编辑器缓存落盘（回调 status=6）。
     *
     * @return 命令是否被接受；<b>不代表已保存完成</b>，保存完成看回调
     */
    public boolean forceSave(String documentKey) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("c", "forcesave");
        command.put("key", documentKey);
        try {
            restClient.post()
                    .uri(properties.normalizedInternalUrl() + "/coauthoring/CommandService.ashx")
                    .header("Authorization", "Bearer " + sign(command))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("onlyoffice forcesave 失败, key={}", documentKey, e);
            return false;
        }
    }

    /**
     * 探活 + JWT 一致性自检。
     *
     * <p>CommandService 的 {@code {c:"info"}} 请求若返回 {@code error=6}，
     * 说明两边 JWT 密钥不一致，这类配置错误必须给出明确提示，而不是让用户看编辑器白屏。
     */
    public ReadyState ready() {
        try {
            restClient.get()
                    .uri(properties.normalizedInternalUrl() + "/healthcheck")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            return ReadyState.unavailable("ONLYOFFICE Document Server 未启动或后端不可达");
        }

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("c", "info");
        try {
            Object response = restClient.post()
                    .uri(properties.normalizedInternalUrl() + "/coauthoring/CommandService.ashx")
                    .header("Authorization", "Bearer " + sign(command))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .retrieve()
                    .body(Object.class);
            if (response instanceof Map<?, ?> map && map.get("error") instanceof Number n && n.intValue() == 6) {
                return ReadyState.jwtMismatch();
            }
            return ReadyState.ok();
        } catch (Exception e) {
            return ReadyState.unavailable("命令服务不可用: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

    /**
     * 下载 Document Server 回传的文件。
     *
     * <p>回调给的 URL 是 Document Server 的<b>公网</b>地址，容器内的后端不一定能访问，
     * 所以把 host 换成 {@code internalUrl}。只允许指向已配置的 Document Server，防止 SSRF。
     */
    public byte[] downloadCallbackFile(String url) throws IOException {
        String internal = internalize(url);
        try {
            byte[] bytes = restClient.get().uri(URI.create(internal)).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new IOException("回调文件为空");
            }
            return bytes;
        } catch (Exception e) {
            throw new IOException("下载回调文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把公网 URL 映射为内部 URL；不匹配则拒绝（防 SSRF）。
     */
    String internalize(String url) throws IOException {
        String internalBase = properties.normalizedInternalUrl();
        if (url.startsWith(internalBase + "/")) {
            return url;
        }
        String publicBase = properties.normalizedPublicUrl();
        if (publicBase != null && url.startsWith(publicBase + "/")) {
            return internalBase + url.substring(publicBase.length());
        }
        try {
            URI target = URI.create(url);
            URI external = publicBase == null ? null : URI.create(publicBase);
            if (external != null
                    && target.getHost() != null
                    && target.getHost().equalsIgnoreCase(external.getHost())
                    && target.getPort() == external.getPort()
                    && target.getRawPath() != null) {
                String query = target.getRawQuery() == null ? "" : "?" + target.getRawQuery();
                return internalBase + target.getRawPath() + query;
            }
        } catch (Exception ignored) {
            // 落到下面的拒绝分支
        }
        throw new IOException("非法的回调文件地址: " + url);
    }

    /**
     * 探活结果。
     */
    public record ReadyState(boolean ready, String code, String message) {

        public static ReadyState ok() {
            return new ReadyState(true, "OK", "ready");
        }

        public static ReadyState unavailable(String message) {
            return new ReadyState(false, "UNAVAILABLE", message);
        }

        public static ReadyState jwtMismatch() {
            return new ReadyState(false, "JWT_MISMATCH",
                    "后端与 ONLYOFFICE 的 JWT 密钥不一致，请用同一个值重建两个服务");
        }
    }
}
