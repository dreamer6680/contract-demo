# 合同模板自定义 / 版本管理 / 商品选模板 / 在线签署 —— 落地包

本目录是「把 `pdf-contract-demo 2` 的能力接入 `shmj-backend`」的可落地实现，
按 `shmj-backend` 现有工程约定（Spring Boot 3.5 / MyBatis XML / 自研鉴权 / OSS / 法大大 v5）编写。

## 先看这三条结论

1. **不需要移植 Demo 的 `PlaceholderProtectionService`**
   后端的 `utils/SdtTemplateUtil`（工作区已有、尚未提交）已经实现了「占位符 → 锁定内容控件 + `w:tag`」，
   并且覆盖正文与页眉页脚。本方案只补一个方向相反的小工具 `SdtTokenRestoreUtil`（把展示值还原成占位符）。

2. **签章位置不要靠像素坐标，也不要自研分页**
   法大大 SDK 的 `FieldPosition` 支持 `positionMode=keyword` + `positionKeyword`，
   还有 `POST /file/get-keyword-positions` 可以反查关键字落在第几页什么坐标。
   于是：模板里保留「甲方（盖章）」这类自然文字 → 发布时反查校验唯一性 → 签署时让法大大按关键字定位。
   **这一步把「用户改模板导致章盖错位置」这个最大风险从「必踩」降为「发布时拦住」。**

3. **模板文件本来就在 OSS，不在仓库里**
   `fasc.templates.contractTemplatePath = templates/contracts/contract-template-v1.docx` 是 OSS key。
   所以「模板入库 + 版本化」不是把本地文件搬进数据库，而是给它加上版本台账与发布闸门。

## 目录

```
integration/
├── ddl/
│   └── V15__contract_template.sql          2 张新表 + 5 处字段扩展 + 回滚脚本
├── src/main/java/shop/shmj/backend/
│   ├── beans/dict/
│   │   ├── ContractTemplateStatus.java          模板状态
│   │   ├── ContractTemplateVersionStatus.java   版本状态
│   │   └── SigningAnchor.java                  签署锚点定义（A/B 公章与签字）
│   ├── beans/dto/contract/
│   │   ├── SigningAnchorConfig.java      锚点配置（存版本快照）
│   │   ├── SigningAnchorResolution.java  锚点定位结果（审计/降级）
│   │   └── SigningAnchorBundle.java      configs + resolutions
│   ├── beans/dto/request/                4 个请求 DTO
│   ├── beans/dto/response/               4 个响应 DTO
│   ├── beans/entity/                     2 个实体
│   ├── mapper/mysql/                     2 个 Mapper 接口
│   ├── configurations/OnlyOfficeProperties.java
│   ├── external/impl/OnlyOfficeClient.java      JWT / 编辑器配置 / forcesave / 回调下载
│   ├── service/contract/
│   │   ├── TemplateFieldRegistry.java          字段字典（反射 V1ContractParam）
│   │   ├── SigningAnchorService.java           关键字锚点校验与字段构造
│   │   ├── ContractTemplateService.java        版本生命周期 + 编辑会话 + 保存回调
│   │   └── ContractRenderService.java         合同渲染唯一入口（替代直读配置路径）
│   ├── utils/SdtTokenRestoreUtil.java          展示值 → 占位符 还原
│   └── controller/
│       ├── manager/ManagerContractTemplateController.java  运营端模板管理
│       ├── platform/ContractTemplateController.java        商品上架选模板
│       └── OnlyOfficeController.java                       Document Server 对接（无登录态）
├── src/main/resources/mapper/mysql/      2 个 Mapper XML
└── patches/existing-files.md             对现有文件的逐项改动（照抄即可）
```

## 关键流程

### 模板编辑（运营）

```
新建模板 → 上传 DOCX
   └─ SdtTemplateUtil.normalize()：{<$.x>} → 锁定内容控件，tag = JsonPath
   └─ 校验：无残缺占位符、控件都有 tag、tag ∈ 字段注册表
上传 → 打开编辑器
   └─ 生成「展示副本」：控件内容换成示例业务值（tag 不变）
   └─ 存 Redis 会话（documentKey / 基线 tag 集合 / 操作人）
编辑保存 → 回调
   └─ 验 JWT → 下载 → SdtTokenRestoreUtil.restore() 还原成占位符
   └─ 与基线 tag 集合比对：不一致直接返回 error=1（编辑器提示保存失败）
点「保存版本」
   └─ 先 forcesave（否则拿到的是编辑器缓存不是最新内容）→ 等回调落盘
   └─ 内容指纹相同则复用，不产生空版本
   └─ 新版本号 = max+1，文件写入不可变路径
点「发布」
   └─ 闸门 1：占位符必须都是已知字段
   └─ 闸门 2：用示例数据渲染 → 上传法大大 → getKeywordPositions → 每个锚点唯一命中
   └─ 通过后：本模板旧发布版本置「已被取代」，商品才可选
```

### 商品 → 订单 → 合同（快照链）

```
上架时选模板（platform_fixed_announce.contract_template_id）
  → 审核通过建商品版本（platform_product_version.contract_template_id/version_no）
  → 下单快照（platform_order.contract_template_id/version_no）
  → ContractTask 渲染（按版本取 OSS 文件 + 按锚点构造签署字段）
  → platform_contract.template_id/version_no/render_snapshot 留痕
```

三级快照的意义：订单永远用「下单那一刻」的模板与版本渲染，
即便运营后来改模板、发新版、甚至停用，也不影响已成交订单。

## 接入步骤

1. 执行 `ddl/V15__contract_template.sql`（测试库另复制一份到
   `src/test/resources/db/migration/mysql/`）。
2. 部署 ONLYOFFICE Document Server，配置 `JWT_SECRET`。
3. 按 `patches/existing-files.md` 的 A ~ C 先合入（**无行为变化**）。
4. 把 `src/**` 拷进对应包，Mapper XML 拷到 `src/main/resources/mapper/mysql/`。
   *注意两个 Mapper XML 的文件名必须与接口同名，否则 MyBatis 绑定失败。*
5. 按 `patches/existing-files.md` 的 E ~ H 补实体与 XML 字段。
6. 按 D、I ~ M 切换行为（**唯一有风险的合入点**，建议 pre 环境先验证）。
7. 配置 `shmj.onlyoffice.*`，发布说明写进 `changes/releases/`。

## 灰度和回滚

| 手段 | 效果 |
|---|---|
| `platform_contract_template` 为空 / `is_default=0` | `resolveForOrder` 返回 null → 回退老配置路径 + 老像素坐标，**行为与今天完全一致** |
| `shmj.onlyoffice.enabled=false` | 模板编辑接口返回「未启用」，合同生成与签署不受影响 |
| 代码回滚 | 新列可空，旧代码忽略即可；无需回滚 DDL |

建议上线顺序：**先只启用「按版本渲染 + 老坐标」（A~M 合入但默认模板不开）→ 跑通 3 笔签署
→ 再打开锚点定位与模板编辑**。

## 与 Demo 的差异对照

| Demo 做法 | 本方案 | 原因 |
|---|---|---|
| 模板文件存容器磁盘 `storage/templates/*.docx` | OSS + 版本表 | 多实例部署、需审计 |
| 模板名存 `.name` 边车文件 | 数据库字段 | — |
| 单表 `contract_document_v2` | 模板主表 + 版本表 | 版本不可变、状态机 |
| 占位符 tag = `placeholder:{<$.x>}` 完整 token | tag = `x`（JsonPath） | 复用后端已有 `SdtTemplateUtil.fillSdt` |
| 无租户、无权限 | 管理端运营维护，企业只能选 | 法大大发起方是平台，签章类型受平台配置约束 |
| 签章坐标写死 | 关键字锚点 + 发布校验 | 模板可变后坐标必然漂移 |
| `activeKeys` / `saveCounters` 存内存 | Redis | 后端多实例 |
| 无字段合法性校验 | tag 必须在 `TemplateFieldRegistry` 内 | 拼错字段会静默留空 |
| 无预览 PDF | 一期返回 DOCX 下载；二期接 LibreOffice | 仓库内无转换能力，不为此阻塞一期 |

## 尚未覆盖（需要你决策）

1. **企业私有模板**：`owner_scope=2` 的表结构与接口已预留，但企业模板必须配套审核流程，
   否则企业可以把不利条款写进模板再让平台盖章。建议二期做。
2. **预览 PDF**：需要 LibreOffice headless 或法大大转换能力，涉及新容器与字体，
   建议二期；一期用「下载 DOCX + 发布时人工确认」过渡。
3. **模板文字级 diff**：Demo 的段落编辑模型可复用，二期做。
4. **操作审计**：`OperationBusinessType` 目前没有合同模板类型，需要新增枚举值后
   在 `ContractTemplateService` 里调用 `operationRecordCollector.collectSystem(...)`。

## 已实测验证的部分

本目录里的两个高风险机制**已经用真实依赖跑通**，不是纸上设计：

```bash
./verification/run.sh     # 只用 ~/.m2 里已有的 POI 5.4.1 / Lombok / Jackson，不需要起后端
```

实测输出（2026-10-27）：

```
==== 1. 占位符往返 ====
[normalize] valid=true converted=3 sdt=3 tags=[seller.name, contractNo]
[display] body=卖方：山西示例能源有限公司，编号：HT-2026-0001
[display] header=页眉编号：HT-2026-0001
[restore] valid=true restored=3 tags=[seller.name, contractNo] untagged=[]
[restore] body=卖方：{<$.seller.name>}，编号：{<$.contractNo>}
[restore] header=页眉编号：{<$.contractNo>}
[assert] roundtrip=true
[assert] idempotent=true
[assert] untaggedDetected=true untagged=[(run 级控件缺少 tag)]
RESULT: PASS

==== 2. 字段注册表 ====
count=30
unknown=[product.untiPrice]
RESULT: PASS
```

验证到的事实：

| 验证点 | 结论 |
|---|---|
| 跨 run 的占位符 | `SdtTemplateUtil.normalize` 能正确合并（用例里故意把 `{<$.seller` / `.name>}` 拆成两个 run） |
| 页眉中的占位符 | 能被归一化、填充、还原（Demo 只处理 `document.xml`，这里是后端实现的优势） |
| 展示副本 → 占位符 | 业务值被完全清除，模板里只剩 `{<$.xxx>}` |
| 幂等性 | 还原后再归一化 `converted=0`，说明规范形态稳定，不会在多次「编辑-保存」中累积垃圾 |
| 篡改检测 | 删掉 `w:tag` 后 `isValid()=false`，保存会被拒绝 |
| 字段注册表 | 反射得到 **30 个** tag，与 Demo 文档里「40 个占位符 / 30 个 key」的统计一致 |
| 拼写错误拦截 | `product.untiPrice` 被判为未知字段 |

**没有验证到的部分（需要你在工程里编译）**：`ContractTemplateService`、`OnlyOfficeClient`、
`SigningAnchorService`、三个 Controller 依赖大量后端既有类（`FileStorage`、`FascOpenApi`、
`AuthedManagerUser`、Redis 等），只能在 `shmj-backend` 里编译。它们按现有源码签名编写，
但没有做过编译验证；落地时请以编译结果为准。

## 一件事需要你看模板文件才能定

`SigningAnchor` 的**默认关键字**（「甲方（盖章）」等）是猜测值。请打开线上模板
`templates/contracts/contract-template-v1.docx` 的签署页确认实际措辞，
然后改两处即可：

1. `SigningAnchor` 枚举的 `defaultKeyword`；
2. 发布页表单的默认值（前端）。

如果措辞不唯一（甲乙双方都写「法定代表人（签字）」），首选改模板文案而不是加「取第 N 次命中」的复杂度。
