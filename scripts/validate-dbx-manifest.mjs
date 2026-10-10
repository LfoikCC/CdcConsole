// Validate the UI-only subset used by these packages against DBX Manifest v1.
export function validateManifest(manifest) {
  const fail = message => { throw new Error(`Invalid DBX manifest: ${message}`) }
  const identifier = /^[a-z0-9][a-z0-9._-]*$/
  if (manifest.manifest_version !== 1) fail('manifest_version must be 1')
  for (const name of ['id', 'name', 'version', 'publisher']) {
    if (typeof manifest[name] !== 'string' || !manifest[name]) fail(`${name} is required`)
  }
  if (!identifier.test(manifest.id)) fail('invalid plugin id')
  if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(manifest.version)) fail('invalid version')
  if (!manifest.engines?.host_api) fail('engines.host_api is required')
  if (!manifest.entrypoints?.ui?.entry) fail('UI entry is required')
  const permissions = new Set(['host.events', 'host.binary', 'host.workbench', 'host.filesystem',
    'host.plans:read', 'host.schema:read', 'host.storage', 'host.ai', 'host.clipboard:read', 'host.data:read'])
  for (const permission of manifest.permissions || []) {
    if (!permissions.has(permission) && !/^host\.network:https:\/\/[A-Za-z0-9._-]+(:[0-9]+)?$/.test(permission)) {
      fail(`unsupported permission ${permission}`)
    }
  }
  const contributions = manifest.contributions || []
  const workbenches = new Set(contributions.filter(item => item.type === 'workbench').map(item => item.id))
  const ids = new Set()
  for (const item of contributions) {
    if (!identifier.test(item.id) || ids.has(item.id)) fail('invalid or duplicate contribution id')
    ids.add(item.id)
    if (!item.label) fail(`${item.id} requires label`)
    if (!['command', 'workbench'].includes(item.type)) fail(`unsupported contribution type ${item.type}`)
    const allowed = new Set(['type', 'id', 'label', 'description', 'icon', ...(item.type === 'command' ? ['action', 'enablement'] : ['ai'])])
    for (const key of Object.keys(item)) if (!allowed.has(key)) fail(`unsupported contribution field ${key}`)
    if (item.type === 'command') {
      if (!item.action) fail(`${item.id} requires action`)
      if (item.action.type !== 'open-workbench' || !workbenches.has(item.action.workbench)) {
        fail(`${item.id} must open a declared workbench`)
      }
      if (item.action.presentation && !['tab', 'panel'].includes(item.action.presentation)) fail('invalid presentation')
      if (item.action.context && (typeof item.action.context !== 'object' || Array.isArray(item.action.context))) fail('invalid context')
    }
  }
}
