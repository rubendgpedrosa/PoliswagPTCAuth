const test = require('node:test');
const assert = require('node:assert');
const {
  normalizeHelperUrl,
  isValidWorkerId,
  withWorkerParams,
  nextBackoff,
} = require('../lib/helperUrl');

test('normalizeHelperUrl: empty input is local mode', () => {
  assert.deepStrictEqual(normalizeHelperUrl(''), { ok: true, local: true });
  assert.deepStrictEqual(normalizeHelperUrl('   '), { ok: true, local: true });
  assert.deepStrictEqual(normalizeHelperUrl(undefined), { ok: true, local: true });
});

test('normalizeHelperUrl: http(s) becomes ws(s) and a missing path becomes /ws', () => {
  const plain = normalizeHelperUrl('http://192.168.1.10:5090');
  assert.strictEqual(plain.wsUrl, 'ws://192.168.1.10:5090/ws');
  assert.strictEqual(plain.healthUrl, 'http://192.168.1.10:5090/health');
  assert.strictEqual(plain.secure, false);
  assert.strictEqual(plain.loopback, false);

  const secure = normalizeHelperUrl(' https://passport.example.com/ ');
  assert.strictEqual(secure.wsUrl, 'wss://passport.example.com/ws');
  assert.strictEqual(secure.healthUrl, 'https://passport.example.com/health');
  assert.strictEqual(secure.secure, true);
});

test('normalizeHelperUrl: keeps ws(s) URLs and explicit paths', () => {
  assert.strictEqual(normalizeHelperUrl('ws://helper:5090/ws').wsUrl, 'ws://helper:5090/ws');
  const proxied = normalizeHelperUrl('wss://example.com/passport/ws');
  assert.strictEqual(proxied.wsUrl, 'wss://example.com/passport/ws');
  assert.strictEqual(proxied.healthUrl, 'https://example.com/health');
});

test('normalizeHelperUrl: detects loopback hosts', () => {
  assert.strictEqual(normalizeHelperUrl('ws://127.0.0.1:5090').loopback, true);
  assert.strictEqual(normalizeHelperUrl('ws://LOCALHOST:5090').loopback, true);
  assert.strictEqual(normalizeHelperUrl('ws://10.0.0.2:5090').loopback, false);
});

test('normalizeHelperUrl: rejects bad input', () => {
  assert.strictEqual(normalizeHelperUrl('helper:5090').ok, false);
  assert.strictEqual(normalizeHelperUrl('not a url').ok, false);
  assert.strictEqual(normalizeHelperUrl('ftp://helper').ok, false);
  assert.strictEqual(normalizeHelperUrl('ws://user:pw@helper').ok, false);
});

test('isValidWorkerId follows the protocol charset', () => {
  assert.strictEqual(isValidWorkerId('0f8fad5b-d9cb-469f-a165-70867728950e'), true);
  assert.strictEqual(isValidWorkerId('a.b_c-1'), true);
  assert.strictEqual(isValidWorkerId(''), false);
  assert.strictEqual(isValidWorkerId('a b'), false);
  assert.strictEqual(isValidWorkerId('x'.repeat(65)), false);
});

test('withWorkerParams appends worker and an encoded token', () => {
  assert.strictEqual(withWorkerParams('ws://h:5090/ws', 'abc', ''), 'ws://h:5090/ws?worker=abc');
  assert.strictEqual(
    withWorkerParams('wss://h/ws', 'abc', 'a b&c=d/+'),
    'wss://h/ws?worker=abc&token=a%20b%26c%3Dd%2F%2B',
  );
  assert.strictEqual(withWorkerParams('ws://h/ws?x=1', 'abc'), 'ws://h/ws?x=1&worker=abc');
});

test('nextBackoff doubles from 2s and caps at 30s', () => {
  assert.strictEqual(nextBackoff(2000), 4000);
  assert.strictEqual(nextBackoff(4000), 8000);
  assert.strictEqual(nextBackoff(16000), 30000);
  assert.strictEqual(nextBackoff(30000), 30000);
  assert.strictEqual(nextBackoff(undefined), 4000);
});
