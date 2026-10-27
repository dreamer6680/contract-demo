# 现有文件改动清单（可直接照抄）

> 目标仓库：`/Users/dream/work/shmj-backend`
> 新增文件见 `integration/src/**`，本文件只列 **对现有文件的修改**。
> 每个条目都给出「定位符号 → 改动前 → 改动后」，避免行号漂移。

---

## 0. 改动总览

| # | 文件 | 改动性质 | 必须 |
|---|---|---|---|
| A | `external/impl/FascOpenApi.java` | 新增方法 + record | ✅ 发布校验依赖 |
| B | `external/impl/FascOpenDefaultApi.java` | 实现 A | ✅ |
| C | `service/template/TemplateComponent.java` | 新增重载 | ✅ |
| D | `task/ContractTask.java` | 改造渲染 + 签章字段 | ✅ |
| E | `beans/entity/PlatformContract.java` + `mapper/mysql/PlatformContractMapper.xml` | 加 4 列 | ✅ |
| F | `beans/entity/PlatformOrder.java` + `PlatformOrderMapper.xml` | 加 2 列 | ✅ |
| G | `beans/entity/PlatformProductVersion.java` + `PlatformProductVersionMapper.xml` | 加 3 列 | ✅ |
| H | `beans/entity/PlatformFixedAnnounce.java` + `PlatformFixedAnnounceMapper.xml` | 加 2 列 | ✅ |
| I | `service/ProductVersionService.java` | 复制模板字段进版本 | ✅ |
| J | `beans/dto/request/AuctionAddedProduct.java` | 加 1 字段 | ✅ |
| K | `service/SaleRoomService.java` | 保存时校验+落库模板 | ✅ |
| L | `beans/dto/insert/PlatformProductEditDto.java` + `SaleRoomService#isLowRiskEdit` | 编辑加字段 + 高风险化 | ✅ |
| M | `service/OrderService.java` | 下单/建合同快照 | ✅ |
| N | `beans/dto/response/ContractDetailRes.java` + mapper XML | 返回模板版本 | 建议 |
| O | `application-{local,pre,pro}.yaml` | 新增 `shmj.onlyoffice.*` | ✅ |
| P | `changes/releases/x.y.z.md` | 发布说明 + DDL | ✅ 流程要求 |
| Q | `test/resources/db/migration/mysql/V15__contract_template.sql` | 复制 `integration/ddl/V15` | ✅ |

> 说明：`SdtTemplateUtil` / `SdtTemplateUtilTest` / `WordTemplateUtil` 当前是**工作区未提交的改动**，
> 本方案直接依赖它们，不需要再改；新代码只额外引入 `SdtTokenRestoreUtil`（新增文件）。

---

## A. `external/impl/FascOpenApi.java`

在接口末尾（`queryMemberIdByUserId` 之后）追加：

```java
    /**
     * 反查关键字在文档中的位置（法大大 /file/get-keyword-positions）。
     *
     * <p>用于发布模板时校验签署锚点：每个锚点关键字必须<b>唯一命中</b>，
     * 否则法大大无法判断章该盖在哪一处。
     *
     * @param fileId processFile 返回的文件 id
     * @param keywords 关键字列表
     * @return 每个命中位置；同一关键字命中多次会出现多条
     */
    List<KeywordPosition> getKeywordPositions(String fileId, List<String> keywords) throws ApiException;

    /**
     * 关键字命中结果。
     *
     * @param keyword 关键字
     * @param pageNo  页码（1 起）
     * @param x       命中位置 x（像素）
     * @param y       命中位置 y（像素）
     */
    record KeywordPosition(String keyword, int pageNo, String x, String y) {
    }
```

> 若项目 Java 版本低于 16 则改成普通静态内部类；本项目是 Java 21，record 可用。

---

## B. `external/impl/FascOpenDefaultApi.java`

新增 import：

```java
import com.fasc.open.api.v5_1.req.doc.GetKeywordPositionReq;
import com.fasc.open.api.v5_1.res.doc.GetKeywordPositionRes;
```

实现（放在 `processFile` 之后，沿用已有 `getAccessToken()` / `extraData()` / `docClient`）：

```java
    @Override
    public List<KeywordPosition> getKeywordPositions(String fileId, List<String> keywords) throws ApiException {
        GetKeywordPositionReq req = new GetKeywordPositionReq();
        req.setAccessToken(getAccessToken());
        req.setFileId(fileId);
        req.setKeywords(keywords);

        BaseRes<List<GetKeywordPositionRes>> response = docClient.getKeywordPosition(req);
        List<GetKeywordPositionRes> data = extraData(response);

        List<KeywordPosition> positions = new ArrayList<>();
        if (data == null) {
            return positions;
        }
        for (GetKeywordPositionRes item : data) {
            if (item.getPositions() == null) {
                continue;
            }
            for (GetKeywordPositionRes.Position p : item.getPositions()) {
                String x = p.getCoordinate() == null ? null : p.getCoordinate().getPositionX();
                String y = p.getCoordinate() == null ? null : p.getCoordinate().getPositionY();
                positions.add(new KeywordPosition(item.getKeyword(), p.getPositionPageNo(), x, y));
            }
        }
        log.info("keyword positions for file {}, keywords {} -> {} hits", fileId, keywords, positions.size());
        return positions;
    }
```

> `extraData(...)` 是项目里已有的泛型解包方法，直接复用；`ArrayList` / `List` 已在该文件中使用。

---

## C. `service/template/TemplateComponent.java`

**改动点 1**：把 `fillContractTemplate(PlatformOrder, PlatformContract)` 改成委托给新重载。

改动前（该方法体最后两行）：

```java
        InputStream template = fileStorage.getFile(shmjFascProperties.getContractTemplatePath());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        validate(param);
        WordTemplateUtil.fillTemplateByPoiTl(template, outputStream, param);
        return outputStream;
    }
```

改动后：

```java
        return fillContractTemplate(order, contract, null);
    }

    /**
     * 用指定模板流渲染合同。
     *
     * <p>模板不再固定读取 {@code fasc.templates.contractTemplatePath}：合同模板已经
     * 支持在线编辑与版本管理，调用方（ContractRenderService）需要按订单快照的版本传流进来。
     * 传 null 时保持历史行为，用作回退。
     */
    public ByteArrayOutputStream fillContractTemplate(PlatformOrder order,
                                                      PlatformContract contract,
                                                      InputStream template) throws ExecutionException, IOException {
        // ……此处保留原有全部字段拼装逻辑（buyer/seller/bank/product/param）……
        InputStream stream = template != null
                ? template
                : fileStorage.getFile(shmjFascProperties.getContractTemplatePath());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        validate(param);
        WordTemplateUtil.fillTemplateByPoiTl(stream, outputStream, param);
        return outputStream;
    }
```

> 实操建议：把原方法体整体挪到三参方法里，只把 `InputStream template = fileStorage.getFile(...)`
> 换成 `InputStream stream = template != null ? template : fileStorage.getFile(...)`，
> 原二参方法保留方法签名并 `return fillContractTemplate(order, contract, null);`。
> 这样 diff 最小，灰度期可随时回退。

---

## D. `task/ContractTask.java`

### D-1 注入新服务

```java
    @Autowired
    private ContractRenderService contractRenderService;
```

（`ContractTemplateService` 只在发布校验用到，这里不需要。）

### D-2 `createContractTask` 里替换渲染

改动前：

```java
        ByteArrayOutputStream outputStream = platformContractTemplateComponent.fillContractTemplate(order, contract);
        String path = CONTRACT_ORIGIN_DOC_FILE.toPath(new String[]{contract.getId()});
```

改动后：

```java
        RenderedContract rendered = contractRenderService.render(order, contract);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.writeBytes(rendered.getDocx());
        String path = CONTRACT_ORIGIN_DOC_FILE.toPath(new String[]{contract.getId()});
```

### D-3 替换写死的签章坐标

改动前：

```java
        List<FascOpenApi.FieldHolder> fields = new ArrayList<>();
        fields.add(createPersonSignField(FascOpenApi.SignContractTaskActorType.PARTY_A, null, 6, 520L, 750L));
        fields.add(createCorpSignField(FascOpenApi.SignContractTaskActorType.PARTY_A, SealType.CONTRACT_SEAL, null,
                6, 520L, 900L));

        fields.add(createCrossPageSealField(FascOpenApi.SignContractTaskActorType.PARTY_A, SealType.CONTRACT_SEAL, 400L));

        FascContractSignTask.SignMember buyer = fascSignService.toSignMember(order.getBuyEnterpriseId(),
                FascOpenApi.SignContractTaskActorType.PARTY_A,
                fields);


        List<FascOpenApi.FieldHolder> fieldsByB = new ArrayList<>();
        fieldsByB.add(createPersonSignField(FascOpenApi.SignContractTaskActorType.PARTY_B, null, 6, 200L, 750L));
        fieldsByB.add(createCorpSignField(FascOpenApi.SignContractTaskActorType.PARTY_B, SealType.CONTRACT_SEAL, null,
                6, 200L, 900L));
        fieldsByB.add(createCrossPageSealField(FascOpenApi.SignContractTaskActorType.PARTY_B, SealType.CONTRACT_SEAL, 800L));

        FascContractSignTask.SignMember seller = fascSignService.toSignMember(order.getSellEnterpriseId(),
                FascOpenApi.SignContractTaskActorType.PARTY_B,
                fieldsByB);
```

改动后：

```java
        // 模板版本决定了签章位置：新版模板用关键字锚点，历史订单沿用像素坐标
        List<FascOpenApi.FieldHolder> buyerFields = rendered.isLegacy()
                ? legacyBuyerFields()
                : rendered.getBuyerFields();
        FascContractSignTask.SignMember buyer = fascSignService.toSignMember(order.getBuyEnterpriseId(),
                FascOpenApi.SignContractTaskActorType.PARTY_A,
                buyerFields);

        List<FascOpenApi.FieldHolder> sellerFields = rendered.isLegacy()
                ? legacySellerFields()
                : rendered.getSellerFields();
        FascContractSignTask.SignMember seller = fascSignService.toSignMember(order.getSellEnterpriseId(),
                FascOpenApi.SignContractTaskActorType.PARTY_B,
                sellerFields);
```

并在类内新增两个私有方法，把旧逻辑原样保留：

```java
    /** 历史模板的签章位置（第 6 页固定坐标）。只在订单没有模板版本时使用。 */
    private List<FascOpenApi.FieldHolder> legacyBuyerFields() {
        List<FascOpenApi.FieldHolder> fields = new ArrayList<>();
        fields.add(createPersonSignField(FascOpenApi.SignContractTaskActorType.PARTY_A, null, 6, 520L, 750L));
        fields.add(createCorpSignField(FascOpenApi.SignContractTaskActorType.PARTY_A,
                SealType.CONTRACT_SEAL, null, 6, 520L, 900L));
        fields.add(createCrossPageSealField(FascOpenApi.SignContractTaskActorType.PARTY_A,
                SealType.CONTRACT_SEAL, 400L));
        return fields;
    }

    /** 同上，乙方。 */
    private List<FascOpenApi.FieldHolder> legacySellerFields() {
        List<FascOpenApi.FieldHolder> fields = new ArrayList<>();
        fields.add(createPersonSignField(FascOpenApi.SignContractTaskActorType.PARTY_B, null, 6, 200L, 750L));
        fields.add(createCorpSignField(FascOpenApi.SignContractTaskActorType.PARTY_B,
                SealType.CONTRACT_SEAL, null, 6, 200L, 900L));
        fields.add(createCrossPageSealField(FascOpenApi.SignContractTaskActorType.PARTY_B,
                SealType.CONTRACT_SEAL, 800L));
        return fields;
    }
```

### D-4 写回模板留痕

在 `PlatformContract toUpdate = PlatformContract.builder()` 那段追加：

```java
                .templateId(rendered.getTemplateId())
                .templateVersionNo(rendered.getTemplateVersionNo())
                .templateName(rendered.isLegacy() ? null : contract.getTemplateName())
                .renderSnapshot(anchorSnapshot(rendered))
```

`anchorSnapshot` 可用 `JsonUtils.toJsonString(rendered.getAnchors())` 实现（锚点 + 定位结果可追溯）。

> 注意：`PLATFORM_CONTRACT` 的 `updateByPrimaryKeySelective` 需要包含这 4 个新列，见 E。

---

## E. `PlatformContract` + `PlatformContractMapper.xml`

实体新增：

```java
    /**
     * 渲染使用的合同模板ID
     */
    @Size(max = 64, message = "模板ID最大长度要小于 64")
    private String templateId;

    /**
     * 渲染使用的模板版本号
     */
    private Integer templateVersionNo;

    /**
     * 模板名称快照
     */
    @Size(max = 255, message = "模板名称最大长度要小于 255")
    private String templateName;

    /**
     * 渲染参数快照
     */
    private String renderSnapshot;
```

`PlatformContractMapper.xml` 三处同步：

1. `<resultMap>` 增加 4 个 `<result column="template_id" property="templateId"/>` 等；
2. `<sql id="Base_Column_List">`（若有）补 4 列；
3. `insert` 的列清单与 values 补 4 项；
4. `updateByPrimaryKeySelective` 的 `<set>` 补 4 个 `<if>`。

---

## F. `PlatformOrder` + `PlatformOrderMapper.xml`

实体新增（放在 `productVersion` 附近，保持语义聚合）：

```java
    /**
     * 下单时的合同模板ID
     */
    @Size(max = 64, message = "合同模板ID最大长度要小于 64")
    private String contractTemplateId;

    /**
     * 下单时的合同模板版本号
     */
    private Integer contractTemplateVersionNo;
```

XML：`resultMap` / `insert` / `updateByPrimaryKeySelective` 同步。

---

## G. `PlatformProductVersion` + Mapper XML

实体新增：

```java
    /**
     * 合同模板ID
     */
    private String contractTemplateId;

    /**
     * 合同模板版本号
     */
    private Integer contractTemplateVersionNo;

    /**
     * 模板快照(名称/fileKey/占位符/锚点)
     */
    private String contractTemplateSnapshot;
```

XML：`insert`（该方法已存在，列清单需补）+ 查询 resultMap 补。

---

## H. `PlatformFixedAnnounce` + Mapper XML

实体新增：

```java
    /**
     * 选择的合同模板ID
     */
    private String contractTemplateId;

    /**
     * 选择的合同模板版本号
     */
    private Integer contractTemplateVersionNo;
```

XML：`resultMap` / `insert` / `updateByPrimaryKeySelective` 同步。

> 为什么模板选择先落在公告表：模板只有在「审核通过」时才被快照进
> `platform_product_version`（与保证金规则、交易费规则完全一致）。
> 审核期间必须记住运营/企业选的是哪一版；`buildVersionFromProduct` 再把它们带进版本快照。

---

## I. `service/ProductVersionService.java#buildVersionFromProduct`

改动前：

```java
        // 公告表字段
        if (announce != null) {
            builder.receiveBankId(announce.getReceiveBankId())
                    .timeToSell(announce.getTimeToSell())
                    ...
        }
```

改动后追加两行：

```java
        if (announce != null) {
            builder.receiveBankId(announce.getReceiveBankId())
                    .timeToSell(announce.getTimeToSell())
                    ...
                    // 合同模板随商品版本冻结
                    .contractTemplateId(announce.getContractTemplateId())
                    .contractTemplateVersionNo(announce.getContractTemplateVersionNo());
        }
```

> 同时建议在 `auditMarket` 的「审核通过」分支里兜底：若 `announce.contractTemplateId` 为空，
> 用 `contractTemplateService` 的平台默认模板补齐；**默认模板也缺失时直接抛错**，
> 不要拖到生成合同时由 `ContractTask` 重试 3 次再飞书告警。

---

## J. `beans/dto/request/AuctionAddedProduct.java`

新增：

```java
    /**
     * 合同模板ID；为空表示使用平台默认模板
     */
    @Size(max = 64, message = "合同模板ID最大长度要小于 64")
    private String contractTemplateId;
```

---

## K. `service/SaleRoomService.java#saveMarketProduct`

在 `PlatformFixedAnnounce platformFixedAnnounce = buildPlatformFixedAnnounce(...)` 之后、
写库之前，加入模板校验：

```java
        // 合同模板：必须是已发布且当前企业可选的模板；空值走平台默认
        Integer templateVersionNo = contractTemplateService.resolveSelectableVersionNo(
                currentEnterpriseId, auctionAddedProduct.getContractTemplateId());
        platformFixedAnnounce.setContractTemplateId(auctionAddedProduct.getContractTemplateId());
        platformFixedAnnounce.setContractTemplateVersionNo(templateVersionNo);
```

配套在 `ContractTemplateService` 增加一个方法（已放在新增文件里，此处为了说明签名）：

```java
    /**
     * 校验商品选择的模板可用，并返回要冻结的版本号。
     *
     * @param templateId 为空时返回平台默认模板的已发布版本号
     * @throws DisallowedOperationException 模板不存在/未发布/不属于该企业
     */
    public Integer resolveSelectableVersionNo(String enterpriseId, String templateId)
```

> `buildPlatformFixedAnnounce(...)` 也要把这两个字段从 DTO 带过去；若该方法用
> `PlatformFixedAnnounce.builder()` 构造，补两行 setter 即可。

---

## L. 编辑已上架商品

### L-1 `beans/dto/insert/PlatformProductEditDto.java`

```java
    /**
     * 合同模板ID
     */
    @Size(max = 64, message = "合同模板ID最大长度要小于 64")
    private String contractTemplateId;
```

### L-2 `SaleRoomService#isLowRiskEdit`

**换合同模板必须视为高风险编辑**（要重新走审核），理由：模板决定合同正文的条款结构，
不能企业自己悄悄换掉。在方法里加一条：

```java
        // 更换合同模板属于高风险修改：合同正文条款会变，必须重新审核
        if (!Objects.equals(editDto.getContractTemplateId(), currentAnnounce.getContractTemplateId())) {
            return false;
        }
```

### L-3 `editAcceptedProduct`

在 `ProductVersionService.editProductAndCreateVersion(...)` 调用之前，
把新模板写到 announce（版本快照由 I 负责）：

```java
        if (!Objects.equals(editDto.getContractTemplateId(), currentAnnounce.getContractTemplateId())) {
            Integer versionNo = contractTemplateService.resolveSelectableVersionNo(
                    user.getCurrentEnterpriseId(), editDto.getContractTemplateId());
            PlatformFixedAnnounce announceUpdate = PlatformFixedAnnounce.builder()
                    .id(currentAnnounce.getId())
                    .contractTemplateId(editDto.getContractTemplateId())
                    .contractTemplateVersionNo(versionNo)
                    .build();
            platformFixedAnnounceMapper.updateByPrimaryKeySelective(announceUpdate);
        }
```

---

## M. `service/OrderService.java`

### M-1 下单时快照

在 `createOrder` 里已读取 `current_version` 的位置附近，补齐：

```java
        PlatformProductVersion productVersion = productVersionService.getVersion(productId, currentVersion);
        order.setProductVersion(currentVersion);
        order.setContractTemplateId(productVersion.getContractTemplateId());
        order.setContractTemplateVersionNo(productVersion.getContractTemplateVersionNo());
```

> 若现有代码是「先落订单再更新版本号」的两步写法，注意在 `platformOrderMapper.insert` 之前赋值。

### M-2 建合同时留痕

`createContractByOrder` 的 `PlatformContract.builder()` 里追加：

```java
                .templateId(platformOrder.getContractTemplateId())
                .templateVersionNo(platformOrder.getContractTemplateVersionNo())
```

`templateName` / `renderSnapshot` 由 `ContractTask` 渲染时补写（能拿到真实模板名）。

---

## N. 合同详情返回模板版本（建议）

`ContractDetailRes` 增加：

```java
    /**
     * 合同依据的模板名称
     */
    private String templateName;

    /**
     * 合同依据的模板版本号
     */
    private Integer templateVersionNo;
```

`PlatformContractMapper.xml` 的 `getDetailContracts` 查询补：

```sql
c.template_name as templateName, c.template_version_no as templateVersionNo,
```

---

## O. 配置

`application-local.yaml`（`pre` / `pro` 同理，pro 必须用环境变量）：

```yaml
shmj:
  onlyoffice:
    enabled: true
    public-url: https://docs.shmj.shop          # 浏览器访问 Document Server
    internal-url: http://onlyoffice:80          # 后端访问 Document Server
    backend-url-for-doc-server: http://shmj-backend:8080  # Document Server 反访本服务
    jwt-secret: ${SHMJ_ONLYOFFICE_JWT_SECRET}   # 必须与 Document Server 的 JWT_SECRET 一致
    session-ttl-minutes: 120
    force-save-timeout-millis: 8000
```

`docker-compose`（部署侧，不在本仓库）：

```yaml
  onlyoffice:
    image: onlyoffice/documentserver:8.2
    environment:
      JWT_ENABLED: "true"
      JWT_SECRET: ${SHMJ_ONLYOFFICE_JWT_SECRET}
    expose: ["80"]
```

> 生产必须 HTTPS + 受控网络；`jwt-secret` 只经环境变量注入，不能进 git。

---

## P. 发布说明

新建 `changes/releases/<version>.md`，至少包含：

```markdown
## 数据库变更
- 新增表 platform_contract_template、platform_contract_template_version
- platform_fixed_announce 增加 contract_template_id / contract_template_version_no
- platform_product_version 增加 contract_template_id / contract_template_version_no / contract_template_snapshot
- platform_order 增加 contract_template_id / contract_template_version_no
- platform_contract 增加 template_id / template_version_no / template_name / render_snapshot

## 上线步骤
1. 执行 DDL（integration/ddl/V15__contract_template.sql）
2. 部署 ONLYOFFICE Document Server，配置 JWT_SECRET
3. 后端配置 shmj.onlyoffice.*
4. 把现有模板登记为平台默认模板（DDL 文件末尾注释段）
5. 打开管理后台 → 合同模板 → 编辑默认模板，给签署区配置锚点并发布
6. 灰度：先在 pre 环境跑 3 笔完整「上架 → 下单 → 签署」

## 回滚
- 代码回滚到上一版本；新列保留（可空，不影响旧代码）
- 若已产生数据：把 platform_contract_template.is_default 置 0 即回到「使用配置路径」的旧行为
```

---

## Q. 测试库迁移

把 `integration/ddl/V15__contract_template.sql` 复制到
`shmj-backend-server/src/test/resources/db/migration/mysql/V15__contract_template.sql`
（Flyway 在 Testcontainers 里按版本号顺序执行；不要改已有文件的版本号）。

---

## 落地顺序建议

1. **A + B + C**（无行为变化，可单独合入，先把「能按版本渲染」的地基打好）
2. **E ~ H** 实体与 XML（无行为变化）
3. 新增 `integration/src` 下的 service / controller
4. **D + I + J + K + L + M**（真正切换行为，配合 pre 环境验证）
5. **N + O + P + Q**

> 第 4 步是唯一有行为风险的合入点。建议在 pre 环境用
> `platform_contract_template.is_default = 0`（即回退到旧配置路径）先验证一遍全链路，
> 再把默认模板打开——这样出问题只需改一行数据即可回到旧行为。
