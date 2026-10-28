'use strict';

// Structure/content tripwires, not a substitute for native-language or browser review.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { LANGUAGES, PAGES, parsePages } = require('../build.js');
const site = path.resolve(__dirname, '..');
const english = require('../src/i18n/en.js');

test('page selection is explicit and cannot escape output directories', () => {
  assert.deepEqual(parsePages([]), PAGES);
  assert.deepEqual(parsePages(['--pages', 'index,docs']), ['index', 'docs']);
  for (const args of [['--pages', ''], ['--pages', '../privacy'], ['--pages', 'docs,docs'],
                      ['--pages'], ['--unknown', 'docs'], ['--pages', 'docs', 'extra']]) {
    assert.throws(() => parsePages(args));
  }
});

for (const lang of LANGUAGES) {
  const tr = require(`../src/i18n/${lang.code}.js`);
  test(`${lang.code}: test branch, Android/iOS and credential policies remain explicit`, () => {
    assert.match(tr.security_branch_notice, /Android[ -]11\+/);
    assert.match(tr.security_branch_notice, /data-policy="test-branch"/);
    assert.match(tr.security_branch_notice, /role="note"/);
    if (lang.code !== 'en') {
      assert.notEqual(tr.security_branch_notice, english.security_branch_notice);
      assert.notEqual(tr.sec_badge_keystore, english.sec_badge_keystore);
    }
    for (const policy of ['lock', 'storage', 'auth', 'migration', 'backup', 'limits']) {
      assert.match(tr.doc_security, new RegExp(`<p data-policy="${policy}">[^<]+</p>`));
    }
    for (const section of [tr.doc_security, tr.doc_ssh_keys]) {
      assert.match(section, /data-platform="android-test"/);
      assert.match(section, /<div data-platform="ios"><p>[^]*?href="\{\{REPO_IOS\}\}"[^]*?<\/p><\/div>/);
      const ios = section.match(/<div data-platform="ios">([^]*?)<\/div>/)[1];
      assert.doesNotMatch(ios, /data-policy=/);
      assert.doesNotMatch(section, /Encrypt sensitive data|hardware-backed on Android 10/);
    }
    assert.match(tr.doc_ssh_keys, /BEGIN ENCRYPTED PRIVATE KEY/);
    assert.match(tr.doc_ssh_keys, /ssh-keygen -p -N/);
    assert.match(tr.doc_security, /Keystore/);
    assert.match(tr.doc_android_tv, /data-policy="tv-auth"/);
    assert.match(tr.doc_backup, /data-platform="android-test" data-backup-format="8"/);
    assert.match(tr.doc_backup, /data-policy="key-reference"/);
    assert.match(tr.doc_backup, /data-policy="pin-retention"/);
    assert.doesNotMatch(tr.doc_backup, /jumpHostIdList/);
    const example = tr.doc_backup.match(/<pre><code>([^]*?)<\/code><\/pre>/)[1];
    const backup = JSON.parse(example);
    assert.equal(backup.version, 8);
    assert.deepEqual(backup.hosts[0].jumpHostLabels, []);
  });
}

test('partial build generates scoped copy in all 20 pages without touching release history', () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'sshborg-site-test-'));
  try {
    const target = path.join(temporary, 'site');
    fs.mkdirSync(target);
    fs.copyFileSync(path.join(site, 'build.js'), path.join(target, 'build.js'));
    fs.cpSync(path.join(site, 'src'), path.join(target, 'src'), { recursive: true });
    const untouched = ['sitemap.xml', 'changelog.html', 'changelog-ios.html', 'privacy_policy.html'];
    for (const name of untouched) fs.writeFileSync(path.join(target, name), 'unchanged fixture');
    execFileSync(process.execPath, [path.join(target, 'build.js'), '--pages', 'index,docs']);
    for (const lang of LANGUAGES) {
      for (const page of ['index', 'docs']) {
        const html = fs.readFileSync(path.join(target, lang.dir, `${page}.html`), 'utf8');
        assert.match(html, /data-policy="test-branch"/);
        assert.doesNotMatch(html, /\{\{[A-Za-z_]+\}\}/, `${lang.code}/${page}: unresolved placeholder`);
        if (page === 'docs') {
          assert.match(html, /data-policy="migration"/);
          assert.match(html, /https:\/\/github.com\/payne1982\/sshborg-ios/);
        }
      }
    }
    for (const name of untouched) assert.equal(fs.readFileSync(path.join(target, name), 'utf8'), 'unchanged fixture');
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});

test('privacy policy distinguishes the Android test branch and UI lock from credential authorization', () => {
  const html = fs.readFileSync(path.join(site, 'privacy_policy.html'), 'utf8');
  assert.match(html, /Android 11\+ security-hardening test branch/);
  assert.match(html, /Hardware backing depends on the device/);
  assert.match(html, /An optional app lock does not replace this requirement/);
  assert.match(html, /Do not uninstall to fix a signing conflict/);
  assert.match(html, /On iOS \(iPhone and iPad\)/);
  assert.doesNotMatch(html, /optionally be encrypted using the <strong>Android Keystore/);
});
