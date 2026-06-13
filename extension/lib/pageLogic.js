// Pure page logic. Loads both as a classic browser script (sets globalThis.pageLogic)
// and as a CommonJS module (module.exports) for unit tests.
(function (root) {
  'use strict';

  function extractLoginCode(html) {
    if (typeof html !== 'string') return null;
    // PTC embeds the code as pokemongo://state=<state>,code=<code> inside HTML.
    // Stop each value at the first delimiter (quote, whitespace, tag, or end) so
    // this works whether the URL sits in a single- or double-quoted attribute.
    const m = html.match(/pokemongo:\/\/state=[^,'"\s<]*,code=([^'"\s<]+)/);
    return m && m[1] ? m[1] : null;
  }

  function classifyPage(html, visibleText) {
    const h = typeof html === 'string' ? html : '';
    const low = h.toLowerCase();
    const text = typeof visibleText === 'string' ? visibleText.toLowerCase() : low;
    if (extractLoginCode(h)) return 'success';
    if (
      text.includes('request unsuccessful. incapsula') ||
      text.includes('incapsula incident id') ||
      text.includes('please verify you are a human') ||
      text.includes('checking your browser')
    ) return 'challenge';
    if (low.includes('your username or password is incorrect')) return 'invalid';
    if (low.includes('your account has been disabled')) return 'invalid';
    if (low.includes('unable to log you in to this account')) return 'banned';
    if (low.includes('allow_submit')) return 'consent';
    if (low.includes('name="password"') || low.includes("name='password'") || low.includes('type="password"')) return 'login';
    return 'unknown';
  }

  const api = { extractLoginCode, classifyPage };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.pageLogic = api;
})(typeof self !== 'undefined' ? self : globalThis);
