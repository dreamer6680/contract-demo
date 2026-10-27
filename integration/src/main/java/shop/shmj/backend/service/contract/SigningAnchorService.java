package shop.shmj.backend.service.contract;

import com.fasc.open.api.bean.common.Field;
import com.fasc.open.api.bean.common.FieldCorpSeal;
import com.fasc.open.api.bean.common.FieldCorpSealCross;
import com.fasc.open.api.bean.common.FieldPersonSign;
import com.fasc.open.api.bean.common.FieldPosition;
import com.fasc.open.api.enums.common.PositionModeEnum;
import com.fasc.open.api.exception.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import shop.shmj.backend.beans.dict.SealType;
import shop.shmj.backend.beans.dict.SigningAnchor;
import shop.shmj.backend.beans.dto.contract.SigningAnchorBundle;
import shop.shmj.backend.beans.dto.contract.SigningAnchorConfig;
import shop.shmj.backend.beans.dto.contract.SigningAnchorResolution;
import shop.shmj.backend.exceptions.DisallowedOperationException;
import shop.shmj.backend.external.FileStorage;
import shop.shmj.backend.external.impl.FascOpenApi;
import shop.shmj.backend.utils.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 签署位置服务：把「签章放哪」从写死坐标改成「按关键字定位」。
 *
 * <h3>为什么必须改</h3>
 * 现有 {@code ContractTask} 把签章位置写死成第 6 页的像素坐标。模板一旦允许运营
 * 在线修改（加一行、换字号、删一段），这些坐标就会漂移，章盖到正文上，而法大大
 * 不会报错——签完才发现，只能走撤销重签。
 *
 * <h3>做法</h3>
 * 模板里保留自然的签署区文字（例如「甲方（盖章）」）。发布模板时：
 * <ol>
 *   <li>用示例数据把模板渲染成真实文档；</li>
 *   <li>上传到法大大并 {@code processFile} 拿到 fileId；</li>
 *   <li>调用 {@code /file/get-keyword-positions} 反查每个关键字落在第几页、什么坐标；</li>
 *   <li>每个关键字必须<b>唯一命中</b>，否则拒绝发布并提示运营改措辞。</li>
 * </ol>
 * 正式签署时用 {@code positionMode=keyword} + 偏移量交给法大大自己定位，
 * 这样无论订单数据长短如何变化，章都跟着关键字走。
 */
@Slf4j
@Service
public class SigningAnchorService {

    @Autowired
    private FascOpenApi fascService;

    @Autowired
    private FileStorage fileStorage;

    /**
     * 默认锚点配置：关键字取枚举里的默认措辞，偏移量按模板版式给定初值。
     * 运营可在发布页调整，调整结果存进版本快照。
     */
    public List<SigningAnchorConfig> defaultConfig() {
        List<SigningAnchorConfig> configs = new ArrayList<>();
        for (SigningAnchor anchor : SigningAnchor.values()) {
            configs.add(SigningAnchorConfig.builder()
                    .anchor(anchor.getCode())
                    .keyword(anchor.getDefaultKeyword())
                    .offsetX(defaultOffsetX(anchor))
                    .offsetY(defaultOffsetY(anchor))
                    .enabled(true)
                    .build());
        }
        return configs;
    }

    /**
     * 关键字锚点通常落在文字左侧；公章需要往右下方挪一点才不会压住文字。
     */
    private String defaultOffsetX(SigningAnchor anchor) {
        return anchor.getFieldKind() == SigningAnchor.SigningFieldKind.CORP_SEAL ? "0" : "0";
    }

    private String defaultOffsetY(SigningAnchor anchor) {
        return anchor.getFieldKind() == SigningAnchor.SigningFieldKind.CORP_SEAL ? "0" : "0";
    }

    /**
     * 发布前校验：把渲染后的文档上传法大大，反查每个关键字的位置。
     *
     * @param renderedDocx 用示例数据渲染后的 DOCX（不是模板原件——占位符展开后版面才会定稿）
     * @param configs      锚点配置
     * @return 每个锚点的定位结果
     * @throws DisallowedOperationException 关键字缺失或有歧义时抛出，带上可读的原因
     */
    public List<SigningAnchorResolution> resolve(byte[] renderedDocx, List<SigningAnchorConfig> configs)
            throws IOException, ApiException {

        List<SigningAnchorConfig> enabled = configs.stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .filter(c -> StringUtils.isNotBlank(c.getKeyword()))
                .toList();
        if (enabled.isEmpty()) {
            throw new DisallowedOperationException("模板未配置任何签署锚点，无法生成签署任务");
        }

        String tempKey = fileStorage.putTempFile("docx", renderedDocx);
        String presigned = fileStorage.generatePresignedUrl(tempKey);
        String fddFileUrl = fascService.uploadFileByURL(presigned, FascOpenApi.FileType.DOC);
        String fileId = fascService.processFile(fddFileUrl, "sign-anchor-check.docx", FascOpenApi.FileType.DOC);

        List<String> keywords = enabled.stream().map(SigningAnchorConfig::getKeyword).distinct().toList();
        List<FascOpenApi.KeywordPosition> positions = fascService.getKeywordPositions(fileId, keywords);

        Map<String, List<FascOpenApi.KeywordPosition>> byKeyword = new LinkedHashMap<>();
        for (FascOpenApi.KeywordPosition p : positions) {
            byKeyword.computeIfAbsent(p.keyword(), k -> new ArrayList<>()).add(p);
        }

        List<SigningAnchorResolution> resolutions = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (SigningAnchorConfig config : enabled) {
            List<FascOpenApi.KeywordPosition> hits = byKeyword.getOrDefault(config.getKeyword(), List.of());
            SigningAnchor anchor = SigningAnchor.fromCode(config.getAnchor());
            String anchorLabel = anchor == null ? config.getAnchor() : anchor.getCode();

            if (hits.isEmpty()) {
                problems.add(String.format("锚点 %s 的关键字「%s」在模板中找不到", anchorLabel, config.getKeyword()));
                continue;
            }
            if (hits.size() > 1) {
                // 同一句话在模板里出现多次时，法大大无法判断盖在哪一处，必须让人改措辞
                problems.add(String.format("锚点 %s 的关键字「%s」命中 %d 处，请改成唯一的措辞",
                        anchorLabel, config.getKeyword(), hits.size()));
                continue;
            }
            FascOpenApi.KeywordPosition hit = hits.get(0);
            resolutions.add(SigningAnchorResolution.builder()
                    .anchor(config.getAnchor())
                    .keyword(config.getKeyword())
                    .offsetX(config.getOffsetX())
                    .offsetY(config.getOffsetY())
                    .pageNo(hit.pageNo())
                    .positionX(hit.x())
                    .positionY(hit.y())
                    .hitCount(hits.size())
                    .build());
        }

        if (!problems.isEmpty()) {
            throw new DisallowedOperationException("签署锚点校验未通过：" + String.join("；", problems));
        }
        return resolutions;
    }

    /**
     * 生成某个签署方的全部签署字段。
     *
     * @param party           签署方（甲方=买方，乙方=卖方）
     * @param configs         版本快照里的锚点配置
     * @param sealType        用哪种企业章（沿用 {@code shmj.seal-usage.contract-seal-type}）
     * @param crossPageSealY  骑缝章纵向位置；为 null 表示不加骑缝章
     */
    public List<FascOpenApi.FieldHolder> buildFields(SigningAnchor.SigningParty party,
                                                     List<SigningAnchorConfig> configs,
                                                     SealType sealType,
                                                     Long crossPageSealY) {
        List<FascOpenApi.FieldHolder> fields = new ArrayList<>();

        for (SigningAnchorConfig config : configs) {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                continue;
            }
            SigningAnchor anchor = SigningAnchor.fromCode(config.getAnchor());
            if (anchor == null || anchor.getParty() != party) {
                continue;
            }
            if (anchor.getFieldKind() == SigningAnchor.SigningFieldKind.CORP_SEAL) {
                fields.add(corpSealField(party, sealType, config));
            } else {
                fields.add(personSignField(party, config));
            }
        }

        if (crossPageSealY != null) {
            fields.add(crossPageSealField(party, sealType, crossPageSealY));
        }
        return fields;
    }

    /**
     * 关键字定位的公章字段。
     */
    private FascOpenApi.FieldHolder corpSealField(SigningAnchor.SigningParty party,
                                                  SealType sealType,
                                                  SigningAnchorConfig config) {
        FieldPosition position = keywordPosition(config);

        Field field = new Field();
        field.setFieldId("corp_seal_" + party.name());
        field.setFieldName("公章");
        field.setFieldType("corp_seal");
        field.setMoveable(true);
        field.setPosition(position);

        FieldCorpSeal corpSeal = new FieldCorpSeal();
        corpSeal.setCategoryType(sealType.fascCategoryType());
        field.setFieldCorpSeal(corpSeal);

        return new FascOpenApi.FieldHolder(field, null);
    }

    /**
     * 关键字定位的经办人签字字段。
     */
    private FascOpenApi.FieldHolder personSignField(SigningAnchor.SigningParty party,
                                                    SigningAnchorConfig config) {
        FieldPosition position = keywordPosition(config);

        Field field = new Field();
        field.setFieldId("person_seal_" + party.name());
        field.setFieldName("经办人印章");
        field.setFieldType("person_sign");
        field.setMoveable(true);
        field.setPosition(position);

        FieldPersonSign personSign = new FieldPersonSign();
        field.setFieldPersonSign(personSign);

        return new FascOpenApi.FieldHolder(field, null);
    }

    private FieldPosition keywordPosition(SigningAnchorConfig config) {
        FieldPosition position = new FieldPosition();
        position.setPositionMode(PositionModeEnum.KEYWORD.getCode());
        position.setPositionKeyword(config.getKeyword());
        if (StringUtils.isNotBlank(config.getOffsetX())) {
            position.setKeywordOffsetX(config.getOffsetX());
        }
        if (StringUtils.isNotBlank(config.getOffsetY())) {
            position.setKeywordOffsetY(config.getOffsetY());
        }
        return position;
    }

    /**
     * 骑缝章不依赖页码，覆盖全部页面。
     */
    private FascOpenApi.FieldHolder crossPageSealField(SigningAnchor.SigningParty party,
                                                       SealType sealType,
                                                       Long positionY) {
        FieldPosition position = new FieldPosition();
        position.setPositionMode(PositionModeEnum.PIXEL.getCode());
        position.setPositionY(Long.toString(positionY));

        Field field = new Field();
        field.setFieldId("corp_seal_cross_" + party.name());
        field.setFieldName("骑缝章");
        field.setFieldType("corp_seal_cross_page");
        field.setMoveable(true);
        field.setPosition(position);

        FieldCorpSealCross cross = new FieldCorpSealCross();
        cross.setCategoryType(sealType.fascCategoryType());
        field.setFieldCorpSealCross(cross);

        return new FascOpenApi.FieldHolder(field, null);
    }

    // ------------------------------------------------------------------
    // JSON 序列化
    // ------------------------------------------------------------------

    /**
     * 序列化为版本快照里的 {@code signing_anchor_json}。
     */
    public String toJson(List<SigningAnchorConfig> configs, List<SigningAnchorResolution> resolutions) {
        try {
            return JsonUtils.toJsonString(SigningAnchorBundle.builder()
                    .configs(configs)
                    .resolutions(resolutions)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("锚点配置序列化失败", e);
        }
    }

    /**
     * 反序列化；兼容「只有 configs 的数组格式」旧数据。
     */
    public SigningAnchorBundle parse(String json) {
        if (StringUtils.isBlank(json)) {
            return SigningAnchorBundle.builder().configs(defaultConfig()).build();
        }
        String trimmed = json.trim();
        try {
            if (trimmed.startsWith("[")) {
                List<SigningAnchorConfig> list = JsonUtils.fromJsonString(trimmed,
                        new TypeReference<List<SigningAnchorConfig>>() {
                        });
                return SigningAnchorBundle.builder()
                        .configs(list == null || list.isEmpty() ? defaultConfig() : list)
                        .build();
            }
            SigningAnchorBundle bundle = JsonUtils.fromJsonString(trimmed, SigningAnchorBundle.class);
            if (bundle == null || bundle.getConfigs() == null || bundle.getConfigs().isEmpty()) {
                return SigningAnchorBundle.builder().configs(defaultConfig()).build();
            }
            return bundle;
        } catch (Exception e) {
            log.warn("锚点配置反序列化失败，回退默认值", e);
            return SigningAnchorBundle.builder().configs(defaultConfig()).build();
        }
    }

    /**
     * 便捷入口：只要 configs。
     */
    public List<SigningAnchorConfig> parseConfig(String json) {
        return parse(json).getConfigs();
    }
}
