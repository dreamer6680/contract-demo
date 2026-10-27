package shop.shmj.backend.configurations;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ONLYOFFICE Document Server 配置。
 *
 * <p><b>三个地址不能混用</b>（这是本地联调最容易踩的坑）：
 * <ul>
 *   <li>{@code publicUrl}：浏览器访问 Document Server 的地址，要写进前端 {@code DocsAPI} 脚本；
 *   <li>{@code internalUrl}：后端访问 Document Server 的地址（CommandService / 健康检查 / 下载缓存文件）；
 *   <li>{@code backendUrlForDocServer}：Document Server 反向访问本服务的地址，
 *       用于拼 {@code document.url} 与 {@code callbackUrl}。Document Server 是独立容器，
 *       访问 {@code localhost:8080} 只会打到它自己。
 * </ul>
 *
 * <p>生产必须 HTTPS + 受控网络；{@code jwtSecret} 不得下发到浏览器。
 */
@Slf4j
@Component
@ConfigurationProperties("shmj.onlyoffice")
@Getter
@Setter
public class OnlyOfficeProperties {

    /**
     * 总开关。关闭后模板编辑接口直接返回「未启用」，不影响合同生成/签署
     */
    private boolean enabled = false;

    /**
     * 浏览器可达的 Document Server 地址，例如 https://docs.shmj.shop
     */
    private String publicUrl;

    /**
     * 本服务内部可达的 Document Server 地址，例如 http://onlyoffice:80
     */
    private String internalUrl;

    /**
     * Document Server 可达的本服务地址，例如 http://shmj-backend:8080
     */
    private String backendUrlForDocServer;

    /**
     * 与 Document Server 的 JWT 密钥，两边必须完全一致
     */
    private String jwtSecret;

    /**
     * 编辑会话有效期（分钟）；超时未保存则回到最后保存的版本
     */
    private int sessionTtlMinutes = 120;

    /**
     * 强制保存等待回执的最长毫秒数
     */
    private long forceSaveTimeoutMillis = 8000L;

    public String normalizedPublicUrl() {
        return trimSlash(publicUrl);
    }

    public String normalizedInternalUrl() {
        return trimSlash(internalUrl);
    }

    public String normalizedBackendUrl() {
        return trimSlash(backendUrlForDocServer);
    }

    private String trimSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("/+$", "");
    }

    @Override
    public String toString() {
        // 绝不打印 jwtSecret
        return "OnlyOfficeProperties{enabled=" + enabled
                + ", publicUrl='" + publicUrl + '\''
                + ", internalUrl='" + internalUrl + '\''
                + ", backendUrlForDocServer='" + backendUrlForDocServer + '\''
                + ", sessionTtlMinutes=" + sessionTtlMinutes + '}';
    }
}
