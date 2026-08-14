-- 批量生成 100 条 safety_report_record + safety_report_visit_record
-- visit_record.record_id 通过 LAST_INSERT_ID() 关联上一条 record 的自增主键
-- 用法: mysql -h <host> -u <user> -p ecomm < batch_insert.sql
-- 或在客户端执行: 先执行整段 DELIMITER 块创建存储过程, 再 CALL batch_insert_records(100);

DELIMITER $$

DROP PROCEDURE IF EXISTS batch_insert_records $$

CREATE PROCEDURE batch_insert_records(IN p_count INT)
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE v_record_id BIGINT;
    DECLARE v_now_ms BIGINT;
    DECLARE v_record_uuid VARCHAR(64);
    DECLARE v_visit_uuid VARCHAR(64);

    START TRANSACTION;

    WHILE i < p_count DO
        SET v_now_ms = UNIX_TIMESTAMP(NOW(3)) * 1000;
        -- 用 UUID 保证唯一性, 去掉横线
        SET v_record_uuid = REPLACE(UUID(), '-', '');
        SET v_visit_uuid  = CONCAT('v', REPLACE(UUID(), '-', ''));

        INSERT INTO `ecomm`.`safety_report_record`(
            `record_uuid`, `user_id`, `email`, `first_name`, `middle_name`, `last_name`,
            `user_name`, `report_to_unit`, `reporter_ip`, `safety_report_id`, `safety_report_name`,
            `customer_id`, `workflow_node_id`, `workflow_node_status`, `report_status_type`,
            `handler_users`, `config_version_record_id`, `report_content`,
            `first_report_time`, `plan_report_start_time`, `plan_report_end_time`,
            `del_time`, `update_time`, `create_time`, `create_date`, `create_at`, `modify_at`
        ) VALUES (
            v_record_uuid, 0, CONCAT('test', i, '@example.com'), 'Michelle', '', 'Marie',
            'Michelle Marie', '9f6a476e48665dcda005b41968513cca', '', 12049, 'VisitorAllEDIT',
            504, 'afb44605-e997-411f-9173-d5233fe814a2', 'Pending View', 'In-progress',
            '{}', 4674, '{}',
            v_now_ms, 0, 0,
            0, v_now_ms, v_now_ms, CURDATE(), NOW(), NOW()
        );

        -- 依赖点: 拿到上一条 record 的自增主键
        SET v_record_id = LAST_INSERT_ID();

        INSERT INTO `ecomm`.`safety_report_visit_record`(
            `visit_uuid`, `customer_id`, `device_id`, `site_id`, `site_name`, `source_type`,
            `visitor_type`, `user_id`, `visitor_name`, `first_name`, `middle_name`, `last_name`,
            `record_id`, `unit_uuid`, `phone`, `email`, `license_type`, `license_number`,
            `host`, `host_confirm`, `profile_photo`, `extend_info`, `offender_status`,
            `check_in_time`, `check_out_time`, `declined_time`, `approved_time`, `note`,
            `invite_by_user_id`, `invite_by_user_name`, `visit_start_time`, `visit_end_time`,
            `create_time`, `update_time`, `destination`, `document_signature`,
            `create_at`, `modify_at`, `invite_type`, `guest_uuid`, `invite_guest_setting_id`
        ) VALUES (
            v_visit_uuid, 504, 0, 1427, 'St. Ann School', 0,
            0, 0, 'Michelle Marie', 'Michelle', '', 'Marie',
            v_record_id, '9f6a476e48665dcda005b41968513cca',
            CONCAT('+1 (571) 510-', LPAD(FLOOR(RAND() * 10000), 4, '0')),
            CONCAT('test', i, '@example.com'), 1, '123456789',
            'gsp2FreePBX PA syste gsp2FreePBX PA syste', NULL,
            'https://capi.s01.crisisgo-staging.net/file-service/file/preview/34d45dbe6e8a44e0bd37a9dc9b41406c.encrypt',
            '{"dymo": {"fileMd5": "a45f8687b2c678dafb852c7e8f0d505d", "fileUuid": "46822985afdf4dd3b73c21dce2a169b8", "originalName": "photo_1779695718495.jpeg"}}',
            3, 0, 0, 0, 0, '',
            4261323, 'gsp1 gsp1', v_now_ms, v_now_ms + 28800000,
            v_now_ms, v_now_ms, 'classroom',
            '[{"fileUrl": "dd897ae19f8742c9977c265f436f410c", "signatureUrl": "https://capi.s01.crisisgo-staging.net/file-service/file/preview/d1bf6729d7bf4f11a6ee44371f0d2705.encrypt", "shouldSignature": 1}]',
            NOW(), NOW(), 1, '', 0
        );

        SET i = i + 1;
    END WHILE;

    COMMIT;
END $$

DELIMITER ;

-- 执行生成 100 条
CALL batch_insert_records(100);

-- 用完可删除存储过程
-- DROP PROCEDURE IF EXISTS batch_insert_records;
