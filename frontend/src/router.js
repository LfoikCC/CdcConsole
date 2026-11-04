import { createRouter, createWebHistory } from 'vue-router'
import { checkIdentity, can } from './auth'
import LoginView from './views/LoginView.vue'
import AuditView from './views/AuditView.vue'
import TasksView from './views/TasksView.vue'
import MonitorView from './views/MonitorView.vue'
import TaskDetailView from './views/TaskDetailView.vue'
import AlertsView from './views/AlertsView.vue'
import SettingsView from './views/SettingsView.vue'
import TaskWizardPage from './views/TaskWizardPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/tasks' },
    { path: '/login', component: LoginView },
    { path: '/audit', component: AuditView, meta: { title: '审计日志' } },
    { path: '/tasks', component: TasksView, meta: { title: '同步任务' } },
    { path: '/monitor', component: MonitorView, meta: { title: '任务监控' } },
    { path: '/tasks/new', component: TaskWizardPage, meta: { title: '新建同步任务', requiresAdmin: true } },
    { path: '/tasks/:id/edit', component: TaskWizardPage, meta: { title: '编辑同步任务', requiresAdmin: true } },
    { path: '/tasks/:id', component: TaskDetailView, meta: { title: '任务详情' } },
    // 旧地址：数据连接已并入数据准备的数据账户
    { path: '/connections', redirect: '/prep/accounts' },
    { path: '/connections/:kind(sources|targets)', redirect: '/prep/accounts' },
    { path: '/alerts', component: AlertsView, meta: { title: '告警中心' } },
    { path: '/settings', component: SettingsView, meta: { title: '系统设置', requiresAdmin: true } },

    // 数据准备
    { path: '/prep', redirect: '/prep/datasets' },
    { path: '/prep/datasets', component: () => import('./views/prep/DatasetsView.vue'), meta: { title: '数据集' } },
    { path: '/prep/datasets/new', component: () => import('./views/prep/DatasetWizard.vue'), meta: { title: '新建数据集', requiresAdmin: true } },
    { path: '/prep/accounts', component: () => import('./views/prep/AccountsView.vue'), meta: { title: '数据账户' } },

    // 数据开发：`new` 必须排在 `:id` 之前，否则会被当成 id 解析
    { path: '/dev', redirect: '/dev/flows' },
    { path: '/dev/flows', component: () => import('./views/dev/FlowsView.vue'), meta: { title: '数据流' } },
    { path: '/dev/flows/new', component: () => import('./views/dev/FlowEditorView.vue'), meta: { title: '新建数据流', requiresAdmin: true, immersive: true } },
    { path: '/dev/flows/:id/edit', component: () => import('./views/dev/FlowEditorView.vue'), meta: { title: '编辑数据流', requiresAdmin: true, immersive: true } },
    { path: '/dev/flows/:id', component: () => import('./views/dev/FlowDetailView.vue'), meta: { title: '数据流详情' } },
    { path: '/dev/flows/:id/runs/:runId', component: () => import('./views/dev/FlowRunView.vue'), meta: { title: '运行详情' } },

    // 数据分析：`/edit` 必须排在 `:id` 之前，否则会被当成 id 解析
    { path: '/bi', redirect: '/bi/dashboards' },
    { path: '/bi/dashboards', component: () => import('./views/bi/DashboardsView.vue'), meta: { title: '数据看板' } },
    { path: '/bi/dashboards/:id/edit', component: () => import('./views/bi/DashboardEditorView.vue'), meta: { title: '编辑看板', requiresAdmin: true, immersive: true } },
    { path: '/bi/dashboards/:id', component: () => import('./views/bi/DashboardDetailView.vue'), meta: { title: '看板详情' } }
  ]
})
router.beforeEach(async to => {
  const user = await checkIdentity()
  if (!user && to.path !== '/login') return { path:'/login', query:{redirect:to.fullPath} }
  if (user && to.path === '/login') return '/tasks'
  // 用 meta 判断，而不是 endsWith('/edit')：后者会把 /dev/flows/5/edit 误判成同步任务的编辑页
  if (user && !can('admin') && to.meta.requiresAdmin) return '/tasks'
})
export default router
