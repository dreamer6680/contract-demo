package shop.shmj.backend.beans.dto.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 锚点在具体文档中的定位结果（发布时反查、或渲染后计算得到）。
 *
 * <p>用途：
 * <ul>
 *   <li>发布 gate：关键字必须<b>唯一命中</b>，否则拒绝发布并提示运营改措辞；</li>
 *   <li>留痕：写进 {@code platform_contract.render_snapshot}，日后可复核章盖在哪；</li>
 *   <li>降级：当关键字定位不可用时，可用这里的像素值退回 {@code positionMode=pixel}。</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SigningAnchorResolution {

    private String anchor;

    private String keyword;

    private String offsetX;

    private String offsetY;

    /**
     * 命中的页码（1 起）
     */
    private Integer pageNo;

    /**
     * 命中位置的 x / y（像素，来自法大大）
     */
    private String positionX;

    private String positionY;

    /**
     * 该关键字在文档中出现的次数；>1 表示歧义，发布时应拒绝
     */
    private Integer hitCount;
}
