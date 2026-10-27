# ONLYOFFICE 占位符锁定 POC

## 目标

验证合同模板中的 `{<$.product.type>}` 格式业务占位符能否在 ONLYOFFICE Docs 中作为内联内容控件锁定，同时允许用户编辑同一段落中的普通文字、修改其他排版，并保存 DOCX。

## 当前状态

ONLYOFFICE 编辑器已嵌入主工作台的“合同模板编辑”页面。打开模板时，后端会把 `{<$.path>}` 占位符包装为 OOXML 内联内容控件并设置 `w:lock="sdtContentLocked"`；内容控件中显示 `/api/contract-fields` 返回的当前字段值（无值为空），原始占位符保存在 `w:tag` 中。保存回调会按 tag 把内容控件还原为占位符、与工作副本比对，缺失或改名时拒绝保存。

占位符归一化已支持跨多个 Word run、同一 run 多个 token 以及 `w:lastRenderedPageBreak`；默认煤炭合同模板的 40 个占位符（30 个 key）可以完整保护。当前保护逻辑仍只针对 `word/document.xml` 正文，不覆盖页眉、页脚和脚注中的占位符；`w:lock` 在 ONLYOFFICE 实际编辑界面的锁定效果仍建议按下方用例手工回归一次。


## 启动 Document Server

在项目根目录执行：

```bash
export ONLYOFFICE_JWT_SECRET='replace-with-a-long-random-secret'
docker compose --profile onlyoffice up -d onlyoffice
docker compose ps onlyoffice
curl -fsS http://localhost:8081/healthcheck
```

Document Server 地址：`http://localhost:8081`。JWT 密钥只用于本地开发，正式部署必须由后端签发/验证 JWT，并通过 HTTPS 和受控网络访问 Document Server；不要把密钥放进 React 前端。

## 验证用例

准备一个独立测试 DOCX，包含以下段落：

`产品类型：{<$.product.type>}，规格及交付要求由双方协商确定。`

预期检查：

1. 页面中占位符位置显示后端字段值；没有值的字段显示为空。
2. 字段内容控件不能编辑或删除（或保存时被拒绝）。
3. 同一段落中的“产品类型：”和“规格及交付要求由双方协商确定。”仍可编辑。
4. 可修改其他段落、字体、对齐、表格布局等。
5. 点击“保存模板版本”后，新版本 DOCX 中是原始 `{<$.product.type>}` 占位符，而不是业务值。
6. 重新打开编辑器后，字段值再次替换进来；没有值的字段仍为空。
7. 后端在保存时检查占位符集合，发现占位符缺失、改名或数量变化时拒绝保存，并返回可理解的错误信息。

## 技术验证顺序

1. 通过 ONLYOFFICE Docs API 打开带有业务值的内容控件 DOCX。
2. 后端将每个正文占位符转换为 OOXML 内联内容控件，设置 `w:lock="sdtContentLocked"`，把原始占位符放进 `w:tag`，内容显示字段值（无值为空）。
3. 在编辑器中测试锁定内容控件与同段普通文字的可编辑性。
4. 后端保存回调下载 DOCX，按 tag 还原占位符，再验证占位符名称和数量与工作副本一致；任何占位符被删除、改名或新增时拒绝保存。
5. 通过实际编辑、保存、重新打开、生成 PDF 的回归测试后，才可认定验证通过。

## 参考文档

- [ONLYOFFICE React 集成](https://api.onlyoffice.com/docs/docs-api/get-started/frontend-frameworks/react)
- [ApiInlineLvlSdt.SetLock](https://api.onlyoffice.com/docs/office-api/usage-api/document-api/ApiInlineLvlSdt/Methods/SetLock/)
- [SdtLock 枚举](https://api.onlyoffice.com/docs/office-api/usage-api/document-api/Enumeration/SdtLock/)
