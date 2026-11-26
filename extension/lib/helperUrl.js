// Pure helper-connection logic. Loads both as a classic browser script (sets
// globalThis.helperUrl) and as a CommonJS module (module.exports) for unit tests.
(function (root) {
  'use strict';

  const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);
  const WORKER_ID_RE = /^[A-Za-z0-9._-]{1,64}$/;
  const BACKOFF_MIN_MS = 2000;
  const BACKOFF_MAX_MS = 30000;

  // Turns what the user typed into the WebSocket and health URLs of a remote helper.
  // Empty input means local mode. http(s):// becomes ws(s)://; a missing path becomes /ws.
  function normalizeHelperUrl(raw) {
    const input = typeof raw === 'string' ? raw.trim() : '';
    if (input === '') return { ok: true, local: true };

    let parsed;
    try {
      parsed = new URL(input);
    } catch {
      return { ok: false, reason: 'not a valid URL (use ws://, wss://, http:// or https://)' };
    }

    let wsProtocol;
    if (parsed.protocol === 'ws:' || parsed.protocol === 'http:') wsProtocol = 'ws:';
    else if (parsed.protocol === 'wss:' || parsed.protocol === 'https:') wsProtocol = 'wss:';
    else return { ok: false, reason: `unsupported scheme ${parsed.protocol} (use ws://, wss://, http:// or https://)` };

    if (!parsed.hostname) return { ok: false, reason: 'missing host' };
    if (parsed.username || parsed.password) {
      return { ok: false, reason: 'credentials in the URL are not supported (use the Worker token field)' };
    }

    const path = parsed.pathname && parsed.pathname !== '/' ? parsed.pathname : '/ws';
    const secure = wsProtocol === 'wss:';
    const wsUrl = `${wsProtocol}//${parsed.host}${path}${parsed.search}`;
    const healthUrl = `${secure ? 'https:' : 'http:'}//${parsed.host}/health`;
    return {
      ok: true,
      local: false,
      wsUrl,
      healthUrl,
      secure,
      loopback: LOOPBACK_HOSTS.has(parsed.hostname.toLowerCase()),
    };
  }

  function isValidWorkerId(id) {
    return typeof id === 'string' && WORKER_ID_RE.test(id);
  }

  // Adds ?worker=<id> (and &token=<token> when set) to a WebSocket URL.
  // wsUrl must not contain a #fragment (normalizeHelperUrl never returns one).
  function withWorkerParams(wsUrl, workerId, token) {
    let url = `${wsUrl}${wsUrl.includes('?') ? '&' : '?'}worker=${encodeURIComponent(workerId)}`;
    if (typeof token === 'string' && token !== '') url += `&token=${encodeURIComponent(token)}`;
    return url;
  }

  // 2s, doubling to at most 30s.
  function nextBackoff(currentMs) {
    if (!Number.isFinite(currentMs) || currentMs < BACKOFF_MIN_MS) return BACKOFF_MIN_MS * 2;
    return Math.min(currentMs * 2, BACKOFF_MAX_MS);
  }

  const api = {
    normalizeHelperUrl,
    isValidWorkerId,
    withWorkerParams,
    nextBackoff,
    BACKOFF_MIN_MS,
    BACKOFF_MAX_MS,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.helperUrl = api;
})(typeof self !== 'undefined' ? self : globalThis);
