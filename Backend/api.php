<?php
/**
 * BiliDiy 云端配置接口（App 端）
 * ---------------------------------------------------------------
 * GET  /api.php?action=config&key=<client_key>
 *   返回公告、更新、锁定状态与 B 站 Cookie
 * GET  /api.php?action=ping
 *   健康检查
 * 可选：Header  X-Bili-Key: <client_key>
 */
declare(strict_types=1);

require __DIR__ . '/lib/storage.php';
require __DIR__ . '/lib/security.php';

sec_headers(true);
sec_body_limit(2 * 1024 * 1024);

$store = store_load_all();
if (!is_array($store)) {
    http_response_code(500);
    echo json_encode(array('code' => 500, 'ok' => false, 'message' => '配置文件缺失或 data/ 不可写'));
    exit;
}

/** 统一输出 */
function out(array $data, int $status = 200): void
{
    http_response_code($status);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/** 限流；超限直接返回 429 */
function rate_or_out(string $name, int $max, int $window, string $message = '请求过于频繁，请稍后再试'): void
{
    $rl = sec_rate_limit($name, $max, $window);
    if (!$rl[0]) {
        header('Retry-After: ' . (int) $rl[2]);
        out(array('code' => 429, 'ok' => false, 'message' => $message, 'retry_after' => (int) $rl[2]), 429);
    }
}

/** 读取 JSON / 表单 body */
function request_body(): array
{
    $raw = file_get_contents('php://input');
    $body = $_POST;
    if (empty($body) && is_string($raw) && $raw !== '') {
        $json = json_decode($raw, true);
        if (is_array($json)) {
            $body = $json;
        }
    }
    return is_array($body) ? $body : array();
}

/** 校验客户端 key（新接口统一入口） */
function require_client(array $store, array $body): void
{
    $key = isset($_GET['key']) ? (string) $_GET['key'] : '';
    if ($key === '' && isset($body['key'])) {
        $key = (string) $body['key'];
    }
    if ($key === '' && isset($_SERVER['HTTP_X_BILI_KEY'])) {
        $key = (string) $_SERVER['HTTP_X_BILI_KEY'];
    }
    if (!hash_equals((string) $store['client_key'], $key)) {
        out(array('code' => 403, 'ok' => false, 'message' => 'client key 无效'), 403);
    }
}

function valid_device(string $device): bool
{
    return (bool) preg_match('/^[A-Za-z0-9_-]{8,64}$/', $device);
}

function account_norm(string $username): string
{
    return strtolower(trim($username));
}

function account_view(array $a): array
{
    $devices = isset($a['devices']) && is_array($a['devices']) ? $a['devices'] : array();
    $quota = account_invite_quota($a);
    return array(
        'username' => (string) ($a['username'] ?? ''),
        'nickname' => (string) ($a['nickname'] ?? ''),
        'status' => (string) ($a['status'] ?? 'active'),
        'created_at' => (int) ($a['created_at'] ?? 0),
        'last_login' => (int) ($a['last_login'] ?? 0),
        'devices' => count($devices),
        'invite_quota' => $quota,
        'can_invite' => account_can_invite($a),
        'invited_by' => (string) ($a['invited_by'] ?? ''),
    );
}

/** 账户剩余邀请名额；老账户没有字段时，按“管理员邀请注册”视为 5 个 */
function account_invite_quota(array $a): int
{
    if (isset($a['invite_quota'])) {
        return max(0, min(5, (int) $a['invite_quota']));
    }
    if (isset($a['invite']) && !isset($a['invited_by'])) return 5;
    return 0;
}

function account_can_invite(array $a): bool
{
    if (isset($a['can_invite'])) return (bool) $a['can_invite'];
    return account_invite_quota($a) > 0;
}

/** 校验账号会话；device 非空时还要求会话与设备绑定 */
function account_session(array $store, string $token, string $device = ''): ?array
{
    if ($token === '' || !preg_match('/^[a-f0-9]{32,128}$/', $token)) return null;
    $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
    $s = isset($sessions[$token]) && is_array($sessions[$token]) ? $sessions[$token] : null;
    if (!is_array($s) || (int) ($s['expires_at'] ?? 0) <= time()) return null;
    $username = account_norm((string) ($s['username'] ?? ''));
    $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
    $a = isset($accounts[$username]) && is_array($accounts[$username]) ? $accounts[$username] : null;
    if (!is_array($a) || (string) ($a['status'] ?? 'active') !== 'active') return null;
    if ($device !== '' && !hash_equals((string) ($s['device_id'] ?? ''), $device)) return null;
    return array('token' => $token, 'session' => $s, 'account' => $a, 'username' => $username);
}

/**
 * 记录一次真实的账号活动：session.last_at 和 account.last_login。
 * 默认 60 秒内不重复落盘，避免 App 每次刷新都写文件。
 */
function account_touch_activity(array &$store, array $s, int $interval = 60): bool
{
    $now = time();
    $token = (string) ($s['token'] ?? '');
    $u = account_norm((string) ($s['username'] ?? ''));
    if ($token === '' || $u === '') return false;
    $changed = false;

    $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
    $sess = isset($sessions[$token]) && is_array($sessions[$token]) ? $sessions[$token] : null;
    if (is_array($sess) && $now - (int) ($sess['last_at'] ?? 0) >= $interval) {
        $sessions[$token]['last_at'] = $now;
        $store['sessions'] = $sessions;
        $changed = true;
    }

    $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
    $a = isset($accounts[$u]) && is_array($accounts[$u]) ? $accounts[$u] : null;
    if (is_array($a) && $now - (int) ($a['last_login'] ?? 0) >= $interval) {
        $accounts[$u]['last_login'] = $now;
        $store['accounts'] = $accounts;
        $changed = true;
    }
    return $changed;
}

/** 当前设备已绑定的账号名（没有返回空串） */
function account_device_owner(array $store, string $device): string
{
    $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
    foreach ($accounts as $u => $a) {
        if (!is_array($a)) continue;
        if (isset($a['devices'][$device])) return account_norm((string) $u);
    }
    return '';
}

function account_prune_sessions(array &$store): void
{
    $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
    $now = time();
    foreach ($sessions as $t => $s) {
        if (!is_array($s) || (int) ($s['expires_at'] ?? 0) <= $now) unset($sessions[$t]);
    }
    $store['sessions'] = $sessions;
}

function invite_active(array $inv): bool
{
    return !empty($inv['enabled'])
        && (int) ($inv['expire_at'] ?? 0) > time()
        && (int) ($inv['uses'] ?? 0) < (int) ($inv['max_uses'] ?? 1);
}

function account_new_token(): string
{
    return bin2hex(random_bytes(32));
}

/** 邀请码：去掉易混淆字符；前缀可自定义 */
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

/** 只向客户端下发播放所需 Cookie，绝不下发 bili_jct 等可写操作字段 */
function playback_cookie(string $cookie): string
{
    $allowed = array('SESSDATA', 'buvid3', 'buvid4', 'b_nut', 'bili_ticket', 'buvid_fp', 'buvid_fp_plain', '_uuid');
    $kept = array();
    foreach (explode(';', $cookie) as $part) {
        $part = trim($part);
        if ($part === '' || strpos($part, '=') === false) continue;
        list($name, $value) = explode('=', $part, 2);
        if (in_array(trim($name), $allowed, true)) $kept[] = trim($name) . '=' . trim($value);
    }
    return implode('; ', $kept);
}

/** 服务端请求 B 站 JSON（不把 Cookie 返回给客户端） */
function bili_json(string $url, string $cookie): array
{
    $headers = array(
        'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36',
        'Referer: https://www.bilibili.com/',
        'Origin: https://www.bilibili.com',
        'Accept: application/json',
        'Cookie: ' . $cookie,
    );
    if (!function_exists('curl_init')) {
        $ctx = stream_context_create(array('http' => array(
            'method' => 'GET',
            'header' => implode("\r\n", $headers),
            'timeout' => 30,
            'ignore_errors' => true,
        )));
        $body = @file_get_contents($url, false, $ctx);
        if ($body === false) out(array('code' => 502, 'ok' => false, 'message' => 'B 站接口请求失败'), 502);
        $json = json_decode((string) $body, true);
        if (!is_array($json)) out(array('code' => 502, 'ok' => false, 'message' => 'B 站接口返回非 JSON'), 502);
        return $json;
    }
    $ch = curl_init($url);
    curl_setopt_array($ch, array(
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 3,
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_TIMEOUT => 30,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_HTTPHEADER => $headers,
    ));
    $body = curl_exec($ch);
    $err = curl_error($ch);
    curl_close($ch);
    if ($body === false || $err !== '') {
        out(array('code' => 502, 'ok' => false, 'message' => 'B 站接口请求失败: ' . $err), 502);
    }
    $json = json_decode((string) $body, true);
    if (!is_array($json)) out(array('code' => 502, 'ok' => false, 'message' => 'B 站接口返回非 JSON'), 502);
    return $json;
}

/** 会员状态（只读） */
function member_view(array $store, string $device): array
{
    $m = isset($store['members'][$device]) && is_array($store['members'][$device])
        ? $store['members'][$device] : array();
    $expire = isset($m['expire_at']) ? (int) $m['expire_at'] : 0;
    return array(
        'active' => $expire > time(),
        'expire_at' => $expire,
        'plan' => isset($m['plan']) ? (string) $m['plan'] : '',
        'source' => isset($m['source']) ? (string) $m['source'] : '',
        'updated_at' => isset($m['updated_at']) ? (int) $m['updated_at'] : 0,
    );
}

/** 会员续期：从「现在」或原有效期较长者开始加天数 */
function extend_member(array &$store, string $device, int $days, string $plan, string $source): array
{
    $old = member_view($store, $device);
    $base = max(time(), (int) $old['expire_at']);
    $expire = $base + max(1, $days) * 86400;
    $store['members'][$device] = array(
        'expire_at' => $expire,
        'plan' => $plan,
        'source' => $source,
        'updated_at' => time(),
    );
    return member_view($store, $device);
}

function clean_history(array $items): array
{
    $out = array();
    foreach ($items as $it) {
        if (!is_array($it)) continue;
        $key = substr(trim((string) ($it['key'] ?? '')), 0, 96);
        $title = substr(trim((string) ($it['title'] ?? '')), 0, 160);
        if ($key === '' || $title === '') continue;
        $kind = substr(trim((string) ($it['kind'] ?? 'video')), 0, 12);
        $cat = strtolower(trim((string) ($it['cat'] ?? '')));
        if (!in_array($cat, array('video', 'anime', 'movie'), true)) {
            $cat = $kind === 'pgc' ? 'movie' : 'video';
        }
        $out[] = array(
            'key' => $key,
            'kind' => $kind,
            'cat' => $cat,
            'bvid' => substr(trim((string) ($it['bvid'] ?? '')), 0, 24),
            'ep' => (int) ($it['ep'] ?? 0),
            'title' => $title,
            'cover' => substr(trim((string) ($it['cover'] ?? '')), 0, 300),
            'pos' => max(0, (int) ($it['pos'] ?? 0)),
            'dur' => max(0, (int) ($it['dur'] ?? 0)),
            'at' => max(0, (int) ($it['at'] ?? 0)),
        );
    }
    return $out;
}

function history_for(array $store, string $device): array
{
    return store_history_load($device);
}

// ---------- 好友 ----------

function account_display(array $store, string $u): string
{
    $u = account_norm($u);
    $a = isset($store['accounts'][$u]) && is_array($store['accounts'][$u])
        ? $store['accounts'][$u] : array();
    $nick = trim((string) ($a['nickname'] ?? ''));
    return $nick !== '' ? $nick : $u;
}

function account_last_active(array $store, string $u): int
{
    $u = account_norm($u);
    $last = 0;
    $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
    if (isset($accounts[$u]) && is_array($accounts[$u])) {
        $last = max($last, (int) ($accounts[$u]['last_login'] ?? 0));
    }
    $now = time();
    $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
    foreach ($sessions as $s) {
        if (!is_array($s)) continue;
        if (account_norm((string) ($s['username'] ?? '')) !== $u) continue;
        if ((int) ($s['expires_at'] ?? 0) <= $now) continue;
        $last = max($last, (int) ($s['last_at'] ?? 0));
    }
    return $last;
}

function friend_list_for(array $store, string $u): array
{
    $u = account_norm($u);
    $raw = isset($store['friends'][$u]) && is_array($store['friends'][$u])
        ? $store['friends'][$u] : array();
    $now = time();
    $out = array();
    foreach ($raw as $row) {
        $name = is_array($row) ? (string) ($row['username'] ?? '') : (string) $row;
        $name = account_norm($name);
        if ($name === '' || $name === $u) continue;
        $last = account_last_active($store, $name);
        $out[$name] = array(
            'username' => $name,
            'nickname' => account_display($store, $name),
            'since' => is_array($row) ? (int) ($row['since'] ?? 0) : 0,
            'last_at' => $last,
            'online' => $last > 0 && $now - $last <= 300,
        );
    }
    uasort($out, static function ($a, $b) {
        $ao = !empty($a['online']) ? 1 : 0;
        $bo = !empty($b['online']) ? 1 : 0;
        if ($ao !== $bo) return $bo <=> $ao;
        return strcmp((string) ($a['nickname'] ?? ''), (string) ($b['nickname'] ?? ''));
    });
    return array_values($out);
}

function friend_has(array $store, string $a, string $b): bool
{
    $a = account_norm($a);
    $b = account_norm($b);
    if ($a === '' || $b === '') return false;
    foreach (friend_list_for($store, $a) as $f) {
        if ((string) ($f['username'] ?? '') === $b) return true;
    }
    return false;
}

function friend_add(array &$store, string $a, string $b): bool
{
    $a = account_norm($a);
    $b = account_norm($b);
    if ($a === '' || $b === '' || $a === $b) return false;
    $friends = isset($store['friends']) && is_array($store['friends']) ? $store['friends'] : array();
    $changed = false;
    foreach (array($a => $b, $b => $a) as $owner => $other) {
        $list = isset($friends[$owner]) && is_array($friends[$owner]) ? $friends[$owner] : array();
        $found = false;
        foreach ($list as $row) {
            $name = is_array($row) ? (string) ($row['username'] ?? '') : (string) $row;
            if (account_norm($name) === $other) { $found = true; break; }
        }
        if (!$found) {
            $list[] = array('username' => $other, 'since' => time());
            $friends[$owner] = $list;
            $changed = true;
        }
    }
    $store['friends'] = $friends;
    return $changed;
}

function friend_remove(array &$store, string $a, string $b): bool
{
    $a = account_norm($a);
    $b = account_norm($b);
    $friends = isset($store['friends']) && is_array($store['friends']) ? $store['friends'] : array();
    $changed = false;
    foreach (array($a, $b) as $owner) {
        $list = isset($friends[$owner]) && is_array($friends[$owner]) ? $friends[$owner] : array();
        $kept = array();
        foreach ($list as $row) {
            $name = is_array($row) ? (string) ($row['username'] ?? '') : (string) $row;
            if (account_norm($name) === ($owner === $a ? $b : $a)) {
                $changed = true;
                continue;
            }
            $kept[] = $row;
        }
        $friends[$owner] = $kept;
    }
    $store['friends'] = $friends;
    return $changed;
}

function friend_request_pair(array $store, string $from, string $to): ?array
{
    $from = account_norm($from);
    $to = account_norm($to);
    $reqs = isset($store['friend_requests']) && is_array($store['friend_requests'])
        ? $store['friend_requests'] : array();
    foreach ($reqs as $r) {
        if (!is_array($r)) continue;
        if (account_norm((string) ($r['from'] ?? '')) === $from
            && account_norm((string) ($r['to'] ?? '')) === $to) return $r;
    }
    return null;
}

// ---------- 会员分享 ----------

function share_secret(): string
{
    if (!function_exists('openssl_encrypt') || !function_exists('openssl_decrypt')) {
        out(array('code' => 500, 'ok' => false, 'message' => '服务器缺少 openssl，无法安全保存分享 Cookie'), 500);
    }
    $file = sec_security_dir() . '/share.key';
    if (!is_file($file)) {
        $raw = random_bytes(32);
        if (@file_put_contents($file, $raw, LOCK_EX) === false) {
            out(array('code' => 500, 'ok' => false, 'message' => '无法创建分享密钥'), 500);
        }
        @chmod($file, 0600);
    }
    $raw = @file_get_contents($file);
    if (!is_string($raw) || strlen($raw) < 16) {
        out(array('code' => 500, 'ok' => false, 'message' => '分享密钥不可用'), 500);
    }
    return hash('sha256', $raw, true);
}

function share_encrypt(string $plain): string
{
    if ($plain === '') return '';
    $key = share_secret();
    $iv = random_bytes(12);
    $tag = '';
    $cipher = openssl_encrypt($plain, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
    if ($cipher === false) return '';
    return base64_encode($iv . $tag . $cipher);
}

function share_decrypt(string $blob): string
{
    if ($blob === '') return '';
    $raw = base64_decode($blob, true);
    if (!is_string($raw) || strlen($raw) < 29) return '';
    $iv = substr($raw, 0, 12);
    $tag = substr($raw, 12, 16);
    $cipher = substr($raw, 28);
    $plain = openssl_decrypt($cipher, 'aes-256-gcm', share_secret(), OPENSSL_RAW_DATA, $iv, $tag);
    return is_string($plain) ? $plain : '';
}

function share_is_active(array $s): bool
{
    return !empty($s['active']) && (int) ($s['expire_at'] ?? 0) > time();
}

function share_view(array $store, array $s): array
{
    $owner = account_norm((string) ($s['owner'] ?? ''));
    $target = account_norm((string) ($s['target'] ?? ''));
    return array(
        'id' => (string) ($s['id'] ?? ''),
        'owner' => $owner,
        'owner_nickname' => account_display($store, $owner),
        'target' => $target,
        'target_nickname' => account_display($store, $target),
        'bili_name' => (string) ($s['bili_name'] ?? ''),
        'days' => (int) ($s['days'] ?? 0),
        'created_at' => (int) ($s['created_at'] ?? 0),
        'expire_at' => (int) ($s['expire_at'] ?? 0),
        'active' => share_is_active($s),
        'last_play_at' => (int) ($s['last_play_at'] ?? 0),
        'last_error' => (string) ($s['last_error'] ?? ''),
    );
}

function share_cookie_cleanup(array &$store, string $owner): void
{
    $owner = account_norm($owner);
    $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
    $still = false;
    foreach ($shares as $s) {
        if (!is_array($s)) continue;
        if (account_norm((string) ($s['owner'] ?? '')) !== $owner) continue;
        if (share_is_active($s)) { $still = true; break; }
    }
    if (!$still) {
        $cookies = isset($store['share_cookies']) && is_array($store['share_cookies'])
            ? $store['share_cookies'] : array();
        unset($cookies[$owner]);
        $store['share_cookies'] = $cookies;
    }
}

$action = isset($_GET['action']) ? (string) $_GET['action'] : 'config';
$ip = sec_client_ip();

// 全局 IP 限流：正常 App 轮询 30 秒一次，300 次/分钟足够宽松
rate_or_out('api_ip:' . $ip, 300, 60);

if ($action === 'ping') {
    out(array(
        'code' => 0,
        'ok' => true,
        'service' => 'bili-config',
        'php' => PHP_VERSION,
        'time' => gmdate('c'),
        'update_at' => isset($store['updated_at']) ? (int) $store['updated_at'] : 0,
    ));
}

if ($action === 'profile') {
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
        out(array('code' => 405, 'ok' => false, 'message' => '请用 POST'), 405);
    }
    header('Cache-Control: no-store, no-cache, must-revalidate');
    header('Pragma: no-cache');
    rate_or_out('profile_ip:' . $ip, 30, 3600, '账户上报过于频繁');
    $pkey = isset($_POST['key']) ? (string) $_POST['key'] : '';
    if ($pkey === '' && isset($_SERVER['HTTP_X_BILI_KEY'])) {
        $pkey = (string) $_SERVER['HTTP_X_BILI_KEY'];
    }
    $raw = file_get_contents('php://input');
    if ($pkey === '' && is_string($raw) && $raw !== '') {
        $json = json_decode($raw, true);
        if (is_array($json) && isset($json['key'])) {
            $pkey = (string) $json['key'];
        }
    }
    if (!hash_equals((string) $store['client_key'], $pkey)) {
        out(array('code' => 403, 'ok' => false, 'message' => 'client key 无效'), 403);
    }

    $body = $_POST;
    if (empty($body) && is_string($raw) && $raw !== '') {
        $json = json_decode($raw, true);
        if (is_array($json)) {
            $body = $json;
        }
    }

    $device = trim((string) ($body['device_id'] ?? ''));
    if (!preg_match('/^[A-Za-z0-9_-]{8,64}$/', $device)) {
        out(array('code' => 400, 'ok' => false, 'message' => 'device_id 格式不正确'), 400);
    }
    $nick = trim((string) ($body['nickname'] ?? ''));
    $nick = function_exists('mb_substr') ? mb_substr($nick, 0, 16, 'UTF-8') : substr($nick, 0, 48);
    if ($nick === '') {
        $nick = '匿名用户';
    }
    $ver = substr(trim((string) ($body['version'] ?? '')), 0, 24);
    $model = substr(trim((string) ($body['model'] ?? '')), 0, 48);

    $profiles = isset($store['profiles']) && is_array($store['profiles']) ? $store['profiles'] : array();
    if (empty($profiles[$device]['token'])) {
        $profiles[$device]['token'] = bin2hex(random_bytes(24));
    }
    $profiles[$device] = array(
        'device_id' => $device,
        'nickname' => $nick,
        'version' => $ver,
        'model' => $model,
        'token' => $profiles[$device]['token'],
        'at' => time(),
    );
    if (count($profiles) > 500) {
        uasort($profiles, static function ($a, $b) {
            return ((int) ($b['at'] ?? 0)) <=> ((int) ($a['at'] ?? 0));
        });
        $profiles = array_slice($profiles, 0, 500, true);
    }
    $store['profiles'] = $profiles;
    if (!store_save($store)) {
        out(array('code' => 500, 'ok' => false, 'message' => '写入失败，请检查 data/ 权限'), 500);
    }
    out(array(
        'code' => 0,
        'ok' => true,
        'profile' => $profiles[$device],
        'device_token' => $profiles[$device]['token'],
        'total' => count($profiles),
    ));
}

if (in_array($action, array(
    'account_register', 'account_login', 'account_me', 'account_logout',
    'account_invite_gen', 'account_invites',
    'friend_list', 'friend_search', 'friend_request', 'friend_requests',
    'friend_accept', 'friend_reject', 'friend_remove',
    'share_create', 'share_list', 'share_revoke', 'share_revoke_all',
    'share_refresh', 'share_playurl'
), true)) {
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
        out(array('code' => 405, 'ok' => false, 'message' => '请用 POST'), 405);
    }
    header('Cache-Control: no-store, no-cache, must-revalidate');
    header('Pragma: no-cache');
    $body = request_body();
    require_client($store, $body);
    $device = trim((string) ($body['device_id'] ?? ''));
    if (!valid_device($device)) {
        out(array('code' => 400, 'ok' => false, 'message' => 'device_id 格式不正确'), 400);
    }

    if ($action === 'account_register') {
        rate_or_out('register_ip:' . $ip, 8, 3600, '注册尝试过多，请稍后再试');
        $invite = strtoupper(trim((string) ($body['invite'] ?? '')));
        $invite = preg_replace('/[^A-Z0-9-]/', '', $invite);
        $username = trim((string) ($body['username'] ?? ''));
        if (!preg_match('/^[A-Za-z0-9_]{3,24}$/', $username)) {
            out(array('code' => 400, 'ok' => false, 'message' => '用户名需为 3-24 位字母、数字或下划线'), 400);
        }
        $password = (string) ($body['password'] ?? '');
        if (strlen($password) < 8 || strlen($password) > 64) {
            out(array('code' => 400, 'ok' => false, 'message' => '密码需为 8-64 位'), 400);
        }
        $nick = trim((string) ($body['nickname'] ?? ''));
        $nick = function_exists('mb_substr') ? mb_substr($nick, 0, 16, 'UTF-8') : substr($nick, 0, 48);
        if ($nick === '') $nick = $username;

        $invites = isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array();
        $inv = isset($invites[$invite]) && is_array($invites[$invite]) ? $invites[$invite] : null;
        if (!is_array($inv)) {
            out(array('code' => 403, 'ok' => false, 'message' => '邀请码无效'), 403);
        }
        if (!invite_active($inv)) {
            out(array('code' => 403, 'ok' => false, 'message' => '邀请码已过期或已用完'), 403);
        }

        $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
        $u = account_norm($username);
        if (isset($accounts[$u])) {
            out(array('code' => 409, 'ok' => false, 'message' => '用户名已存在'), 409);
        }
        $owner = account_device_owner($store, $device);
        if ($owner !== '' && $owner !== $u) {
            out(array('code' => 409, 'ok' => false, 'message' => '该设备已绑定其他账号'), 409);
        }

        // 管理员邀请注册的账户获得 5 个邀请名额；用户邀请注册的账户没有邀请资格
        $inviter = account_norm((string) ($inv['issuer'] ?? 'admin'));
        $grantInvite = ($inviter === 'admin' || !empty($inv['grant_quota']));
        $accounts[$u] = array(
            'username' => $username,
            'password_hash' => password_hash($password, PASSWORD_DEFAULT),
            'nickname' => $nick,
            'status' => 'active',
            'created_at' => time(),
            // 注册不等于登录；首次 account_me / 密码登录时再写入真实时间
            'last_login' => 0,
            'invite' => $invite,
            'invited_by' => $inviter,
            'invite_quota' => $grantInvite ? 5 : 0,
            'can_invite' => $grantInvite,
            'devices' => array($device => time()),
        );
        $inv['uses'] = (int) ($inv['uses'] ?? 0) + 1;
        $inv['used_by'] = isset($inv['used_by']) && is_array($inv['used_by']) ? $inv['used_by'] : array();
        $inv['used_by'][] = array('username' => $u, 'device' => $device, 'at' => time());
        $invites[$invite] = $inv;
        $store['accounts'] = $accounts;
        $store['invites'] = $invites;

        account_prune_sessions($store);
        $token = account_new_token();
        $expires = time() + 30 * 86400;
        $store['sessions'][$token] = array(
            'username' => $u, 'device_id' => $device,
            'created_at' => time(), 'expires_at' => $expires, 'last_at' => time(),
        );
        if (!store_save($store)) {
            out(array('code' => 500, 'ok' => false, 'message' => '注册写入失败，请稍后再试'), 500);
        }
        out(array(
            'code' => 0, 'ok' => true, 'message' => '注册成功',
            'account_token' => $token,
            'expires_at' => $expires,
            'account' => account_view($accounts[$u]),
        ));
    }

    if ($action === 'account_login') {
        rate_or_out('login_ip:' . $ip, 12, 900, '登录尝试过多，请稍后再试');
        $username = trim((string) ($body['username'] ?? ''));
        $password = (string) ($body['password'] ?? '');
        if ($username === '' || $password === '') {
            out(array('code' => 400, 'ok' => false, 'message' => '请输入账号和密码'), 400);
        }
        rate_or_out('login_user:' . account_norm($username), 10, 900, '该账号登录尝试过多，请稍后再试');
        $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();
        $u = account_norm($username);
        $a = isset($accounts[$u]) && is_array($accounts[$u]) ? $accounts[$u] : null;
        if (!is_array($a) || !password_verify($password, (string) ($a['password_hash'] ?? ''))) {
            out(array('code' => 401, 'ok' => false, 'message' => '账号或密码错误'), 401);
        }
        if ((string) ($a['status'] ?? 'active') !== 'active') {
            out(array('code' => 403, 'ok' => false, 'message' => '账号已被停用，请联系管理员'), 403);
        }
        $owner = account_device_owner($store, $device);
        if ($owner !== '' && $owner !== $u) {
            out(array('code' => 409, 'ok' => false, 'message' => '该设备已绑定其他账号'), 409);
        }

        $a['last_login'] = time();
        $a['devices'] = isset($a['devices']) && is_array($a['devices']) ? $a['devices'] : array();
        $a['devices'][$device] = time();
        $accounts[$u] = $a;
        $store['accounts'] = $accounts;

        account_prune_sessions($store);
        $sessions = isset($store['sessions']) && is_array($store['sessions']) ? $store['sessions'] : array();
        foreach ($sessions as $t => $s) {
            if (is_array($s) && account_norm((string) ($s['username'] ?? '')) === $u && (string) ($s['device_id'] ?? '') === $device) {
                unset($sessions[$t]);
            }
        }
        $token = account_new_token();
        $expires = time() + 30 * 86400;
        $sessions[$token] = array(
            'username' => $u, 'device_id' => $device,
            'created_at' => time(), 'expires_at' => $expires, 'last_at' => time(),
        );
        $store['sessions'] = $sessions;
        if (!store_save($store)) {
            out(array('code' => 500, 'ok' => false, 'message' => '登录写入失败，请稍后再试'), 500);
        }
        out(array(
            'code' => 0, 'ok' => true, 'message' => '登录成功',
            'account_token' => $token,
            'expires_at' => $expires,
            'account' => account_view($accounts[$u]),
        ));
    }

    if ($action === 'account_me') {
        $token = (string) ($body['account_token'] ?? '');
        $s = account_session($store, $token, $device);
        if ($s === null) {
            out(array('code' => 401, 'ok' => false, 'message' => '登录已失效，请重新登录'), 401);
        }
        if (account_touch_activity($store, $s)) {
            store_save($store);
            $u = account_norm((string) $s['username']);
            if (isset($store['accounts'][$u]) && is_array($store['accounts'][$u])) {
                $s['account'] = $store['accounts'][$u];
            }
        }
        out(array(
            'code' => 0, 'ok' => true,
            'account_expires_at' => (int) ($s['session']['expires_at'] ?? 0),
            'account' => account_view($s['account']),
        ));
    }

    if ($action === 'account_invite_gen') {
        $token = (string) ($body['account_token'] ?? '');
        $s = account_session($store, $token, $device);
        if ($s === null) {
            out(array('code' => 401, 'ok' => false, 'message' => '登录已失效，请重新登录'), 401);
        }
        $u = account_norm((string) $s['username']);
        $a = isset($store['accounts'][$u]) && is_array($store['accounts'][$u]) ? $store['accounts'][$u] : null;
        if (!is_array($a)) {
            out(array('code' => 404, 'ok' => false, 'message' => '账号不存在'), 404);
        }
        if (!account_can_invite($a)) {
            out(array('code' => 403, 'ok' => false, 'message' => '该账号没有邀请资格'), 403);
        }
        $quota = account_invite_quota($a);
        if ($quota <= 0) {
            out(array('code' => 403, 'ok' => false, 'message' => '邀请名额已用完'), 403);
        }
        rate_or_out('invite_gen:' . $u, 6, 3600, '邀请码生成过于频繁，请稍后再试');

        $invites = isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array();
        $prefix = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', (string) ($body['prefix'] ?? 'INV')));
        $prefix = substr($prefix, 0, 8);
        if ($prefix === '') $prefix = 'INV';
        $code = make_invite_code($prefix);
        $try = 0;
        while (isset($invites[$code]) && $try++ < 20) $code = make_invite_code($prefix);
        $expires = time() + 30 * 86400;
        $invites[$code] = array(
            'code' => $code,
            'prefix' => $prefix,
            'created_at' => time(),
            'expire_at' => $expires,
            'max_uses' => 1,
            'uses' => 0,
            'enabled' => true,
            'note' => '由 ' . $u . ' 邀请',
            'issuer' => $u,
            'grant_quota' => 0,
            'used_by' => array(),
        );
        $a['invite_quota'] = $quota - 1;
        $a['can_invite'] = true;
        $store['accounts'][$u] = $a;
        $store['invites'] = $invites;
        if (!store_save($store)) {
            out(array('code' => 500, 'ok' => false, 'message' => '邀请码写入失败，请稍后再试'), 500);
        }
        out(array(
            'code' => 0, 'ok' => true, 'message' => '邀请码已生成',
            'invite' => $code,
            'expires_at' => $expires,
            'invite_quota' => $quota - 1,
            'can_invite' => true,
            'account' => account_view($a),
        ));
    }

    if ($action === 'account_invites') {
        $token = (string) ($body['account_token'] ?? '');
        $s = account_session($store, $token, $device);
        if ($s === null) {
            out(array('code' => 401, 'ok' => false, 'message' => '登录已失效，请重新登录'), 401);
        }
        $u = account_norm((string) $s['username']);
        $invites = isset($store['invites']) && is_array($store['invites']) ? $store['invites'] : array();
        $mine = array();
        foreach ($invites as $code => $inv) {
            if (!is_array($inv) || account_norm((string) ($inv['issuer'] ?? '')) !== $u) continue;
            $usedBy = isset($inv['used_by']) && is_array($inv['used_by']) ? $inv['used_by'] : array();
            $users = array();
            foreach ($usedBy as $row) {
                if (is_array($row) && isset($row['username'])) $users[] = (string) $row['username'];
            }
            $mine[] = array(
                'code' => (string) $code,
                'prefix' => (string) ($inv['prefix'] ?? ''),
                'created_at' => (int) ($inv['created_at'] ?? 0),
                'expire_at' => (int) ($inv['expire_at'] ?? 0),
                'max_uses' => (int) ($inv['max_uses'] ?? 1),
                'uses' => (int) ($inv['uses'] ?? 0),
                'enabled' => !empty($inv['enabled']),
                'note' => (string) ($inv['note'] ?? ''),
                'used_users' => $users,
            );
        }
        usort($mine, static function ($a, $b) {
            return ((int) ($b['created_at'] ?? 0)) <=> ((int) ($a['created_at'] ?? 0));
        });
        out(array(
            'code' => 0, 'ok' => true,
            'invite_quota' => account_invite_quota($s['account']),
            'can_invite' => account_can_invite($s['account']),
            'invites' => $mine,
            'account' => account_view($s['account']),
        ));
    }

    if (in_array($action, array(
        'friend_list', 'friend_search', 'friend_request', 'friend_requests',
        'friend_accept', 'friend_reject', 'friend_remove',
        'share_create', 'share_list', 'share_revoke', 'share_revoke_all',
        'share_refresh', 'share_playurl'
    ), true)) {
        $token = (string) ($body['account_token'] ?? '');
        $s = account_session($store, $token, $device);
        if ($s === null) {
            out(array('code' => 401, 'ok' => false, 'message' => '登录已失效，请重新登录'), 401);
        }
        $me = account_norm((string) ($s['username'] ?? ''));
        if ($me === '') {
            out(array('code' => 401, 'ok' => false, 'message' => '账号状态异常'), 401);
        }
        $accounts = isset($store['accounts']) && is_array($store['accounts']) ? $store['accounts'] : array();

        if ($action === 'friend_list') {
            rate_or_out('friend_list:' . $me, 40, 60);
            if (account_touch_activity($store, $s)) {
                store_save($store);
            }
            out(array(
                'code' => 0,
                'ok' => true,
                'friends' => friend_list_for($store, $me),
            ));
        }

        if ($action === 'friend_search') {
            rate_or_out('friend_search:' . $me, 30, 60);
            $name = account_norm((string) ($body['username'] ?? ''));
            if ($name === '' || !preg_match('/^[a-z0-9_]{3,24}$/', $name)) {
                out(array('code' => 400, 'ok' => false, 'message' => '用户名格式不正确'), 400);
            }
            if ($name === $me) {
                out(array('code' => 400, 'ok' => false, 'message' => '不能添加自己'), 400);
            }
            if (!isset($accounts[$name]) || !is_array($accounts[$name])) {
                out(array('code' => 404, 'ok' => false, 'message' => '用户不存在'), 404);
            }
            $isFriend = friend_has($store, $me, $name);
            $pending = friend_request_pair($store, $me, $name);
            out(array(
                'code' => 0,
                'ok' => true,
                'user' => array(
                    'username' => $name,
                    'nickname' => account_display($store, $name),
                    'friend' => $isFriend,
                    'requested' => is_array($pending),
                ),
            ));
        }

        if ($action === 'friend_request') {
            rate_or_out('friend_request:' . $me, 12, 3600, '好友申请过于频繁');
            $name = account_norm((string) ($body['username'] ?? ''));
            if ($name === '' || !preg_match('/^[a-z0-9_]{3,24}$/', $name)) {
                out(array('code' => 400, 'ok' => false, 'message' => '用户名格式不正确'), 400);
            }
            if ($name === $me) {
                out(array('code' => 400, 'ok' => false, 'message' => '不能添加自己'), 400);
            }
            if (!isset($accounts[$name]) || !is_array($accounts[$name])) {
                out(array('code' => 404, 'ok' => false, 'message' => '用户不存在'), 404);
            }
            if (friend_has($store, $me, $name)) {
                out(array('code' => 409, 'ok' => false, 'message' => '已经是好友'), 409);
            }
            if (friend_request_pair($store, $me, $name) !== null) {
                out(array('code' => 409, 'ok' => false, 'message' => '已发送过申请'), 409);
            }
            if (friend_request_pair($store, $name, $me) !== null) {
                out(array('code' => 409, 'ok' => false, 'message' => '对方已向你发送申请，请在申请列表处理'), 409);
            }
            $reqs = isset($store['friend_requests']) && is_array($store['friend_requests'])
                ? $store['friend_requests'] : array();
            $outCount = 0;
            foreach ($reqs as $r) {
                if (is_array($r) && account_norm((string) ($r['from'] ?? '')) === $me) $outCount++;
            }
            if ($outCount >= 10) {
                out(array('code' => 429, 'ok' => false, 'message' => '待处理申请过多，请先处理'), 429);
            }
            $id = bin2hex(random_bytes(8));
            $reqs[$id] = array(
                'id' => $id,
                'from' => $me,
                'to' => $name,
                'created_at' => time(),
            );
            $store['friend_requests'] = $reqs;
            if (!store_save($store)) {
                out(array('code' => 500, 'ok' => false, 'message' => '好友申请写入失败'), 500);
            }
            out(array('code' => 0, 'ok' => true, 'message' => '好友申请已发送', 'id' => $id));
        }

        if ($action === 'friend_requests') {
            rate_or_out('friend_requests:' . $me, 40, 60);
            $reqs = isset($store['friend_requests']) && is_array($store['friend_requests'])
                ? $store['friend_requests'] : array();
            $incoming = array();
            $outgoing = array();
            foreach ($reqs as $r) {
                if (!is_array($r)) continue;
                $from = account_norm((string) ($r['from'] ?? ''));
                $to = account_norm((string) ($r['to'] ?? ''));
                if ($to === $me) {
                    $incoming[] = array(
                        'id' => (string) ($r['id'] ?? ''),
                        'from' => $from,
                        'from_nickname' => account_display($store, $from),
                        'created_at' => (int) ($r['created_at'] ?? 0),
                    );
                } elseif ($from === $me) {
                    $outgoing[] = array(
                        'id' => (string) ($r['id'] ?? ''),
                        'to' => $to,
                        'to_nickname' => account_display($store, $to),
                        'created_at' => (int) ($r['created_at'] ?? 0),
                    );
                }
            }
            out(array('code' => 0, 'ok' => true, 'incoming' => $incoming, 'outgoing' => $outgoing));
        }

        if ($action === 'friend_accept' || $action === 'friend_reject') {
            $id = strtolower(trim((string) ($body['id'] ?? '')));
            if ($id === '' || !preg_match('/^[a-f0-9]{16}$/', $id)) {
                out(array('code' => 400, 'ok' => false, 'message' => '申请编号不正确'), 400);
            }
            $reqs = isset($store['friend_requests']) && is_array($store['friend_requests'])
                ? $store['friend_requests'] : array();
            $r = isset($reqs[$id]) && is_array($reqs[$id]) ? $reqs[$id] : null;
            if (!is_array($r) || account_norm((string) ($r['to'] ?? '')) !== $me) {
                out(array('code' => 404, 'ok' => false, 'message' => '申请不存在或已处理'), 404);
            }
            $from = account_norm((string) ($r['from'] ?? ''));
            if ($action === 'friend_accept') {
                friend_add($store, $me, $from);
            }
            unset($reqs[$id]);
            $store['friend_requests'] = $reqs;
            if (!store_save($store)) {
                out(array('code' => 500, 'ok' => false, 'message' => '好友关系写入失败'), 500);
            }
            out(array(
                'code' => 0,
                'ok' => true,
                'message' => $action === 'friend_accept' ? '已添加好友' : '已拒绝申请',
            ));
        }

        if ($action === 'friend_remove') {
            $name = account_norm((string) ($body['username'] ?? ''));
            if (!friend_has($store, $me, $name)) {
                out(array('code' => 404, 'ok' => false, 'message' => '不是好友'), 404);
            }
            friend_remove($store, $me, $name);
            $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
            foreach ($shares as $sid => $share) {
                if (!is_array($share)) continue;
                $owner = account_norm((string) ($share['owner'] ?? ''));
                $target = account_norm((string) ($share['target'] ?? ''));
                if (($owner === $me && $target === $name) || ($owner === $name && $target === $me)) {
                    $shares[$sid]['active'] = false;
                    $shares[$sid]['updated_at'] = time();
                }
            }
            $store['shares'] = $shares;
            share_cookie_cleanup($store, $me);
            share_cookie_cleanup($store, $name);
            if (!store_save($store)) {
                out(array('code' => 500, 'ok' => false, 'message' => '好友关系写入失败'), 500);
            }
            out(array('code' => 0, 'ok' => true, 'message' => '已删除好友，相关分享已停止'));
        }

        if ($action === 'share_create') {
            rate_or_out('share_create:' . $me, 20, 3600, '分享操作过于频繁');
            $target = account_norm((string) ($body['target'] ?? ''));
            $days = max(1, min(365, (int) ($body['days'] ?? 30)));
            if (!friend_has($store, $me, $target)) {
                out(array('code' => 403, 'ok' => false, 'message' => '只能分享给好友'), 403);
            }
            $raw = trim((string) ($body['cookie'] ?? ''));
            if ($raw === '' || strlen($raw) > 8192) {
                out(array('code' => 400, 'ok' => false, 'message' => '分享 Cookie 为空或过长'), 400);
            }
            $nav = bili_json('https://api.bilibili.com/x/web-interface/nav', $raw);
            $navData = isset($nav['data']) && is_array($nav['data']) ? $nav['data'] : array();
            if ((int) ($nav['code'] ?? -1) !== 0 || empty($navData['isLogin'])) {
                out(array('code' => 403, 'ok' => false, 'message' => '分享者 B站 Cookie 未登录或已失效'), 403);
            }
            if ((int) ($navData['vipStatus'] ?? 0) !== 1 || (int) ($navData['vipDueDate'] ?? 0) <= time() * 1000) {
                out(array('code' => 403, 'ok' => false, 'message' => '分享者当前不是有效 B站大会员'), 403);
            }
            $filtered = playback_cookie($raw);
            if (stripos($filtered, 'SESSDATA=') === false) {
                out(array('code' => 400, 'ok' => false, 'message' => 'Cookie 缺少播放所需的 SESSDATA'), 400);
            }
            $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
            $activeCount = 0;
            $existingId = '';
            foreach ($shares as $sid => $share) {
                if (!is_array($share) || !share_is_active($share)) continue;
                if (account_norm((string) ($share['owner'] ?? '')) === $me) {
                    $activeCount++;
                    if (account_norm((string) ($share['target'] ?? '')) === $target) $existingId = (string) $sid;
                }
            }
            if ($existingId === '' && $activeCount >= 5) {
                out(array('code' => 429, 'ok' => false, 'message' => '同时最多分享给 5 位好友，请先撤回一个'), 429);
            }
            if ($existingId !== '') {
                $newExpire = max((int) ($shares[$existingId]['expire_at'] ?? 0), time() + $days * 86400);
                $shares[$existingId]['expire_at'] = $newExpire;
                $shares[$existingId]['days'] = max(1, (int) ceil(($newExpire - time()) / 86400));
                $shares[$existingId]['active'] = true;
                $shares[$existingId]['bili_name'] = (string) ($navData['uname'] ?? '');
                $shares[$existingId]['bili_mid'] = (int) ($navData['mid'] ?? 0);
                $shares[$existingId]['updated_at'] = time();
                $sid = $existingId;
            } else {
                $sid = bin2hex(random_bytes(10));
                $shares[$sid] = array(
                    'id' => $sid,
                    'owner' => $me,
                    'target' => $target,
                    'days' => $days,
                    'created_at' => time(),
                    'expire_at' => time() + $days * 86400,
                    'active' => true,
                    'bili_name' => (string) ($navData['uname'] ?? ''),
                    'bili_mid' => (int) ($navData['mid'] ?? 0),
                    'updated_at' => time(),
                );
            }
            $cookies = isset($store['share_cookies']) && is_array($store['share_cookies'])
                ? $store['share_cookies'] : array();
            $enc = share_encrypt($filtered);
            if ($enc === '') {
                out(array('code' => 500, 'ok' => false, 'message' => '分享 Cookie 加密失败'), 500);
            }
            $cookies[$me] = array(
                'cookie' => $enc,
                'mid' => (int) ($navData['mid'] ?? 0),
                'name' => (string) ($navData['uname'] ?? ''),
                'updated_at' => time(),
            );
            $store['shares'] = $shares;
            $store['share_cookies'] = $cookies;
            if (!store_save($store)) {
                out(array('code' => 500, 'ok' => false, 'message' => '分享记录写入失败'), 500);
            }
            out(array(
                'code' => 0,
                'ok' => true,
                'message' => '会员已分享',
                'share' => share_view($store, $shares[$sid]),
            ));
        }

        if ($action === 'share_list') {
            rate_or_out('share_list:' . $me, 40, 60);
            $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
            $outgoing = array();
            $incoming = array();
            foreach ($shares as $share) {
                if (!is_array($share)) continue;
                $owner = account_norm((string) ($share['owner'] ?? ''));
                $target = account_norm((string) ($share['target'] ?? ''));
                if ($owner === $me) $outgoing[] = share_view($store, $share);
                if ($target === $me) $incoming[] = share_view($store, $share);
            }
            $sort = static function ($a, $b) {
                if (($a['active'] ?? false) !== ($b['active'] ?? false)) {
                    return ($a['active'] ?? false) ? -1 : 1;
                }
                return ((int) ($b['expire_at'] ?? 0)) <=> ((int) ($a['expire_at'] ?? 0));
            };
            usort($outgoing, $sort);
            usort($incoming, $sort);
            out(array('code' => 0, 'ok' => true, 'outgoing' => $outgoing, 'incoming' => $incoming));
        }

        if ($action === 'share_revoke') {
            $id = strtolower(trim((string) ($body['id'] ?? '')));
            $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
            if ($id === '' || !isset($shares[$id]) || !is_array($shares[$id])) {
                out(array('code' => 404, 'ok' => false, 'message' => '分享记录不存在'), 404);
            }
            if (account_norm((string) ($shares[$id]['owner'] ?? '')) !== $me) {
                out(array('code' => 403, 'ok' => false, 'message' => '只能撤回自己的分享'), 403);
            }
            $shares[$id]['active'] = false;
            $shares[$id]['updated_at'] = time();
            $store['shares'] = $shares;
            share_cookie_cleanup($store, $me);
            if (!store_save($store)) {
                out(array('code' => 500, 'ok' => false, 'message' => '撤回失败'), 500);
            }
            out(array('code' => 0, 'ok' => true, 'message' => '已撤回分享'));
        }

        if ($action === 'share_revoke_all') {
            $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
            $count = 0;
            foreach ($shares as $sid => $share) {
                if (!is_array($share)) continue;
                if (account_norm((string) ($share['owner'] ?? '')) !== $me) continue;
                if (share_is_active($share)) $count++;
                $shares[$sid]['active'] = false;
                $shares[$sid]['updated_at'] = time();
            }
            $store['shares'] = $shares;
            share_cookie_cleanup($store, $me);
            if (!store_save($store)) {
                out(array('code' => 500, 'ok' => false, 'message' => '撤回失败'), 500);
            }
            out(array('code' => 0, 'ok' => true, 'message' => '已撤回全部分享', 'count' => $count));
        }

        if ($action === 'share_refresh') {
            rate_or_out('share_refresh:' . $me, 20, 3600);
            $raw = trim((string) ($body['cookie'] ?? ''));
            if ($raw === '' || strlen($raw) > 8192) {
                out(array('code' => 400, 'ok' => false, 'message' => 'Cookie 为空或过长'), 400);
            }
            $nav = bili_json('https://api.bilibili.com/x/web-interface/nav', $raw);
            $navData = isset($nav['data']) && is_array($nav['data']) ? $nav['data'] : array();
            if ((int) ($nav['code'] ?? -1) !== 0 || empty($navData['isLogin'])) {
                out(array('code' => 403, 'ok' => false, 'message' => 'B站 Cookie 未登录或已失效'), 403);
            }
            if ((int) ($navData['vipStatus'] ?? 0) !== 1 || (int) ($navData['vipDueDate'] ?? 0) <= time() * 1000) {
                out(array('code' => 403, 'ok' => false, 'message' => '当前账号不是有效 B站大会员'), 403);
            }
            $filtered = playback_cookie($raw);
            if (stripos($filtered, 'SESSDATA=') === false) {
                out(array('code' => 400, 'ok' => false, 'message' => 'Cookie 缺少播放所需的 SESSDATA'), 400);
            }
            $enc = share_encrypt($filtered);
            if ($enc === '') {
                out(array('code' => 500, 'ok' => false, 'message' => '分享 Cookie 加密失败'), 500);
            }
            $cookies = isset($store['share_cookies']) && is_array($store['share_cookies'])
                ? $store['share_cookies'] : array();
            $cookies[$me] = array(
                'cookie' => $enc,
                'mid' => (int) ($navData['mid'] ?? 0),
                'name' => (string) ($navData['uname'] ?? ''),
                'updated_at' => time(),
            );
            $store['share_cookies'] = $cookies;
            if (!store_save($store)) {
                out(array('code' => 500, 'ok' => false, 'message' => '刷新失败'), 500);
            }
            $active = 0;
            $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
            foreach ($shares as $share) {
                if (is_array($share) && account_norm((string) ($share['owner'] ?? '')) === $me && share_is_active($share)) $active++;
            }
            out(array('code' => 0, 'ok' => true, 'message' => '分享 Cookie 已更新', 'active' => $active));
        }

        if ($action === 'share_playurl') {
            rate_or_out('share_playurl:' . $me, 40, 60, '共享播放解析过于频繁');
            $id = strtolower(trim((string) ($body['share_id'] ?? '')));
            $ep = preg_replace('/\D/', '', (string) ($body['ep_id'] ?? '0'));
            $qn = max(16, min(127, (int) ($body['qn'] ?? 80)));
            if ($id === '' || $ep === '' || $ep === '0') {
                out(array('code' => 400, 'ok' => false, 'message' => '缺少分享编号或 ep_id'), 400);
            }
            $shares = isset($store['shares']) && is_array($store['shares']) ? $store['shares'] : array();
            $share = isset($shares[$id]) && is_array($shares[$id]) ? $shares[$id] : null;
            if (!is_array($share) || account_norm((string) ($share['target'] ?? '')) !== $me) {
                out(array('code' => 403, 'ok' => false, 'message' => '分享不存在或不属于当前账号'), 403);
            }
            if (!share_is_active($share)) {
                out(array('code' => 410, 'ok' => false, 'message' => '分享已结束或已过期'), 410);
            }
            $owner = account_norm((string) ($share['owner'] ?? ''));
            $cookies = isset($store['share_cookies']) && is_array($store['share_cookies'])
                ? $store['share_cookies'] : array();
            $rec = isset($cookies[$owner]) && is_array($cookies[$owner]) ? $cookies[$owner] : null;
            $cookie = is_array($rec) ? share_decrypt((string) ($rec['cookie'] ?? '')) : '';
            if ($cookie === '' || stripos($cookie, 'SESSDATA=') === false) {
                out(array('code' => 410, 'ok' => false, 'message' => '分享者的播放 Cookie 已失效，请让对方重新绑定或刷新分享'), 410);
            }
            $url = 'https://api.bilibili.com/pgc/player/web/playurl?' . http_build_query(array(
                'ep_id' => $ep, 'qn' => $qn, 'fnval' => 4048, 'fnver' => 0, 'fourk' => 1,
            ));
            $json = bili_json($url, $cookie);
            if ((int) ($json['code'] ?? -1) !== 0) {
                $shares[$id]['last_error'] = (string) ($json['message'] ?? 'B站解析失败');
                $shares[$id]['updated_at'] = time();
                $store['shares'] = $shares;
                store_save($store);
                out(array(
                    'code' => 502,
                    'ok' => false,
                    'bili_code' => (int) ($json['code'] ?? -1),
                    'message' => 'B站共享播放失败: ' . (string) ($json['message'] ?? 'unknown'),
                ), 502);
            }
            $result = isset($json['result']) && is_array($json['result']) ? $json['result'] : null;
            if (!is_array($result)) {
                out(array('code' => 502, 'ok' => false, 'message' => 'B站返回缺少播放数据'), 502);
            }
            $shares[$id]['last_play_at'] = time();
            $shares[$id]['last_error'] = '';
            $store['shares'] = $shares;
            store_save($store);
            out(array(
                'code' => 0,
                'ok' => true,
                'mode' => 'pgc',
                'quality' => (int) ($result['quality'] ?? $qn),
                'result' => $result,
            ));
        }
    }

    if ($action === 'account_logout') {
        $token = (string) ($body['account_token'] ?? '');
        if ($token !== '' && isset($store['sessions'][$token])) {
            unset($store['sessions'][$token]);
            store_save($store);
        }
        out(array('code' => 0, 'ok' => true, 'message' => '已退出登录'));
    }
}

if (in_array($action, array('status', 'activate', 'history_get', 'history_save', 'history_clear'), true)) {
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
        out(array('code' => 405, 'ok' => false, 'message' => '请用 POST'), 405);
    }
    $body = request_body();
    require_client($store, $body);
    $device = trim((string) ($body['device_id'] ?? ''));
    if (!valid_device($device)) {
        out(array('code' => 400, 'ok' => false, 'message' => 'device_id 格式不正确'), 400);
    }
    rate_or_out('api_dev:' . $device, 120, 60);

    if ($action === 'status') {
        out(array(
            'code' => 0,
            'ok' => true,
            'member' => member_view($store, $device),
            'history_count' => count(history_for($store, $device)),
            'server_time' => gmdate('c'),
        ));
    }

    if ($action === 'activate') {
        rate_or_out('activate:' . $device, 6, 600, '激活尝试过多，请稍后再试');
        $code = strtoupper(trim((string) ($body['code'] ?? '')));
        $code = preg_replace('/[^A-Z0-9-]/', '', $code);
        $codes = isset($store['codes']) && is_array($store['codes']) ? $store['codes'] : array();
        if ($code === '' || !isset($codes[$code]) || !is_array($codes[$code])) {
            out(array('code' => 404, 'ok' => false, 'message' => '激活码无效'), 404);
        }
        $c = $codes[$code];
        if (!empty($c['used_by'])) {
            out(array('code' => 409, 'ok' => false, 'message' => '激活码已被使用'), 409);
        }
        if (!empty($c['code_expire_at']) && (int) $c['code_expire_at'] < time()) {
            out(array('code' => 410, 'ok' => false, 'message' => '激活码已过期'), 410);
        }
        $days = max(1, (int) ($c['days'] ?? 30));
        $plan = (string) ($c['plan'] ?? 'month');
        $member = extend_member($store, $device, $days, $plan, 'code');
        $c['used_by'] = $device;
        $c['used_at'] = time();
        $store['codes'][$code] = $c;
        if (!store_save($store)) {
            out(array('code' => 500, 'ok' => false, 'message' => '写入失败，请稍后重试'), 500);
        }
        out(array(
            'code' => 0,
            'ok' => true,
            'message' => '激活成功',
            'member' => $member,
        ));
    }

    if ($action === 'history_get') {
        out(array(
            'code' => 0,
            'ok' => true,
            'items' => history_for($store, $device),
            'member' => member_view($store, $device),
        ));
    }

    if ($action === 'history_save') {
        rate_or_out('history_save:' . $device, 60, 60, '同步过于频繁');
        $raw = $body['items'] ?? array();
        if (is_string($raw)) {
            $raw = json_decode($raw, true);
        }
        if (!is_array($raw)) {
            out(array('code' => 400, 'ok' => false, 'message' => 'items 格式不正确'), 400);
        }
        $incoming = clean_history($raw);
        $ok = store_history_save($device, $incoming);
        if (!$ok) {
            out(array('code' => 500, 'ok' => false, 'message' => '写入失败，请检查 data/history/ 权限'), 500);
        }
        $count = count(store_history_load($device));
        out(array('code' => 0, 'ok' => true, 'count' => $count));
    }

    if ($action === 'history_clear') {
        if (!store_history_clear($device)) {
            out(array('code' => 500, 'ok' => false, 'message' => '清除失败，请稍后重试'), 500);
        }
        out(array('code' => 0, 'ok' => true));
    }
}

if ($action === 'playurl') {
    out(array('code' => 410, 'ok' => false, 'message' => '云端解析已停用，请使用客户端本地解析'), 410);
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
        out(array('code' => 405, 'ok' => false, 'message' => '请用 POST'), 405);
    }
    $body = request_body();
    require_client($store, $body);
    $device = trim((string) ($body['device_id'] ?? ''));
    if (!valid_device($device)) {
        out(array('code' => 400, 'ok' => false, 'message' => 'device_id 格式不正确'), 400);
    }
    $profile = isset($store['profiles'][$device]) && is_array($store['profiles'][$device])
        ? $store['profiles'][$device] : null;
    if (!is_array($profile) || empty($profile['token'])) {
        out(array('code' => 403, 'ok' => false, 'message' => '设备未注册，请先同步账户'), 403);
    }
    $token = (string) ($body['token'] ?? '');
    if (!hash_equals((string) $profile['token'], $token)) {
        out(array('code' => 403, 'ok' => false, 'message' => '设备令牌无效'), 403);
    }
    $ts = (int) ($body['ts'] ?? 0);
    if ($ts <= 0 || abs(time() - $ts) > 300) {
        out(array('code' => 401, 'ok' => false, 'message' => '请求已过期'), 401);
    }
    $bvid = preg_replace('/[^A-Za-z0-9]/', '', (string) ($body['bvid'] ?? ''));
    $cid = preg_replace('/\D/', '', (string) ($body['cid'] ?? '0'));
    $ep = preg_replace('/\D/', '', (string) ($body['ep_id'] ?? '0'));
    $qn = max(16, min(127, (int) ($body['qn'] ?? 80)));
    if ($ep === '0' && ($bvid === '' || $cid === '')) {
        out(array('code' => 400, 'ok' => false, 'message' => '缺少 bvid/cid 或 ep_id'), 400);
    }
    $signText = $device . '|' . $ts . '|' . $bvid . '|' . $cid . '|' . $ep . '|' . $qn;
    $sig = (string) ($body['sig'] ?? '');
    if (!hash_equals(hash_hmac('sha256', $signText, (string) $profile['token']), $sig)) {
        out(array('code' => 403, 'ok' => false, 'message' => '签名无效'), 403);
    }
    $accountToken = (string) ($body['account_token'] ?? '');
    if (account_session($store, $accountToken, $device) === null) {
        out(array('code' => 401, 'ok' => false, 'message' => '登录已失效，请重新登录'), 401);
    }
    rate_or_out('playurl:' . $device, 40, 60, '播放解析过于频繁');

    if (!empty($store['lock']['locked'])) {
        out(array(
            'code' => 423,
            'ok' => false,
            'message' => (string) ($store['lock']['message'] ?? '服务已暂停'),
        ), 423);
    }
    $cookie = (string) ($store['cookie'] ?? '');
    if ($cookie === '') {
        out(array('code' => 500, 'ok' => false, 'message' => '云端 Cookie 未配置'), 500);
    }

    if ($ep !== '0') {
        $url = 'https://api.bilibili.com/pgc/player/web/playurl?' . http_build_query(array(
            'ep_id' => $ep, 'qn' => $qn, 'fnval' => 4048, 'fnver' => 0, 'fourk' => 1,
        ));
        $json = bili_json($url, $cookie);
        if ((int) ($json['code'] ?? -1) !== 0) {
            out(array(
                'code' => 502,
                'ok' => false,
                'mode' => 'pgc',
                'bili_code' => (int) ($json['code'] ?? -1),
                'message' => 'B 站 PGC playurl 错误: ' . (string) ($json['message'] ?? 'unknown'),
            ), 502);
        }
        $data = $json['result'] ?? null;
        $mode = 'pgc';
    } else {
        $url = 'https://api.bilibili.com/x/player/playurl?' . http_build_query(array(
            'bvid' => $bvid, 'cid' => $cid, 'qn' => $qn, 'fnval' => 4048, 'fnver' => 0, 'fourk' => 1,
        ));
        $json = bili_json($url, $cookie);
        if ((int) ($json['code'] ?? -1) !== 0) {
            out(array(
                'code' => 502,
                'ok' => false,
                'mode' => 'ugc',
                'bili_code' => (int) ($json['code'] ?? -1),
                'message' => 'B 站 playurl 错误: ' . (string) ($json['message'] ?? 'unknown'),
            ), 502);
        }
        $data = $json['data'] ?? null;
        $mode = 'ugc';
    }
    if (!is_array($data)) {
        out(array('code' => 502, 'ok' => false, 'message' => 'B 站返回缺少播放数据'), 502);
    }
    out(array('code' => 0, 'ok' => true, 'mode' => $mode, 'data' => $data));
}

if ($action === 'cookie') {
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
        out(array('code' => 405, 'ok' => false, 'message' => '请用 POST'), 405);
    }
    $body = request_body();
    require_client($store, $body);
    $device = trim((string) ($body['device_id'] ?? ''));
    if (!valid_device($device)) {
        out(array('code' => 400, 'ok' => false, 'message' => 'device_id 格式不正确'), 400);
    }
    $profile = isset($store['profiles'][$device]) && is_array($store['profiles'][$device])
        ? $store['profiles'][$device] : null;
    if (!is_array($profile) || empty($profile['token'])) {
        out(array('code' => 403, 'ok' => false, 'message' => '设备未注册'), 403);
    }
    $token = (string) ($body['token'] ?? '');
    if (!hash_equals((string) $profile['token'], $token)) {
        out(array('code' => 403, 'ok' => false, 'message' => '设备令牌无效'), 403);
    }
    $ts = (int) ($body['ts'] ?? 0);
    if ($ts <= 0 || abs(time() - $ts) > 300) {
        out(array('code' => 401, 'ok' => false, 'message' => '请求已过期'), 401);
    }
    $sig = (string) ($body['sig'] ?? '');
    if (!hash_equals(hash_hmac('sha256', $device . '|' . $ts . '|cookie', (string) $profile['token']), $sig)) {
        out(array('code' => 403, 'ok' => false, 'message' => '签名无效'), 403);
    }
    $accountToken = (string) ($body['account_token'] ?? '');
    $session = account_session($store, $accountToken, $device);
    if ($session === null) {
        out(array('code' => 401, 'ok' => false, 'message' => '登录已失效，请重新登录'), 401);
    }
    rate_or_out('cookie:' . $device, 10, 60, 'Cookie 请求过于频繁');
    rate_or_out('cookie_acct:' . account_norm((string) $session['username']), 10, 60, 'Cookie 请求过于频繁');
    header('Cache-Control: no-store, no-cache, must-revalidate');
    header('Pragma: no-cache');
    $full = (string) ($store['cookie'] ?? '');
    out(array(
        'code' => 0,
        'ok' => true,
        'cookie' => playback_cookie($full),
        'scope' => 'playback',
        'expires_in' => 43200,
    ));
}

if ($action !== 'config') {
    out(array('code' => 400, 'ok' => false, 'message' => '未知 action'), 400);
}

$key = isset($_GET['key']) ? (string) $_GET['key'] : '';
if ($key === '' && isset($_SERVER['HTTP_X_BILI_KEY'])) {
    $key = (string) $_SERVER['HTTP_X_BILI_KEY'];
}
if ($key === '' && isset($_POST['key'])) {
    $key = (string) $_POST['key'];
}
if (!hash_equals((string) $store['client_key'], $key)) {
    out(array('code' => 403, 'ok' => false, 'message' => 'client key 无效'), 403);
}

$ann = isset($store['announcement']) && is_array($store['announcement']) ? $store['announcement'] : array();
$upd = isset($store['update']) && is_array($store['update']) ? $store['update'] : array();
$lock = isset($store['lock']) && is_array($store['lock']) ? $store['lock'] : array();

out(array(
    'code' => 0,
    'ok' => true,
    'server_time' => gmdate('c'),
    'config_version' => isset($store['updated_at']) ? (int) $store['updated_at'] : 0,
    'announcement' => array(
        'enabled' => !empty($ann['enabled']),
        'id' => isset($ann['id']) ? (string) $ann['id'] : '',
        'title' => isset($ann['title']) ? (string) $ann['title'] : '',
        'content' => isset($ann['content']) ? (string) $ann['content'] : '',
        'updated_at' => isset($ann['updated_at']) ? (int) $ann['updated_at'] : 0,
    ),
    'update' => array(
        'enabled' => !empty($upd['enabled']),
        'version_code' => isset($upd['version_code']) ? (int) $upd['version_code'] : 0,
        'version_name' => isset($upd['version_name']) ? (string) $upd['version_name'] : '',
        'url' => isset($upd['url']) ? (string) $upd['url'] : '',
        'changelog' => isset($upd['changelog']) ? (string) $upd['changelog'] : '',
        'force' => !empty($upd['force']),
    ),
    'lock' => array(
        'locked' => !empty($lock['locked']),
        'title' => isset($lock['title']) ? (string) $lock['title'] : '服务已暂停',
        'message' => isset($lock['message']) ? (string) $lock['message'] : '',
    ),
));
