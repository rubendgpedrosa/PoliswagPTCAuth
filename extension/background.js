'use strict';

const CONTROL_URL = chrome.runtime.getURL('control.html');

async function openOrFocusControl() {
  const existing = await chrome.tabs.query({ url: CONTROL_URL });
  if (existing.length > 0) {
    await chrome.tabs.update(existing[0].id, { active: true });
    await chrome.windows.update(existing[0].windowId, { focused: true });
    return;
  }
  await chrome.tabs.create({ url: CONTROL_URL, pinned: true });
}

chrome.runtime.onInstalled.addListener(openOrFocusControl);
chrome.runtime.onStartup.addListener(openOrFocusControl);
chrome.action.onClicked.addListener(openOrFocusControl);
