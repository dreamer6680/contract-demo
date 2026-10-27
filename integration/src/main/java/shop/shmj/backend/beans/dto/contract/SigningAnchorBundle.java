package shop.shmj.backend.beans.dto.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 存进 {@code platform_contract_template_version.signing_anchor_json} 的完整锚点包。
 *
 * <p>{@code configs} 是签署时真正使用的配置（关键字 + 偏移），
 * {@code resolutions} 是发布时用示例文档反查出来的实际坐标，用于审计与降级。
 *
 * <p>兼容旧数据：如果解析到的 JSON 以 {@code [} 开头，视为只有 configs 的旧格式。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SigningAnchorBundle {

    private List<SigningAnchorConfig> configs;

    private List<SigningAnchorResolution> resolutions;
}
