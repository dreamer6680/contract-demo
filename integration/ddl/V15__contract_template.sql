-- =============================================================
-- V15: 合同模板库（模板 + 不可变版本 + 商品/订单/合同 绑定字段）
-- 说明：生产库无 Flyway，需随发布说明人工执行；本文件同时放入
--       shmj-backend-server/src/test/resources/db/migration/mysql/
-- 回滚：见文件末尾 DOWN 段
-- =============================================================

-- ----------------------------
-- 1. 合同模板（逻辑模板，一个名称一条记录）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `platform_contract_template`
(
    `id`                   varchar(64)  NOT NULL,
    `template_code`        varchar(64)  NOT NULL DEFAULT '' COMMENT '业务编码，便于前端/配置引用',
    `template_name`        varchar(255) NOT NULL COMMENT '模板名称',
    `owner_scope`          int          NOT NULL DEFAULT 1 COMMENT '1 平台公共 2 企业私有',
    `owner_enterprise_id`  varchar(64)  DEFAULT NULL COMMENT '企业私有模板的归属企业',
    `status`               int          NOT NULL DEFAULT 100 COMMENT 'ContractTemplateStatus:100草稿 200已发布 300已停用',
    `current_version_no`   int          NOT NULL DEFAULT 1 COMMENT '当前编辑中的版本号',
    `published_version_no` int          DEFAULT NULL COMMENT '已发布版本号；商品只能选有该值的模板',
    `sign_anchor_mode`     int          NOT NULL DEFAULT 2 COMMENT '1 关键字定位 2 像素坐标(历史兼容)',
    `is_default`           int          NOT NULL DEFAULT 0 COMMENT '1=平台默认模板',
    `remark`               varchar(255) DEFAULT NULL,
    `create_user_id`       varchar(64)  DEFAULT NULL,
    `create_user_name`     varchar(64)  DEFAULT NULL,
    `create_time`          datetime     NOT NULL,
    `update_time`          datetime     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `unq_template_code` (`template_code`) USING BTREE,
    KEY `idx_status_scope` (`status`, `owner_scope`),
    KEY `idx_owner_enterprise` (`owner_enterprise_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='合同模板';

-- ----------------------------
-- 2. 合同模板版本（只增不改；file_key 指向 OSS 上的「占位符版」DOCX）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `platform_contract_template_version`
(
    `id`                   varchar(64)  NOT NULL,
    `template_id`          varchar(64)  NOT NULL,
    `version_no`           int          NOT NULL COMMENT '从 1 递增',
    `file_key`             varchar(255) NOT NULL COMMENT '占位符版 DOCX 的 OSS key',
    `preview_pdf_key`      varchar(255) DEFAULT NULL COMMENT '预览 PDF（二期）',
    `file_sha256`          varchar(64)  NOT NULL COMMENT '内容指纹，用于去重与防重复保存',
    `placeholder_snapshot` text         COMMENT '占位符快照 JSON: [{"tag":"seller.name","count":1}]',
    `signing_anchor_json`  text         COMMENT '签署锚点配置 JSON: [{"anchor":"A_CORP_SEAL","keyword":"甲方（盖章）","offsetX":"-120","offsetY":"-40"}]',
    `change_summary`       varchar(500) DEFAULT NULL COMMENT '版本变更说明',
    `status`               int          NOT NULL DEFAULT 100 COMMENT 'ContractTemplateVersionStatus',
    `create_user_id`       varchar(64)  DEFAULT NULL,
    `create_user_name`     varchar(64)  DEFAULT NULL,
    `create_time`          datetime     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `unq_template_version` (`template_id`, `version_no`) USING BTREE,
    KEY `idx_template_status` (`template_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='合同模板版本';

-- ----------------------------
-- 3. 商品公告：上架时选择的模板先落在这里
--    模板在「审核通过」时才会被快照进 platform_product_version
--    （与保证金/交易费规则同构：商品版本快照在审核通过时创建），
--    审核期间需要记住选的到底是哪一版。
-- ----------------------------
ALTER TABLE `platform_fixed_announce`
    ADD COLUMN `contract_template_id`         varchar(64) DEFAULT NULL COMMENT '选择的合同模板ID',
    ADD COLUMN `contract_template_version_no` int         DEFAULT NULL COMMENT '选择的合同模板版本号';

-- ----------------------------
-- 4. 商品版本：冻结「上架时选的是哪版模板」
--    放在版本表而不是商品主表：与保证金/交易费规则保持一致，编辑可版本化
-- ----------------------------
ALTER TABLE `platform_product_version`
    ADD COLUMN `contract_template_id`         varchar(64) DEFAULT NULL COMMENT '合同模板ID',
    ADD COLUMN `contract_template_version_no` int         DEFAULT NULL COMMENT '合同模板版本号',
    ADD COLUMN `contract_template_snapshot`   text COMMENT '模板快照(名称/fileKey/占位符/锚点)';

-- ----------------------------
-- 5. 订单：下单时随 product_version 一起快照
-- ----------------------------
ALTER TABLE `platform_order`
    ADD COLUMN `contract_template_id`         varchar(64) DEFAULT NULL COMMENT '下单时的合同模板ID',
    ADD COLUMN `contract_template_version_no` int         DEFAULT NULL COMMENT '下单时的合同模板版本号';

-- ----------------------------
-- 6. 合同：渲染留痕（哪版模板、用什么锚点、渲染参数快照）
-- ----------------------------
ALTER TABLE `platform_contract`
    ADD COLUMN `template_id`         varchar(64) DEFAULT NULL COMMENT '渲染使用的合同模板ID',
    ADD COLUMN `template_version_no` int         DEFAULT NULL COMMENT '渲染使用的模板版本号',
    ADD COLUMN `template_name`       varchar(255) DEFAULT NULL COMMENT '模板名称快照',
    ADD COLUMN `render_snapshot`     text COMMENT '渲染参数快照(V1ContractParam + 锚点定位结果)';

-- ----------------------------
-- 7. 历史数据回填：把现有唯一模板登记为「平台默认模板 v1」
--    注意：下方 :PARAM 需替换为真实 OSS key（即 fasc.templates.contractTemplatePath 当前值）
-- ----------------------------
-- INSERT INTO platform_contract_template
-- (id, template_code, template_name, owner_scope, status, current_version_no, published_version_no,
--  sign_anchor_mode, is_default, create_user_id, create_user_name, create_time)
-- VALUES ('tpl_default_coal_sale_contract_v1', 'COAL_SALE_STANDARD', '煤炭买卖合同', 1, 200, 1, 1, 2, 1,
--         'system', '系统迁移', NOW());
--
-- INSERT INTO platform_contract_template_version
-- (id, template_id, version_no, file_key, file_sha256, placeholder_snapshot, signing_anchor_json,
--  change_summary, status, create_user_id, create_user_name, create_time)
-- VALUES ('tplv_default_coal_sale_contract_v1', 'tpl_default_coal_sale_contract_v1', 1,
--         'templates/contracts/contract-template-v1.docx', '', NULL, NULL,
--         '历史模板迁移，签署坐标沿用像素模式', 200, 'system', '系统迁移', NOW());

-- =============================================================
-- DOWN（回滚）
-- =============================================================
-- ALTER TABLE `platform_contract` DROP COLUMN `render_snapshot`, DROP COLUMN `template_name`,
--     DROP COLUMN `template_version_no`, DROP COLUMN `template_id`;
-- ALTER TABLE `platform_order` DROP COLUMN `contract_template_version_no`, DROP COLUMN `contract_template_id`;
-- ALTER TABLE `platform_fixed_announce` DROP COLUMN `contract_template_version_no`, DROP COLUMN `contract_template_id`;
-- ALTER TABLE `platform_product_version` DROP COLUMN `contract_template_snapshot`,
--     DROP COLUMN `contract_template_version_no`, DROP COLUMN `contract_template_id`;
-- DROP TABLE IF EXISTS `platform_contract_template_version`;
-- DROP TABLE IF EXISTS `platform_contract_template`;
