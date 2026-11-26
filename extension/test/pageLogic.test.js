const test = require('node:test');
const assert = require('node:assert');
const { extractLoginCode, classifyPage } = require('../lib/pageLogic');

test('extractLoginCode pulls the code from a pokemongo redirect string', () => {
  const html = `<a href="pokemongo://state=abc,code=THE_CODE_123">continue</a>`;
  assert.strictEqual(extractLoginCode(html), 'THE_CODE_123');
});

test('extractLoginCode returns null when there is no code', () => {
  assert.strictEqual(extractLoginCode('<html>nothing here</html>'), null);
  assert.strictEqual(extractLoginCode(null), null);
});

test('extractLoginCode stops at &, comma, and # delimiters', () => {
  assert.strictEqual(extractLoginCode('pokemongo://state=abc,code=CODE1&extra=1'), 'CODE1');
  assert.strictEqual(extractLoginCode('pokemongo://state=abc,code=CODE2,other=2'), 'CODE2');
  assert.strictEqual(extractLoginCode('pokemongo://state=abc,code=CODE3#frag'), 'CODE3');
});

test('extractLoginCode decodes URI-encoded codes and tolerates malformed escapes', () => {
  assert.strictEqual(extractLoginCode('pokemongo://state=abc,code=ABC%2B123%3D'), 'ABC+123=');
  assert.strictEqual(extractLoginCode('pokemongo://state=abc,code=BAD%ZZCODE'), 'BAD%ZZCODE');
});

test('classifyPage detects success when a code is present', () => {
  assert.strictEqual(classifyPage(`x pokemongo://state=s,code=c '`), 'success');
});

test('classifyPage detects an Imperva challenge', () => {
  assert.strictEqual(classifyPage('Request unsuccessful. Incapsula incident ID: 123'), 'challenge');
});

test('classifyPage detects a visible human verification challenge', () => {
  assert.strictEqual(classifyPage('<html></html>', 'Please verify you are a human'), 'challenge');
});

test('classifyPage does not flag normal pages that reference Incapsula resources', () => {
  const html = '<input name="email"><input name="password" type="password"><script src="/_Incapsula_Resource"></script>';
  assert.strictEqual(classifyPage(html, 'Username Password'), 'login');
});

test('classifyPage detects invalid credentials', () => {
  assert.strictEqual(classifyPage('<p>Your username or password is incorrect.</p>'), 'invalid');
});

test('classifyPage detects a banned account', () => {
  assert.strictEqual(classifyPage('We are unable to log you in to this account. Please contact Customer Service'), 'banned');
});

test('classifyPage uses visibleText for invalid/banned and ignores hidden HTML strings', () => {
  const loginHtml = '<input name="email"><input name="password" type="password">' +
    '<script>const errs = ["Your username or password is incorrect", "Your account has been disabled", "Unable to log you in to this account"];</script>';
  assert.strictEqual(classifyPage(loginHtml, 'Sign in Username Password'), 'login');
  assert.strictEqual(classifyPage('<html></html>', 'Your username or password is incorrect.'), 'invalid');
  assert.strictEqual(classifyPage('<html></html>', 'Your account has been disabled.'), 'invalid');
  assert.strictEqual(classifyPage('<html></html>', 'Unable to log you in to this account.'), 'banned');
});

test('classifyPage keeps consent and login detection on HTML even when visibleText is provided', () => {
  assert.strictEqual(classifyPage('<input type="submit" name="allow_submit" value="Allow">', 'Continue'), 'consent');
  assert.strictEqual(classifyPage('<input name="email"><input type="password">', 'Sign in'), 'login');
});

test('classifyPage detects the consent page', () => {
  assert.strictEqual(classifyPage('<input type="submit" name="allow_submit" value="Allow">'), 'consent');
});

test('classifyPage detects the login form', () => {
  assert.strictEqual(classifyPage('<input name="email"><input name="password" type="password">'), 'login');
});

test('classifyPage returns unknown for an unrecognized page', () => {
  assert.strictEqual(classifyPage('<html><body>loading…</body></html>'), 'unknown');
});
