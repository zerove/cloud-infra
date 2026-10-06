-- Flyway 迁移脚本 (PostgreSQL 16)
-- 版本: V18__fix_notification_status_column_type.sql
-- 描述: 修正 sys_notification.status 的列类型 —— 从 VARCHAR 改为 SMALLINT
--
-- 缺陷 (2026-10-06 实测):
--   `/notification/logs`、`/notification/search`、`/notification/history`
--   三个端点全部返回 409, 真实报错:
--       Error attempting to get column 'status' from result set.
--       Data integrity violation: conversion to class java.lang.Integer
--                               from varchar not supported
--
-- 根因 (三方对照, 不是猜测):
--   枚举 NotificationStatus (cloud-infra-api/.../enums/NotificationStatus.java):
--       UNREAD(0, "未读"), SENT(1, "已发送"), READ(2, "已读")
--       @EnumValue 标在 `private final Integer code` 上
--               ↓ MyBatis 按 Integer 读写
--   V13 建表: status VARCHAR(20) NOT NULL DEFAULT 'UNREAD'
--               ↓ 列是字符串
--   实存值:   0 / 0 / 0 / 0  (整数, 由代码显式写入)
--
--   即 DDL 按枚举**名称**声明, 代码按枚举**代码**读写 —— 写入成功但读取失败。
--   DEFAULT 'UNREAD' 从未真正生效, 因为 Notification.create() 总是显式 setStatus。
--
-- 同表内的对照证据 (这是判定依据, 不是类比推测):
--   dispatch_status  DispatchStatus 的 @EnumValue 同样是 Integer,
--                      列类型 SMALLINT(V15 迁移), 实存 2, **读取正常**。
--   两个字段结构完全同构, 唯一差别是列类型 —— 一个对、一个错。
--
-- 为什么改列而不是改枚举:
--   1. 数据库实存值已经是 0 (数字), 改枚举需同步改全部存量数据;
--   2. dispatch_status 已确立"枚举 Integer + 列 SMALLINT"为本表约定;
--   3. V15 的注释明确写了 status 的语义是 UNREAD/SENT/READ,
--      但那是**语义**描述, 与存储类型无关;
--   4. 若改枚举为 String, status 与 dispatch_status 会在同一张表里
--      呈现两套不同的存储约定, 后续维护者仍需分别记忆 —— 与
--      "同构字段用同一约定"相悖。
--
-- 存量数据转换:
--   实测 4 行 status 全为 '0'。转换需兼容两种形态:
--     '0'/'1'/'2'  → 直接去引号转数字 (当前实际形态)
--     'UNREAD'/'SENT'/'READ' → 按枚举定义映射 (DEFAULT 值遗留的形态)
--   未识别的值不应静默变成 0(那会让"已读"看起来像"未读"), 故直接抛错,
--   让迁移失败并暴露 —— 宁可迁移失败, 不可静默改坏数据。

-- ── 1. 先检测未识别值(不动数据) ─────────────────────────────────────
-- ⚠️ 顺序很关键: 不能先 `SET status = NULL` 再检测。
--    status 列是 NOT NULL, 该 UPDATE 会**立即**抛
--    "null value in column status violates not-null constraint",
--    根本执行不到后面的检测块 —— 报错的是约束, 而不是我设计的
--    "无法识别的值" 提示, 运维看到的信息会指向错误方向。
--    这是负向对照(GARBAGE 用例)真实抓出来的, 不是推测。
--
-- 检测放在 RAISE 之前, 且不改任何数据:
DO $$
DECLARE
    bad_count INTEGER;
    bad_sample TEXT;
BEGIN
    -- 与下方转换的 CASE 保持同一套取值; ELSE 即"未识别"
    SELECT count(*), min(status) INTO bad_count, bad_sample
    FROM sys_notification
    WHERE status IS NOT NULL
      AND status NOT IN ('0','1','2','UNREAD','SENT','READ');

    IF bad_count > 0 THEN
        RAISE EXCEPTION
            'sys_notification.status 存在 % 行无法识别的值(样本: %)。'
            '预期取值: 0/1/2 或 UNREAD/SENT/READ。'
            '请人工确认后修正, 本迁移不做静默转换。',
            bad_count, bad_sample;
    END IF;
END $$;

-- ── 2. 归一化存量值为枚举名称 ──────────────────────────────────────────
-- 检测已通过, 此处 ELSE NULL 仅作兵底; NOT NULL 约束仍会兜底。
UPDATE sys_notification
SET status = CASE status
    WHEN '0'     THEN 'UNREAD'
    WHEN '1'     THEN 'SENT'
    WHEN '2'     THEN 'READ'
    WHEN 'UNREAD' THEN 'UNREAD'
    WHEN 'SENT'   THEN 'SENT'
    WHEN 'READ'   THEN 'READ'
END
WHERE status IS NOT NULL;

-- ── 3. 改列类型 ───────────────────────────────────────────────────────
-- ⚠️ 必须先 DROP DEFAULT 再 ALTER TYPE。
-- PostgreSQL 不允许把 varchar 的默认值('UNREAD')自动转换为 smallint,
-- 漏掉这步会直接报:
--     default for column "status" cannot be cast automatically to type smallint
-- (这是我第一版迁移真实踩到的错, 不是推测)
ALTER TABLE sys_notification ALTER COLUMN status DROP DEFAULT;

-- USING 子句必须显式给出: VARCHAR → SMALLINT 不是隐式转换, 缺失会直接报错。
-- 名称先转代码: UNREAD=0 SENT=1 READ=2
ALTER TABLE sys_notification
    ALTER COLUMN status TYPE SMALLINT USING (
        CASE status
            WHEN 'UNREAD' THEN 0
            WHEN 'SENT'   THEN 1
            WHEN 'READ'   THEN 2
        END
    );

-- ── 4. 对齐默认值与约束 ───────────────────────────────────────────────
-- ALTER TYPE 之后重建默认值, 必须是数值形式。
ALTER TABLE sys_notification
    ALTER COLUMN status SET DEFAULT 0;

-- 原声明 NOT NULL 保持(V13 已有), 此处显式重申以便自文档
ALTER TABLE sys_notification
    ALTER COLUMN status SET NOT NULL;

COMMENT ON COLUMN sys_notification.status IS
    '未读状态: 0=未读(UNREAD) 1=已发送(SENT) 2=已读(READ)。'
    '与 NotificationStatus(code) 及 dispatch_status 的存储约定一致(均为数值)。';

-- ── 5. 校验 ───────────────────────────────────────────────────────────
DO $$
DECLARE
    col_type TEXT;
    col_default TEXT;
BEGIN
    SELECT data_type, column_default INTO col_type, col_default
    FROM information_schema.columns
    WHERE table_name = 'sys_notification' AND column_name = 'status';

    IF col_type <> 'smallint' THEN
        RAISE EXCEPTION '列类型转换失败: 期望 smallint, 实际 %', col_type;
    END IF;
    -- 默认值形如 '0'::smallint 或 0, 只要求前缀是 0/1/2 之一
    IF col_default !~ '^(0|1|2)' THEN
        RAISE EXCEPTION '默认值未对齐: 期望 0/1/2 开头, 实际 %', col_default;
    END IF;

    RAISE NOTICE 'V18 完成: sys_notification.status 已为 smallint, 默认值 %', col_default;
END $$;