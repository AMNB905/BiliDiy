<?php
/**
 * BiliDiy 管理后台
 * ---------------------------------------------------------------
 * 首次访问：需要 setup_token 初始化管理员密码
 * 之后：账号密码登录，可管理云公告 / 云更新 / 云端 Cookie / 远程锁定
 */
declare(strict_types=1);

require __DIR__ . '/lib/storage.php';
require __DIR__ . '/lib/security.php';

sec_headers(false);
sec_session_start();
sec_admin_guard_session();

define('DATA_DIR', __DIR__ . '/data');
define('DOWNLOAD_DIR', __DIR__ . '/download');

$store = store_load_all();
if (!is_array($store)) {
    http_response_code(500);
    echo '配置文件缺失：data/config.php（若仍只有 data/store.php，请确认 data/ 目录可写以完成自动迁移）';
    exit;
}

function h(?string $s): string
{
    return htmlspecialchars((string) $s, ENT_QUOTES, 'UTF-8');
}

function base_url(): string
{
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');
    $host = isset($_SERVER['HTTP_HOST']) ? $_SERVER['HTTP_HOST'] : 'localhost';
    $dir = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'])), '/');
    return ($https ? 'https' : 'http') . '://' . $host . $dir;
}

/** 只返回 Cookie 字段名，后台页面永远不回显字段值 */
function cookie_field_names(string $cookie): array
{
    $names = array();
    foreach (explode(';', $cookie) as $part) {
        if (strpos($part, '=') === false) continue;
        $name = trim(explode('=', $part, 2)[0]);
        if ($name !== '' && !in_array($name, $names, true)) $names[] = $name;
    }
    return $names;
}

function redirect_self(string $msg, string $type = 'ok'): void
{
    $_SESSION['flash'] = array('m' => $msg, 't' => $type);
    header('Location: ' . strtok($_SERVER['REQUEST_URI'], '?'));
    exit;
}

function csrf_token(): string
{
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(16));
    }
    return $_SESSION['csrf'];
}

function csrf_ok(): bool
{
    return isset($_POST['csrf'], $_SESSION['csrf'])
        && hash_equals((string) $_SESSION['csrf'], (string) $_POST['csrf']);
}

function member_plans(): array
{
    return array(
        'month' => array('label' => '月卡 · 30 天', 'days' => 30),
        'season' => array('label' => '季卡 · 90 天', 'days' => 90),
        'year' => array('label' => '年卡 · 365 天', 'days' => 365),
    );
}

/** 邀请码：前缀可自定义，默认 INV-XXXX-XXXX-XXXX */
function make_invite_code(string $prefix = 'INV'): string
{
    $prefix = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', $prefix));
    $prefix = substr($prefix, 0, 8);
    if ($prefix === '') $prefix = 'INV';
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    $out = array();
    for ($g = 0; $g < 3; $g++) {
        $s = '';
        for ($i = 0; $i < 4; $i++) $s .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        $out[] = $s;
    }
    return $prefix . '-' . implode('-', $out);
}

function member_info_admin(array $store, string $device): array
{
    $m = isset($store['members'][$device]) && is_array($store['members'][$device])
        ? $store['members'][$device] : array();
    $expire = isset($m['expire_at']) ? (int) $m['expire_at'] : 0;
    return array(
        'active' => $expire > time(),
        'expire_at' => $expire,
        'plan' => isset($m['plan']) ? (string) $m['plan'] : '',
    );
}

$isSetup = ((string) $store['admin_hash'] === '');
$logged = !$isSetup && !empty($_SESSION['adm_ok']);
$flash = isset($_SESSION['flash']) ? $_SESSION['flash'] : null;
unset($_SESSION['flash']);

$do = isset($_POST['do']) ? (string) $_POST['do'] : (isset($_GET['do']) ? (string) $_GET['do'] : '');
$ip = sec_client_ip();

// 管理操作只接受 POST，避免 GET 触发状态变更
if ($do !== '' && ($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
    http_response_code(405);
    header('Allow: POST');
    echo 'Method Not Allowed';
    exit;
}

// ---------------- 初始化 / 登录 / 退出 ----------------

if ($isSetup) {
    if ($do === 'setup') {
        $rl = sec_rate_limit('admin_setup:' . $ip, 5, 3600);
        if (!$rl[0]) {
            redirect_self('初始化尝试过多，请稍后再试', 'err');
        }
        $token = isset($_POST['token']) ? trim((string) $_POST['token']) : '';
        $pw = isset($_POST['password']) ? (string) $_POST['password'] : '';
        $pw2 = isset($_POST['password2']) ? (string) $_POST['password2'] : '';
        if (empty($store['setup_token']) || !hash_equals((string) $store['setup_token'], $token)) {
            usleep(400000);
            redirect_self('初始化令牌不正确', 'err');
        }
        if (strlen($pw) < 10) {
            redirect_self('密码至少 10 位', 'err');
        }
        if ($pw !== $pw2) {
            redirect_self('两次输入的密码不一致', 'err');
        }
        $store['admin_hash'] = password_hash($pw, PASSWORD_DEFAULT);
        $store['setup_token'] = bin2hex(random_bytes(16));
        if (!store_save($store)) {
            redirect_self('写入配置失败，请检查 data/ 目录权限', 'err');
        }
        sec_rate_clear('admin_setup:' . $ip);
        session_regenerate_id(true);
        $_SESSION['adm_ok'] = true;
        $_SESSION['adm_login'] = time();
        $_SESSION['adm_last'] = time();
        redirect_self('管理员密码已设置，已自动登录');
    }
} elseif ($do === 'login') {
    $rl = sec_rate_limit('admin_login:' . $ip, 5, 900);
    if (!$rl[0]) {
        $min = max(1, (int) ceil($rl[2] / 60));
        redirect_self('密码错误次数过多，请 ' . $min . ' 分钟后再试', 'err');
    }
    $pw = isset($_POST['password']) ? (string) $_POST['password'] : '';
    if (password_verify($pw, (string) $store['admin_hash'])) {
        sec_rate_clear('admin_login:' . $ip);
        session_regenerate_id(true);
        $_SESSION['adm_ok'] = true;
        $_SESSION['adm_login'] = time();
        $_SESSION['adm_last'] = time();
        redirect_self('登录成功');
    }
    usleep(500000);
    redirect_self('密码错误', 'err');
} elseif ($do === 'logout') {
    sec_rate_clear('admin_login:' . $ip);
    $_SESSION = array();
    session_destroy();
    setcookie('BILIDIY_ADMIN', '', array(
        'expires' => time() - 3600,
        'path' => '/',
        'secure' => sec_is_https(),
        'httponly' => true,
        'samesite' => 'Strict',
    ));
    header('Location: ' . strtok($_SERVER['REQUEST_URI'], '?'));
    exit;
}

// ---------------- 后台操作 ----------------

if ($logged && $do !== '' && $do !== 'login' && $do !== 'logout') {
    if (!csrf_ok()) {
        redirect_self('CSRF 校验失败，请重试', 'err');
    }

    if ($do === 'save_announcement') {
        $store['announcement'] = array(
            'enabled' => isset($_POST['enabled']),
            'id' => 'a-' . time() . '-' . substr(bin2hex(random_bytes(3)), 0, 6),
            'title' => trim((string) (isset($_POST['title']) ? $_POST['title'] : '')),
            'content' => trim((string) (isset($_POST['content']) ? $_POST['content'] : '')),
            'updated_at' => time(),
        );
        $ok = store_save($store);
        redirect_self($ok ? '公告已保存' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'save_update') {
        $store['update'] = array(
            'enabled' => isset($_POST['enabled']),
            'version_code' => (int) (isset($_POST['version_code']) ? $_POST['version_code'] : 0),
            'version_name' => trim((string) (isset($_POST['version_name']) ? $_POST['version_name'] : '')),
            'url' => trim((string) (isset($_POST['url']) ? $_POST['url'] : '')),
            'changelog' => trim((string) (isset($_POST['changelog']) ? $_POST['changelog'] : '')),
            'force' => isset($_POST['force']),
        );
        $ok = store_save($store);
        redirect_self($ok ? '更新信息已保存' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'save_cookie') {
        $cookie = trim((string) (isset($_POST['cookie']) ? $_POST['cookie'] : ''));
        $cookie = str_replace(array("\r", "\n", "\t"), '', $cookie);
        if ($cookie === '') {
            redirect_self('未填写新 Cookie，已保持原值不变');
        }
        if (stripos($cookie, 'SESSDATA=') === false) {
            redirect_self('Cookie 必须包含 SESSDATA', 'err');
        }
        $store['cookie'] = $cookie;
        $ok = store_save($store);
        redirect_self($ok ? 'Cookie 已更新，App 下次启动生效' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'save_lock') {
        $store['lock'] = array(
            'locked' => isset($_POST['locked']),
            'title' => trim((string) (isset($_POST['title']) ? $_POST['title'] : '服务已暂停')),
            'message' => trim((string) (isset($_POST['message']) ? $_POST['message'] : '')),
        );
        $ok = store_save($store);
        redirect_self($ok ? ($store['lock']['locked'] ? '已开启远程锁定' : '已解除远程锁定') : '保存失败',
            $ok ? 'ok' : 'err');
    }

    if ($do === 'lock' || $do === 'unlock') {
        if (!isset($store['lock']) || !is_array($store['lock'])) {
            $store['lock'] = array('locked' => false, 'title' => '服务已暂停', 'message' => '');
        }
        $store['lock']['locked'] = ($do === 'lock');
        $ok = store_save($store);
        redirect_self($ok ? ($do === 'lock' ? '已锁定，App 将无法使用' : '已解除锁定') : '保存失败',
            $ok ? 'ok' : 'err');
    }

    if ($do === 'change_pass') {
        $rl = sec_rate_limit('admin_pass:' . $ip, 5, 900);
        if (!$rl[0]) {
            redirect_self('密码修改尝试过多，请稍后再试', 'err');
        }
        $cur = (string) (isset($_POST['current']) ? $_POST['current'] : '');
        $pw = (string) (isset($_POST['password']) ? $_POST['password'] : '');
        $pw2 = (string) (isset($_POST['password2']) ? $_POST['password2'] : '');
        if (!password_verify($cur, (string) $store['admin_hash'])) {
            usleep(500000);
            redirect_self('当前密码不正确', 'err');
        }
        if (strlen($pw) < 10) {
            redirect_self('新密码至少 10 位', 'err');
        }
        if ($pw !== $pw2) {
            redirect_self('两次输入的新密码不一致', 'err');
        }
        $store['admin_hash'] = password_hash($pw, PASSWORD_DEFAULT);
        $ok = store_save($store);
        if ($ok) {
            sec_rate_clear('admin_pass:' . $ip);
            session_regenerate_id(true);
            $_SESSION['adm_login'] = time();
            $_SESSION['adm_last'] = time();
        }
        redirect_self($ok ? '密码已修改' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'generate_codes') {
        $plans = member_plans();
        $customDays = (int) (isset($_POST['days']) ? $_POST['days'] : 0);
        if ($customDays > 0) {
            $plan = 'custom';
            $days = max(1, min(3650, $customDays));
            $label = '自定义 ' . $days . ' 天';
        } else {
            $plan = isset($_POST['plan']) ? (string) $_POST['plan'] : 'month';
            if (!isset($plans[$plan])) {
                redirect_self('套餐不存在', 'err');
            }
            $days = (int) $plans[$plan]['days'];
            $label = $plans[$plan]['label'];
        }
        $count = (int) (isset($_POST['count']) ? $_POST['count'] : 1);
        $count = max(1, min(20, $count));
        $codes = isset($store['codes']) && is_array($store['codes']) ? $store['codes'] : array();
        $prefix = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', (string) (isset($_POST['prefix']) ? $_POST['prefix'] : 'BD')));
        $prefix = substr($prefix, 0, 8);
        if ($prefix === '') $prefix = 'BD';
        $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
        $generated = array();
        for ($i = 0; $i < $count; $i++) {
            $code = '';
            do {
                $code = $prefix . '-';
                for ($g = 0; $g < 2; $g++) {
                    if ($g > 0) $code .= '-';
                    for ($j = 0; $j < 4; $j++) {
                        $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
                    }
                }
            } while (isset($codes[$code]));
            $codes[$code] = array(
                'prefix' => $prefix,
                'plan' => $plan,
                'days' => $days,
                'created_at' => time(),
                'code_expire_at' => time() + 365 * 86400,
                'used_by' => '',
                'used_at' => 0,
            );
            $generated[] = $code;
        }
        uasort($codes, static function ($a, $b) {
            return ((int) ($b['created_at'] ?? 0)) <=> ((int) ($a['created_at'] ?? 0));
        });
        if (count($codes) > 500) {
            $codes = array_slice($codes, 0, 500, true);
        }
        $store['codes'] = $codes;
        $ok = store_save($store);
        redirect_self($ok
            ? '已生成 ' . count($generated) . ' 个' . $label . '激活码：' . implode(' / ', $generated)
            : '激活码生成后写入失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'grant_member') {
        $plans = member_plans();
        $device = trim((string) (isset($_POST['device_id']) ? $_POST['device_id'] : ''));
        $plan = isset($_POST['plan']) ? (string) $_POST['plan'] : 'month';
        if (!preg_match('/^[A-Za-z0-9_-]{8,64}$/', $device)) {
            redirect_self('设备 ID 格式不正确', 'err');
        }
        if (!isset($plans[$plan])) {
            redirect_self('套餐不存在', 'err');
        }
        $members = isset($store['members']) && is_array($store['members']) ? $store['members'] : array();
        $old = member_info_admin($store, $device);
        $base = max(time(), (int) $old['expire_at']);
        $members[$device] = array(
            'expire_at' => $base + (int) $plans[$plan]['days'] * 86400,
            'plan' => $plan,
            'source' => 'admin',
            'updated_at' => time(),
        );
        $store['members'] = $members;
        $ok = store_save($store);
        redirect_self($ok
            ? '已为 ' . $device . ' 开通' . $plans[$plan]['label'] . '，有效期至 ' . date('Y-m-d H:i', $members[$device]['expire_at'])
            : '开通失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'revoke_member') {
        $device = trim((string) (isset($_POST['device_id']) ? $_POST['device_id'] : ''));
        if (!preg_match('/^[A-Za-z0-9_-]{8,64}$/', $device)) {
            redirect_self('设备 ID 格式不正确', 'err');
        }
        $members = isset($store['members']) && is_array($store['members']) ? $store['members'] : array();
        $members[$device] = array(
            'expire_at' => time() - 1,
            'plan' => '',
            'source' => 'admin',
            'updated_at' => time(),
        );
        $store['members'] = $members;
        $ok = store_save($store);
        redirect_self($ok ? '已取消该设备会员' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'invite_gen') {
        $count = max(1, min(20, (int) (isset($_POST['count']) ? $_POST['count'] : 1)));
        $days = max(1, min(365, (int) (isset($_POST['days']) ? $_POST['days'] : 7)));
        $uses = max(1, min(100, (int) (isset($_POST['uses']) ? $_POST['uses'] : 1)));
        $note = trim((string) (isset($_POST['note']) ? $_POST['note'] : ''));
        $note = function_exists('mb_substr') ? mb_substr($note, 0, 40, 'UTF-8') : substr($note, 0, 40);
        $prefix = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', (string) (isset($_POST['prefix']) ? $_POST['prefix'] : 'INV')));
        $prefix = substr($prefix, 0, 8);
        if ($prefix === '') $prefix = 'INV';
        $invites = isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array();
        $created = array();
        for ($i = 0; $i < $count; $i++) {
            $code = make_invite_code($prefix);
            $try = 0;
            while (isset($invites[$code]) && $try++ < 20) $code = make_invite_code($prefix);
            $invites[$code] = array(
                'code' => $code,
                'prefix' => $prefix,
                'created_at' => time(),
                'expire_at' => time() + $days * 86400,
                'max_uses' => $uses,
                'uses' => 0,
                'enabled' => true,
                'note' => $note,
                'issuer' => 'admin',
                'grant_quota' => 5,
                'used_by' => array(),
            );
            $created[] = $code;
        }
        $store['invites'] = $invites;
        $ok = store_save($store);
        redirect_self($ok
            ? '已生成 ' . count($created) . ' 个邀请码：' . implode('  ', $created)
            : '邀请码生成失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'invite_disable') {
        $code = strtoupper(trim((string) (isset($_POST['invite']) ? $_POST['invite'] : '')));
        $invites = isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array();
        if (!isset($invites[$code])) redirect_self('邀请码不存在', 'err');
        $invites[$code]['enabled'] = false;
        $store['invites'] = $invites;
        $ok = store_save($store);
        redirect_self($ok ? '邀请码已停用' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'delete_code') {
        $code = strtoupper(trim((string) (isset($_POST['code']) ? $_POST['code'] : '')));
        $codes = isset($store['codes']) && is_array($store['codes']) ? $store['codes'] : array();
        if (!isset($codes[$code])) redirect_self('激活码不存在', 'err');
        unset($codes[$code]);
        $store['codes'] = $codes;
        $ok = store_save($store);
        redirect_self($ok ? '激活码已删除' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'invite_delete') {
        $code = strtoupper(trim((string) (isset($_POST['invite']) ? $_POST['invite'] : '')));
        $invites = isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array();
        if (!isset($invites[$code])) redirect_self('邀请码不存在', 'err');
        unset($invites[$code]);
        $store['invites'] = $invites;
        $ok = store_save($store);
        redirect_self($ok ? '邀请码已删除' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'account_ban' || $do === 'account_unban') {
        $username = strtolower(trim((string) (isset($_POST['username']) ? $_POST['username'] : '')));
        $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
        if (!isset($accounts[$username]) || !is_array($accounts[$username])) {
            redirect_self('账号不存在', 'err');
        }
        $accounts[$username]['status'] = $do === 'account_ban' ? 'disabled' : 'active';
        if ($do === 'account_ban') {
            $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
            foreach ($sessions as $t => $s) {
                if (is_array($s) && strtolower((string) ($s['username'] ?? '')) === $username) unset($sessions[$t]);
            }
            $store['sessions'] = $sessions;
        }
        $store['accounts'] = $accounts;
        $ok = store_save($store);
        redirect_self($ok
            ? ($do === 'account_ban' ? '账号已停用并清除会话' : '账号已恢复')
            : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'account_clear_sessions') {
        $username = strtolower(trim((string) (isset($_POST['username']) ? $_POST['username'] : '')));
        $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
        $n = 0;
        foreach ($sessions as $t => $s) {
            if (is_array($s) && strtolower((string) ($s['username'] ?? '')) === $username) {
                unset($sessions[$t]);
                $n++;
            }
        }
        $store['sessions'] = $sessions;
        $ok = store_save($store);
        redirect_self($ok ? '已清除 ' . $n . ' 个会话' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'account_delete') {
        $username = strtolower(trim((string) (isset($_POST['username']) ? $_POST['username'] : '')));
        $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
        if (!isset($accounts[$username])) {
            redirect_self('账号不存在', 'err');
        }
        unset($accounts[$username]);
        $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
        foreach ($sessions as $t => $s) {
            if (is_array($s) && strtolower((string) ($s['username'] ?? '')) === $username) unset($sessions[$t]);
        }
        $store['accounts'] = $accounts;
        $store['sessions'] = $sessions;
        $ok = store_save($store);
        redirect_self($ok ? '账号已删除' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'clear_history') {
        $device = trim((string) (isset($_POST['device_id']) ? $_POST['device_id'] : ''));
        if (!preg_match('/^[A-Za-z0-9_-]{8,64}$/', $device)) {
            redirect_self('设备 ID 格式不正确', 'err');
        }
        $ok = store_history_clear($device);
        redirect_self($ok ? '已清空该设备的云端观看记录' : '保存失败', $ok ? 'ok' : 'err');
    }

    if ($do === 'upload_apk') {
        $f = isset($_FILES['apk']) ? $_FILES['apk'] : null;
        if (!$f || !isset($f['error'])) {
            redirect_self('没有收到文件', 'err');
        }
        $code = (int) $f['error'];
        if ($code !== UPLOAD_ERR_OK) {
            $msg = array(
                UPLOAD_ERR_INI_SIZE => '文件超过 PHP upload_max_filesize（当前 ' . ini_get('upload_max_filesize') . '）',
                UPLOAD_ERR_FORM_SIZE => '文件超过表单上限',
                UPLOAD_ERR_PARTIAL => '上传中断',
                UPLOAD_ERR_NO_FILE => '没有选择文件',
                UPLOAD_ERR_NO_TMP_DIR => '缺少临时目录',
                UPLOAD_ERR_CANT_WRITE => '写入临时文件失败',
                UPLOAD_ERR_EXTENSION => '被扩展阻止',
            );
            redirect_self(isset($msg[$code]) ? $msg[$code] . '；请改用 FTP 上传后填下载地址' : '上传失败', 'err');
        }
        $name = preg_replace('/[^A-Za-z0-9._-]/', '_', (string) $f['name']);
        if (!preg_match('/\.apk$/i', $name)) {
            redirect_self('只允许上传 .apk 文件', 'err');
        }
        if (!is_dir(DOWNLOAD_DIR) && !@mkdir(DOWNLOAD_DIR, 0755, true)) {
            redirect_self('创建 download/ 目录失败', 'err');
        }
        $dest = DOWNLOAD_DIR . '/' . $name;
        if (!@move_uploaded_file($f['tmp_name'], $dest)) {
            redirect_self('保存 APK 失败（检查 download/ 权限）', 'err');
        }
        @chmod($dest, 0644);
        $store['update'] = array(
            'enabled' => true,
            'version_code' => (int) (isset($_POST['version_code']) ? $_POST['version_code'] : 0),
            'version_name' => trim((string) (isset($_POST['version_name']) ? $_POST['version_name'] : '')),
            'url' => base_url() . '/download/' . $name,
            'changelog' => trim((string) (isset($_POST['changelog']) ? $_POST['changelog'] : '')),
            'force' => isset($_POST['force']),
        );
        $ok = store_save($store);
        redirect_self($ok ? 'APK 已上传并同步更新信息' : 'APK 已上传但配置写入失败', $ok ? 'ok' : 'err');
    }

    redirect_self('未知操作', 'err');
}

// ---------------- 页面 ----------------

$ann = isset($store['announcement']) && is_array($store['announcement']) ? $store['announcement'] : array();
$upd = isset($store['update']) && is_array($store['update']) ? $store['update'] : array();
$lock = isset($store['lock']) && is_array($store['lock']) ? $store['lock'] : array();
$profiles = isset($store['profiles']) && is_array($store['profiles']) ? $store['profiles'] : array();
uasort($profiles, static function ($a, $b) {
    return ((int) ($b['at'] ?? 0)) <=> ((int) ($a['at'] ?? 0));
});
$members = isset($store['members']) && is_array($store['members']) ? $store['members'] : array();
$codes = isset($store['codes']) && is_array($store['codes']) ? $store['codes'] : array();
uasort($codes, static function ($a, $b) {
    return ((int) ($b['created_at'] ?? 0)) <=> ((int) ($a['created_at'] ?? 0));
});
$accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
uasort($accounts, static function ($a, $b) {
    $la = (int) ($a['last_login'] ?? 0) ?: (int) ($a['created_at'] ?? 0);
    $lb = (int) ($b['last_login'] ?? 0) ?: (int) ($b['created_at'] ?? 0);
    return $lb <=> $la;
});
$invites = isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array();
uasort($invites, static function ($a, $b) {
    return ((int) ($b['created_at'] ?? 0)) <=> ((int) ($a['created_at'] ?? 0));
});
$sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
$activeAccounts = 0;
foreach ($accounts as $a) {
    if (is_array($a) && (string) ($a['status'] ?? 'active') === 'active') $activeAccounts++;
}
$activeInvites = 0;
foreach ($invites as $inv) {
    if (is_array($inv) && !empty($inv['enabled']) && (int) ($inv['expire_at'] ?? 0) > time() && (int) ($inv['uses'] ?? 0) < (int) ($inv['max_uses'] ?? 1)) $activeInvites++;
}
$history = store_history_all();
$catLabels = array('video' => '视频', 'anime' => '番剧', 'movie' => '电影');

// 设备 -> 账户，后台按账户汇总而不是按设备堆叠
$deviceAccount = array();
foreach ($accounts as $u => $a) {
    if (!is_array($a)) continue;
    $devices = isset($a['devices']) && is_array($a['devices']) ? $a['devices'] : array();
    $nick = trim((string) ($a['nickname'] ?? ''));
    $label = $nick !== '' ? $nick . ' · ' . $u : $u;
    foreach ($devices as $dev => $t) {
        $deviceAccount[(string) $dev] = array('username' => $u, 'label' => $label);
    }
}

$historyGroups = array();
foreach ($history as $dev => $items) {
    if (!is_array($items)) continue;
    $dev = (string) $dev;
    $owner = isset($deviceAccount[$dev]) ? $deviceAccount[$dev] : null;
    $gkey = $owner ? 'u:' . $owner['username'] : 'd:' . $dev;
    if (!isset($historyGroups[$gkey])) {
        $historyGroups[$gkey] = array(
            'key' => $gkey,
            'label' => $owner ? $owner['label'] : '设备 ' . $dev,
            'devices' => array(),
            'items' => array(),
            'last' => 0,
        );
    }
    $historyGroups[$gkey]['devices'][$dev] = true;
    foreach ($items as $it) {
        if (!is_array($it)) continue;
        $cat = (string) ($it['cat'] ?? '');
        if (!isset($catLabels[$cat])) {
            $cat = ((string) ($it['kind'] ?? 'video')) === 'pgc' ? 'movie' : 'video';
        }
        $it['cat'] = $cat;
        $it['device_id'] = $dev;
        $it['account'] = $historyGroups[$gkey]['label'];
        // 同一账户下相同内容只保留最新一条，避免一次同步产生多条重复
        $bvid = (string) ($it['bvid'] ?? '');
        $ident = $cat . '|' . ($bvid !== '' ? $bvid . '|' . (int) ($it['ep'] ?? 0) : (string) ($it['title'] ?? ''));
        $old = isset($historyGroups[$gkey]['items'][$ident]) ? $historyGroups[$gkey]['items'][$ident] : null;
        if ($old !== null) {
            $newAt = (int) ($it['at'] ?? 0);
            $oldAt = (int) ($old['at'] ?? 0);
            if ($newAt > $oldAt || ($newAt === $oldAt && (int) ($it['pos'] ?? 0) > (int) ($old['pos'] ?? 0))) {
                $historyGroups[$gkey]['items'][$ident] = $it;
            }
        } else {
            $historyGroups[$gkey]['items'][$ident] = $it;
        }
        $historyGroups[$gkey]['last'] = max((int) $historyGroups[$gkey]['last'], (int) ($it['at'] ?? 0));
    }
}
foreach ($historyGroups as $gk => $g) {
    uasort($historyGroups[$gk]['items'], static function ($a, $b) {
        return ((int) ($b['at'] ?? 0)) <=> ((int) ($a['at'] ?? 0));
    });
}
uasort($historyGroups, static function ($a, $b) {
    return ((int) ($b['last'] ?? 0)) <=> ((int) ($a['last'] ?? 0));
});

$catCounts = array('all' => 0, 'video' => 0, 'anime' => 0, 'movie' => 0);
foreach ($historyGroups as $g) {
    foreach ($g['items'] as $it) {
        $catCounts['all']++;
        if (isset($catCounts[$it['cat']])) $catCounts[$it['cat']]++;
    }
}
$historyTotal = $catCounts['all'];

$histFilter = isset($_GET['hist']) ? (string) $_GET['hist'] : 'all';
if (!isset($catCounts[$histFilter])) $histFilter = 'all';
$histAccount = isset($_GET['acc']) ? (string) $_GET['acc'] : '';
if ($histAccount !== '' && !isset($historyGroups[$histAccount])) $histAccount = '';

$historyGroupsFiltered = array();
$historyList = array();
foreach ($historyGroups as $gkey => $g) {
    if ($histAccount !== '' && $gkey !== $histAccount) continue;
    $items = array();
    foreach ($g['items'] as $it) {
        if ($histFilter !== 'all' && (string) ($it['cat'] ?? '') !== $histFilter) continue;
        $items[] = $it;
    }
    if (!$items) continue;
    $historyGroupsFiltered[$gkey] = array(
        'key' => $gkey,
        'label' => $g['label'],
        'devices' => array_keys($g['devices']),
        'items' => $items,
    );
    foreach ($items as $it) $historyList[] = $it;
}

if (isset($_GET['ajax']) && $_GET['ajax'] === 'history') {
    header('Content-Type: application/json; charset=utf-8');
    $payload = array(
        'ok' => true,
        'filter' => $histFilter,
        'account' => $histAccount,
        'counts' => $catCounts,
        'total' => $historyTotal,
        'groups' => array(),
    );
    foreach ($historyGroupsFiltered as $g) {
        $items = array();
        foreach ($g['items'] as $it) {
            $items[] = array(
                'cat' => (string) ($it['cat'] ?? 'video'),
                'title' => (string) ($it['title'] ?? ''),
                'pos' => (int) ($it['pos'] ?? 0),
                'dur' => (int) ($it['dur'] ?? 0),
                'at' => (int) ($it['at'] ?? 0),
                'device_id' => (string) ($it['device_id'] ?? ''),
            );
        }
        $payload['groups'][] = array(
            'key' => (string) $g['key'],
            'label' => (string) $g['label'],
            'devices' => $g['devices'],
            'items' => $items,
        );
    }
    echo json_encode($payload, JSON_UNESCAPED_UNICODE);
    exit;
}

$plans = member_plans();
$activeMembers = 0;
foreach ($members as $m) {
    if (is_array($m) && (int) ($m['expire_at'] ?? 0) > time()) $activeMembers++;
}
$apiUrl = base_url() . '/api.php';
$cssVer = '3.9.10';
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>BiliDiy 管理后台</title>
<style>
  :root{--pink:#FB7299;--pink-deep:#D94A77;--pink-soft:#FFF1F5;--ink:#1F2329;--muted:#8A8F99;--line:#E7E9EE;--bg:#F5F6F9;--card:#FFFFFF;--shadow:0 10px 30px rgba(31,35,41,.06)}
  *{box-sizing:border-box}
  html{scroll-behavior:smooth}
  body{margin:0;background:radial-gradient(900px 360px at 18% -100px,#FFE9F0 0,rgba(245,246,249,0) 68%),var(--bg);color:var(--ink);font:14px/1.65 -apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
  .wrap{max-width:1180px;margin:0 auto;padding:22px 20px 56px}
  .top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}
  .brand{display:flex;align-items:center;gap:11px;min-width:0}
  .logo{width:40px;height:40px;border-radius:12px;background:linear-gradient(145deg,#FF8FB0,var(--pink));color:#fff;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:18px;flex:0 0 auto;box-shadow:0 6px 16px rgba(251,114,153,.25)}
  h1{font-size:18px;margin:0;letter-spacing:.2px}
  .sub{color:var(--muted);font-size:12px;margin-top:1px}
  .login{max-width:430px;margin:9vh auto 0}
  .login .card{padding:28px 28px 24px;box-shadow:0 20px 60px rgba(31,35,41,.09)}
  .login .top{margin-bottom:8px}
  .app{display:grid;grid-template-columns:224px minmax(0,1fr);gap:24px;align-items:start}
  .side{position:sticky;top:20px;background:rgba(255,255,255,.9);border:1px solid var(--line);border-radius:18px;padding:18px 14px 14px;box-shadow:0 8px 26px rgba(31,35,41,.05);backdrop-filter:saturate(180%) blur(12px)}
  .side-brand{display:flex;align-items:center;gap:10px;padding:0 4px 16px;border-bottom:1px solid var(--line)}
  .side-name{font-size:15px;font-weight:700;line-height:1.3}
  .side-sub{font-size:11.5px;color:var(--muted);margin-top:1px}
  .side-nav{margin-top:14px}
  .nav-label{font-size:10.5px;letter-spacing:.12em;color:#B2B6BF;padding:12px 10px 6px;text-transform:uppercase}
  .nav-btn{display:flex;align-items:center;gap:9px;width:100%;padding:9px 10px;border:0;border-radius:10px;background:transparent;color:var(--ink);font:inherit;font-size:13px;text-align:left;cursor:pointer;transition:background .15s ease,color .15s ease}
  .nav-btn:hover{background:#F8F9FB}
  .nav-btn.on{background:var(--pink-soft);color:var(--pink-deep);font-weight:600}
  .nav-btn.on::before{content:"";width:3px;height:14px;border-radius:2px;background:var(--pink);flex:0 0 auto}
  .nav-btn:not(.on)::before{content:"";width:3px;height:14px;border-radius:2px;background:transparent;flex:0 0 auto}
  .nav-btn span{margin-left:auto;font-size:11px;color:var(--muted);font-weight:400}
  .side-foot{margin-top:16px;padding:13px 10px 4px;border-top:1px solid var(--line)}
  .side-foot-title{font-size:11px;color:var(--muted)}
  .side-foot .mono{display:block;margin-top:2px;color:var(--ink);font-size:12px}
  .side-foot-note{font-size:11px;color:#B2B6BF;margin-top:8px;line-height:1.55}
  .main{min-width:0}
  .topbar{position:sticky;top:0;z-index:20;display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:50px;margin:-2px 0 16px;padding:10px 2px;background:rgba(245,246,249,.9);backdrop-filter:saturate(180%) blur(12px)}
  .crumb{font-size:12.5px;color:var(--muted);min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .crumb i{font-style:normal;color:#C3C7CF;margin:0 7px}
  .crumb b{color:var(--ink);font-weight:600}
  .top-actions{display:flex;align-items:center;gap:8px;flex:0 0 auto}
  .status-chip{display:inline-flex;align-items:center;gap:6px;height:28px;padding:0 10px;border:1px solid var(--line);border-radius:999px;background:rgba(255,255,255,.8);font-size:11.5px;color:var(--muted);white-space:nowrap}
  .status-chip.good{background:#F0FAF3;border-color:#D6EFDD;color:#258A45}
  .status-chip.bad{background:#FFF4F4;border-color:#FFD9D9;color:#C0392B}
  .status-chip.plain{background:transparent}
  .status-chip .dot{width:6px;height:6px;border-radius:50%;background:#3BB15B;display:inline-block}
  .status-chip.bad .dot{background:#E05353}
  .page-head{display:flex;align-items:flex-end;justify-content:space-between;gap:12px;margin:2px 0 14px}
  .page-head h2{font-size:23px;line-height:1.2;margin:0;letter-spacing:-.3px}
  .page-head p{margin:4px 0 0;font-size:12.5px;color:var(--muted)}
  .cards{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;align-items:start}
  .cards.one{grid-template-columns:minmax(0,1fr)}
  .card.m-hide{display:none!important}
  .card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:22px 22px 20px;box-shadow:0 2px 8px rgba(31,35,41,.035),0 14px 34px rgba(31,35,41,.025);min-width:0}
  .card.wide{grid-column:1/-1}
  .cards.one .card h2{display:none}
  .card h2{font-size:16px;margin:0 0 6px;display:flex;align-items:center;gap:8px;letter-spacing:-.1px}
  .card h2:before{content:"";width:3px;height:14px;background:var(--pink);border-radius:2px;flex:0 0 auto}
  .desc{color:var(--muted);font-size:12.5px;margin:2px 0 16px;line-height:1.6}
  label{display:block;font-size:12px;color:#6F747D;margin:12px 0 6px;font-weight:500}
  input[type=text],input[type=password],input[type=number],textarea,select{width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:10px;background:#FBFBFD;font:inherit;color:var(--ink);outline:none;transition:border-color .15s ease,box-shadow .15s ease,background .15s ease}
  input:focus,textarea:focus,select:focus{border-color:#FFB3C8;background:#fff;box-shadow:0 0 0 3px rgba(251,114,153,.1)}
  textarea{min-height:100px;resize:vertical}
  .row{display:flex;gap:12px;flex-wrap:wrap}
  .row>*{flex:1;min-width:150px}
  .switch{display:flex;align-items:center;gap:9px;margin:12px 0 6px}
  .switch input{width:18px;height:18px;accent-color:var(--pink);flex:0 0 auto}
  .switch label{margin:0;color:var(--ink);font-size:12.5px}
  .btn{display:inline-flex;align-items:center;justify-content:center;height:38px;padding:0 18px;border:0;border-radius:10px;background:var(--pink);color:#fff;font-weight:600;cursor:pointer;font:inherit;font-size:13px;transition:filter .15s ease,transform .15s ease}
  .btn:hover{filter:brightness(.96)}
  .btn:active{transform:translateY(1px)}
  .btn.ghost{background:#fff;color:var(--pink-deep);border:1px solid #FFD3E0}
  .btn.danger{background:#FF4D4F}
  .btn.small{height:30px;padding:0 12px;font-size:12px;border-radius:8px}
  .btn-line{display:flex;gap:10px;flex-wrap:wrap;margin-top:18px}
  .pill{display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;font-size:12px;background:var(--pink-soft);color:var(--pink-deep)}
  .pill.off{background:#F2F3F5;color:var(--muted)}
  .pill.warn{background:#FFF4E5;color:#C46A00}
  .pill.cat{background:#EEF4FF;color:#2F54EB}
  .flash{padding:11px 14px;border-radius:12px;margin:0 0 16px;font-size:13px}
  .flash.ok{background:#EAF8EE;color:#1D7A3B;border:1px solid #CBEBDO}
  .flash.err{background:#FFF0F0;color:#C0392B;border:1px solid #FFD4D4}
  code,.mono{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;word-break:break-all}
  .kv{display:grid;grid-template-columns:92px 1fr;gap:9px 14px;font-size:13px;margin-top:6px}
  .kv .k{color:var(--muted)}
  .foot{color:#B2B6BF;font-size:11.5px;text-align:center;margin-top:26px}
  details{border:1px dashed #DFE1E7;border-radius:12px;padding:11px 13px;margin-top:16px;background:#FCFCFD}
  summary{cursor:pointer;color:#717680;font-size:12px;font-weight:500}
  .badge{font-size:11px;color:#fff;background:var(--pink);padding:1px 7px;border-radius:6px}
  .table-wrap{width:100%;overflow-x:auto;margin-top:8px}
  .tbl{width:100%;border-collapse:collapse;font-size:12px}
  .tbl th,.tbl td{padding:9px 8px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
  .tbl th{color:var(--muted);font-weight:500;white-space:nowrap}
  .tbl .code{white-space:nowrap;background:var(--pink-soft);color:var(--pink-deep);padding:2px 6px;border-radius:6px}
  .hint{font-size:12px;color:var(--muted);margin:8px 0 0;line-height:1.6}
  .tabs{display:flex;gap:8px;overflow-x:auto;padding:2px 0 10px;scrollbar-width:none}
  .tabs::-webkit-scrollbar{display:none}
  .tabs a{flex:0 0 auto;text-decoration:none;font-size:12px;color:var(--muted);border:1px solid var(--line);background:#FBFBFD;border-radius:999px;padding:5px 11px}
  .tabs a.on{background:var(--pink-soft);color:var(--pink-deep);border-color:#FFD6E3;font-weight:600}
  .hist-tools{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:0 0 10px}
  .hist-tools select{max-width:240px;padding:7px 10px;border:1px solid var(--line);border-radius:9px;background:#fff;font:inherit;font-size:12px}
  .hist-tools .hint{margin:0}
  .hist-group td{background:var(--pink-soft);color:var(--pink-deep);font-weight:700;border-bottom:1px solid #FFD6E3}
  .pager{display:flex;gap:6px;flex-wrap:wrap;margin-top:12px}
  .pager a{min-width:30px;height:30px;display:inline-flex;align-items:center;justify-content:center;text-decoration:none;font-size:12px;border:1px solid var(--line);border-radius:9px;color:var(--muted);background:#fff}
  .pager a.on{background:var(--pink-soft);border-color:#FFD6E3;color:var(--pink-deep);font-weight:600}
  .mtabbar{display:none}
  .msubnav{display:none}
  .msubnav[hidden]{display:none!important}
  @media (max-width:880px){
    .wrap{padding:10px 12px 28px}
    .app{display:block}
    .side{display:none}
    .topbar{position:sticky;top:0;z-index:20;min-height:50px;margin:0 -12px 0;padding:9px 12px 8px;background:rgba(245,246,249,.95);border-bottom:1px solid rgba(231,233,238,.55);backdrop-filter:saturate(180%) blur(12px)}
    .topbar .status-chip.plain{display:none}
    .topbar .status-chip{display:none}
    .top-actions .btn{height:32px;padding:0 12px;font-size:12px}
    .page-head{margin:2px 0 10px}
    .page-head h2{font-size:20px}
    .page-head p{font-size:12px}
    .msubnav{display:flex;gap:7px;overflow-x:auto;scrollbar-width:none;margin:0 0 11px;padding:1px 0}
    .msubnav::-webkit-scrollbar{display:none}
    .msubnav button{flex:0 0 auto;min-height:32px;border:1px solid var(--line);background:#fff;color:#717680;font:inherit;font-size:12px;line-height:1;padding:0 13px;border-radius:999px;white-space:nowrap}
    .msubnav button.on{background:#fff;color:var(--pink-deep);border-color:#FFB9CF;font-weight:600;box-shadow:0 0 0 3px rgba(251,114,153,.08)}
    .card.sub-hide{display:none!important}
    .cards{display:block}
    .card{margin-bottom:12px;padding:17px 15px 16px;border-radius:16px}
    .card[data-msec].m-hide{display:none!important}
    .row{display:block}
    .row>*{min-width:0;margin-bottom:10px}
    .row>*:last-child{margin-bottom:0}
    .btn-line{gap:8px}
    .btn-line .btn{flex:1;min-height:42px}
    .kv{grid-template-columns:86px 1fr;gap:8px 10px;font-size:12px}
    input[type=text],input[type=password],input[type=number],textarea,select{min-height:44px;padding:11px 12px}
    textarea{min-height:124px}
    .tbl{min-width:0}
    .table-wrap{max-height:52vh;overflow:auto;-webkit-overflow-scrolling:touch}
    .hist-table tr:first-child{display:none}
    .hist-table tr{display:block;border:1px solid var(--line);border-radius:12px;padding:10px;margin-bottom:8px;background:#FBFBFD}
    .hist-table td{display:block;border:0;padding:2px 0}
    .hist-table td:nth-child(2){margin:5px 0}
    .flash{margin:10px 0 8px}
    .login{margin-top:5vh}
    .login .card{padding:22px 20px}
    .mtabbar{display:flex;position:sticky;top:50px;z-index:19;gap:7px;overflow-x:auto;scrollbar-width:none;margin:0 -12px 10px;padding:9px 12px 10px;background:rgba(245,246,249,.95);border-bottom:1px solid rgba(231,233,238,.55);backdrop-filter:saturate(180%) blur(12px)}
    .mtabbar::-webkit-scrollbar{display:none}
    .mtabbar button{flex:0 0 auto;min-height:32px;border:1px solid var(--line);background:#fff;color:var(--muted);font:inherit;font-size:12px;line-height:1;padding:0 13px;border-radius:999px;cursor:pointer;white-space:nowrap}
    .mtabbar button.on{color:var(--pink-deep);background:var(--pink-soft);border-color:#FFD6E3;font-weight:600}
    .foot{margin-top:18px}
  }
</style>
</head>
<body>
<div class="wrap">

<?php if ($isSetup): ?>
  <div class="login">
    <div class="top"><div class="brand"><div class="logo">B</div><div><h1>BiliDiy 初始化</h1><div class="sub">首次使用，请设置管理员密码</div></div></div></div>
    <?php if ($flash): ?><div class="flash <?= h($flash['t']) ?>"><?= h($flash['m']) ?></div><?php endif; ?>
    <div class="card">
      <h2>设置管理员密码</h2>
      <p class="desc">需要部署时生成的 setup_token。设置完成后该 token 自动失效。</p>
      <form method="post">
        <input type="hidden" name="do" value="setup">
        <label>setup_token</label>
        <input type="text" name="token" placeholder="部署时生成的初始化令牌" autocomplete="off">
        <label>新密码（至少 8 位）</label>
        <input type="password" name="password" autocomplete="new-password">
        <label>再次输入</label>
        <input type="password" name="password2" autocomplete="new-password">
        <div class="btn-line"><button class="btn" type="submit">完成初始化</button></div>
      </form>
    </div>
  </div>
<?php elseif (!$logged): ?>
  <div class="login">
    <div class="top"><div class="brand"><div class="logo">B</div><div><h1>BiliDiy 管理后台</h1><div class="sub">云公告 · 云更新 · 云端 Cookie · 远程锁定</div></div></div></div>
    <?php if ($flash): ?><div class="flash <?= h($flash['t']) ?>"><?= h($flash['m']) ?></div><?php endif; ?>
    <div class="card">
      <h2>管理员登录</h2>
      <p class="desc">请输入后台密码。</p>
      <form method="post">
        <input type="hidden" name="do" value="login">
        <label>密码</label>
        <input type="password" name="password" autocomplete="current-password" autofocus>
        <div class="btn-line"><button class="btn" type="submit">登录</button></div>
      </form>
    </div>
  </div>
<?php else: ?>
  <div class="app">
    <aside class="side">
      <div class="side-brand">
        <div class="logo">B</div>
        <div><div class="side-name">BiliDiy</div><div class="side-sub">云端管理后台</div></div>
      </div>
      <nav class="side-nav" id="sideNav" aria-label="后台导航">
        <div class="nav-label">内容</div>
        <button type="button" class="nav-btn on" data-sec="home">概览</button>
        <button type="button" class="nav-btn" data-sec="announce">云公告</button>
        <button type="button" class="nav-btn" data-sec="update">云更新</button>
        <div class="nav-label">用户与数据</div>
        <button type="button" class="nav-btn" data-sec="member">会员与激活码</button>
        <button type="button" class="nav-btn" data-sec="accounts">账户与邀请码</button>
        <button type="button" class="nav-btn" data-sec="history">观看记录</button>
        <div class="nav-label">系统</div>
        <button type="button" class="nav-btn" data-sec="system">系统设置</button>
      </nav>
      <div class="side-foot">
        <div class="side-foot-title">控制台版本</div>
        <span class="mono">v<?= h($cssVer) ?></span>
        <div class="side-foot-note">修改保存后，App 重新打开即生效。</div>
      </div>
    </aside>

    <main class="main">
      <div class="topbar">
        <div class="crumb">控制台<i>/</i><b id="pageCrumb">概览</b></div>
        <div class="top-actions">
          <?php if (!empty($lock['locked'])): ?>
            <span class="status-chip bad"><span class="dot"></span>已锁定</span>
          <?php else: ?>
            <span class="status-chip good"><span class="dot"></span>云端在线</span>
          <?php endif; ?>
          <span class="status-chip plain">v<?= h($cssVer) ?></span>
          <form method="post" style="margin:0">
            <input type="hidden" name="do" value="logout">
            <button class="btn ghost small" type="submit">退出</button>
          </form>
        </div>
      </div>

      <nav class="mtabbar" id="mtabbar">
        <button type="button" data-mtab="home" class="on">概览</button>
        <button type="button" data-mtab="announce">公告</button>
        <button type="button" data-mtab="update">更新</button>
        <button type="button" data-mtab="member">会员</button>
        <button type="button" data-mtab="accounts">账户</button>
        <button type="button" data-mtab="history">记录</button>
        <button type="button" data-mtab="system">系统</button>
      </nav>

      <div id="adminFlashWrap"><?php if ($flash): ?><div class="flash <?= h($flash['t']) ?>"><?= h($flash['m']) ?></div><?php endif; ?></div>

      <div class="page-head">
        <div><h2 id="pageTitle">概览</h2><p id="pageDesc">云端运行状态与常用操作</p></div>
      </div>

      <div class="msubnav" id="cardSubnav" aria-label="分区子页面"></div>

      <div class="cards" id="adminCards">
  <div class="card" id="status" data-msec="home">
    <h2>运行状态</h2>
    <p class="desc">这三个开关决定 App 打开时看到什么。</p>
    <div class="kv">
      <div class="k">远程锁定</div>
      <div>
        <?php if (!empty($lock['locked'])): ?><span class="pill warn">已锁定 · App 不可用</span>
        <?php else: ?><span class="pill off">未锁定</span><?php endif; ?>
      </div>
      <div class="k">云公告</div>
      <div><?= !empty($ann['enabled']) ? '<span class="pill">已开启</span>' : '<span class="pill off">已关闭</span>' ?>
        <span class="mono" style="color:#9499A0"> <?= h(isset($ann['title']) ? $ann['title'] : '') ?></span></div>
      <div class="k">云更新</div>
      <div><?= !empty($upd['enabled']) ? '<span class="pill">已开启</span>' : '<span class="pill off">已关闭</span>' ?>
        <span class="mono" style="color:#9499A0"> v<?= h(isset($upd['version_name']) ? $upd['version_name'] : '') ?> (<?= (int) (isset($upd['version_code']) ? $upd['version_code'] : 0) ?>)</span></div>
      <div class="k">Cookie</div>
      <div><?= empty($store['cookie']) ? '<span class="pill off">空</span>' : '<span class="pill">已配置 · ' . strlen((string) $store['cookie']) . ' 字符</span>' ?></div>
      <div class="k">设备账户</div>
      <div><span class="pill<?= $profiles ? '' : ' off' ?>"><?= count($profiles) ?> 台设备</span></div>
      <div class="k">登录账户</div>
      <div><span class="pill<?= $activeAccounts ? '' : ' off' ?>"><?= $activeAccounts ?> 个有效</span>
        <span class="mono" style="color:#9499A0"> · <?= count($accounts) ?> 个注册 · <?= count($sessions) ?> 个会话</span></div>
      <div class="k">注册邀请码</div>
      <div><span class="pill<?= $activeInvites ? '' : ' off' ?>"><?= $activeInvites ?> 个可用</span>
        <span class="mono" style="color:#9499A0"> · 共 <?= count($invites) ?> 个</span></div>
      <div class="k">会员</div>
      <div><span class="pill<?= $activeMembers ? '' : ' off' ?>"><?= $activeMembers ?> 个有效会员</span>
        <span class="mono" style="color:#9499A0"> · <?= count($members) ?> 条开通记录</span></div>
      <div class="k">激活码</div>
      <div><span class="pill<?= $codes ? '' : ' off' ?>"><?= count($codes) ?> 个</span>
        <span class="mono" style="color:#9499A0"> · <?= $historyTotal ?> 条云端观看记录</span></div>
      <div class="k">API 地址</div>
      <div class="mono"><?= h($apiUrl) ?></div>
      <div class="k">客户端 Key</div>
      <div class="mono"><?= h((string) $store['client_key']) ?></div>
    </div>
    <div class="btn-line">
      <form method="post" style="margin:0"><input type="hidden" name="do" value="lock"><input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>"><button class="btn danger" type="submit">一键锁定 App</button></form>
      <form method="post" style="margin:0"><input type="hidden" name="do" value="unlock"><input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>"><button class="btn ghost" type="submit">解除锁定</button></form>
    </div>
  </div>

  <div class="card" id="announce" data-msec="announce">
    <h2>云公告</h2>
    <p class="desc">App 启动后弹窗提示一次；首页也会显示一条可关闭的公告条。</p>
    <form method="post">
      <input type="hidden" name="do" value="save_announcement">
      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
      <div class="switch"><input type="checkbox" id="ann_en" name="enabled" <?= !empty($ann['enabled']) ? 'checked' : '' ?>><label for="ann_en" style="margin:0">启用公告</label></div>
      <label>标题</label>
      <input type="text" name="title" value="<?= h(isset($ann['title']) ? $ann['title'] : '') ?>" placeholder="例如：服务器维护通知">
      <label>正文</label>
      <textarea name="content" placeholder="支持换行"><?= h(isset($ann['content']) ? $ann['content'] : '') ?></textarea>
      <div class="btn-line"><button class="btn" type="submit">保存公告</button></div>
    </form>
  </div>

  <div class="card" id="update" data-msec="update">
    <h2>云更新</h2>
    <p class="desc">版本号大于当前 App 的 versionCode 时提示更新；勾选「强制」则不可跳过。</p>
    <form method="post">
      <input type="hidden" name="do" value="save_update">
      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
      <div class="switch"><input type="checkbox" id="up_en" name="enabled" <?= !empty($upd['enabled']) ? 'checked' : '' ?>><label for="up_en" style="margin:0">启用更新提示</label></div>
      <div class="row">
        <div><label>versionCode</label><input type="number" name="version_code" value="<?= (int) (isset($upd['version_code']) ? $upd['version_code'] : 0) ?>"></div>
        <div><label>versionName</label><input type="text" name="version_name" value="<?= h(isset($upd['version_name']) ? $upd['version_name'] : '') ?>" placeholder="3.2.0"></div>
      </div>
      <label>下载地址（APK 直链或网盘页）</label>
      <input type="text" name="url" value="<?= h(isset($upd['url']) ? $upd['url'] : '') ?>" placeholder="https://.../BiliDiy-3.2.0-release.apk">
      <label>更新说明</label>
      <textarea name="changelog"><?= h(isset($upd['changelog']) ? $upd['changelog'] : '') ?></textarea>
      <div class="switch"><input type="checkbox" id="up_force" name="force" <?= !empty($upd['force']) ? 'checked' : '' ?>><label for="up_force" style="margin:0">强制更新（不允许「稍后」）</label></div>
      <div class="btn-line"><button class="btn" type="submit">保存更新信息</button></div>
    </form>

    <details>
      <summary>直接上传 APK（会自动填好下载地址）</summary>
      <form method="post" enctype="multipart/form-data">
        <input type="hidden" name="do" value="upload_apk">
        <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
        <label>APK 文件</label>
        <input type="file" name="apk" accept=".apk,application/vnd.android.package-archive">
        <div class="row">
          <div><label>versionCode</label><input type="number" name="version_code" value="<?= (int) (isset($upd['version_code']) ? $upd['version_code'] : 0) ?>"></div>
          <div><label>versionName</label><input type="text" name="version_name" value="<?= h(isset($upd['version_name']) ? $upd['version_name'] : '') ?>"></div>
        </div>
        <label>更新说明</label>
        <textarea name="changelog"><?= h(isset($upd['changelog']) ? $upd['changelog'] : '') ?></textarea>
        <div class="switch"><input type="checkbox" id="up_force2" name="force" <?= !empty($upd['force']) ? 'checked' : '' ?>><label for="up_force2" style="margin:0">强制更新</label></div>
        <div class="btn-line"><button class="btn" type="submit">上传并启用</button></div>
        <p class="desc" style="margin-top:10px">当前 PHP 上传上限：<?= h((string) ini_get('upload_max_filesize')) ?>（若 APK 更大，请用 FTP 上传后在上一栏填下载地址）</p>
      </form>
    </details>
  </div>

  <div class="card" id="cookie" data-msec="system">
    <h2>云端 Cookie</h2>
    <p class="desc">后台页面只显示字段名，不再回显 Cookie 值，避免截图、录屏或浏览器缓存泄露。客户端只拿播放字段，不会拿到 bili_jct 等可写字段。</p>
    <?php
      $cookieNames = cookie_field_names((string) (isset($store['cookie']) ? $store['cookie'] : ''));
    ?>
    <p class="hint">已配置 <?= count($cookieNames) ?> 个字段：<?= h(implode('、', $cookieNames) ?: '无') ?></p>
    <form method="post">
      <input type="hidden" name="do" value="save_cookie">
      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
      <label>替换完整 Cookie（留空则保持不变）</label>
      <textarea name="cookie" class="mono" style="min-height:150px" placeholder="粘贴新的完整 Cookie，必须包含 SESSDATA=" autocomplete="off" spellcheck="false"></textarea>
      <div class="btn-line"><button class="btn" type="submit">保存新 Cookie</button></div>
    </form>
  </div>

  <div class="card wide" id="member" data-msec="member">
    <h2>会员管理</h2>
    <p class="desc">激活码用于 App 内会员激活；会员专享的番剧/影视片段必须激活后才能播放。也可以直接给指定设备 ID 开通。</p>
    <form method="post">
      <input type="hidden" name="do" value="grant_member">
      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
      <label>设备 ID（设置 → 账户里查看）</label>
      <input type="text" name="device_id" placeholder="dev_xxxxxxxxxxxxxxxxxxxx" autocomplete="off">
      <div class="row">
        <div><label>开通时长</label>
          <select name="plan" style="width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:10px;background:var(--bg);font:inherit">
            <?php foreach ($plans as $k => $p): ?><option value="<?= h($k) ?>"><?= h($p['label']) ?></option><?php endforeach; ?>
          </select>
        </div>
        <div style="display:flex;align-items:flex-end"><button class="btn" type="submit" style="width:100%">开通 / 续期</button></div>
      </div>
      <p class="hint">已有会员会从当前到期时间继续累加。</p>
    </form>

    <details>
      <summary>生成激活码（月卡 / 季卡 / 年卡 / 自定义天数）</summary>
      <form method="post">
        <input type="hidden" name="do" value="generate_codes">
        <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
        <div class="row">
          <div><label>套餐</label>
            <select name="plan" style="width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:10px;background:var(--bg);font:inherit">
              <?php foreach ($plans as $k => $p): ?><option value="<?= h($k) ?>"><?= h($p['label']) ?></option><?php endforeach; ?>
            </select>
          </div>
          <div><label>生成数量（1–20）</label><input type="number" name="count" value="1" min="1" max="20"></div>
          <div><label>前缀（可自定义）</label><input type="text" name="prefix" value="BD" maxlength="8" placeholder="BD / VIP / AM" autocomplete="off"></div>
        </div>
        <div class="row">
          <div><label>自定义时长（天，可选）</label><input type="number" name="days" min="1" max="3650" placeholder="留空则使用上方套餐"></div>
        </div>
        <div class="btn-line"><button class="btn" type="submit">生成激活码</button></div>
        <p class="hint">填了自定义天数就按天数生成，留空按套餐；激活码生成后 365 天内有效，一次性使用；前缀支持字母数字，最多 8 位。</p>
      </form>
    </details>

    <details>
      <summary>激活码列表（<?= count($codes) ?>）</summary>
      <?php if ($codes): ?>
        <div class="table-wrap">
        <table class="tbl">
          <tr><th>激活码</th><th>套餐</th><th>状态</th><th>使用设备</th><th>操作</th></tr>
          <?php $ci = 0; foreach ($codes as $code => $c): $ci++; if ($ci > 20) break; ?>
            <tr>
              <td><span class="code"><?= h((string) $code) ?></span></td>
              <td><?php
                if (($c['plan'] ?? '') === 'custom') {
                    echo h('自定义 ' . (int) ($c['days'] ?? 0) . ' 天');
                } else {
                    echo h(isset($plans[$c['plan']]['label']) ? $plans[$c['plan']]['label'] : (string) ($c['plan'] ?? ''));
                }
              ?></td>
              <td><?= empty($c['used_by']) ? '<span class="pill">未使用</span>' : '<span class="pill off">已使用</span>' ?></td>
              <td class="mono"><?= h((string) ($c['used_by'] ?? '')) ?></td>
              <td>
                <form method="post" style="margin:0" data-confirm="删除该激活码？已使用的记录也会消失。">
                  <input type="hidden" name="do" value="delete_code">
                  <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
                  <input type="hidden" name="code" value="<?= h((string) $code) ?>">
                  <button class="btn danger" type="submit">删除</button>
                </form>
              </td>
            </tr>
          <?php endforeach; ?>
        </table>
        </div>
        <?php if (count($codes) > 20): ?><p class="hint">仅显示最近 20 个，共 <?= count($codes) ?> 个。</p><?php endif; ?>
      <?php else: ?>
        <p class="hint">还没有生成过激活码。</p>
      <?php endif; ?>
    </details>
  </div>

  <div class="card" id="lock" data-msec="system">
    <h2>远程锁定</h2>
    <p class="desc">锁定后 App 内会弹出不可关闭的提示框，所有功能不可用，直到解除。</p>
    <form method="post">
      <input type="hidden" name="do" value="save_lock">
      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
      <div class="switch"><input type="checkbox" id="lk" name="locked" <?= !empty($lock['locked']) ? 'checked' : '' ?>><label for="lk" style="margin:0">启用远程锁定</label></div>
      <label>弹窗标题</label>
      <input type="text" name="title" value="<?= h(isset($lock['title']) ? $lock['title'] : '服务已暂停') ?>">
      <label>提示正文</label>
      <textarea name="message" placeholder="例如：该应用已被远程停用，请联系管理员。"><?= h(isset($lock['message']) ? $lock['message'] : '') ?></textarea>
      <div class="btn-line"><button class="btn danger" type="submit">保存锁定设置</button></div>
    </form>
  </div>

  <div class="card wide" id="accounts" data-msec="accounts">
    <h2>账户与邀请码</h2>
    <p class="desc">App 强制登录；注册必须使用邀请码。邀请码一次性或多次、可设有效期；账号支持停用和恢复，停用会立即清除会话。</p>

    <div class="kv" style="margin-bottom:14px">
      <div class="k">注册账户</div>
      <div><span class="pill"><?= count($accounts) ?> 个</span> <span class="mono" style="color:#9499A0">有效 <?= $activeAccounts ?> 个 · 会话 <?= count($sessions) ?> 个</span></div>
      <div class="k">邀请码</div>
      <div><span class="pill"><?= count($invites) ?> 个</span> <span class="mono" style="color:#9499A0">当前可用 <?= $activeInvites ?> 个</span></div>
    </div>

    <details>
      <summary>生成邀请码</summary>
      <form method="post">
        <input type="hidden" name="do" value="invite_gen">
        <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
        <div class="row">
          <div><label>生成数量（1-20）</label><input type="number" name="count" value="1" min="1" max="20"></div>
          <div><label>有效期（天）</label><input type="number" name="days" value="7" min="1" max="365"></div>
          <div><label>每个可用次数</label><input type="number" name="uses" value="1" min="1" max="100"></div>
        </div>
        <label>备注（可选）</label>
        <input type="text" name="note" placeholder="例如：给朋友 / 某批次" maxlength="40" autocomplete="off">
        <label>前缀（可自定义）</label>
        <input type="text" name="prefix" value="INV" placeholder="INV / AM / FRIEND" maxlength="8" autocomplete="off">
        <div class="btn-line"><button class="btn" type="submit">生成邀请码</button></div>
        <p class="hint">生成后会显示在页面顶部提示里；一次性码建议有效期短一些。</p>
      </form>
    </details>

    <details>
      <summary>邀请码列表（<?= count($invites) ?>）</summary>
      <?php if (!$invites): ?>
        <p class="hint">还没有邀请码。先生成一个再发给用户。</p>
      <?php else: ?>
        <div class="table-wrap">
        <table class="tbl">
          <tr><th>邀请码</th><th>状态</th><th>使用</th><th>有效期</th><th>备注</th><th>操作</th></tr>
          <?php $ii = 0; foreach ($invites as $code => $inv): $ii++; if ($ii > 40) break; ?>
            <?php
              $enabled = !empty($inv['enabled']);
              $expired = (int) ($inv['expire_at'] ?? 0) <= time();
              $full = (int) ($inv['uses'] ?? 0) >= (int) ($inv['max_uses'] ?? 1);
              $state = !$enabled ? '已停用' : ($expired ? '已过期' : ($full ? '已用完' : '可用'));
              $stateClass = $state === '可用' ? '' : ' off';
            ?>
            <tr>
              <td><span class="code"><?= h((string) $code) ?></span></td>
              <td><span class="pill<?= $stateClass ?>"><?= h($state) ?></span></td>
              <td><?= (int) ($inv['uses'] ?? 0) ?> / <?= (int) ($inv['max_uses'] ?? 1) ?></td>
              <td><?= h(date('Y-m-d H:i', (int) ($inv['expire_at'] ?? 0))) ?></td>
              <td class="mono"><?= h((string) ($inv['note'] ?? '')) ?></td>
              <td>
                <div style="display:flex;gap:6px;flex-wrap:wrap">
                <?php if ($state === '可用'): ?>
                  <form method="post" style="margin:0">
                    <input type="hidden" name="do" value="invite_disable">
                    <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
                    <input type="hidden" name="invite" value="<?= h((string) $code) ?>">
                    <button class="btn ghost" type="submit">停用</button>
                  </form>
                <?php endif; ?>
                  <form method="post" style="margin:0" data-confirm="删除该邀请码？该码及使用记录会消失。">
                    <input type="hidden" name="do" value="invite_delete">
                    <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
                    <input type="hidden" name="invite" value="<?= h((string) $code) ?>">
                    <button class="btn danger" type="submit">删除</button>
                  </form>
                </div>
              </td>
            </tr>
          <?php endforeach; ?>
        </table>
        </div>
        <?php if (count($invites) > 40): ?><p class="hint">仅显示最近 40 个。</p><?php endif; ?>
      <?php endif; ?>
    </details>

    <details>
      <summary>账户列表（<?= count($accounts) ?>）</summary>
      <?php if (!$accounts): ?>
        <p class="hint">还没有注册账户。生成邀请码后发给用户注册。</p>
      <?php else: ?>
        <div class="table-wrap">
        <table class="tbl">
          <tr><th>账号</th><th>昵称</th><th>设备</th><th>邀请</th><th>注册 / 最近登录</th><th>状态</th><th>操作</th></tr>
          <?php $ai = 0; foreach ($accounts as $u => $a): $ai++; if ($ai > 40) break; ?>
            <?php
              $status = (string) ($a['status'] ?? 'active');
              $devices = isset($a['devices']) && is_array($a['devices']) ? count($a['devices']) : 0;
              $hasQuota = isset($a['invite_quota']) ? true : (isset($a['invite']) && !isset($a['invited_by']));
              $quota = isset($a['invite_quota']) ? max(0, min(5, (int) $a['invite_quota'])) : ($hasQuota ? 5 : 0);
              $canInvite = isset($a['can_invite']) ? (bool) $a['can_invite'] : $hasQuota;
            ?>
            <tr>
              <td><span class="code"><?= h((string) ($a['username'] ?? $u)) ?></span></td>
              <td><?= h((string) ($a['nickname'] ?? '')) ?></td>
              <td><?= (int) $devices ?></td>
              <td class="mono"><?= $canInvite ? h($quota . ' / 5') : '无资格' ?><?= !empty($a['invited_by']) ? '<br><span style="color:#9499A0">来自 ' . h((string) $a['invited_by']) . '</span>' : '' ?></td>
              <td class="mono">
                <?php
                  $createdAt = (int) ($a['created_at'] ?? 0);
                  $lastLogin = (int) ($a['last_login'] ?? 0);
                  // 老数据注册时把 last_login 写成了 created_at，这种同值不能再当作“最近登录”
                  $lastKnown = $lastLogin > 0 && $lastLogin !== $createdAt;
                ?>
                <?= $createdAt > 0 ? h(date('Y-m-d', $createdAt)) : '—' ?><br>
                <span style="color:#9499A0"><?= $lastKnown ? h(date('m-d H:i', $lastLogin)) : '未记录' ?></span>
              </td>
              <td><span class="pill<?= $status === 'active' ? '' : ' off' ?>"><?= $status === 'active' ? '正常' : '已停用' ?></span></td>
              <td>
                <div style="display:flex;gap:6px;flex-wrap:wrap">
                  <?php if ($status === 'active'): ?>
                    <form method="post" style="margin:0">
                      <input type="hidden" name="do" value="account_ban">
                      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
                      <input type="hidden" name="username" value="<?= h((string) ($a['username'] ?? $u)) ?>">
                      <button class="btn danger" type="submit">停用</button>
                    </form>
                  <?php else: ?>
                    <form method="post" style="margin:0">
                      <input type="hidden" name="do" value="account_unban">
                      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
                      <input type="hidden" name="username" value="<?= h((string) ($a['username'] ?? $u)) ?>">
                      <button class="btn ghost" type="submit">恢复</button>
                    </form>
                  <?php endif; ?>
                  <form method="post" style="margin:0">
                    <input type="hidden" name="do" value="account_clear_sessions">
                    <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
                    <input type="hidden" name="username" value="<?= h((string) ($a['username'] ?? $u)) ?>">
                    <button class="btn ghost" type="submit">清除会话</button>
                  </form>
                  <form method="post" style="margin:0" data-confirm="删除账号后不可恢复，确定？">
                    <input type="hidden" name="do" value="account_delete">
                    <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
                    <input type="hidden" name="username" value="<?= h((string) ($a['username'] ?? $u)) ?>">
                    <button class="btn danger" type="submit">删除</button>
                  </form>
                </div>
              </td>
            </tr>
          <?php endforeach; ?>
        </table>
        </div>
        <?php if (count($accounts) > 40): ?><p class="hint">仅显示最近 40 个。</p><?php endif; ?>
      <?php endif; ?>
    </details>
  </div>

  <div class="card" id="devices" data-msec="system">
    <h2>设备账户</h2>
    <p class="desc">App 首次打开会生成设备 ID，用户可在「设置 → 账户」里改昵称；这里只做展示，不涉及密码。</p>
    <?php if (!$profiles): ?>
      <p class="desc" style="margin:0">还没有设备上报。让用户打开一次 App 即可。</p>
    <?php else: ?>
      <div class="kv">
        <?php $i = 0; foreach ($profiles as $p): $i++; if ($i > 20) break; ?>
          <div class="k mono"><?= h((string) ($p['device_id'] ?? '')) ?></div>
          <div>
            <strong><?= h((string) ($p['nickname'] ?? '')) ?></strong>
            <?php $mi = member_info_admin($store, (string) ($p['device_id'] ?? '')); ?>
            <?php if ($mi['active']): ?>
              <span class="pill">会员至 <?= h(date('Y-m-d', $mi['expire_at'])) ?></span>
            <?php else: ?>
              <span class="pill off">未开通</span>
            <?php endif; ?>
            <span class="mono" style="color:#9499A0">
              <?= h((string) ($p['version'] ?? '')) ?><?= !empty($p['model']) ? ' · ' . h((string) $p['model']) : '' ?>
              <?= !empty($p['at']) ? ' · ' . h(date('m-d H:i', (int) $p['at'])) : '' ?>
            </span>
          </div>
        <?php endforeach; ?>
      </div>
      <?php if (count($profiles) > 20): ?>
        <p class="desc" style="margin:12px 0 0">仅显示最近 20 台，共 <?= count($profiles) ?> 台。</p>
      <?php endif; ?>
    <?php endif; ?>
  </div>

  <div class="card wide" id="history" data-msec="history">
    <h2>云端观看记录</h2>
    <p class="desc">每个账户单独一个文件、不设条数上限；同一账户下的相同内容已合并，只保留最新进度。支持按账户和视频 / 番剧 / 电影筛选，筛选与刷新不刷新整页。</p>
    <div class="tabs" id="histTabs">
      <a data-hist="all" class="<?= $histFilter === 'all' ? 'on' : '' ?>" href="?hist=all<?= $histAccount !== '' ? '&acc=' . h(urlencode($histAccount)) : '' ?>#history">全部 <span data-count="all"><?= (int) $catCounts['all'] ?></span></a>
      <a data-hist="video" class="<?= $histFilter === 'video' ? 'on' : '' ?>" href="?hist=video<?= $histAccount !== '' ? '&acc=' . h(urlencode($histAccount)) : '' ?>#history">视频 <span data-count="video"><?= (int) $catCounts['video'] ?></span></a>
      <a data-hist="anime" class="<?= $histFilter === 'anime' ? 'on' : '' ?>" href="?hist=anime<?= $histAccount !== '' ? '&acc=' . h(urlencode($histAccount)) : '' ?>#history">番剧 <span data-count="anime"><?= (int) $catCounts['anime'] ?></span></a>
      <a data-hist="movie" class="<?= $histFilter === 'movie' ? 'on' : '' ?>" href="?hist=movie<?= $histAccount !== '' ? '&acc=' . h(urlencode($histAccount)) : '' ?>#history">电影 <span data-count="movie"><?= (int) $catCounts['movie'] ?></span></a>
    </div>
    <div class="hist-tools">
      <select id="histAccount" aria-label="按账户筛选">
        <option value="">全部账户</option>
        <?php foreach ($historyGroups as $gk => $g): ?>
          <option value="<?= h((string) $gk) ?>" <?= $histAccount === (string) $gk ? 'selected' : '' ?>><?= h((string) $g['label']) ?></option>
        <?php endforeach; ?>
      </select>
      <button class="btn ghost" type="button" id="histRefresh">刷新记录</button>
      <span class="hint" id="histStatus">当前 <?= count($historyList) ?> 条，共 <?= (int) $historyTotal ?> 条已合并记录</span>
    </div>
    <div id="histBody">
      <?php if (!$historyGroupsFiltered): ?>
        <p class="desc" style="margin:0">当前筛选没有记录。</p>
      <?php else: ?>
        <div class="table-wrap">
          <table class="tbl hist-table">
            <tr><th>分类</th><th>内容</th><th>进度</th><th>时间</th></tr>
            <?php foreach ($historyGroupsFiltered as $g): ?>
              <tr class="hist-group"><td colspan="4"><?= h((string) $g['label']) ?> <span style="color:#9499A0;font-weight:400"> · <?= count($g['devices']) ?> 台设备</span></td></tr>
              <?php foreach ($g['items'] as $hitem): ?>
                <tr>
                  <td><span class="pill cat"><?= h($catLabels[$hitem['cat']] ?? '视频') ?></span></td>
                  <td><?= h((string) ($hitem['title'] ?? '')) ?></td>
                  <td><?= (int) round(((int) ($hitem['pos'] ?? 0)) / 1000) ?>s<?= !empty($hitem['dur']) ? ' / ' . (int) round(((int) $hitem['dur']) / 1000) . 's' : '' ?></td>
                  <td><?= !empty($hitem['at']) ? h(date('m-d H:i', (int) $hitem['at'])) : '' ?></td>
                </tr>
              <?php endforeach; ?>
            <?php endforeach; ?>
          </table>
        </div>
      <?php endif; ?>
    </div>
    <p class="hint" style="margin-top:8px">同一账户下相同 bvid / 剧集只显示最新一条；不同账户不会混在一起。</p>
    <details>
      <summary>按设备清空观看记录</summary>
      <form method="post">
        <input type="hidden" name="do" value="clear_history">
        <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
        <label>设备 ID</label>
        <div class="row">
          <div><input type="text" name="device_id" placeholder="dev_xxxxxxxxxxxxxxxxxxxx" autocomplete="off"></div>
          <div style="display:flex;align-items:flex-end"><button class="btn danger" type="submit" style="width:100%">清空该账户记录</button></div>
        </div>
      </form>
    </details>
  </div>

  <div class="card" id="password" data-msec="system">
    <h2>修改密码</h2>
    <form method="post">
      <input type="hidden" name="do" value="change_pass">
      <input type="hidden" name="csrf" value="<?= h(csrf_token()) ?>">
      <label>当前密码</label>
      <input type="password" name="current" autocomplete="current-password">
      <div class="row">
        <div><label>新密码（至少 8 位）</label><input type="password" name="password" autocomplete="new-password"></div>
        <div><label>再次输入</label><input type="password" name="password2" autocomplete="new-password"></div>
      </div>
      <div class="btn-line"><button class="btn" type="submit">修改密码</button></div>
    </form>
  </div>
  </div>

  <div class="foot">BiliDiy 云端控制台 · 建议定期更换密码与 Cookie</div>
  </main>
  </div>
  <script>
  (function () {
    var bar = document.getElementById('mtabbar');
    var side = document.getElementById('sideNav');
    var cardsEl = document.getElementById('adminCards');
    var subnav = document.getElementById('cardSubnav');
    var pageTitle = document.getElementById('pageTitle');
    var pageDesc = document.getElementById('pageDesc');
    var pageCrumb = document.getElementById('pageCrumb');
    if (!cardsEl) return;
    var mq = window.matchMedia('(max-width: 880px)');
    var current = sessionStorage.getItem('msec') || 'home';
    var meta = {
      home: ['概览', '云端运行状态与常用操作'],
      announce: ['云公告', 'App 启动弹窗与首页公告条'],
      update: ['云更新', '版本号、下载地址与更新说明'],
      member: ['会员与激活码', '会员开通、激活码生成与使用记录'],
      accounts: ['账户与邀请码', '注册账户、邀请码与会话管理'],
      history: ['观看记录', '按设备合并的云端观看记录'],
      system: ['系统设置', 'Cookie、远程锁定、设备与密码']
    };
    function applySub(sec, cards) {
      if (!subnav) return;
      cards.forEach(function (c) { c.classList.remove('sub-hide'); });
      var sectionCards = cards.filter(function (c) { return c.getAttribute('data-msec') === sec; });
      if (!mq.matches || sectionCards.length <= 1) {
        subnav.hidden = true;
        subnav.innerHTML = '';
        return;
      }
      var key = 'cardsec:' + sec;
      var chosen = '';
      try { chosen = sessionStorage.getItem(key) || ''; } catch (e) {}
      if (!chosen || !sectionCards.some(function (c) { return c.id === chosen; })) {
        chosen = sectionCards[0].id;
      }
      subnav.hidden = false;
      subnav.innerHTML = '';
      sectionCards.forEach(function (c) {
        var h2 = c.querySelector('h2');
        var label = h2 ? h2.textContent.trim() : c.id;
        var b = document.createElement('button');
        b.type = 'button';
        b.textContent = label;
        b.setAttribute('data-card', c.id);
        b.classList.toggle('on', c.id === chosen);
        b.addEventListener('click', function () {
          try { sessionStorage.setItem(key, c.id); } catch (e) {}
          applySub(sec, cards);
          window.scrollTo({ top: 0, behavior: 'smooth' });
        });
        subnav.appendChild(b);
        c.classList.toggle('sub-hide', c.id !== chosen);
      });
    }
    function apply(sec, scroll) {
      var cards = Array.prototype.slice.call(document.querySelectorAll('.card[data-msec]'));
      var visible = 0;
      cards.forEach(function (c) {
        var show = c.getAttribute('data-msec') === sec;
        c.classList.toggle('m-hide', !show);
        if (show) visible++;
      });
      cardsEl.classList.toggle('one', visible === 1);
      applySub(sec, cards);
      if (bar) {
        Array.prototype.forEach.call(bar.querySelectorAll('button'), function (b) {
          b.classList.toggle('on', b.getAttribute('data-mtab') === sec);
        });
      }
      if (side) {
        Array.prototype.forEach.call(side.querySelectorAll('.nav-btn'), function (b) {
          b.classList.toggle('on', b.getAttribute('data-sec') === sec);
        });
      }
      var m = meta[sec] || meta.home;
      if (pageTitle) pageTitle.textContent = m[0];
      if (pageDesc) pageDesc.textContent = m[1];
      if (pageCrumb) pageCrumb.textContent = m[0];
      document.title = 'BiliDiy 管理后台 · ' + m[0];
      if (scroll) window.scrollTo({ top: 0, behavior: 'smooth' });
    }
    function select(sec, scroll) {
      current = sec in meta ? sec : 'home';
      try { sessionStorage.setItem('msec', current); } catch (e) {}
      apply(current, scroll);
    }
    window.bdApplyAdminSection = function () { apply(current, false); };
    window.bdApplyMobileTab = window.bdApplyAdminSection;
    if (bar) {
      Array.prototype.forEach.call(bar.querySelectorAll('button'), function (b) {
        b.addEventListener('click', function () { select(b.getAttribute('data-mtab'), true); });
      });
    }
    if (side) {
      Array.prototype.forEach.call(side.querySelectorAll('.nav-btn'), function (b) {
        b.addEventListener('click', function () { select(b.getAttribute('data-sec'), true); });
      });
    }
    document.addEventListener('submit', function () {
      try { sessionStorage.setItem('msec', current); } catch (e) {}
    }, true);
    var hashMap = { status: 'home', announce: 'announce', update: 'update', cookie: 'system', member: 'member', accounts: 'accounts', lock: 'system', devices: 'system', history: 'history', password: 'system' };
    if (location.hash && hashMap[location.hash.slice(1)]) current = hashMap[location.hash.slice(1)];
    apply(current, false);
    if (mq.addEventListener) mq.addEventListener('change', function () { apply(current, false); });
    else if (mq.addListener) mq.addListener(function () { apply(current, false); });
  })();
  </script>
  <script>
  (function () {
    var tabs = document.getElementById('histTabs');
    var body = document.getElementById('histBody');
    var select = document.getElementById('histAccount');
    var refresh = document.getElementById('histRefresh');
    var status = document.getElementById('histStatus');
    if (!tabs || !body || !select || !status) return;
    var current = 'all';
    var active = tabs.querySelector('a.on');
    if (active) current = active.getAttribute('data-hist') || 'all';

    function catLabel(cat) {
      return cat === 'anime' ? '番剧' : (cat === 'movie' ? '电影' : '视频');
    }

    function progress(item) {
      var p = Math.round((item.pos || 0) / 1000);
      var d = Math.round((item.dur || 0) / 1000);
      return d > 0 ? p + 's / ' + d + 's' : p + 's';
    }

    function dateText(at) {
      if (!at) return '';
      var d = new Date(at * 1000);
      var mm = ('0' + (d.getMonth() + 1)).slice(-2);
      var dd = ('0' + d.getDate()).slice(-2);
      var hh = ('0' + d.getHours()).slice(-2);
      var mi = ('0' + d.getMinutes()).slice(-2);
      return mm + '-' + dd + ' ' + hh + ':' + mi;
    }

    function render(data) {
      body.innerHTML = '';
      if (!data.groups || !data.groups.length) {
        var empty = document.createElement('p');
        empty.className = 'desc';
        empty.style.margin = '0';
        empty.textContent = '当前筛选没有记录。';
        body.appendChild(empty);
      } else {
        var wrap = document.createElement('div');
        wrap.className = 'table-wrap';
        var table = document.createElement('table');
        table.className = 'tbl hist-table';
        var head = document.createElement('tr');
        ['分类', '内容', '进度', '时间'].forEach(function (t) {
          var th = document.createElement('th');
          th.textContent = t;
          head.appendChild(th);
        });
        table.appendChild(head);
        data.groups.forEach(function (g) {
          var gh = document.createElement('tr');
          gh.className = 'hist-group';
          var gc = document.createElement('td');
          gc.colSpan = 4;
          gc.textContent = g.label + ' · ' + g.devices.length + ' 台设备';
          gh.appendChild(gc);
          table.appendChild(gh);
          (g.items || []).forEach(function (it) {
            var tr = document.createElement('tr');
            var c1 = document.createElement('td');
            var pill = document.createElement('span');
            pill.className = 'pill cat';
            pill.textContent = catLabel(it.cat);
            c1.appendChild(pill);
            var c2 = document.createElement('td');
            c2.textContent = it.title || '';
            var c3 = document.createElement('td');
            c3.textContent = progress(it);
            var c4 = document.createElement('td');
            c4.textContent = dateText(it.at);
            [c1, c2, c3, c4].forEach(function (c) { tr.appendChild(c); });
            table.appendChild(tr);
          });
        });
        wrap.appendChild(table);
        body.appendChild(wrap);
      }
      Object.keys(data.counts || {}).forEach(function (k) {
        Array.prototype.forEach.call(tabs.querySelectorAll('[data-count="' + k + '"]'), function (el) {
          el.textContent = data.counts[k];
        });
      });
      status.textContent = '当前 ' + (data.groups || []).reduce(function (n, g) { return n + (g.items || []).length; }, 0) + ' 条，共 ' + data.total + ' 条已合并记录';
    }

    function load(hist, acc) {
      current = hist;
      var q = '?ajax=history&hist=' + encodeURIComponent(hist);
      if (acc) q += '&acc=' + encodeURIComponent(acc);
      Array.prototype.forEach.call(tabs.querySelectorAll('a'), function (a) {
        a.classList.toggle('on', a.getAttribute('data-hist') === hist);
      });
      refresh.disabled = true;
      refresh.textContent = '刷新中…';
      fetch(q, { credentials: 'same-origin', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then(function (data) {
          render(data);
          var url = '?hist=' + encodeURIComponent(hist);
          if (acc) url += '&acc=' + encodeURIComponent(acc);
          history.replaceState(null, '', url + '#history');
        })
        .catch(function () {
          status.textContent = '刷新失败，请稍后重试';
        })
        .finally(function () {
          refresh.disabled = false;
          refresh.textContent = '刷新记录';
        });
    }

    Array.prototype.forEach.call(tabs.querySelectorAll('a[data-hist]'), function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault();
        load(a.getAttribute('data-hist'), select.value);
      });
    });
    select.addEventListener('change', function () { load(current, select.value); });
    if (refresh) refresh.addEventListener('click', function () { load(current, select.value); });
  })();
  </script>
  <script>
  (function () {
    var cards = document.getElementById('adminCards');
    if (!cards) return;
    var toastTimer = 0;

    function toast(msg, isErr) {
      var el = document.getElementById('bdToast');
      if (!el) {
        el = document.createElement('div');
        el.id = 'bdToast';
        el.setAttribute('role', 'status');
        el.setAttribute('aria-live', 'polite');
        el.style.cssText = 'position:fixed;left:50%;bottom:84px;transform:translateX(-50%);z-index:9999;max-width:min(680px,calc(100vw - 28px));max-height:45vh;overflow:auto;padding:10px 14px;border-radius:12px;box-shadow:0 10px 30px rgba(0,0,0,.18);font-size:13px;line-height:1.55;white-space:pre-wrap;word-break:break-word;pointer-events:none;display:none';
        document.body.appendChild(el);
      }
      el.style.background = isErr ? '#FFF0F0' : '#EAF8EE';
      el.style.color = isErr ? '#C0392B' : '#1D7A3B';
      el.style.border = isErr ? '1px solid #FFD4D4' : '1px solid #CBEBDO';
      el.textContent = msg;
      el.style.display = 'block';
      clearTimeout(toastTimer);
      toastTimer = setTimeout(function () { el.style.display = 'none'; }, 4500);
    }

    function targetsFor(action) {
      if (action === 'save_announcement') return ['announce', 'status'];
      if (action === 'save_update' || action === 'upload_apk') return ['update', 'status'];
      if (action === 'save_cookie') return ['cookie', 'status'];
      if (action === 'save_lock' || action === 'lock' || action === 'unlock') return ['lock', 'status'];
      if (action === 'grant_member' || action === 'revoke_member' ||
          action === 'generate_codes' || action === 'delete_code') return ['member', 'status'];
      if (action === 'invite_gen' || action === 'invite_disable' || action === 'invite_delete' ||
          action === 'account_ban' || action === 'account_unban' ||
          action === 'account_clear_sessions' || action === 'account_delete') return ['accounts', 'status'];
      if (action === 'clear_history') return ['status'];
      if (action === 'change_pass') return ['password'];
      return ['status'];
    }

    function replaceCard(doc, id) {
      var oldCard = document.getElementById(id);
      var newCard = doc.getElementById(id);
      if (!oldCard || !newCard) return false;
      var openStates = Array.prototype.map.call(oldCard.querySelectorAll('details'), function (d) {
        return !!d.open;
      });
      oldCard.innerHTML = newCard.innerHTML;
      Array.prototype.forEach.call(oldCard.querySelectorAll('details'), function (d, i) {
        if (openStates[i]) d.open = true;
      });
      return true;
    }

    function applyResponse(doc, action) {
      var oldWrap = document.getElementById('adminFlashWrap');
      var newWrap = doc.getElementById('adminFlashWrap');
      if (oldWrap && newWrap) oldWrap.innerHTML = newWrap.innerHTML;
      var flash = doc.querySelector('#adminFlashWrap .flash');
      var isErr = !!flash && flash.classList.contains('err');
      var msg = flash ? flash.textContent.trim() : '';
      targetsFor(action).forEach(function (id) { replaceCard(doc, id); });
      if (action === 'clear_history' && !isErr) {
        var refresh = document.getElementById('histRefresh');
        if (refresh) refresh.click();
      }
      if (window.bdApplyMobileTab) window.bdApplyMobileTab();
      toast(msg || (isErr ? '操作失败' : '操作已完成'), isErr);
    }

    document.addEventListener('submit', function (e) {
      var form = e.target;
      if (!form || form.tagName !== 'FORM' || !form.closest('#adminCards')) return;
      var doEl = form.querySelector('input[name="do"]');
      var action = doEl ? doEl.value : '';
      if (!action || action === 'logout' || action === 'login' || action === 'setup') return;
      e.preventDefault();
      var confirmMsg = form.getAttribute('data-confirm');
      if (confirmMsg && !window.confirm(confirmMsg)) return;
      if (form.getAttribute('data-bd-busy') === '1') return;
      form.setAttribute('data-bd-busy', '1');
      var btn = (e.submitter && e.submitter.tagName === 'BUTTON' ? e.submitter : null) ||
        form.querySelector('button[type="submit"], button:not([type]), input[type="submit"]');
      var oldText = btn && btn.tagName === 'BUTTON' ? btn.textContent : '';
      if (btn) {
        btn.disabled = true;
        if (btn.tagName === 'BUTTON') btn.textContent = '处理中…';
      }
      fetch(form.getAttribute('action') || location.pathname, {
        method: 'POST',
        body: new FormData(form),
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'XMLHttpRequest' }
      })
        .then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.text();
        })
        .then(function (html) {
          var doc = new DOMParser().parseFromString(html, 'text/html');
          if (!doc.getElementById('adminCards')) {
            // 会话已失效：回到登录页，这是唯一会整页跳转的情况
            location.reload();
            return;
          }
          applyResponse(doc, action);
        })
        .catch(function (err) {
          toast('操作失败：' + (err && err.message ? err.message : '网络错误'), true);
        })
        .finally(function () {
          form.removeAttribute('data-bd-busy');
          if (btn && btn.isConnected) {
            btn.disabled = false;
            if (btn.tagName === 'BUTTON') btn.textContent = oldText;
          }
        });
    }, false);
  })();
  </script>
<?php endif; ?>
</div>
</body>
</html>
