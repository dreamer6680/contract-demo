package shop.shmj.backend.beans.dto.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个签署锚点的配置，序列化进 {@code platform_contract_template_version.signing_anchor_json}。
 *
 * <p>{@code keyword} 是法大大关键字定位用的检索词；{@code offsetX/offsetY} 是
 * 相对关键字锚点左上角的像素偏移（单位与法大大一致：像素，可负）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SigningAnchorConfig {

    /**
     * 锚点编码，见 {@code SigningAnchor}
     */
    private String anchor;

    /**
     * 关键字（模板中必须唯一出现的文字，例如「甲方（盖章）」）
     */
    private String keyword;

    /**
     * 相对关键字的横向偏移
     */
    private String offsetX;

    /**
     * 相对关键字的纵向偏移
     */
    private String offsetY;

    /**
     * 是否启用；停用后该锚点不生成签署字段
     */
    private Boolean enabled;
}
