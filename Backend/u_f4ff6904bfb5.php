<?php
/**
 * BiliDiy 受保护的部署接收端
 * ---------------------------------------------------------------
 * 原公开上传地址已改为必须携带 X-Deploy-Token。
 * 只允许写入代码文件和 download/*.apk，禁止写入 data/、禁止路径穿越。
 */
declare(strict_types=1);

@ini_set('display_errors', '0');
@ini_set('log_errors', '1');

const DEPLOY_TOKEN = 'CHANGE_ME_DEPLOY_TOKEN_32_BYTES';
const MAX_FILE_BYTES = 40 * 1024 * 1024;
const MAX_TOTAL_BYTES = 60 * 1024 * 1024;

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function reply(array $data, int $status = 200): void
{
    http_response_code($status);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'POST') {
    header('Allow: POST');
    reply(array('code' => 405, 'ok' => false, 'message' => 'Method Not Allowed'), 405);
}

$token = isset($_SERVER['HTTP_X_DEPLOY_TOKEN']) ? (string) $_SERVER['HTTP_X_DEPLOY_TOKEN'] : '';
if ($token === '' && isset($_POST['token'])) $token = (string) $_POST['token'];
if (!hash_equals(DEPLOY_TOKEN, $token)) {
    error_log('BiliDiy deploy rejected: bad token from ' . ($_SERVER['REMOTE_ADDR'] ?? '?'));
    reply(array('code' => 403, 'ok' => false, 'message' => 'forbidden'), 403);
}

$raw = file_get_contents('php://input');
$json = json_decode((string) $raw, true);
if (!is_array($json) || !isset($json['files']) || !is_array($json['files'])) {
    reply(array('code' => 400, 'ok' => false, 'message' => 'files required'), 400);
}

$allowed = array(
    '#^api\.php$#',
    '#^admin\.php$#',
    '#^u_f4ff6904bfb5\.php$#',
    '#^lib/[A-Za-z0-9_.-]+\.php$#',
    '#^download/[A-Za-z0-9_.-]+\.apk$#',
);
$allowedLib = array('storage.php', 'security.php');

$out = array();
$errors = array();
$total = 0;
foreach ($json['files'] as $item) {
    if (!is_array($item)) continue;
    $path = isset($item['path']) ? str_replace('\\', '/', trim((string) $item['path'])) : '';
    $data = isset($item['data']) ? (string) $item['data'] : '';

    if ($path === '' || strpos($path, '..') !== false || strpos($path, "\0") !== false || $path[0] === '/') {
        $errors[] = array('path' => $path, 'message' => '非法路径');
        continue;
    }
    $isAllowed = false;
    foreach ($allowed as $re) {
        if (preg_match($re, $path)) { $isAllowed = true; break; }
    }
    if (preg_match('#^lib/([A-Za-z0-9_.-]+\.php)$#', $path, $m) && !in_array($m[1], $allowedLib, true) && !in_array($m[1], array('storage.php', 'security.php'), true)) {
        $isAllowed = false;
    }
    if (!$isAllowed) {
        $errors[] = array('path' => $path, 'message' => '路径不在允许清单');
        continue;
    }
    if (strlen($data) > (MAX_FILE_BYTES * 4 / 3 + 8)) {
        $errors[] = array('path' => $path, 'message' => '文件过大');
        continue;
    }
    $bytes = base64_decode($data, true);
    if ($bytes === false) {
        $errors[] = array('path' => $path, 'message' => 'base64 解码失败');
        continue;
    }
    $len = strlen($bytes);
    if ($len > MAX_FILE_BYTES) {
        $errors[] = array('path' => $path, 'message' => '文件超过 40MB');
        continue;
    }
    $total += $len;
    if ($total > MAX_TOTAL_BYTES) {
        $errors[] = array('path' => $path, 'message' => '本次上传总量超过 60MB');
        continue;
    }
    if (preg_match('#\.php$#', $path) && substr($bytes, 0, 5) !== '<?php') {
        $errors[] = array('path' => $path, 'message' => 'PHP 文件格式不正确');
        continue;
    }

    $dest = __DIR__ . '/' . $path;
    $dir = dirname($dest);
    if (!is_dir($dir) && !@mkdir($dir, 0755, true)) {
        $errors[] = array('path' => $path, 'message' => '目录创建失败');
        continue;
    }
    // 代码文件保留上一版备份；APK 不备份，避免占用空间
    if (is_file($dest) && preg_match('#\.php$#', $path)) {
        @copy($dest, $dest . '.bak');
    }
    $tmp = $dir . '/.' . basename($path) . '.' . bin2hex(random_bytes(6)) . '.tmp';
    if (@file_put_contents($tmp, $bytes, LOCK_EX) === false || !@rename($tmp, $dest)) {
        @unlink($tmp);
        $errors[] = array('path' => $path, 'message' => '写入失败');
        continue;
    }
    @chmod($dest, 0644);
    if (function_exists('opcache_invalidate')) @opcache_invalidate($dest, true);
    $out[] = array('path' => $path, 'ok' => true, 'bytes' => $len, 'sha1' => sha1($bytes));
}

if (!$out && $errors) {
    reply(array('code' => 400, 'ok' => false, 'deployed' => array(), 'errors' => $errors), 400);
}
reply(array('code' => 0, 'ok' => true, 'deployed' => $out, 'errors' => $errors));
