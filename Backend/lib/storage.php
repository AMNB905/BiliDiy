<?php
/**
 * BiliDiy 独立文件存储
 * ---------------------------------------------------------------
 * data/config.php       公告 / 更新 / Cookie / 锁定 / 管理员密码
 * data/profiles.php     设备账户
 * data/members.php      会员
 * data/codes.php        会员激活码
 * data/accounts.php     登录账户
 * data/invites.php      注册邀请码
 * data/sessions.php     账号登录会话
 * data/friends.php      账号好友关系
 * data/friend_requests.php 好友申请
 * data/shares.php       会员分享记录
 * data/share_cookies.php 分享播放专用 Cookie（加密，仅服务端使用）
 * data/history/<device>.php  每个账户一份观看记录（不设上限）
 *
 * 旧版 data/store.php 仍保留作备份；首次读取时自动拆分迁移。
 */
declare(strict_types=1);

function store_data_dir(): string
{
    return dirname(__DIR__) . '/data';
}

function store_path(string $name): string
{
    return store_data_dir() . '/' . $name;
}

function store_ensure_dir(string $dir): bool
{
    if (is_dir($dir)) return true;
    return @mkdir($dir, 0755, true);
}

function store_php_read(string $path, array $default = array()): array
{
    if (!is_file($path)) return $default;
    $data = @include $path;
    return is_array($data) ? $data : $default;
}

function store_php_write(string $path, array $data): bool
{
    $dir = dirname($path);
    if (!store_ensure_dir($dir)) return false;
    $body = "<?php\n/**\n * BiliDiy 独立数据文件（自动写入，请勿手工编辑）\n */\nreturn "
        . var_export($data, true) . ";\n";
    $tmp = $dir . '/.' . basename($path) . '.' . bin2hex(random_bytes(6)) . '.tmp';
    if (@file_put_contents($tmp, $body, LOCK_EX) === false) {
        return false;
    }
    if (!@rename($tmp, $path)) {
        @unlink($tmp);
        return false;
    }
    @chmod($path, 0644);
    return true;
}

/** 首次使用旧 store.php 时，把各段拆到独立文件；config 最后写，失败可重试 */
function store_bootstrap(): bool
{
    $configPath = store_path('config.php');
    if (is_file($configPath)) return true;
    $legacyPath = store_path('store.php');
    if (!is_file($legacyPath)) return false;
    $legacy = @include $legacyPath;
    if (!is_array($legacy)) return false;

    $profiles = isset($legacy['profiles']) && is_array($legacy['profiles']) ? $legacy['profiles'] : array();
    $members = isset($legacy['members']) && is_array($legacy['members']) ? $legacy['members'] : array();
    $codes = isset($legacy['codes']) && is_array($legacy['codes']) ? $legacy['codes'] : array();

    store_php_write(store_path('profiles.php'), $profiles);
    store_php_write(store_path('members.php'), $members);
    store_php_write(store_path('codes.php'), $codes);

    $history = isset($legacy['history']) && is_array($legacy['history']) ? $legacy['history'] : array();
    foreach ($history as $device => $items) {
        if (is_array($items) && $items) {
            store_history_save((string) $device, array_values($items));
        }
    }

    $config = array(
        'schema' => 3,
        'client_key' => (string) ($legacy['client_key'] ?? ''),
        'admin_hash' => (string) ($legacy['admin_hash'] ?? ''),
        'setup_token' => (string) ($legacy['setup_token'] ?? ''),
        'announcement' => isset($legacy['announcement']) && is_array($legacy['announcement'])
            ? $legacy['announcement'] : array(),
        'update' => isset($legacy['update']) && is_array($legacy['update']) ? $legacy['update'] : array(),
        'cookie' => (string) ($legacy['cookie'] ?? ''),
        'lock' => isset($legacy['lock']) && is_array($legacy['lock']) ? $legacy['lock'] : array(),
        'updated_at' => time(),
    );
    return store_php_write($configPath, $config);
}

function store_load_all(): ?array
{
    if (!store_bootstrap()) return null;
    $config = store_php_read(store_path('config.php'));
    if (!$config) return null;
    $config['profiles'] = store_php_read(store_path('profiles.php'));
    $config['members'] = store_php_read(store_path('members.php'));
    $config['codes'] = store_php_read(store_path('codes.php'));
    $config['accounts'] = store_php_read(store_path('accounts.php'));
    $config['invites'] = store_php_read(store_path('invites.php'));
    $config['sessions'] = store_php_read(store_path('sessions.php'));
    $config['friends'] = store_php_read(store_path('friends.php'));
    $config['friend_requests'] = store_php_read(store_path('friend_requests.php'));
    $config['shares'] = store_php_read(store_path('shares.php'));
    $config['share_cookies'] = store_php_read(store_path('share_cookies.php'));
    return $config;
}

/** 兼容原调用：只写四个小文件，观看记录按账户单独写，绝不整包重写 */
function store_save(array $store): bool
{
    $config = array();
    foreach (array('schema', 'client_key', 'admin_hash', 'setup_token', 'announcement', 'update', 'cookie', 'lock') as $key) {
        if (array_key_exists($key, $store)) $config[$key] = $store[$key];
    }
    if (!isset($config['schema'])) $config['schema'] = 3;
    $config['updated_at'] = time();

    $ok = store_php_write(store_path('config.php'), $config);
    $ok = store_php_write(store_path('profiles.php'), isset($store['profiles']) && is_array($store['profiles']) ? $store['profiles'] : array()) && $ok;
    $ok = store_php_write(store_path('members.php'), isset($store['members']) && is_array($store['members']) ? $store['members'] : array()) && $ok;
    $ok = store_php_write(store_path('codes.php'), isset($store['codes']) && is_array($store['codes']) ? $store['codes'] : array()) && $ok;
    $ok = store_php_write(store_path('accounts.php'), isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array()) && $ok;
    $ok = store_php_write(store_path('invites.php'), isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array()) && $ok;
    $ok = store_php_write(store_path('sessions.php'), isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array()) && $ok;
    $ok = store_php_write(store_path('friends.php'), isset($store['friends']) && is_array($store['friends']) ? $store['friends'] : array()) && $ok;
    $ok = store_php_write(store_path('friend_requests.php'), isset($store['friend_requests']) && is_array($store['friend_requests']) ? $store['friend_requests'] : array()) && $ok;
    $ok = store_php_write(store_path('shares.php'), isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array()) && $ok;
    $ok = store_php_write(store_path('share_cookies.php'), isset($store['share_cookies']) && is_array($store['share_cookies']) ? $store['share_cookies'] : array()) && $ok;
    return $ok;
}

function store_valid_device(string $device): bool
{
    return (bool) preg_match('/^[A-Za-z0-9_-]{8,64}$/', $device);
}

function store_history_file(string $device): ?string
{
    if (!store_valid_device($device)) return null;
    return store_data_dir() . '/history/' . $device . '.php';
}

function store_history_load(string $device): array
{
    $file = store_history_file($device);
    if ($file === null) return array();
    $items = store_php_read($file);
    usort($items, static function ($a, $b) {
        return ((int) ($b['at'] ?? 0)) <=> ((int) ($a['at'] ?? 0));
    });
    return $items;
}

/** 按 key 合并；无条数上限，每个账户一个文件 */
function store_history_save(string $device, array $incoming): bool
{
    $file = store_history_file($device);
    if ($file === null) return false;
    store_ensure_dir(dirname($file));
    $lockDir = store_data_dir() . '/.locks';
    store_ensure_dir($lockDir);
    $lock = @fopen($lockDir . '/' . $device . '.lock', 'c');
    if (!$lock) return false;
    @flock($lock, LOCK_EX);

    $old = store_php_read($file);
    foreach ($incoming as $it) {
        if (!is_array($it)) continue;
        $key = (string) ($it['key'] ?? '');
        if ($key === '') continue;
        if (!isset($old[$key]) || (int) ($it['at'] ?? 0) >= (int) ($old[$key]['at'] ?? 0)) {
            $old[$key] = $it;
        }
    }
    uasort($old, static function ($a, $b) {
        return ((int) ($b['at'] ?? 0)) <=> ((int) ($a['at'] ?? 0));
    });
    $ok = store_php_write($file, array_values($old));
    @flock($lock, LOCK_UN);
    @fclose($lock);
    return $ok;
}

function store_history_clear(string $device): bool
{
    $file = store_history_file($device);
    if ($file === null) return false;
    if (!is_file($file)) return true;
    return @unlink($file);
}

/** 后台用：读取所有账户的观看记录，返回 device => items */
function store_history_all(): array
{
    $dir = store_data_dir() . '/history';
    if (!is_dir($dir)) return array();
    $out = array();
    foreach (glob($dir . '/*.php') ?: array() as $file) {
        $device = basename($file, '.php');
        if (!store_valid_device($device)) continue;
        $items = store_php_read($file);
        usort($items, static function ($a, $b) {
            return ((int) ($b['at'] ?? 0)) <=> ((int) ($a['at'] ?? 0));
        });
        $out[$device] = $items;
    }
    return $out;
}

function store_history_total(): int
{
    $total = 0;
    foreach (store_history_all() as $items) {
        $total += count($items);
    }
    return $total;
}
