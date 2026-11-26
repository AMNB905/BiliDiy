<?php
/**
 * BiliDiy 安全基础设施
 * - 统一安全响应头 / 会话 Cookie
 * - 限流（登录、激活、接口）
 * - 请求体大小限制
 */
declare(strict_types=1);

function sec_is_https(): bool
{
    if (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') return true;
    if (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && strtolower((string) $_SERVER['HTTP_X_FORWARDED_PROTO']) === 'https') return true;
    return false;
}

function sec_client_ip(): string
{
    // 只信任 REMOTE_ADDR，避免伪造 X-Forwarded-For 绕过限流
    $ip = isset($_SERVER['REMOTE_ADDR']) ? (string) $_SERVER['REMOTE_ADDR'] : '0.0.0.0';
    return substr($ip, 0, 64);
}

function sec_headers(bool $json = false): void
{
    header('X-Content-Type-Options: nosniff');
    header('X-Frame-Options: DENY');
    header('Referrer-Policy: no-referrer');
    header('Permissions-Policy: geolocation=(), microphone=(), camera=()');
    header('Cross-Origin-Resource-Policy: same-origin');
    if ($json) {
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
    } else {
        header("Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
    }
}

function sec_session_start(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) return;
    ini_set('session.use_strict_mode', '1');
    ini_set('session.use_only_cookies', '1');
    ini_set('session.cookie_httponly', '1');
    session_name('BILIDIY_ADMIN');
    session_set_cookie_params(array(
        'lifetime' => 0,
        'path' => '/',
        'secure' => sec_is_https(),
        'httponly' => true,
        'samesite' => 'Strict',
    ));
    session_start();
}

function sec_security_dir(): string
{
    $dir = dirname(__DIR__) . '/data/.security';
    if (!is_dir($dir)) @mkdir($dir, 0700, true);
    return $dir;
}

/**
 * 滑动窗口限流。
 * @return array{0:bool,1:int,2:int} [是否放行, 当前次数, 还需等待秒数]
 */
function sec_rate_limit(string $name, int $max, int $window): array
{
    $max = max(1, $max);
    $window = max(1, $window);
    $dir = sec_security_dir();
    $file = $dir . '/' . hash('sha256', $name) . '.json';
    $fp = @fopen($file, 'c+');
    if (!$fp) {
        // 安全目录不可写时，放行以免整站不可用；应保证 data/ 可写
        return array(true, 0, 0);
    }
    @flock($fp, LOCK_EX);
    $raw = stream_get_contents($fp);
    $arr = json_decode((string) $raw, true);
    if (!is_array($arr)) $arr = array();
    $now = time();
    $arr = array_values(array_filter($arr, static function ($t) use ($now, $window) {
        return is_int($t) && $t > $now - $window;
    }));
    $count = count($arr);
    if ($count >= $max) {
        $retry = 1;
        if ($count > 0) $retry = max(1, ((int) $arr[0]) + $window - $now);
        @flock($fp, LOCK_UN);
        @fclose($fp);
        return array(false, $count, $retry);
    }
    $arr[] = $now;
    @ftruncate($fp, 0);
    @rewind($fp);
    @fwrite($fp, json_encode($arr));
    @fflush($fp);
    @flock($fp, LOCK_UN);
    @fclose($fp);
    return array(true, $count + 1, 0);
}

function sec_rate_clear(string $name): void
{
    $file = sec_security_dir() . '/' . hash('sha256', $name) . '.json';
    if (is_file($file)) @unlink($file);
}

function sec_body_limit(int $maxBytes): void
{
    $len = isset($_SERVER['CONTENT_LENGTH']) ? (int) $_SERVER['CONTENT_LENGTH'] : 0;
    if ($len > $maxBytes) {
        http_response_code(413);
        echo json_encode(array('code' => 413, 'ok' => false, 'message' => '请求体过大'));
        exit;
    }
}

function sec_admin_guard_session(): void
{
    if (empty($_SESSION['adm_ok'])) return;
    $now = time();
    $last = isset($_SESSION['adm_last']) ? (int) $_SESSION['adm_last'] : $now;
    $login = isset($_SESSION['adm_login']) ? (int) $_SESSION['adm_login'] : $now;
    if ($now - $last > 3600 || $now - $login > 43200) {
        $_SESSION = array();
        session_destroy();
        sec_session_start();
        $_SESSION['flash'] = array('m' => '登录已超时，请重新登录', 't' => 'err');
        return;
    }
    $_SESSION['adm_last'] = $now;
}
