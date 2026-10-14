const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'index.html'), 'utf8');
const script = html.slice(html.indexOf('async function savedPromptRequest('), html.indexOf('// \u2500\u2500\u2500 File selection'))
  + html.slice(html.indexOf('function switchTab(tab)'), html.indexOf('function apiFetch('));

class Element {
  constructor() {
    this.children = [];
    this.value = '';
    this.textContent = '';
    this.disabled = false;
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

test('Generate has no quick prompt save/load controls or popup', () => {
  const generate = html.slice(html.indexOf('<div id="tab-generate"'), html.indexOf('<div id="tab-browse"'));
  assert.doesNotMatch(generate, /save-prompt-btn|load-prompt-btn|saved-prompts-box/);
  assert.doesNotMatch(html, /saved-prompts-dialog|saveCurrentPrompt|openSavedPrompts|renderSavedPrompts/);
  assert.match(html, /id="managed-save-btn"/);
  assert.match(html, /id="managed-use-btn"/);
});

test('management renders prompt names as text and transfers exact text to Generate', async () => {
  const { context, element } = await managerSetup([
    { name: '<script>unsafe & "name"</script>', prompt: '  saved\ntext  ', etag: '"v1"' }
  ]);
  const button = element('prompt-manager-list').children[0];
  assert.equal(button.textContent, '<script>unsafe & "name"</script>');
  button.onclick();
  context.useManagedPrompt();
  assert.equal(element('prompt').value, '  saved\ntext  ');
  assert.equal(element('prompt').resized, true);
  assert.equal(element('prompt').focused, true);
});

test('management reports empty lists and load failures', async () => {
  const { context, element, respond } = await managerSetup([]);
  assert.match(element('prompt-manager-status').textContent, /Add your first prompt/);
  respond({ error: 'Storage unavailable' }, 503);
  await context.loadPromptManager();
  assert.match(element('prompt-manager-status').textContent, /Storage unavailable/);
  assert.equal(element('managed-refresh-btn').disabled, false);
});

test('management save reports duplicate names and network failures without losing edits', async () => {
  const { context, element, requests, responses, respond } = await managerSetup([]);
  context.addManagedPrompt();
  element('managed-prompt-text').value = 'original';
  respond({ error: 'That name already exists. Choose another name.' }, 409);
  await context.saveManagedPrompt();
  assert.match(element('prompt-manager-status').textContent, /already exists/);
  responses.push(new Error('Network unavailable'));
  await context.saveManagedPrompt();
  assert.match(element('prompt-manager-status').textContent, /Network unavailable/);
  assert.equal(element('managed-prompt-text').value, 'original');
  assert.equal(element('managed-save-btn').disabled, false);
  assert.equal(requests[1].options.method, 'POST');
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
