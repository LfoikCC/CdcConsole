// Single source of truth for status vocabulary: every page renders the same
// label + badge tone instead of leaking raw enums such as RUNNING or FAILED.

const instanceLabels = {
  DRAFT: '草稿',
  STARTING: '启动中',
  SNAPSHOTTING: '全量同步',
  RUNNING: '运行中',
  DEGRADED: '部分异常',
  PAUSED: '已暂停',
  STOPPED: '已停止',
  COMPLETED: '已完成',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  ABORTED: '已中止',
  CANCELLED: '已取消',
}

const instanceTones = {
  DRAFT: 'muted',
  STARTING: 'info',
  SNAPSHOTTING: 'purple',
  RUNNING: 'success',
  DEGRADED: 'warning',
  PAUSED: 'warning',
  STOPPED: 'muted',
  COMPLETED: 'success',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  ABORTED: 'danger',
  CANCELLED: 'muted',
}

const levelLabels = { INFO: '信息', WARN: '警告', WARNING: '警告', ERROR: '错误', DEBUG: '调试' }
const levelTones = { INFO: 'info', WARN: 'warning', WARNING: 'warning', ERROR: 'danger', DEBUG: 'muted' }

const discrepancyTones = {
  TARGET_MISSING: 'danger',
  TARGET_REDUNDANT: 'warning',
  FIELD_MISMATCH: 'info',
  DELETE_RESIDUE: 'purple',
}

export function instanceStatusMeta(status) {
  if (!status) return { label: '-', tone: 'muted' }
  return { label: instanceLabels[status] || status, tone: instanceTones[status] || 'muted' }
}

export function levelMeta(level) {
  if (!level) return { label: '-', tone: 'muted' }
  return { label: levelLabels[level] || level, tone: levelTones[level] || 'muted' }
}

export function enabledMeta(enabled) {
  return enabled ? { label: '启用', tone: 'success' } : { label: '停用', tone: 'muted' }
}

export function reconciliationMeta(enabled) {
  return enabled ? { label: '已配置', tone: 'info' } : { label: '未配置', tone: 'muted' }
}

export function discrepancyTone(type) {
  return discrepancyTones[type] || 'muted'
}

export function badgeClass(tone) {
  return `badge badge-${tone || 'muted'}`
}
