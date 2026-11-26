(function () {
  'use strict';

  if (window.__poliswagPassportInjected) return;
  window.__poliswagPassportInjected = true;

  if (!self.pageLogic || typeof window.poliswagBridge === 'undefined') {
    return;
  }

  const { classifyPage, extractLoginCode } = self.pageLogic;
  const bridge = window.poliswagBridge;

  let credentials = null;
  let credentialsPromise = null;
  let done = false;
  let loginSubmitted = false;
  let consentClicked = false;
  let challengeReported = false;
  let lastReportedState = null;
  let evaluateRunning = false;
  let evaluatePending = false;
  let observer = null;

  function postToHost(payload) {
    try {
      bridge.postMessage(JSON.stringify(payload));
    } catch {}
  }

  function getCredentials() {
    if (!credentialsPromise) {
      credentialsPromise = new Promise((resolve) => {
        let settled = false;
        const finish = (val) => {
          if (settled) return;
          settled = true;
          resolve(val);
        };
        try {
          bridge.onmessage = (event) => {
            try {
              const resp = JSON.parse(event.data);
              if (resp && resp.type === 'credentials') {
                if (resp.job !== false && resp.username && resp.password) {
                  finish({ username: resp.username, password: resp.password });
                } else {
                  finish(null);
                }
              }
            } catch {
              finish(null);
            }
          };
          postToHost({ type: 'need-credentials' });
        } catch {
          finish(null);
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

  function stopObserving() {
    done = true;
    if (observer) {
      try { observer.disconnect(); } catch {}
    }
  }

  async function evaluate() {
    if (done) return;
    if (!document.documentElement) return;
    if (evaluateRunning) {
      evaluatePending = true;
      return;
    }
    evaluateRunning = true;
    try {
      if (!credentials) {
        credentials = await getCredentials();
        if (!credentials) {
          stopObserving();
          return;
        }
      }
      if (done) return;

      const html = document.documentElement.outerHTML;
      const visibleText = document.body ? document.body.innerText : '';
      const state = classifyPage(html, visibleText);
      if (state !== 'challenge') challengeReported = false;

      if (state !== lastReportedState) {
        lastReportedState = state;
        postToHost({ type: 'state', state });
      }

      switch (state) {
        case 'success': {
          stopObserving();
          postToHost({ type: 'result', status: 'SUCCESS', login_code: extractLoginCode(html) });
          return;
        }
        case 'invalid':
          stopObserving();
          postToHost({ type: 'result', status: 'INVALID' });
          return;
        case 'banned':
          stopObserving();
          postToHost({ type: 'result', status: 'BANNED' });
          return;
        case 'challenge':
          if (!challengeReported) {
            challengeReported = true;
            postToHost({ type: 'challenge' });
          }
          return;
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
          return;
      }
    } finally {
      evaluateRunning = false;
      if (evaluatePending && !done) {
        evaluatePending = false;
        evaluate();
      }
    }
  }

  function start() {
    if (!observer && document.documentElement) {
      observer = new MutationObserver(() => evaluate());
      observer.observe(document.documentElement, { childList: true, subtree: true });
    }
    evaluate();
  }

  if (document.documentElement) {
    start();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  }
})();
