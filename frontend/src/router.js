import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import { dbxHost } from './hostAdapter'
import TasksView from './views/TasksView.vue'
import MonitorView from './views/MonitorView.vue'
import TaskDetailView from './views/TaskDetailView.vue'
import ConnectionsView from './views/ConnectionsView.vue'
import AlertsView from './views/AlertsView.vue'
import SettingsView from './views/SettingsView.vue'
import TaskWizardPage from './views/TaskWizardPage.vue'

export default createRouter({
  history: dbxHost.isDbx() ? createWebHashHistory() : createWebHistory(),
  routes: [
    { path: '/', redirect: '/tasks' },
    { path: '/tasks', component: TasksView },
    { path: '/monitor', component: MonitorView },
    { path: '/tasks/new', component: TaskWizardPage },
    { path: '/tasks/:id/edit', component: TaskWizardPage },
    { path: '/tasks/:id', component: TaskDetailView },
    { path: '/connections', redirect: '/connections/sources' },
    { path: '/connections/:kind(sources|targets)', component: ConnectionsView },
    { path: '/alerts', component: AlertsView },
    { path: '/settings', component: SettingsView }
  ]
})
