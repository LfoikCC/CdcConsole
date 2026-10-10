import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { validateManifest } from './validate-dbx-manifest.mjs'

const manifest = JSON.parse(await readFile(new URL('../plugin/manifest.json', import.meta.url)))
const probe = JSON.parse(await readFile(new URL('../plugin/probe/manifest.json', import.meta.url)))

test('both plugin manifests pass the supported DBX UI contract', () => {
  validateManifest(manifest)
  validateManifest(probe)
})
test('commands without action are rejected before packaging', () => {
  const invalid = structuredClone(manifest)
  delete invalid.contributions[1].action
  assert.throws(() => validateManifest(invalid), /requires action/)
})
test('undeclared permissions are rejected before packaging', () => {
  assert.throws(() => validateManifest({ ...manifest, permissions: ['backend:access'] }), /unsupported permission/)
})
test('commands cannot reference an absent workbench', () => {
  const invalid = structuredClone(manifest)
  invalid.contributions[1].action.workbench = 'missing'
  assert.throws(() => validateManifest(invalid), /declared workbench/)
})
