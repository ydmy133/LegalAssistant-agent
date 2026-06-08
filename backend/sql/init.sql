-- ============================================================
-- Legal Assistant Agent - 数据库初始化脚本
-- 使用方法: mysql -u root -p < init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS legal_assistant DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE legal_assistant;

-- 法律文档表 (上传的法律文档元数据)
CREATE TABLE `document` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`       VARCHAR(256) NOT NULL COMMENT '文档标题',
    `file_name`   VARCHAR(512) NOT NULL COMMENT '原始文件名',
    `file_path`   VARCHAR(512) NOT NULL COMMENT '存储路径',
    `file_type`   VARCHAR(32)  NOT NULL COMMENT '文件类型(pdf/docx/txt)',
    `file_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    `chunk_count` INT          NOT NULL DEFAULT 0 COMMENT '分块数量',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=已处理, 0=处理中, -1=处理失败',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法律文档表';

-- 会话表 (对话会话)
CREATE TABLE `conversation` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  VARCHAR(64)  NOT NULL COMMENT '会话UUID',
    `title`       VARCHAR(256) DEFAULT '新对话' COMMENT '会话标题',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`),
    CONSTRAINT `fk_conversation_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- 消息表 (会话中的对话记录)
CREATE TABLE `message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT       NOT NULL COMMENT '关联会话ID',
    `role`            VARCHAR(16)  NOT NULL COMMENT '角色: user/assistant/tool',
    `content`         TEXT         NOT NULL COMMENT '消息内容',
    `metadata_json`   TEXT         DEFAULT NULL COMMENT '附加元数据(JSON)',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表';

-- 用户表
CREATE TABLE `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(256) NOT NULL COMMENT '密码(BCrypt加密)',
    `email`       VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `phone`       VARCHAR(32)  DEFAULT NULL COMMENT '手机号',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 用户模型配置表
CREATE TABLE `user_model_config` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `provider_name` VARCHAR(64)  NOT NULL COMMENT '模型提供商(OpenAI/DeepSeek/Zhipu/...)',
    `model_name`    VARCHAR(128) NOT NULL COMMENT '模型名称(gpt-4o-mini/deepseek-chat/...)',
    `api_key`       VARCHAR(512) NOT NULL COMMENT 'API Key',
    `base_url`      VARCHAR(256) DEFAULT NULL COMMENT 'API端点(空则使用默认)',
    `is_default`    TINYINT      DEFAULT 0 COMMENT '是否默认模型',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户模型配置表';

-- 法律案件表 (案例记录)
CREATE TABLE `legal_case` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `case_number`   VARCHAR(128) NOT NULL COMMENT '案号',
    `title`         VARCHAR(512) NOT NULL COMMENT '案件标题',
    `court`         VARCHAR(256) DEFAULT NULL COMMENT '审理法院',
    `case_type`     VARCHAR(64)  DEFAULT NULL COMMENT '案件类型(民事/刑事/行政)',
    `parties`       TEXT         DEFAULT NULL COMMENT '当事人信息(JSON)',
    `summary`       TEXT         DEFAULT NULL COMMENT '案件摘要',
    `content`       LONGTEXT     DEFAULT NULL COMMENT '案件详细内容',
    `judgment_date` DATE         DEFAULT NULL COMMENT '判决日期',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_case_number` (`case_number`),
    KEY `idx_case_type` (`case_type`),
    KEY `idx_judgment_date` (`judgment_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法律案件表';
