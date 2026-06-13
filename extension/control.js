'use strict';

// 127.0.0.1, not localhost: Firefox-based browsers resolve `localhost` to IPv6 ::1
// first, but the server binds IPv4 (0.0.0.0), so ws://localhost would be refused.
//
// Some Firefox-family profiles require wss from extension pages. The helper serves wss
// after `poliswag-passport cert` creates cert files, or when TLS_KEY/TLS_CERT are set.
const IS_FIREFOX_FAMILY = /\bFirefox\//.test(navigator.userAgent);
const WS_URLS = IS_FIREFOX_FAMILY
  ? ['wss://127.0.0.1:5443/ws', 'ws://127.0.0.1:5090/ws']
  : ['ws://127.0.0.1:5090/ws', 'wss://127.0.0.1:5443/ws'];
const HEALTH_URL = 'http://127.0.0.1:5090/health';
let wsUrlIdx = 0;

const statusEl = document.getElementById('status');
const serverWarningEl = document.getElementById('serverWarning');
const serverWarningTitleEl = document.getElementById('serverWarningTitle');
const serverWarningTextEl = document.getElementById('serverWarningText');
const serverWarningHintEl = document.getElementById('serverWarningHint');
const startCommandEl = document.getElementById('startCommand');
const logEl = document.getElementById('log');
const activeJobsEl = document.getElementById('activeJobs');
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
let ws = null;
const failedTransports = new Set();
let helperHealth = 'unknown'; // unknown, up, down
let healthProbeSeq = 0;

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
}

function renderConnectionWarning() {
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
  statusEl.lastElementChild.textContent = connected ? 'connected' : 'disconnected';
  serverWarningEl.hidden = connected;
  if (connected) helperHealth = 'up';
  if (!connected) renderConnectionWarning();
}

async function checkHelperHealth() {
  const seq = ++healthProbeSeq;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 1200);
  try {
    const res = await fetch(HEALTH_URL, {
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

function connect() {
  const url = WS_URLS[wsUrlIdx % WS_URLS.length];
  ws = new WebSocket(url);
  ws.onopen = () => {
    failedTransports.clear();
    helperHealth = 'up';
    setStatus(true);
    log(`connected to Poliswag Passport (${url})`);
  };
  ws.onclose = () => {
    failedTransports.add(transportName(url));
    setStatus(false);
    checkHelperHealth();
    wsUrlIdx += 1; // alternate transport so whichever the browser allows wins
    const next = WS_URLS[wsUrlIdx % WS_URLS.length];
    log(`disconnected, retrying (${next}) in 2s`);
    setTimeout(connect, 2000);
  };
  ws.onerror = () => { try { ws.close(); } catch {} };
  ws.onmessage = (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.type === 'job') startJob(msg);
    else if (msg.type === 'cancel') cancelJob(msg.id, msg.status || 'CANCELLED');
  };
}

async function startJob(job) {
  const startedAt = Date.now();
  const active = !IS_FIREFOX_FAMILY;
  log(`job ${shortId(job.id)} for ${job.username} — opening ${active ? 'foreground' : 'background'} tab`);
  const tab = await chrome.tabs.create({ url: job.url, active });
  tabJobs.set(tab.id, { ...job, startedAt });
  stats.total += 1;
  render();
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
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'result', id: job.id, status: result.status, login_code: result.login_code }));
  }
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
  if (!tabId || !tabJobs.has(tabId)) return;
  const job = tabJobs.get(tabId);

  if (msg.type === 'need-credentials') {
    sendResponse({ username: job.username, password: job.password });
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

document.getElementById('clearLog').addEventListener('click', () => {
  logLines = [];
  renderLog();
});

setStatus(false);
render();
setInterval(renderActiveJobs, 1000);
checkHelperHealth();
connect();
