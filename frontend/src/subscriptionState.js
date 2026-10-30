export function subscriptionTableStatus(subscription, table) {
  return ['CANCELLED', 'PAUSED', 'FAILED'].includes(subscription.status) ? subscription.status : table.status
}

export function subscriptionPhase(status) {
  return {ACTIVE:'投递中',INITIALIZING_SNAPSHOT:'全量初始化中',CATCHING_UP:'追平增量中',PAUSED:'已暂停',CANCELLED:'已取消',FAILED:'失败'}[status] || status
}

export function initializationLabel(table) {
  if (table.status==='INITIALIZING_SNAPSHOT') return '全量初始化中'
  if (table.status==='CATCHING_UP') return '追平增量中'
  if (table.status==='FAILED') return table.initializationCompletedAt?'失败（曾完成初始化）':'未完成'
  if (table.initializationCompletedAt) return '已完成'
  return {INITIALIZING_SNAPSHOT:'全量初始化中',CATCHING_UP:'追平增量中',ACTIVE:'已完成',FAILED:'未完成',PAUSED:'已暂停',CANCELLED:'已取消'}[table.status] || '尚未完成'
}
