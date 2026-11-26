'use strict';

// pageLogic is loaded as a classic script before this file (see manifest content_scripts).
const { classifyPage, extractLoginCode } = self.pageLogic;

let credentials = null;
let credentialsPromise = null;
let done = false;
let loginSubmitted = false;
let consentClicked = false;
let challengeReported = false;
let lastReportedState = null;
let evaluateRunning = false;
let evaluatePending = false;

function getCredentials() {
  if (!credentialsPromise) {
    credentialsPromise = new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage({ type: 'need-credentials' }, (resp) => {
          void chrome.runtime.lastError;
          if (resp && resp.job !== false && resp.username && resp.password) {
            resolve({ username: resp.username, password: resp.password });
          } else {
            resolve(null);
          }
        });
      } catch {
        resolve(null);
      }
    });
  }
  return credentialsPromise;
}

// React-friendly value setter so the page registers the input.
function setNativeValue(el, value) {
  const proto = Object.getPrototypeOf(el);
  const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
  const setter = descriptor && descriptor.set;
  if (setter) setter.call(el, value); else el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

function isDisabled(el) {
  return !!(el && (el.disabled || el.getAttribute('aria-disabled') === 'true'));
}

function findSubmit(root) {
  const scope = root || document;
  return scope.querySelector('button[type="submit"], input[type="submit"], button:not([type])');
}

function submitLogin(form) {
  const btn = findSubmit(form) || findSubmit(document);
  if (btn) {
    if (isDisabled(btn)) return false;
    btn.click();
    return true;
  }
  if (form && typeof form.requestSubmit === 'function') {
    form.requestSubmit();
    return true;
  }
  if (form) {
    form.submit();
    return true;
  }
  return false;
}

function fillAndSubmitLogin() {
  const email = document.querySelector('input[name="email"], input[type="email"]');
  const password = document.querySelector('input[name="password"], input[type="password"]');
  if (!email || !password || !credentials) return false;
  setNativeValue(email, credentials.username);
  setNativeValue(password, credentials.password);
  const form = password.closest('form') || email.closest('form');
  return submitLogin(form);
}

function clickConsent() {
  const allow = document.querySelector('[name="allow_submit"], button[type="submit"], input[type="submit"]');
  if (allow) { allow.click(); return true; }
  return false;
}

async function evaluate() {
  if (done) return;
  if (evaluateRunning) {
    evaluatePending = true;
    return;
  }
  evaluateRunning = true;
  try {
    if (!credentials) {
      credentials = await getCredentials();
      if (!credentials) {
        done = true;
        observer.disconnect();
        return;
      }
    }
    if (done) return;

    const html = document.documentElement.outerHTML;
    const visibleText = document.body ? document.body.innerText : '';
    const state = classifyPage(html, visibleText);
    if (state !== 'challenge') challengeReported = false;

    // Report each page-state change to the control tab so a stalled/looping login is
    // visible there (e.g. a job stuck on "unknown" before the server times it out).
    if (state !== lastReportedState) {
      lastReportedState = state;
      try { chrome.runtime.sendMessage({ type: 'state', state }); } catch {}
    }

    switch (state) {
      case 'success': {
        done = true;
        observer.disconnect();
        chrome.runtime.sendMessage({ type: 'result', status: 'SUCCESS', login_code: extractLoginCode(html) });
        return;
      }
      case 'invalid':
        done = true;
        observer.disconnect();
        chrome.runtime.sendMessage({ type: 'result', status: 'INVALID' });
        return;
      case 'banned':
        done = true;
        observer.disconnect();
        chrome.runtime.sendMessage({ type: 'result', status: 'BANNED' });
        return;
      case 'challenge':
        if (!challengeReported) {
          challengeReported = true;
          chrome.runtime.sendMessage({ type: 'challenge' });
        }
        return; // keep observing; the human solves it and the DOM changes
      case 'login':
        if (!loginSubmitted && fillAndSubmitLogin()) {
          loginSubmitted = true;
        }
        return;
      case 'consent':
        if (!consentClicked && clickConsent()) {
          consentClicked = true;
        }
        return;
      default:
        return; // unknown: wait for the next mutation
    }
  } finally {
    evaluateRunning = false;
    if (evaluatePending && !done) {
      evaluatePending = false;
      evaluate();
    }
  }
}

// Run now and on DOM changes (covers SPA transitions and post-submit content).
const observer = new MutationObserver(() => evaluate());
observer.observe(document.documentElement, { childList: true, subtree: true });
evaluate();
