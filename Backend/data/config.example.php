<?php
/**
 * BiliDiy 数据配置示例
 * 复制为 data/config.php 后修改；不要直接把生产环境 data/ 提交到仓库。
 */
return array(
    'schema' => 3,
    'client_key' => 'bili_your_client_key',
    'admin_hash' => '',
    'setup_token' => 'CHANGE_ME_SETUP_TOKEN',
    'announcement' => array(
        'enabled' => true,
        'id' => 'a-init',
        'title' => 'BiliDiy 云端配置已启用',
        'content' => '可在此修改公告；客户端重新打开后生效。',
        'updated_at' => time(),
    ),
    'update' => array(
        'enabled' => false,
        'version_code' => 0,
        'version_name' => '',
        'url' => '',
        'changelog' => '',
        'force' => false,
    ),
    'cookie' => '',
    'lock' => array(
        'locked' => false,
        'title' => '服务已暂停',
        'message' => '该应用已被远程停用，请稍后再试或联系管理员。',
    ),
    'updated_at' => time(),
);
