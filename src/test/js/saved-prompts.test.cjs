const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'index.html'), 'utf8');
const script = html.slice(html.indexOf('let savedPrompts = []'), html.indexOf('// \u2500\u2500\u2500 File selection'))
  + html.slice(html.indexOf('function switchTab(tab)'), html.indexOf('function apiFetch('));

class Element {
  constructor() {
    this.children = [];
    this.value = '';
    this.textContent = '';
    this.disabled = false;
    this.open = false;
    const classes = new Set();
    this.classList = {
      add: value => classes.add(value),
      remove: value => classes.delete(value),
      contains: value => classes.has(value),
      toggle: (value, enabled) => enabled ? classes.add(value) : classes.delete(value)
    };
  }
  replaceChildren() { this.children = []; }
  append(...children) { this.children.push(...children); }
  setAttribute() {}
  showModal() { this.open = true; }
  close() { this.open = false; }
  focus(options) { this.focused = true; this.focusOptions = options; }
  setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; }
  querySelectorAll() { return this.children.filter(child => child.onclick); }
}

function setup() {
  const elements = new Map();
  const requests = [];
  const responses = [];
  const listeners = {};
  const context = vm.createContext({
    document: {
      getElementById(id) {
        if (!elements.has(id)) elements.set(id, new Element());
        return elements.get(id);
      },
      createElement() { return new Element(); },
      querySelectorAll(selector) {
        const prefix = selector === '.tab-panel' ? 'tab-' : 'tabbutton-';
        return [...elements.entries()].filter(([id]) => id.startsWith(prefix)).map(([, element]) => element);
      },
      querySelector(selector) {
        const tab = selector.match(/onclick\*="([^"]+)"/)[1];
        return this.getElementById('tabbutton-' + tab);
      }
    },
    window: {
      prompt: () => 'Name',
      addEventListener: (event, listener) => listeners[event] = listener,
      scrollX: 0, scrollY: 0,
      scrollTo(x, y) { this.scrollX = x; this.scrollY = y; }
    },
    confirm: () => true,
    autoResize: element => element.resized = true,
    apiFetch: async (url, options) => {
      requests.push({ url, options });
      const response = responses.shift();
      if (response instanceof Error) throw response;
      return response;
    }
  });
  vm.runInContext(script, context);
  const element = id => context.document.getElementById(id);
  element('tab-generate').classList.add('active');
  element('tab-saved-prompts');
  const respond = (data, status = 200) => responses.push({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    text: async () => JSON.stringify(data),
    json: async () => data
  });
  return { context, element, requests, responses, respond, listeners };
}

test('saving preserves exact prompt text and reports success', async () => {
  const { context, element, requests, respond } = setup();
  element('prompt').value = '  A scene\nwith trees  ';
  respond({ name: 'Name' }, 201);
  await context.saveCurrentPrompt();
  assert.deepEqual(JSON.parse(requests[0].options.body), { name: 'Name', prompt: element('prompt').value });
  assert.equal(element('saved-prompts-status').textContent, 'Saved "Name".');
  assert.equal(element('save-prompt-btn').disabled, false);
});

test('empty prompts, cancelled naming and blank names do not save', async () => {
  const { context, element, requests } = setup();
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /Enter a prompt/);
  element('prompt').value = 'text';
  context.window.prompt = () => null;
  await context.saveCurrentPrompt();
  context.window.prompt = () => ' ';
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /1-100 characters/);
  assert.equal(requests.length, 0);
});

test('duplicate names and network failures report errors without changing prompt', async () => {
  const { context, element, responses, respond } = setup();
  element('prompt').value = 'original';
  respond({ error: 'That name already exists. Choose another name.' }, 409);
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /already exists/);
  responses.push(new Error('Network unavailable'));
  await context.saveCurrentPrompt();
  assert.match(element('saved-prompts-status').textContent, /Network unavailable/);
  assert.equal(element('prompt').value, 'original');
  assert.equal(element('save-prompt-btn').disabled, false);
});

test('load popup renders names as text and loading changes only the prompt', async () => {
  const { context, element, respond } = setup();
  element('model').value = 'gpt-image-2';
  element('size').value = 'auto';
  respond([{ name: '<script>unsafe & "name"</script>', prompt: '  saved\ntext  ' }]);
  await context.openSavedPrompts();
  const row = element('saved-prompts-list').children[0];
  assert.equal(row.children[0].textContent, '<script>unsafe & "name"</script>');
  assert.equal(element('saved-prompts-dialog').open, true);
  row.children[1].onclick();
  assert.equal(element('prompt').value, '  saved\ntext  ');
  assert.equal(element('prompt').resized, true);
  assert.equal(element('prompt').focused, true);
  assert.equal(element('model').value, 'gpt-image-2');
  assert.equal(element('size').value, 'auto');
  assert.equal(element('saved-prompts-dialog').open, false);
});

test('delete requires confirmation and removes a row only after success', async () => {
  const { context, element, requests, respond } = setup();
  respond([{ name: 'name / &', prompt: 'text', etag: '"v1"' }]);
  await context.openSavedPrompts();
  const row = element('saved-prompts-list').children[0];
  context.confirm = () => false;
  await row.children[2].onclick();
  assert.equal(requests.length, 1);
  assert.equal(element('saved-prompts-list').children.length, 1);
  context.confirm = () => true;
  respond(null, 204);
  await row.children[2].onclick();
  assert.equal(requests[1].url, '/api/saved-prompts?name=name%20%2F%20%26');
  assert.equal(requests[1].options.method, 'DELETE');
  assert.equal(requests[1].options.headers['If-Match'], '"v1"');
  assert.equal(element('saved-prompts-list').children.length, 0);
  assert.equal(element('saved-prompts-dialog').open, true);
  assert.match(element('saved-prompts-dialog-status').textContent, /Deleted/);
});

test('failed deletion keeps the row and re-enables its buttons', async () => {
  const { context, element, respond } = setup();
  respond([{ name: 'name', prompt: 'text' }]);
  await context.openSavedPrompts();
  const row = element('saved-prompts-list').children[0];
  respond({ error: 'Storage unavailable' }, 503);
  await row.children[2].onclick();
  assert.equal(element('saved-prompts-list').children[0], row);
  assert.equal(row.children[1].disabled, false);
  assert.equal(row.children[2].disabled, false);
  assert.match(element('saved-prompts-dialog-status').textContent, /Storage unavailable/);
});

test('empty lists and load failures are explicit and clear stale rows', async () => {
  const { context, element, respond } = setup();
  respond([]);
  await context.openSavedPrompts();
  assert.match(element('saved-prompts-dialog-status').textContent, /No saved prompts/);
  element('saved-prompts-dialog').close();
  respond({ error: 'Storage unavailable' }, 503);
  await context.openSavedPrompts();
  assert.equal(element('saved-prompts-list').children.length, 0);
  assert.match(element('saved-prompts-dialog-status').textContent, /Storage unavailable/);
  assert.equal(element('load-prompt-btn').disabled, false);
});

test('name validation trims only edges and rejects prohibited characters', () => {
  const { context } = setup();
  assert.equal(context.normalizeSavedPromptName('  Night  sky  '), 'Night  sky');
  assert.equal(context.normalizeSavedPromptName('Cafe\u0301'), 'Caf\u00e9');
  assert.equal(context.normalizeSavedPromptName('a'.repeat(100)), 'a'.repeat(100));
  for (const character of '/\\:*?"<>|\n\u007f\u0085') {
    assert.throws(() => context.normalizeSavedPromptName('a' + character + 'b'));
  }
  assert.throws(() => context.normalizeSavedPromptName('a'.repeat(101)));
});

async function managerSetup(prompts = [
  { name: 'First', prompt: 'original', etag: '"v1"' },
  { name: 'Second', prompt: 'second text', etag: '"v2"' }
]) {
  const env = setup();
  env.respond(prompts);
  await env.context.loadPromptManager();
  return env;
}

test('management selection fills editor without changing Generate and guards dirty selection', async () => {
  const { context, element } = await managerSetup();
  element('prompt').value = 'Generate stays here';
  element('prompt-manager-list').children[0].onclick();
  assert.equal(element('managed-prompt-text').value, 'original');
  assert.equal(element('managed-save-btn').disabled, true);
  element('managed-prompt-text').value = 'edited';
  context.updatePromptManagerControls();
  assert.equal(element('managed-save-btn').disabled, false);
  context.confirm = () => false;
  element('prompt-manager-list').children[1].onclick();
  assert.equal(element('managed-prompt-text').value, 'edited');
  context.confirm = () => true;
  element('prompt-manager-list').children[1].onclick();
  assert.equal(element('managed-prompt-text').value, 'second text');
  assert.equal(element('prompt').value, 'Generate stays here');
});

test('selecting a long prompt focuses its start without scrolling the page', async () => {
  const { context, element } = await managerSetup([
    { name: 'Long prompt', prompt: 'A long prompt line.\n'.repeat(200), etag: '"v1"' }
  ]);
  context.window.scrollY = 50;
  context.autoResize = editor => { editor.resized = true; context.window.scrollY = 0; };
  element('prompt-manager-list').children[0].onclick();
  const editor = element('managed-prompt-text');
  assert.equal(editor.focused, true);
  assert.equal(editor.focusOptions.preventScroll, true);
  assert.equal(editor.selectionStart, 0);
  assert.equal(editor.selectionEnd, 0);
  assert.equal(context.window.scrollY, 50);
});

test('Add creates a named local draft and Save creates it without overwriting', async () => {
  const { context, element, requests, respond } = await managerSetup([]);
  context.window.prompt = () => '  Night  sky  ';
  context.addManagedPrompt();
  assert.equal(requests.length, 1);
  assert.equal(element('managed-prompt-name').textContent, 'Night  sky');
  assert.equal(element('managed-prompt-text').value, '');
  assert.equal(element('managed-save-btn').disabled, true);
  element('managed-prompt-text').value = '  scene\ntext  ';
  context.updatePromptManagerControls();
  respond({ name: 'Night  sky', prompt: '  scene\ntext  ', etag: '"new"' }, 201);
  await context.saveManagedPrompt();
  assert.equal(requests[1].options.method, 'POST');
  assert.deepEqual(JSON.parse(requests[1].options.body), { name: 'Night  sky', prompt: '  scene\ntext  ' });
  assert.equal(element('managed-save-btn').disabled, true);
  assert.equal(element('prompt-manager-list').children[0].textContent, 'Night  sky');
});

test('Add rejects invalid and conflicting names and respects cancellation', async () => {
  const { context, element, requests } = await managerSetup();
  for (const name of ['first', 'bad/name', 'bad\\name']) {
    context.window.prompt = () => name;
    context.addManagedPrompt();
    assert.equal(element('managed-prompt-text').disabled, true);
  }
  context.window.prompt = () => null;
  context.addManagedPrompt();
  assert.equal(requests.length, 1);
  assert.equal(element('managed-prompt-text').disabled, true);
});

test('saving existing edits sends If-Match and retains edits after a conflict', async () => {
  const { context, element, requests, respond } = await managerSetup();
  element('prompt-manager-list').children[0].onclick();
  element('managed-prompt-text').value = '  edits  ';
  respond({ error: 'The prompt changed in another session.' }, 412);
  await context.saveManagedPrompt();
  assert.equal(requests[1].options.method, 'PUT');
  assert.equal(requests[1].options.headers['If-Match'], '"v1"');
  assert.equal(element('managed-prompt-text').value, '  edits  ');
  assert.equal(element('managed-save-btn').disabled, false);
  assert.match(element('prompt-manager-status').textContent, /changed/);
  respond({ name: 'First', prompt: '  edits  ', etag: '"v3"' });
  await context.saveManagedPrompt();
  assert.equal(element('managed-save-btn').disabled, true);
});

test('rename sends ETag, allows case-only changes, and preserves pending edits', async () => {
  const { context, element, requests, respond } = await managerSetup();
  element('prompt-manager-list').children[0].onclick();
  element('managed-prompt-text').value = 'unsaved editor text';
  context.window.prompt = () => 'FIRST';
  respond({ name: 'FIRST', prompt: 'original', etag: '"v3"' });
  await context.renameManagedPrompt();
  assert.equal(requests[1].url, '/api/saved-prompts/rename');
  assert.equal(requests[1].options.headers['If-Match'], '"v1"');
  assert.deepEqual(JSON.parse(requests[1].options.body), { name: 'First', newName: 'FIRST' });
  assert.equal(element('managed-prompt-text').value, 'unsaved editor text');
  assert.equal(element('managed-prompt-name').textContent, 'FIRST');
  assert.equal(element('managed-save-btn').disabled, false);
  respond({ name: 'FIRST', prompt: 'unsaved editor text', etag: '"v4"' });
  await context.saveManagedPrompt();
  assert.equal(requests[2].options.headers['If-Match'], '"v3"');
});

test('rename conflict preserves editor and selection; draft rename stays local', async () => {
  const { context, element, requests, respond } = await managerSetup();
  element('prompt-manager-list').children[0].onclick();
  context.window.prompt = () => 'second';
  await context.renameManagedPrompt();
  assert.equal(requests.length, 1);
  context.window.prompt = () => 'New name';
  respond({ error: 'Name is already in use.' }, 409);
  await context.renameManagedPrompt();
  assert.equal(element('managed-prompt-name').textContent, 'First');
  assert.equal(element('managed-prompt-text').value, 'original');
  context.window.prompt = () => 'Draft';
  context.addManagedPrompt();
  element('managed-prompt-text').value = 'draft text';
  context.window.prompt = () => 'Renamed draft';
  await context.renameManagedPrompt();
  assert.equal(requests.length, 2);
  assert.equal(element('managed-prompt-name').textContent, 'Renamed draft');
  assert.equal(element('managed-prompt-text').value, 'draft text');
});

test('management delete confirms, uses If-Match, and preserves failed deletions', async () => {
  const { context, element, requests, respond } = await managerSetup();
  element('prompt-manager-list').children[0].onclick();
  context.confirm = () => false;
  await context.deleteManagedPrompt();
  assert.equal(requests.length, 1);
  context.confirm = () => true;
  respond({ error: 'The prompt changed.' }, 412);
  await context.deleteManagedPrompt();
  assert.equal(requests[1].options.headers['If-Match'], '"v1"');
  assert.equal(element('managed-prompt-text').value, 'original');
  respond(null, 204);
  await context.deleteManagedPrompt();
  assert.equal(element('managed-prompt-text').value, '');
  assert.equal(element('managed-prompt-text').disabled, true);
  assert.equal(element('prompt-manager-list').children.length, 1);
});

test('deleting a draft sends no request', async () => {
  const { context, element, requests } = await managerSetup([]);
  context.addManagedPrompt();
  await context.deleteManagedPrompt();
  assert.equal(requests.length, 1);
  assert.equal(element('managed-prompt-text').disabled, true);
});

test('refresh warns before discarding edits and retains them on failure', async () => {
  const { context, element, requests, respond } = await managerSetup();
  element('prompt-manager-list').children[0].onclick();
  element('managed-prompt-text').value = 'pending edits';
  context.confirm = () => false;
  await context.loadPromptManager();
  assert.equal(requests.length, 1);
  context.confirm = () => true;
  respond({ error: 'Storage unavailable' }, 503);
  await context.loadPromptManager();
  assert.equal(element('managed-prompt-text').value, 'pending edits');
  respond([{ name: 'First', prompt: 'latest server text', etag: '"latest"' }]);
  await context.loadPromptManager();
  assert.equal(element('managed-prompt-text').value, 'latest server text');
});

test('Use in Generate honors discard warning and changes no generation settings', async () => {
  const { context, element } = await managerSetup();
  element('tab-generate').classList.remove('active');
  element('tab-saved-prompts').classList.add('active');
  element('prompt-manager-list').children[0].onclick();
  element('managed-prompt-text').value = 'pending text to use';
  element('prompt').value = 'original generate text';
  element('model').value = 'gpt-image-2';
  element('size').value = 'auto';
  context.confirm = () => false;
  context.useManagedPrompt();
  assert.equal(element('prompt').value, 'original generate text');
  assert.equal(element('tab-saved-prompts').classList.contains('active'), true);
  context.confirm = () => true;
  context.useManagedPrompt();
  assert.equal(element('prompt').value, 'pending text to use');
  assert.equal(element('tab-generate').classList.contains('active'), true);
  assert.equal(element('model').value, 'gpt-image-2');
  assert.equal(element('size').value, 'auto');
  assert.equal(element('managed-prompt-text').value, 'original');
});

test('browser navigation warns while edits are unsaved', async () => {
  const { context, element, listeners } = await managerSetup();
  element('prompt-manager-list').children[0].onclick();
  const event = { preventDefault() { this.prevented = true; } };
  listeners.beforeunload(event);
  assert.equal(event.prevented, undefined);
  element('managed-prompt-text').value = 'pending';
  listeners.beforeunload(event);
  assert.equal(event.prevented, true);
  assert.equal(event.returnValue, '');
  assert.equal(context.managedPromptDirty(), true);
});

test('in-flight mutations disable editing and switching away until complete', async () => {
  const { context, element, responses } = await managerSetup();
  element('tab-generate').classList.remove('active');
  element('tab-saved-prompts').classList.add('active');
  element('prompt-manager-list').children[0].onclick();
  element('managed-prompt-text').value = 'edited';
  let complete;
  const pending = new Promise(resolve => complete = resolve);
  responses.push({ ok: true, status: 200, json: () => pending });
  const operation = context.saveManagedPrompt();
  await Promise.resolve();
  assert.equal(element('managed-prompt-text').disabled, true);
  assert.equal(element('managed-refresh-btn').disabled, true);
  assert.equal(context.switchTab('generate'), false);
  complete({ name: 'First', prompt: 'edited', etag: '"new"' });
  await operation;
  assert.equal(element('managed-prompt-text').disabled, false);
  assert.equal(element('managed-save-btn').disabled, true);
});
