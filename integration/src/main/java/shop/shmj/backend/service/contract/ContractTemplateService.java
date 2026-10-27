package shop.shmj.backend.service.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shop.shmj.backend.beans.dict.ContractTemplateStatus;
import shop.shmj.backend.beans.dict.ContractTemplateVersionStatus;
import shop.shmj.backend.beans.dict.SigningAnchor;
import shop.shmj.backend.beans.dto.contract.SigningAnchorBundle;
import shop.shmj.backend.beans.dto.contract.SigningAnchorConfig;
import shop.shmj.backend.beans.dto.contract.SigningAnchorResolution;
import shop.shmj.backend.beans.dto.request.ContractTemplateCreateReq;
import shop.shmj.backend.beans.dto.request.ContractTemplatePublishReq;
import shop.shmj.backend.beans.dto.request.ContractTemplateSaveVersionReq;
import shop.shmj.backend.beans.dto.response.ContractTemplateOptionRes;
import shop.shmj.backend.beans.dto.response.ContractTemplateRes;
import shop.shmj.backend.beans.dto.response.ContractTemplateVersionRes;
import shop.shmj.backend.beans.dto.response.EditorConfigRes;
import shop.shmj.backend.beans.entity.PlatformContractTemplate;
import shop.shmj.backend.beans.entity.PlatformContractTemplateVersion;
import shop.shmj.backend.configurations.OnlyOfficeProperties;
import shop.shmj.backend.exceptions.DisallowedOperationException;
import shop.shmj.backend.external.FileStorage;
import shop.shmj.backend.external.impl.OnlyOfficeClient;
import shop.shmj.backend.mapper.mysql.PlatformContractTemplateMapper;
import shop.shmj.backend.mapper.mysql.PlatformContractTemplateVersionMapper;
import shop.shmj.backend.utils.IDGenerator;
import shop.shmj.backend.utils.JsonUtils;
import shop.shmj.backend.utils.SdtTemplateUtil;
import shop.shmj.backend.utils.SdtTokenRestoreUtil;
import shop.shmj.backend.utils.WordTemplateUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 合同模板与版本管理的核心服务。
 *
 * <h3>模型</h3>
 * <pre>
 * platform_contract_template             逻辑模板（名称、状态、当前/已发布版本号）
 *   └─ platform_contract_template_version  版本（只增不改，file_key 指向 OSS 上的占位符版 DOCX）
 * </pre>
 *
 * <h3>三条不可妥协的规则</h3>
 * <ol>
 *   <li><b>版本不可变</b>：发布过的文件永不覆盖。改一个字 = 新版本号，否则历史合同无法举证。</li>
 *   <li><b>只有已发布版本能被商品选中</b>：草稿状态永远不出现在商品下拉框里。</li>
 *   <li><b>发布必须过两道闸门</b>：占位符集合必须与已知字段注册表一致；签署锚点必须唯一命中。</li>
 * </ol>
 *
 * <h3>与 ONLINE OFFICE 的配合</h3>
 * 编辑器看到的不是模板原件，而是「展示副本」：锁定内容控件里显示的是示例业务值。
 * 保存回调里把控件还原成占位符（{@link SdtTokenRestoreUtil}），再与基线 tag 集合比对，
 * 不一致就拒绝保存——这样即使编辑器没有真正锁住控件，业务值也不会被写死进模板。
 */
@Slf4j
@Service
public class ContractTemplateService {

    /**
     * 模板文件的 OSS 前缀
     */
    private static final String TEMPLATE_ROOT = "contract-templates";

    private static final String SESSION_BY_TEMPLATE = "shmj:onlyoffice:session:tpl:";
    private static final String SESSION_BY_KEY = "shmj:onlyoffice:session:key:";
    private static final String SAVE_SEQ = "shmj:onlyoffice:saveseq:";

    @Autowired
    private PlatformContractTemplateMapper templateMapper;

    @Autowired
    private PlatformContractTemplateVersionMapper versionMapper;

    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private OnlyOfficeClient onlyOfficeClient;

    @Autowired
    private OnlyOfficeProperties onlyOfficeProperties;

    @Autowired
    private SigningAnchorService signingAnchorService;

    @Autowired
    private TemplateFieldRegistry fieldRegistry;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ==================================================================
    // 列表 / 详情
    // ==================================================================

    public List<ContractTemplateRes> listTemplates(String keyword, Integer status, Integer ownerScope,
                                                   String enterpriseId) {
        List<PlatformContractTemplate> templates =
                templateMapper.selectTemplates(keyword, status, ownerScope, enterpriseId);
        return templates.stream().map(this::toRes).collect(Collectors.toList());
    }

    public ContractTemplateRes getTemplate(String templateId) {
        return toRes(requireTemplate(templateId));
    }

    public List<ContractTemplateVersionRes> listVersions(String templateId) {
        requireTemplate(templateId);
        return versionMapper.selectByTemplateId(templateId).stream()
                .map(this::toVersionRes)
                .collect(Collectors.toList());
    }

    public ContractTemplateVersionRes getVersion(String templateId, int versionNo) {
        return toVersionRes(requireVersion(templateId, versionNo));
    }

    /**
     * 商品上架下拉框用。
     */
    public List<ContractTemplateOptionRes> listSelectable(String enterpriseId) {
        return templateMapper.selectSelectableTemplates(enterpriseId).stream().map(t -> {
            ContractTemplateOptionRes res = new ContractTemplateOptionRes();
            res.setId(t.getId());
            res.setTemplateName(t.getTemplateName());
            res.setPublishedVersionNo(t.getPublishedVersionNo());
            res.setIsDefault(t.getIsDefault());
            res.setOwnerScope(t.getOwnerScope());
            return res;
        }).collect(Collectors.toList());
    }

    /**
     * 字段字典，给编辑器右侧「插入字段」面板。
     */
    public List<TemplateFieldRegistry.FieldDescriptor> listFields() {
        return fieldRegistry.describe();
    }

    // ==================================================================
    // 新建 / 上传
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public String createTemplate(ContractTemplateCreateReq req, String operatorId, String operatorName) {
        String code = StringUtils.isBlank(req.getTemplateCode())
                ? "TPL_" + IDGenerator.generateID()
                : req.getTemplateCode().trim();
        if (templateMapper.selectByTemplateCode(code) != null) {
            throw new DisallowedOperationException("模板编码已存在: " + code);
        }
        if (Integer.valueOf(2).equals(req.getOwnerScope()) && StringUtils.isBlank(req.getOwnerEnterpriseId())) {
            throw new DisallowedOperationException("企业私有模板必须指定归属企业");
        }

        Date now = new Date();
        PlatformContractTemplate template = PlatformContractTemplate.builder()
                .id(IDGenerator.generateID())
                .templateCode(code)
                .templateName(req.getTemplateName().trim())
                .ownerScope(req.getOwnerScope() == null ? 1 : req.getOwnerScope())
                .ownerEnterpriseId(req.getOwnerEnterpriseId())
                .status(ContractTemplateStatus.DRAFT.getCode())
                // 还没有任何版本；上传 docx 后才会出现 v1
                .currentVersionNo(0)
                .signAnchorMode(1)
                .isDefault(0)
                .remark(req.getRemark())
                .createUserId(operatorId)
                .createUserName(operatorName)
                .createTime(now)
                .updateTime(now)
                .build();
        templateMapper.insert(template);
        log.info("创建合同模板 templateId={}, code={}, operator={}", template.getId(), code, operatorId);
        return template.getId();
    }

    /**
     * 上传 DOCX 作为新版本。
     *
     * <p>上传时立刻做归一化：把 {@code {<$.x>}} 包进锁定内容控件并记录 tag。
     * 归一化不通过（有残缺占位符、控件没有 tag）直接拒绝，不留到编辑阶段才发现。
     */
    @Transactional(rollbackFor = Exception.class)
    public ContractTemplateVersionRes uploadTemplate(String templateId,
                                                     byte[] raw,
                                                     String originalFilename,
                                                     String operatorId,
                                                     String operatorName) throws IOException {
        PlatformContractTemplate template = requireTemplate(templateId);
        assertDraftAllowed(template);

        if (raw == null || raw.length == 0) {
            throw new DisallowedOperationException("上传文件为空");
        }
        if (originalFilename != null && !originalFilename.toLowerCase().endsWith(".docx")) {
            throw new DisallowedOperationException("只支持 .docx 模板（不支持 .doc / .docm）");
        }

        SdtTemplateUtil.NormalizeResult normalized =
                SdtTemplateUtil.normalize(new ByteArrayInputStream(raw));
        assertNormalized(normalized, "上传模板");

        int nextVersionNo = versionMapper.selectMaxVersionNo(templateId) + 1;
        byte[] canonical = normalized.getDocx();
        String fileKey = canonicalKey(templateId, nextVersionNo);
        fileStorage.putFile(fileKey, canonical, true, FileStorage.DEFAULT_HEADERS);

        PlatformContractTemplateVersion version = insertVersion(
                template, nextVersionNo, fileKey, canonical, normalized.getTags(),
                signingAnchorService.toJson(signingAnchorService.defaultConfig(), null),
                "上传 " + (originalFilename == null ? "模板" : originalFilename),
                operatorId, operatorName);

        log.info("上传合同模板版本 templateId={}, versionNo={}, tags={}",
                templateId, nextVersionNo, normalized.getTags().size());
        return toVersionRes(version);
    }

    // ==================================================================
    // 在线编辑
    // ==================================================================

    /**
     * 打开编辑器：把当前草稿做一份「展示副本」并登记会话。
     */
    public EditorConfigRes openEditSession(String templateId,
                                                                               String userId,
                                                                               String userName) throws IOException {
        if (!onlyOfficeProperties.isEnabled()) {
            throw new DisallowedOperationException("ONLYOFFICE 编辑未启用，请管理员配置 shmj.onlyoffice.*");
        }
        PlatformContractTemplate template = requireTemplate(templateId);
        PlatformContractTemplateVersion draft = ensureDraft(template, userId, userName);

        byte[] canonical = readFile(draft.getFileKey());
        byte[] display = SdtTemplateUtil.fillSdt(new ByteArrayInputStream(canonical), fieldRegistry.sampleData());
        String displayHash = sha256(display);

        String displayKey = String.format("%s/%s/v%d/display-%s.docx",
                TEMPLATE_ROOT, templateId, draft.getVersionNo(), displayHash.substring(0, 12));
        fileStorage.putFile(displayKey, display, false, FileStorage.DEFAULT_HEADERS);

        String documentKey = sha256((templateId + ":" + draft.getVersionNo() + ":" + displayHash)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 32);

        EditSession session = new EditSession();
        session.setTemplateId(templateId);
        session.setVersionNo(draft.getVersionNo());
        session.setDocumentKey(documentKey);
        session.setOperatorId(userId);
        session.setOperatorName(userName);
        session.setBaselineTags(parseTags(draft.getPlaceholderSnapshot()));
        session.setStagingKey(null);
        session.setSavedAt(0L);
        storeSession(session);

        String token = onlyOfficeClient.sign(Map.of("k", documentKey, "t", templateId));
        String documentUrl = onlyOfficeProperties.normalizedBackendUrl()
                + "/contract/onlyoffice/document/" + documentKey + "?token=" + token;

        Map<String, Object> editor = onlyOfficeClient.buildEditorConfig(
                templateId, draft.getVersionNo(), documentKey, documentUrl,
                template.getTemplateName() + " v" + draft.getVersionNo(), userId, userName);

        EditorConfigRes res = new EditorConfigRes();
        res.setDocumentServerUrl(String.valueOf(editor.get("documentServerUrl")));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) editor.get("config");
        res.setConfig(config);
        res.setVersionNo(draft.getVersionNo());
        res.setSessionExpireAt(new Date(System.currentTimeMillis()
                + Duration.ofMinutes(onlyOfficeProperties.getSessionTtlMinutes()).toMillis()));
        return res;
    }

    /**
     * Document Server 拉取「展示副本」。仅接受带签名 token 且与 session 匹配的请求。
     */
    public byte[] loadDocumentForEditor(String documentKey, String token) throws IOException {
        if (!onlyOfficeClient.verify(token)) {
            throw new DisallowedOperationException("非法的文档访问凭证");
        }
        EditSession session = loadSessionByKey(documentKey);
        if (session == null) {
            throw new DisallowedOperationException("编辑会话已过期，请重新打开编辑器");
        }
        PlatformContractTemplateVersion version =
                requireVersion(session.getTemplateId(), session.getVersionNo());
        byte[] canonical = readFile(version.getFileKey());
        return SdtTemplateUtil.fillSdt(new ByteArrayInputStream(canonical), fieldRegistry.sampleData());
    }

    /**
     * 处理 Document Server 的保存回调。
     *
     * <p>这是模板安全的关键一环：回调里的文件是「展示副本」，必须还原成占位符并与基线比对，
     * 任何占位符缺失/改名都拒绝保存（返回 error=1，编辑器会提示保存失败）。
     *
     * @return 给 Document Server 的响应，必须含 {@code error: 0}
     */
    public Map<String, Object> handleSaveCallback(String templateId,
                                                  String authHeader,
                                                  Map<String, Object> body) {
        if (!onlyOfficeClient.verify(extractToken(authHeader, body))) {
            log.warn("拒绝未签名的 onlyoffice 回调, templateId={}", templateId);
            return Map.of("error", 1);
        }
        Object keyValue = body.get("key");
        String documentKey = keyValue == null ? null : String.valueOf(keyValue);
        EditSession session = documentKey == null ? null : loadSessionByKey(documentKey);
        if (session == null) {
            log.warn("回调找不到编辑会话, templateId={}, key={}", templateId, documentKey);
            return Map.of("error", 1);
        }

        int status = body.get("status") instanceof Number n ? n.intValue() : -1;
        // 2 = 所有用户关闭后保存；6 = forcesave 结果
        if (status != 2 && status != 6) {
            return Map.of("error", 0);
        }
        Object urlValue = body.get("url");
        if (!(urlValue instanceof String url) || url.isBlank()) {
            log.warn("保存回调缺少文件地址, key={}", documentKey);
            return Map.of("error", 1);
        }

        try {
            byte[] edited = onlyOfficeClient.downloadCallbackFile(url);
            SdtTokenRestoreUtil.RestoreResult restored = SdtTokenRestoreUtil.restore(edited);
            if (!restored.isValid()) {
                log.error("保存被拒绝：存在无 tag 的控件 {}, key={}", restored.getUntagged(), documentKey);
                return Map.of("error", 1);
            }

            Set<String> baseline = new LinkedHashSet<>(session.getBaselineTags());
            Set<String> current = new TreeSet<>(restored.getTags());
            if (!new TreeSet<>(baseline).equals(current)) {
                log.error("保存被拒绝：占位符集合变化, baseline={}, current={}", baseline, current);
                return Map.of("error", 1);
            }

            // 归一化兜底：用户可能在控件外敲了新的 {<...>}
            SdtTemplateUtil.NormalizeResult normalized =
                    SdtTemplateUtil.normalize(new ByteArrayInputStream(restored.getDocx()));
            if (!normalized.isValid()) {
                log.error("保存被拒绝：归一化不通过 leftover={}, untagged={}",
                        normalized.getLeftover(), normalized.getUntagged());
                return Map.of("error", 1);
            }

            String stagingKey = String.format("%s/%s/staging/%s.docx", TEMPLATE_ROOT, templateId, documentKey);
            fileStorage.putFile(stagingKey, normalized.getDocx(), false, FileStorage.DEFAULT_HEADERS);

            session.setStagingKey(stagingKey);
            session.setSavedAt(System.currentTimeMillis());
            storeSession(session);
            saveSeq(documentKey).incrementAndGet();

            log.info("模板编辑已落盘 key={}, restored={}", documentKey, restored.getRestored());
            return Map.of("error", 0);
        } catch (Exception e) {
            log.error("处理保存回调失败 key={}", documentKey, e);
            return Map.of("error", 1);
        }
    }

    /**
     * 把编辑器当前内容固化为一个新的不可变版本。
     *
     * <p>之所以要先 forcesave：ONLYOFFICE 的空闲自动保存只更新它自己的缓存，
     * 后端看到的模板文件是由 status=6 回调刷新的。不让它强制保存，就可能把旧内容存成新版本。
     */
    public ContractTemplateVersionRes saveVersion(String templateId,
                                                  ContractTemplateSaveVersionReq req,
                                                  String operatorId,
                                                  String operatorName) throws IOException {
        PlatformContractTemplate template = requireTemplate(templateId);
        EditSession session = loadSessionByTemplate(templateId);
        if (session == null) {
            throw new DisallowedOperationException("编辑会话已过期，请重新打开编辑器再保存");
        }
        if (!StringUtils.equals(session.getOperatorId(), operatorId)) {
            throw new DisallowedOperationException("编辑会话属于其他用户，不能以其名义保存");
        }

        RAtomicLong seq = saveSeq(session.getDocumentKey());
        long before = seq.get();
        boolean flushed = onlyOfficeClient.forceSave(session.getDocumentKey());
        boolean received = false;
        if (flushed) {
            long deadline = System.currentTimeMillis() + onlyOfficeProperties.getForceSaveTimeoutMillis();
            while (System.currentTimeMillis() < deadline) {
                if (seq.get() > before) {
                    received = true;
                    break;
                }
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 重新读会话，回调可能刚刚更新了 stagingKey
        session = loadSessionByTemplate(templateId);
        if (session == null || StringUtils.isBlank(session.getStagingKey())) {
            throw new DisallowedOperationException("编辑器内容尚未保存到服务端，请稍后重试");
        }

        byte[] content = readFile(session.getStagingKey());
        String sha = sha256(content);

        PlatformContractTemplateVersion existingDraft = versionMapper.selectDraft(templateId);
        if (existingDraft != null && StringUtils.equals(existingDraft.getFileSha256(), sha)) {
            log.info("模板内容无变化，跳过创建新版本 templateId={}, version={}",
                    templateId, existingDraft.getVersionNo());
            ContractTemplateVersionRes same = toVersionRes(existingDraft);
            same.setChangeSummary((received ? "" : "（未收到强制保存回执）") + "内容无变化，沿用 v"
                    + existingDraft.getVersionNo());
            return same;
        }

        SdtTemplateUtil.NormalizeResult normalized =
                SdtTemplateUtil.normalize(new ByteArrayInputStream(content));
        assertNormalized(normalized, "保存版本");

        int nextVersionNo = versionMapper.selectMaxVersionNo(templateId) + 1;
        String fileKey = canonicalKey(templateId, nextVersionNo);
        // 版本文件先落 OSS（不可变路径，overwrite=true 表示禁止覆盖）
        fileStorage.putFile(fileKey, normalized.getDocx(), true, FileStorage.DEFAULT_HEADERS);

        String anchors = existingDraft == null
                ? signingAnchorService.toJson(signingAnchorService.defaultConfig(), null)
                : existingDraft.getSigningAnchorJson();

        // 只有落库这一段需要事务：上面的 forcesave 轮询最长 8 秒，
        // 若把整个方法放进 @Transactional，会长时间占住数据库连接（池只有 30）。
        String finalAnchors = anchors;
        PlatformContractTemplateVersion version = transactionTemplate.execute(status -> {
            try {
                return insertVersion(template, nextVersionNo, fileKey, normalized.getDocx(),
                        normalized.getTags(), finalAnchors,
                        StringUtils.defaultIfBlank(req.getChangeSummary(), "编辑器保存"),
                        operatorId, operatorName);
            } catch (RuntimeException e) {
                status.setRollbackOnly();
                throw e;
            }
        });
        if (version == null) {
            throw new IllegalStateException("保存模板版本失败: " + templateId + " v" + nextVersionNo);
        }

        if (!received) {
            log.warn("保存版本时未收到强制保存回执 templateId={}, version={}", templateId, nextVersionNo);
        }
        log.info("保存合同模板版本 templateId={}, version={}, tags={}",
                templateId, nextVersionNo, normalized.getTags().size());
        return toVersionRes(version);
    }

    // ==================================================================
    // 发布 / 停用
    // ==================================================================

    /**
     * 发布版本：占位符校验 + 签署锚点校验，通过后才可被商品选择。
     */
    public ContractTemplateRes publish(String templateId,
                                       ContractTemplatePublishReq req,
                                       String operatorId) throws Exception {
        PlatformContractTemplate template = requireTemplate(templateId);

        PlatformContractTemplateVersion version = req.getVersionNo() == null
                ? versionMapper.selectDraft(templateId)
                : versionMapper.selectByTemplateIdAndVersion(templateId, req.getVersionNo());
        if (version == null) {
            throw new DisallowedOperationException("没有可发布的模板版本，请先上传或保存一次");
        }

        byte[] canonical = readFile(version.getFileKey());

        // 闸门 1：占位符必须都是已知字段
        SdtTemplateUtil.NormalizeResult normalized =
                SdtTemplateUtil.normalize(new ByteArrayInputStream(canonical));
        assertNormalized(normalized, "发布校验");
        Set<String> unknown = fieldRegistry.unknownTags(normalized.getTags());
        if (!unknown.isEmpty()) {
            throw new DisallowedOperationException(
                    "模板引用了未知字段，渲染时会留空：" + String.join(", ", unknown)
                            + "。请从「插入字段」面板重新插入。");
        }
        if (!new TreeSet<>(normalized.getTags()).equals(new TreeSet<>(parseTags(version.getPlaceholderSnapshot())))) {
            throw new DisallowedOperationException("模板文件与版本快照不一致，请重新保存一次再进行发布");
        }

        // 闸门 2：签署锚点必须唯一命中
        List<SigningAnchorConfig> configs = req.getAnchors() == null || req.getAnchors().isEmpty()
                ? signingAnchorService.parseConfig(version.getSigningAnchorJson()).getConfigs()
                : req.getAnchors();
        byte[] renderedSample = renderSample(canonical);
        List<SigningAnchorResolution> resolutions = signingAnchorService.resolve(renderedSample, configs);

        String anchorJson = signingAnchorService.toJson(configs, resolutions);

        // 校验阶段会调用法大大（上传 + processFile + 反查坐标），这些 HTTP 调用
        // 必须放在事务外面，否则一次发布就把数据库连接占住几秒。
        final boolean asDefault = Boolean.TRUE.equals(req.getAsDefault());
        transactionTemplate.execute(status -> {
            versionMapper.updateAnchors(version.getId(), anchorJson);
            versionMapper.supersedeOtherPublished(templateId, version.getVersionNo());
            versionMapper.updateStatus(version.getId(), ContractTemplateVersionStatus.PUBLISHED.getCode());

            templateMapper.updatePublished(templateId, version.getVersionNo(),
                    ContractTemplateStatus.PUBLISHED.getCode(), 1, new Date());

            if (asDefault) {
                templateMapper.clearDefaultFlag();
                templateMapper.updateByPrimaryKeySelective(PlatformContractTemplate.builder()
                        .id(templateId)
                        .isDefault(1)
                        .updateTime(new Date())
                        .build());
            }
            return null;
        });

        log.info("发布合同模板 templateId={}, version={}, anchors={}, operator={}",
                templateId, version.getVersionNo(), resolutions.size(), operatorId);
        return getTemplate(templateId);
    }

    /**
     * 停用模板：已选它的商品不受影响，只是不再允许新的选择。
     */
    @Transactional(rollbackFor = Exception.class)
    public void offline(String templateId) {
        requireTemplate(templateId);
        templateMapper.updateStatus(templateId, ContractTemplateStatus.OFFLINE.getCode(), new Date());
        log.info("停用合同模板 templateId={}", templateId);
    }

    // ==================================================================
    // 供下单/渲染链路使用
    // ==================================================================

    /**
     * 解析订单最终要用的模板版本。
     *
     * <p>优先级：订单快照 → 平台默认已发布模板 → 返回 null（由调用方回退到历史配置路径）。
     * 这条回退链保证上线过程中历史订单不会因为「没有模板」而生成失败。
     */
    public ResolvedTemplate resolveForOrder(@Nullable String templateId,
                                            @Nullable Integer versionNo) {
        if (StringUtils.isNotBlank(templateId) && versionNo != null) {
            PlatformContractTemplateVersion version = versionMapper.selectByTemplateIdAndVersion(templateId, versionNo);
            if (version != null) {
                return new ResolvedTemplate(templateId, versionNo, version.getFileKey(),
                        signingAnchorService.parseConfig(version.getSigningAnchorJson()));
            }
            log.warn("订单引用的模板版本不存在，回退默认模板 templateId={}, version={}", templateId, versionNo);
        }
        PlatformContractTemplate defaultTemplate = templateMapper.selectDefaultPublished();
        if (defaultTemplate == null) {
            return null;
        }
        PlatformContractTemplateVersion published =
                versionMapper.selectPublished(defaultTemplate.getId());
        if (published == null) {
            return null;
        }
        return new ResolvedTemplate(defaultTemplate.getId(), published.getVersionNo(), published.getFileKey(),
                signingAnchorService.parseConfig(published.getSigningAnchorJson()));
    }

    /**
     * 商品上架/编辑时校验模板可用，并返回要冻结的版本号。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code templateId} 为空 → 用平台默认模板；默认模板也没有则直接报错（配置问题要早暴露）；</li>
     *   <li>模板必须已发布（{@code published_version_no} 非空），草稿不可选；</li>
     *   <li>模板必须对当前企业可见（平台公共，或该企业私有）；</li>
     *   <li>模板不能是已停用状态。</li>
     * </ul>
     *
     * @param enterpriseId 当前企业
     * @param templateId   企业选的模板；可空
     * @return 要写进商品版本快照的模板版本号
     */
    public Integer resolveSelectableVersionNo(String enterpriseId, String templateId) {
        PlatformContractTemplate template;
        if (StringUtils.isBlank(templateId)) {
            template = templateMapper.selectDefaultPublished();
            if (template == null) {
                throw new DisallowedOperationException("未配置平台默认合同模板，无法上架商品");
            }
        } else {
            template = requireTemplate(templateId);
        }

        if (Integer.valueOf(ContractTemplateStatus.OFFLINE.getCode()).equals(template.getStatus())) {
            throw new DisallowedOperationException("合同模板已停用，不能选择: " + template.getTemplateName());
        }
        if (template.getPublishedVersionNo() == null) {
            throw new DisallowedOperationException("合同模板尚未发布，不能选择: " + template.getTemplateName());
        }
        boolean visible = Integer.valueOf(1).equals(template.getOwnerScope())
                || StringUtils.equals(template.getOwnerEnterpriseId(), enterpriseId);
        if (!visible) {
            throw new DisallowedOperationException("无权使用该合同模板: " + template.getTemplateName());
        }
        return template.getPublishedVersionNo();
    }

    /**
     * 解析结果：文件 key + 锚点配置。
     */
    @Getter
    public static class ResolvedTemplate {

        private final String templateId;

        private final int versionNo;

        private final String fileKey;

        private final SigningAnchorBundle anchors;

        public ResolvedTemplate(String templateId, int versionNo, String fileKey, SigningAnchorBundle anchors) {
            this.templateId = templateId;
            this.versionNo = versionNo;
            this.fileKey = fileKey;
            this.anchors = anchors;
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private PlatformContractTemplate requireTemplate(String templateId) {
        PlatformContractTemplate template = templateMapper.selectByPrimaryKey(templateId);
        if (template == null) {
            throw new DisallowedOperationException("合同模板不存在: " + templateId);
        }
        return template;
    }

    private PlatformContractTemplateVersion requireVersion(String templateId, int versionNo) {
        PlatformContractTemplateVersion version =
                versionMapper.selectByTemplateIdAndVersion(templateId, versionNo);
        if (version == null) {
            throw new DisallowedOperationException("模板版本不存在: " + templateId + " v" + versionNo);
        }
        return version;
    }

    private void assertDraftAllowed(PlatformContractTemplate template) {
        if (Integer.valueOf(ContractTemplateStatus.OFFLINE.getCode()).equals(template.getStatus())) {
            throw new DisallowedOperationException("模板已停用，如需修改请先重新启用");
        }
    }

    private void assertNormalized(SdtTemplateUtil.NormalizeResult normalized, String scene) {
        if (!normalized.isValid()) {
            throw new DisallowedOperationException(scene + "失败："
                    + (normalized.getLeftover().isEmpty() ? "" : "存在无法解析的占位符 " + normalized.getLeftover() + "；")
                    + (normalized.getUntagged().isEmpty() ? "" : "存在没有 tag 的内容控件 " + normalized.getUntagged() + "；")
                    + "请检查模板中的 {<...>} 拼写。");
        }
    }

    /**
     * 没有草稿时，基于已发布版本复制出一个新草稿（编辑已发布模板的标准路径）。
     */
    private PlatformContractTemplateVersion ensureDraft(PlatformContractTemplate template,
                                                        String operatorId,
                                                        String operatorName) {
        PlatformContractTemplateVersion draft = versionMapper.selectDraft(template.getId());
        if (draft != null) {
            return draft;
        }
        PlatformContractTemplateVersion published = versionMapper.selectPublished(template.getId());
        if (published == null) {
            throw new DisallowedOperationException("模板还没有任何版本，请先上传 DOCX");
        }
        int nextVersionNo = versionMapper.selectMaxVersionNo(template.getId()) + 1;
        byte[] content = readFile(published.getFileKey());
        String fileKey = canonicalKey(template.getId(), nextVersionNo);
        fileStorage.putFile(fileKey, content, true, FileStorage.DEFAULT_HEADERS);

        PlatformContractTemplateVersion version = insertVersion(
                template, nextVersionNo, fileKey, content,
                parseTags(published.getPlaceholderSnapshot()),
                published.getSigningAnchorJson(),
                "基于 v" + published.getVersionNo() + " 开始编辑",
                operatorId, operatorName);
        log.info("基于已发布版本创建草稿 templateId={}, from=v{}, to=v{}",
                template.getId(), published.getVersionNo(), nextVersionNo);
        return version;
    }

    private PlatformContractTemplateVersion insertVersion(PlatformContractTemplate template,
                                                          int versionNo,
                                                          String fileKey,
                                                          byte[] content,
                                                          Set<String> tags,
                                                          String anchorsJson,
                                                          String changeSummary,
                                                          String operatorId,
                                                          String operatorName) {
        PlatformContractTemplateVersion version = PlatformContractTemplateVersion.builder()
                .id(IDGenerator.generateID())
                .templateId(template.getId())
                .versionNo(versionNo)
                .fileKey(fileKey)
                .fileSha256(sha256(content))
                .placeholderSnapshot(toTagsJson(tags))
                .signingAnchorJson(anchorsJson)
                .changeSummary(changeSummary)
                .status(ContractTemplateVersionStatus.DRAFT.getCode())
                .createUserId(operatorId)
                .createUserName(operatorName)
                .createTime(new Date())
                .build();
        versionMapper.insert(version);

        templateMapper.updateEditVersion(template.getId(), versionNo, new Date());
        // 一个模板同时只保留一个草稿
        versionMapper.selectByTemplateId(template.getId()).stream()
                .filter(v -> v.getStatus() != null
                        && v.getStatus().equals(ContractTemplateVersionStatus.DRAFT.getCode())
                        && !v.getId().equals(version.getId()))
                .forEach(v -> versionMapper.updateStatus(v.getId(),
                        ContractTemplateVersionStatus.SUPERSEDED.getCode()));
        return version;
    }

    /**
     * 用示例数据把模板渲染成真实文档，用于锚点反查与发布前自检。
     */
    private byte[] renderSample(byte[] canonical) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WordTemplateUtil.fillTemplateByPoiTl(new ByteArrayInputStream(canonical), out,
                fieldRegistry.sampleData());
        return out.toByteArray();
    }

    private byte[] readFile(String key) {
        try (InputStream in = fileStorage.getFile(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取模板文件失败: " + key, e);
        }
    }

    private String canonicalKey(String templateId, int versionNo) {
        return String.format("%s/%s/v%d/template.docx", TEMPLATE_ROOT, templateId, versionNo);
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算文件指纹失败", e);
        }
    }

    private String toTagsJson(Set<String> tags) {
        try {
            return JsonUtils.toJsonString(new ArrayList<>(tags));
        } catch (Exception e) {
            throw new IllegalStateException("占位符快照序列化失败", e);
        }
    }

    private List<String> parseTags(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> tags = JsonUtils.fromJsonString(json, new TypeReference<List<String>>() {
            });
            return tags == null ? List.of() : tags;
        } catch (Exception e) {
            log.warn("占位符快照解析失败: {}", json, e);
            return List.of();
        }
    }

    private String extractToken(String authHeader, Map<String, Object> body) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        Object token = body == null ? null : body.get("token");
        return token == null ? null : String.valueOf(token);
    }

    // ------------------------- 会话存储 -------------------------

    private void storeSession(EditSession session) {
        String json;
        try {
            json = JsonUtils.toJsonString(session);
        } catch (Exception e) {
            throw new IllegalStateException("编辑会话序列化失败", e);
        }
        Duration ttl = Duration.ofMinutes(onlyOfficeProperties.getSessionTtlMinutes());
        bucket(SESSION_BY_TEMPLATE + session.getTemplateId()).set(json, ttl);
        bucket(SESSION_BY_KEY + session.getDocumentKey()).set(json, ttl);
    }

    private EditSession loadSessionByTemplate(String templateId) {
        return readSession(bucket(SESSION_BY_TEMPLATE + templateId).get());
    }

    private EditSession loadSessionByKey(String documentKey) {
        return readSession(bucket(SESSION_BY_KEY + documentKey).get());
    }

    private EditSession readSession(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JsonUtils.fromJsonString(json, EditSession.class);
        } catch (Exception e) {
            log.warn("编辑会话解析失败", e);
            return null;
        }
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }

    private RAtomicLong saveSeq(String documentKey) {
        return redissonClient.getAtomicLong(SAVE_SEQ + documentKey);
    }

    /**
     * 一次编辑会话的登记信息。
     */
    @Getter
    @Setter
    public static class EditSession {

        private String templateId;

        private Integer versionNo;

        private String documentKey;

        private String operatorId;

        private String operatorName;

        /**
         * 打开编辑器时的占位符集合，保存回调必须与之一致
         */
        private List<String> baselineTags;

        /**
         * 最近一次成功保存的暂存文件 key（还原后的占位符版）
         */
        private String stagingKey;

        private Long savedAt;
    }

    // ------------------------- 转换 -------------------------

    private ContractTemplateRes toRes(PlatformContractTemplate template) {
        ContractTemplateRes res = new ContractTemplateRes();
        res.setId(template.getId());
        res.setTemplateCode(template.getTemplateCode());
        res.setTemplateName(template.getTemplateName());
        res.setOwnerScope(template.getOwnerScope());
        res.setOwnerEnterpriseId(template.getOwnerEnterpriseId());
        res.setStatus(template.getStatus());
        res.setStatusDesc(describeTemplateStatus(template.getStatus()));
        res.setCurrentVersionNo(template.getCurrentVersionNo());
        res.setPublishedVersionNo(template.getPublishedVersionNo());
        res.setSignAnchorMode(template.getSignAnchorMode());
        res.setIsDefault(template.getIsDefault());
        res.setRemark(template.getRemark());
        res.setCreateTime(template.getCreateTime());
        res.setUpdateTime(template.getUpdateTime());

        PlatformContractTemplateVersion published = versionMapper.selectPublished(template.getId());
        if (published != null) {
            res.setPlaceholderCount(parseTags(published.getPlaceholderSnapshot()).size());
            res.setSigningAnchors(signingAnchorService.parse(published.getSigningAnchorJson()));
            res.setProductUsageCount(versionMapper.countProductUsage(template.getId(), published.getVersionNo()));
        }
        return res;
    }

    private ContractTemplateVersionRes toVersionRes(PlatformContractTemplateVersion version) {
        ContractTemplateVersionRes res = new ContractTemplateVersionRes();
        res.setId(version.getId());
        res.setTemplateId(version.getTemplateId());
        res.setVersionNo(version.getVersionNo());
        res.setFileKey(version.getFileKey());
        res.setFileUrl(fileStorage.generatePresignedUrl(version.getFileKey()));
        res.setFileSha256(version.getFileSha256());
        res.setPlaceholderSnapshot(version.getPlaceholderSnapshot());
        res.setPlaceholderCount(parseTags(version.getPlaceholderSnapshot()).size());
        res.setSigningAnchorJson(version.getSigningAnchorJson());
        res.setChangeSummary(version.getChangeSummary());
        res.setStatus(version.getStatus());
        res.setStatusDesc(describeVersionStatus(version.getStatus()));
        res.setCreateUserId(version.getCreateUserId());
        res.setCreateUserName(version.getCreateUserName());
        res.setCreateTime(version.getCreateTime());
        return res;
    }

    private String describeTemplateStatus(Integer code) {
        for (ContractTemplateStatus status : ContractTemplateStatus.values()) {
            if (Integer.valueOf(status.getCode()).equals(code)) {
                return status.getDesc();
            }
        }
        return null;
    }

    private String describeVersionStatus(Integer code) {
        for (ContractTemplateVersionStatus status : ContractTemplateVersionStatus.values()) {
            if (Integer.valueOf(status.getCode()).equals(code)) {
                return status.getDesc();
            }
        }
        return null;
    }

    /**
     * 便于断言锚点枚举与配置一致：返回所有必填锚点编码。
     */
    public Set<String> requiredAnchorCodes() {
        return java.util.Arrays.stream(SigningAnchor.values())
                .map(SigningAnchor::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
