'use strict';

// 127.0.0.1, not localhost: Firefox-based browsers resolve `localhost` to IPv6 ::1
// first, but the server binds IPv4 (0.0.0.0), so ws://localhost would be refused.
//
// Some Firefox-family profiles require wss from extension pages. The helper serves wss
// after `poliswag-passport cert` creates cert files, or when TLS_KEY/TLS_CERT are set.
const IS_FIREFOX_FAMILY = /\bFirefox\//.test(navigator.userAgent);
const LOCAL_WS_URLS = IS_FIREFOX_FAMILY
  ? ['wss://127.0.0.1:5443/ws', 'ws://127.0.0.1:5090/ws']
  : ['ws://127.0.0.1:5090/ws', 'wss://127.0.0.1:5443/ws'];
const LOCAL_HEALTH_URL = 'http://127.0.0.1:5090/health';
let wsUrlIdx = 0;

// Connection settings. helper is the normalizeHelperUrl() result for the saved Helper URL
// (local: true when it is empty). The token is only ever put into the WebSocket URL; it is
// never logged or shown (the logged/displayed URL is the one without query parameters).
const settings = { helperUrl: '', workerToken: '' };
let helper = { ok: true, local: true };

function wsUrls() {
  return helper.local ? LOCAL_WS_URLS : [helper.wsUrl];
}

function healthUrl() {
  return helper.local ? LOCAL_HEALTH_URL : helper.healthUrl;
}

function needsFirefoxWssWarning(h) {
  return IS_FIREFOX_FAMILY && h.ok && !h.local && !h.secure && !h.loopback;
}

const FIREFOX_WSS_WARNING = 'Firefox/Zen only allow wss:// to remote helpers. Use a wss:// URL (a reverse proxy with TLS in front of the helper).';

// One worker ID per control tab. sessionStorage survives reloads of this tab, so a
// reload reconnects as the same worker (the helper replaces the stale connection).
function loadWorkerId() {
  let id = null;
  try { id = sessionStorage.getItem('poliswagWorkerId'); } catch {}
  if (!helperUrl.isValidWorkerId(id)) {
    id = crypto.randomUUID();
    try { sessionStorage.setItem('poliswagWorkerId', id); } catch {}
  }
  return id;
}
const WORKER_ID = loadWorkerId();

const statusEl = document.getElementById('status');
const serverWarningEl = document.getElementById('serverWarning');
const serverWarningTitleEl = document.getElementById('serverWarningTitle');
const serverWarningTextEl = document.getElementById('serverWarningText');
const serverWarningHintEl = document.getElementById('serverWarningHint');
const startCommandEl = document.getElementById('startCommand');
const permissionsWarningEl = document.getElementById('permissionsWarning');
const grantPermissionsBtn = document.getElementById('grantPermissions');
const logEl = document.getElementById('log');
const activeJobsEl = document.getElementById('activeJobs');
const helperFormEl = document.getElementById('helperForm');
const helperUrlInputEl = document.getElementById('helperUrlInput');
const workerTokenInputEl = document.getElementById('workerTokenInput');
const helperSettingsMsgEl = document.getElementById('helperSettingsMsg');
const helperSettingsWarningEl = document.getElementById('helperSettingsWarning');
const workerIdEl = document.getElementById('workerId');
const statEls = {
  total: document.getElementById('statTotal'),
  success: document.getElementById('statSuccess'),
  failed: document.getElementById('statFailed'),
  challenges: document.getElementById('statChallenges'),
};

const stats = {
  total: 0,
  success: 0,
  failed: 0,
  challenges: 0,
};

function shortId(id) {
  return id ? id.slice(0, 8) : 'unknown';
}

function elapsed(startedAt) {
  const seconds = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${seconds % 60}s`;
}

const LOG_CAP = 300;
let logLines = []; // newest first

function renderLog() {
  logEl.textContent = logLines.join('\n');
}

function log(line) {
  const ts = new Date().toLocaleTimeString();
  logLines.unshift(`${ts}  ${line}`);
  if (logLines.length > LOG_CAP) logLines.length = LOG_CAP;
  renderLog();
}

function renderStats() {
  statEls.total.textContent = String(stats.total);
  statEls.success.textContent = String(stats.success);
  statEls.failed.textContent = String(stats.failed);
  statEls.challenges.textContent = String(stats.challenges);
}

function renderActiveJobs() {
  const entries = [...tabJobs.entries()];
  if (entries.length === 0) {
    activeJobsEl.innerHTML = '<div class="empty">No active jobs.</div>';
    return;
  }

  activeJobsEl.textContent = '';
  for (const [tabId, job] of entries) {
    const row = document.createElement('div');
    row.className = 'job';

    const body = document.createElement('div');
    const title = document.createElement('div');
    title.className = 'job-title';
    title.textContent = job.username;
    const meta = document.createElement('div');
    meta.className = 'job-meta';
    meta.textContent = `${shortId(job.id)} · ${elapsed(job.startedAt)} · tab ${tabId}`;
    body.append(title, meta);

    const tag = document.createElement('div');
    const hasChallenge = challengedJobs.has(job.id);
    tag.className = hasChallenge ? 'tag challenge' : 'tag';
    tag.textContent = hasChallenge ? 'challenge' : 'running';

    row.append(body, tag);
    activeJobsEl.append(row);
  }
}

function render() {
  renderStats();
  renderActiveJobs();
}

// jobs in flight: tabId -> { id, username, password, url }
const tabJobs = new Map();
const challengedJobs = new Set();
const pendingResults = [];
// Jobs whose tab is still being created, and those cancelled during that time.
const startingJobs = new Set();
const cancelledBeforeStart = new Set();
let ws = null;
let replaced = false;
let connectGen = 0; // bumped on every reconnect-with-new-settings; stale sockets ignore their close
let reconnectTimer = null;
let reconnectDelay = helperUrl.BACKOFF_MIN_MS;
const failedTransports = new Set();
let helperHealth = 'unknown'; // unknown, up, down
let healthProbeSeq = 0;

const REQUIRED_ORIGINS = (chrome.runtime.getManifest && chrome.runtime.getManifest().host_permissions) || [];

const IS_WINDOWS = /\bWindows\b|\bWin64\b|\bWin32\b/.test(navigator.userAgent);
const RUN_COMMAND = IS_WINDOWS
  ? `.\\poliswag-passport.exe ${IS_FIREFOX_FAMILY ? 'firefox' : 'chrome'}`
  : `./poliswag-passport ${IS_FIREFOX_FAMILY ? 'firefox' : 'chrome'}`;

function transportName(url) {
  return url.startsWith('wss:') ? 'wss' : 'ws';
}

function setConnectionWarning({ title, text, hint = '', command = RUN_COMMAND }) {
  serverWarningTitleEl.textContent = title;
  serverWarningTextEl.textContent = text;
  serverWarningHintEl.textContent = hint;
  serverWarningHintEl.hidden = hint === '';
  startCommandEl.textContent = command;
  startCommandEl.hidden = command === '';
}

function renderConnectionWarning() {
  if (replaced) {
    setConnectionWarning({
      title: 'Connection replaced',
      text: "This control tab's connection was replaced — reload to reconnect.",
      hint: 'If you duplicated this tab, close the duplicate instead: duplicates share the same worker ID.',
      command: '',
    });
    return;
  }

  if (!helper.ok) {
    setConnectionWarning({
      title: 'Helper URL invalid',
      text: `The saved Helper URL is invalid: ${helper.reason}.`,
      hint: 'Fix it in the Helper settings below, or clear it to use the local helper.',
      command: '',
    });
    return;
  }

  if (!helper.local) {
    const firefoxHint = needsFirefoxWssWarning(helper) ? ` ${FIREFOX_WSS_WARNING}` : '';
    if (helperHealth === 'up') {
      setConnectionWarning({
        title: 'WebSocket refused',
        text: 'The remote helper is reachable, but the control tab could not connect to it.',
        hint: `Check that the worker token is right (the helper refuses a wrong or missing token).${firefoxHint}`,
        command: helper.wsUrl,
      });
      return;
    }
    setConnectionWarning({
      title: helperHealth === 'down' ? 'Helper not reachable' : 'Helper disconnected',
      text: 'Could not connect to the remote helper at this URL.',
      hint: `Check that the helper container is running and the worker token is right.${firefoxHint}`,
      command: helper.wsUrl,
    });
    return;
  }

  const wssFailed = failedTransports.has('wss');
  const wsFailed = failedTransports.has('ws');

  if (helperHealth === 'down') {
    setConnectionWarning({
      title: 'Helper not reachable',
      text: 'Run Poliswag Passport before using Dragonite.',
    });
    return;
  }

  if (IS_FIREFOX_FAMILY && wssFailed && wsFailed) {
    setConnectionWarning({
      title: helperHealth === 'up' ? 'Certificate setup needed' : 'Server or certificate needed',
      text: helperHealth === 'up'
        ? 'The helper is running, but Firefox/Zen could not open a WebSocket. Restart it in Firefox mode.'
        : 'Run Poliswag Passport in Firefox mode. If it is already running, stop it and start it again with this command.',
      hint: 'Firefox/Zen may need a trusted local certificate for secure WebSocket connections.',
      command: RUN_COMMAND,
    });
    return;
  }

  if (IS_FIREFOX_FAMILY && wssFailed) {
    setConnectionWarning({
      title: 'Checking fallback connection',
      text: 'Secure WebSocket did not connect. The control tab is trying the plain local connection next.',
      hint: `If this keeps retrying while the helper is running, restart it with: ${RUN_COMMAND}`,
    });
    return;
  }

  setConnectionWarning({
    title: helperHealth === 'up' ? 'WebSocket disconnected' : 'Server disconnected',
    text: helperHealth === 'up'
      ? 'The helper health check works, but the control tab is not connected. Reload this tab or restart Poliswag Passport.'
      : 'Run Poliswag Passport before using Dragonite.',
  });
}

function setStatus(connected) {
  statusEl.className = connected ? 'pill ok' : 'pill bad';
  statusEl.lastElementChild.textContent = connected ? 'connected' : (replaced ? 'replaced' : 'disconnected');
  serverWarningEl.hidden = connected;
  if (connected) helperHealth = 'up';
  if (!connected) renderConnectionWarning();
}

async function checkHelperHealth() {
  const seq = ++healthProbeSeq;
  const controller = new AbortController();
  if (!helper.ok) return;
  const timer = setTimeout(() => controller.abort(), helper.local ? 1200 : 3000);
  try {
    const res = await fetch(healthUrl(), {
      cache: 'no-store',
      signal: controller.signal,
    });
    let body = null;
    try { body = await res.json(); } catch {}
    if (seq !== healthProbeSeq) return;
    helperHealth = res.ok && body && body.status === 'OK' ? 'up' : 'down';
  } catch {
    if (seq !== healthProbeSeq) return;
    helperHealth = 'down';
  } finally {
    clearTimeout(timer);
  }
  if (!serverWarningEl.hidden) renderConnectionWarning();
}

function sendResult(msg) {
  const payload = JSON.stringify({
    type: 'result',
    id: msg.id,
    status: msg.status,
    login_code: msg.login_code,
  });
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(payload);
      return;
    } catch {}
  }
  pendingResults.push(payload);
}

function flushPendingResults() {
  while (pendingResults.length > 0 && ws && ws.readyState === WebSocket.OPEN) {
    const payload = pendingResults[0];
    try {
      ws.send(payload);
      pendingResults.shift();
    } catch {
      break;
    }
  }
}

function validateJobUrl(rawUrl) {
  if (typeof rawUrl !== 'string' || rawUrl.length === 0) {
    return { ok: false, reason: 'missing job URL' };
  }
  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return { ok: false, reason: 'malformed job URL' };
  }
  if (parsed.protocol !== 'https:') {
    return { ok: false, reason: `disallowed URL protocol: ${parsed.protocol}` };
  }
  if (parsed.hostname !== 'access.pokemon.com') {
    return { ok: false, reason: `disallowed URL host: ${parsed.hostname}` };
  }
  return { ok: true };
}

function scheduleReconnect(gen) {
  clearTimeout(reconnectTimer);
  const delay = reconnectDelay;
  reconnectDelay = helperUrl.nextBackoff(reconnectDelay);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (gen === connectGen) connect();
  }, delay);
  return delay;
}

function connect() {
  if (replaced || !helper.ok) return;
  const gen = connectGen;
  const urls = wsUrls();
  const url = urls[wsUrlIdx % urls.length]; // display/log form: never includes the token
  let sock;
  try {
    sock = new WebSocket(helperUrl.withWorkerParams(url, WORKER_ID, settings.workerToken));
  } catch {
    // Do not log the exception: its message can contain the full URL (and token).
    failedTransports.add(transportName(url));
    setStatus(false);
    const delay = scheduleReconnect(gen);
    log(`could not open WebSocket to ${url}, retrying in ${Math.round(delay / 1000)}s`);
    return;
  }
  ws = sock;
  sock.onopen = () => {
    if (gen !== connectGen) return;
    reconnectDelay = helperUrl.BACKOFF_MIN_MS;
    failedTransports.clear();
    helperHealth = 'up';
    setStatus(true);
    log(`connected to Poliswag Passport (${url})`);
    flushPendingResults();
  };
  sock.onclose = (ev) => {
    if (gen !== connectGen) return; // superseded by a settings change
    if (ev && ev.code === 4001) {
      replaced = true;
      setStatus(false);
      log("this control tab's connection was replaced — reload to reconnect");
      return;
    }
    failedTransports.add(transportName(url));
    setStatus(false);
    checkHelperHealth();
    wsUrlIdx += 1; // local mode: alternate transport so whichever the browser allows wins
    const next = urls[wsUrlIdx % urls.length];
    const delay = scheduleReconnect(gen);
    log(`disconnected, retrying (${next}) in ${Math.round(delay / 1000)}s`);
  };
  sock.onerror = () => { try { sock.close(); } catch {} };
  sock.onmessage = (ev) => {
    if (gen !== connectGen) return; // a socket we already replaced: ignore its jobs
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.type === 'job') startJob(msg);
    else if (msg.type === 'cancel') cancelJob(msg.id, msg.status || 'CANCELLED');
  };
}

async function startJob(job) {
  const startedAt = Date.now();
  const check = validateJobUrl(job.url);
  if (!check.ok) {
    stats.total += 1;
    stats.failed += 1;
    sendResult({ id: job.id, status: 'ERROR' });
    log(`job ${shortId(job.id)} ${job.username || 'unknown'} -> ERROR (${check.reason})`);
    render();
    return;
  }

  const active = !IS_FIREFOX_FAMILY;
  log(`job ${shortId(job.id)} for ${job.username} — opening ${active ? 'foreground' : 'background'} tab`);
  let tab;
  startingJobs.add(job.id);
  try {
    tab = await chrome.tabs.create({ url: 'about:blank', active });
  } catch (err) {
    startingJobs.delete(job.id);
    cancelledBeforeStart.delete(job.id);
    const reason = `could not create tab: ${err && err.message ? err.message : err}`;
    stats.total += 1;
    stats.failed += 1;
    sendResult({ id: job.id, status: 'ERROR' });
    log(`job ${shortId(job.id)} ${job.username} -> ERROR (${reason})`);
    render();
    return;
  }

  if (cancelledBeforeStart.delete(job.id)) {
    // The helper cancelled the job while the tab was being created.
    startingJobs.delete(job.id);
    stats.total += 1;
    stats.failed += 1;
    log(`job ${shortId(job.id)} ${job.username} -> cancelled before start, closing tab`);
    render();
    chrome.tabs.remove(tab.id).catch(() => {});
    return;
  }
  startingJobs.delete(job.id);
  tabJobs.set(tab.id, { ...job, startedAt });
  stats.total += 1;
  render();

  try {
    await chrome.tabs.update(tab.id, { url: job.url });
  } catch (err) {
    const reason = `could not navigate tab: ${err && err.message ? err.message : err}`;
    finishJob(tab.id, { status: 'ERROR', reason });
  }
}

async function focusTab(tabId) {
  try {
    const tab = await chrome.tabs.get(tabId);
    await chrome.tabs.update(tabId, { active: true });
    if (tab.windowId) await chrome.windows.update(tab.windowId, { focused: true });
  } catch (err) {
    log(`could not focus challenge tab: ${err && err.message ? err.message : err}`);
  }
}

function finishJob(tabId, result, { closeTab = true } = {}) {
  const job = tabJobs.get(tabId);
  if (!job) return;
  tabJobs.delete(tabId);
  challengedJobs.delete(job.id);
  if (result.status === 'SUCCESS') stats.success += 1;
  else stats.failed += 1;
  sendResult({ id: job.id, status: result.status, login_code: result.login_code });
  // Annotate non-success outcomes with the reason or the last page state seen, so the
  // log explains *why* a job ended (the server only sees the final status).
  const why = result.reason
    ? ` (${result.reason})`
    : (result.status !== 'SUCCESS' && job.lastState ? ` (last page: ${job.lastState})` : '');
  log(`job ${shortId(job.id)} ${job.username} -> ${result.status} in ${elapsed(job.startedAt)}${why}`);
  render();
  if (closeTab) chrome.tabs.remove(tabId).catch(() => {});
}

// Server gave up on a job (e.g. it timed out and already answered Dragonite). Close the
// lingering job tab. We do NOT send a result back — the server already settled it; the
// tabJobs delete also stops onRemoved from re-reporting an ERROR.
function cancelJob(id, status) {
  if (startingJobs.has(id)) {
    cancelledBeforeStart.add(id); // startJob closes the tab once it exists
    return;
  }
  for (const [tabId, job] of tabJobs) {
    if (job.id !== id) continue;
    tabJobs.delete(tabId);
    challengedJobs.delete(id);
    stats.failed += 1;
    const last = job.lastState ? ` (last page: ${job.lastState})` : '';
    log(`job ${shortId(id)} ${job.username} -> ${status} in ${elapsed(job.startedAt)} (server cancelled, closing tab)${last}`);
    render();
    chrome.tabs.remove(tabId).catch(() => {});
    return;
  }
}

// Capture the OAuth login code straight from the navigation URL. After a successful
// login PTC redirects the job tab to the redirect_uri (…?code=<authcode>) or to a
// pokemongo://state=…,code=<authcode> deep link. content.js only runs on
// access.pokemon.com, so it never sees that final URL — we watch navigations here to
// grab the code on any host/scheme. `[?#&,]code=` matches both forms and won't match
// the request's own `code_challenge=`.
function captureCodeFromUrl(details) {
  if (details.frameId !== 0) return; // top frame only
  const job = tabJobs.get(details.tabId);
  if (!job) return;
  const isDeepLink = /^pokemongo:\/\//i.test(details.url);
  if (!isDeepLink) {
    let parsed;
    try { parsed = new URL(details.url); } catch { return; }
    if (parsed.hostname === 'access.pokemon.com') return;
  }
  const m = details.url.match(/[?#&,]code=([^&,#'"\s]+)/);
  if (!m) return;
  let code = m[1];
  try { code = decodeURIComponent(code); } catch {}
  log(`job ${shortId(job.id)} — captured login code from redirect`);
  finishJob(details.tabId, { status: 'SUCCESS', login_code: code });
}
chrome.webNavigation.onBeforeNavigate.addListener(captureCodeFromUrl);
chrome.webNavigation.onCommitted.addListener(captureCodeFromUrl);

// Messages from the content script running in the job tab.
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const tabId = sender.tab && sender.tab.id;
  if (!tabId || !tabJobs.has(tabId)) {
    if (msg && msg.type === 'need-credentials') {
      sendResponse({ job: false });
      return true;
    }
    return;
  }
  const job = tabJobs.get(tabId);

  if (msg.type === 'need-credentials') {
    sendResponse({ job: true, username: job.username, password: job.password });
    return true;
  }
  if (msg.type === 'state') {
    job.lastState = msg.state;
    // 'success' is covered by finishJob; 'challenge' has its own richer line below.
    if (msg.state !== 'success' && msg.state !== 'challenge') {
      log(`job ${shortId(job.id)} ${job.username} — page: ${msg.state}`);
    }
    return;
  }
  if (msg.type === 'challenge') {
    if (challengedJobs.has(job.id)) return;
    challengedJobs.add(job.id);
    stats.challenges += 1;
    log(`job ${shortId(job.id)} ${job.username} — challenge after ${elapsed(job.startedAt)}, focusing tab`);
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'challenge', id: job.id }));
    }
    render();
    focusTab(tabId);
    chrome.notifications.create(`challenge-${job.id}`, {
      type: 'basic',
      iconUrl: chrome.runtime.getURL('icons/icon-128.png'),
      title: 'PTC challenge needs attention',
      message: `Focused the captcha tab for ${job.username}.`,
    });
    return;
  }
  if (msg.type === 'result') {
    finishJob(tabId, { status: msg.status, login_code: msg.login_code });
    return;
  }
});

// If a job tab is closed (manually or by a crash), report ERROR so the server doesn't
// hang to timeout. finishJob logs it with the reason and duration; closeTab:false because
// the tab is already gone.
chrome.tabs.onRemoved.addListener((tabId) => {
  if (tabJobs.has(tabId)) {
    finishJob(tabId, { status: 'ERROR', reason: 'job tab closed' }, { closeTab: false });
  }
});

async function checkHostPermissions() {
  if (!chrome.permissions || !chrome.permissions.contains || REQUIRED_ORIGINS.length === 0) {
    return;
  }
  try {
    const granted = await chrome.permissions.contains({ origins: REQUIRED_ORIGINS });
    permissionsWarningEl.hidden = !!granted;
    if (!granted) {
      log('missing host permissions — click "Grant permissions" to allow site access');
    }
  } catch (err) {
    log(`could not check host permissions: ${err && err.message ? err.message : err}`);
  }
}

if (grantPermissionsBtn) {
  grantPermissionsBtn.addEventListener('click', async () => {
    if (!chrome.permissions || !chrome.permissions.request) return;
    try {
      const granted = await chrome.permissions.request({ origins: REQUIRED_ORIGINS });
      permissionsWarningEl.hidden = !!granted;
      log(granted ? 'host permissions granted' : 'host permissions request was denied');
    } catch (err) {
      log(`could not request host permissions: ${err && err.message ? err.message : err}`);
    }
  });
}

document.getElementById('clearLog').addEventListener('click', () => {
  logLines = [];
  renderLog();
});

function renderHelperSettingsWarning(h) {
  const text = !h.ok ? `Invalid Helper URL: ${h.reason}.` : (needsFirefoxWssWarning(h) ? FIREFOX_WSS_WARNING : '');
  helperSettingsWarningEl.textContent = text;
  helperSettingsWarningEl.hidden = text === '';
}

function describeHelper() {
  return helper.local ? 'local helper (127.0.0.1)' : `remote helper ${helper.wsUrl}`;
}

// Close the current socket without triggering its reconnect, and connect with the
// current settings right away.
function reconnectNow() {
  connectGen += 1;
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  reconnectDelay = helperUrl.BACKOFF_MIN_MS;
  replaced = false;
  wsUrlIdx = 0;
  failedTransports.clear();
  helperHealth = 'unknown';
  healthProbeSeq += 1; // drop any in-flight probe for the old URL
  // Held results belong to the old helper; never send them (login codes) to a new one.
  pendingResults.length = 0;
  if (ws) {
    const old = ws;
    ws = null;
    try { old.close(); } catch {}
  }
  setStatus(false);
  checkHelperHealth();
  connect();
}

function applySettings(stored) {
  settings.helperUrl = typeof stored.helperUrl === 'string' ? stored.helperUrl : '';
  settings.workerToken = typeof stored.workerToken === 'string' ? stored.workerToken : '';
  helper = helperUrl.normalizeHelperUrl(settings.helperUrl);
  helperUrlInputEl.value = settings.helperUrl;
  workerTokenInputEl.value = settings.workerToken;
  renderHelperSettingsWarning(helper);
}

helperUrlInputEl.addEventListener('input', () => {
  helperSettingsMsgEl.textContent = '';
  renderHelperSettingsWarning(helperUrl.normalizeHelperUrl(helperUrlInputEl.value));
});

helperFormEl.addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const next = {
    helperUrl: helperUrlInputEl.value.trim(),
    workerToken: workerTokenInputEl.value.trim(),
  };
  const normalized = helperUrl.normalizeHelperUrl(next.helperUrl);
  renderHelperSettingsWarning(normalized);
  if (!normalized.ok) {
    helperSettingsMsgEl.textContent = 'Not saved.';
    return;
  }
  try {
    await chrome.storage.local.set(next);
  } catch (err) {
    helperSettingsMsgEl.textContent = 'Could not save settings.';
    log(`could not save helper settings: ${err && err.message ? err.message : err}`);
    return;
  }
  applySettings(next);
  helperSettingsMsgEl.textContent = 'Saved.';
  log(`helper settings saved: ${describeHelper()}${settings.workerToken ? ', token set' : ', no token'} — reconnecting`);
  reconnectNow();
});

async function start() {
  workerIdEl.textContent = WORKER_ID;
  setStatus(false);
  render();
  setInterval(renderActiveJobs, 1000);
  checkHostPermissions();
  let stored = {};
  try {
    stored = await chrome.storage.local.get({ helperUrl: '', workerToken: '' });
  } catch (err) {
    log(`could not load helper settings, using the local helper: ${err && err.message ? err.message : err}`);
  }
  applySettings(stored || {});
  if (!helper.local) log(`using ${describeHelper()}`);
  setStatus(false);
  checkHelperHealth();
  connect();
}

start();
