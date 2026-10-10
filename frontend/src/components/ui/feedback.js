import { reactive } from 'vue'

export const feedback = reactive({ notices: [], confirmation: null })
let nextId = 0
function notice(message, type = 'info') {
  const id = ++nextId
  feedback.notices.push({ id, message: typeof message === 'string' ? message : message.message, type })
  setTimeout(() => { feedback.notices = feedback.notices.filter(item => item.id !== id) }, type === 'error' ? 7000 : 4000)
}
export const CdcMessage = Object.assign(notice, Object.fromEntries(['success','warning','error','info'].map(type => [type, message => notice(message, type)])))
export const CdcMessageBox = {
  confirm(message, title = '确认操作') {
    if (feedback.confirmation) return Promise.reject('cancel')
    return new Promise((resolve, reject) => { feedback.confirmation = { message, title, resolve, reject } })
  }
}
export function finishConfirmation(confirmed) {
  const pending = feedback.confirmation
  feedback.confirmation = null
  if (pending) confirmed ? pending.resolve() : pending.reject('cancel')
}
