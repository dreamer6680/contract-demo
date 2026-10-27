package shop.shmj.backend.beans.dict;

import lombok.Getter;

/**
 * 签署区锚点定义。
 *
 * <p><b>为什么需要这层抽象</b>：现有 {@code ContractTask} 把签章位置写死成
 * 「第 6 页 (520,750)」这类像素坐标。一旦模板允许用户自定义，版式必然变化，
 * 写死坐标就会把章盖到正文上，而且法大大不会报错——签完才发现。
 *
 * <p>解法：模板里保留自然的签署区文字（如「甲方（盖章）」），渲染后由
 * 法大大按 <b>关键字定位</b>（{@code PositionModeEnum.KEYWORD}）来找位置。
 * 同一份配置还能在发布时用 {@code /file/get-keyword-positions} 反查坐标，
 * 用于「关键字必须唯一命中」的发布校验。
 *
 * <p>注意：关键字是<b>可配置</b>的（存 version.signing_anchor_json），
 * 因为历史模板的措辞可能与默认值不同，不能硬编码。
 */
@Getter
public enum SigningAnchor {

    /** 甲方（买方）公章 */
    A_CORP_SEAL("A_CORP_SEAL", "甲方（盖章）", SigningParty.PARTY_A, SigningFieldKind.CORP_SEAL),
    /** 甲方经办人签字 */
    A_PERSON_SIGN("A_PERSON_SIGN", "法定代表人（签字）", SigningParty.PARTY_A, SigningFieldKind.PERSON_SIGN),
    /** 乙方（卖方）公章 */
    B_CORP_SEAL("B_CORP_SEAL", "乙方（盖章）", SigningParty.PARTY_B, SigningFieldKind.CORP_SEAL),
    /** 乙方经办人签字 */
    B_PERSON_SIGN("B_PERSON_SIGN", "法定代表人（签字）", SigningParty.PARTY_B, SigningFieldKind.PERSON_SIGN),
    ;

    /**
     * 锚点编码（写进 JSON 与前端表单）
     */
    private final String code;

    /**
     * 默认关键字；模板措辞不同时由运营在发布前改写
     */
    private final String defaultKeyword;

    /**
     * 归属签署方
     */
    private final SigningParty party;

    /**
     * 字段类型
     */
    private final SigningFieldKind fieldKind;

    SigningAnchor(String code, String defaultKeyword, SigningParty party, SigningFieldKind fieldKind) {
        this.code = code;
        this.defaultKeyword = defaultKeyword;
        this.party = party;
        this.fieldKind = fieldKind;
    }

    public static SigningAnchor fromCode(String code) {
        for (SigningAnchor anchor : values()) {
            if (anchor.code.equals(code)) {
                return anchor;
            }
        }
        return null;
    }

    public enum SigningParty {
        /** 甲方 = 买方 */
        PARTY_A,
        /** 乙方 = 卖方 */
        PARTY_B,
    }

    public enum SigningFieldKind {
        CORP_SEAL,
        PERSON_SIGN,
        ;
    }
}
