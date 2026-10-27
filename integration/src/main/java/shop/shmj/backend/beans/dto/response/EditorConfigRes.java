package shop.shmj.backend.beans.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.Map;

/**
 * 打开 ONLYOFFICE 编辑器所需的响应。
 *
 * <p>{@code config} 里已经带好签名（{@code token}），前端直接交给
 * {@code new DocsAPI.DocEditor(id, config)} 即可，不需要也不允许前端参与签名。
 */
@Getter
@Setter
public class EditorConfigRes {

    /**
     * 浏览器可访问的 Document Server 地址，用于动态加载 api.js
     */
    private String documentServerUrl;

    /**
     * 已签名的编辑器配置
     */
    private Map<String, Object> config;

    /**
     * 会话过期时间；过期后编辑器即使还开着，保存也会被拒绝
     */
    private Date sessionExpireAt;

    /**
     * 本次编辑的版本号（草稿）
     */
    private Integer versionNo;
}
