import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/chat',
    children: [
      { path: 'chat', component: () => import('../views/ChatView.vue') },
      { path: 'chat/:sessionId', component: () => import('../views/ChatView.vue') },
      { path: 'documents', component: () => import('../views/DocumentsView.vue') },
      { path: 'settings', component: () => import('../views/SettingsView.vue') },
    ],
  },
  { path: '/login', component: () => import('../views/Login.vue') },
  { path: '/register', component: () => import('../views/Register.vue') },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (token && (to.path === '/login' || to.path === '/register')) {
    next('/chat')
  } else if (!token && to.path !== '/login' && to.path !== '/register') {
    next('/login')
  } else {
    next()
  }
})

export default router
