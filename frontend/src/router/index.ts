// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { createRouter, createWebHistory } from 'vue-router'
import { authenticatedUser } from '../auth/authService'
import { NAV_ITEMS } from '../navigation'
import { rememberLastTab } from '../utils/lastTab'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    // Never a real page: HomeView immediately router.replace()s to the caller's
    // remembered (or first visible) tab once auth + menu-visibility are known. Kept as
    // a route (rather than resolved in a global guard) so it can simply wait on those
    // async reads client-side.
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/clusters', name: 'clusters', component: () => import('../views/ClustersView.vue') },
    { path: '/stages', name: 'stages', component: () => import('../views/StagesView.vue') },
    { path: '/products', name: 'products', component: () => import('../views/ProductsView.vue') },
    { path: '/workloads', name: 'workloads', component: () => import('../views/WorkloadsView.vue') },
    { path: '/releases', name: 'releases', component: () => import('../views/ReleasesView.vue') },
    {
      path: '/release-history',
      name: 'release-history',
      component: () => import('../views/ReleaseHistoryDashboardView.vue'),
    },
    { path: '/audit', name: 'audit', component: () => import('../views/AuditView.vue') },
    { path: '/configuration', name: 'configuration', component: () => import('../views/ConfigurationView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

const NAV_KEYS = new Set(NAV_ITEMS.map((item) => item.key))

// Remembers the last nav tab a user had open, per user (see utils/lastTab), so HomeView
// can send them back to it next time instead of always landing on a fixed default.
router.afterEach((to) => {
  const key = typeof to.name === 'string' ? to.name : undefined
  const userId = authenticatedUser.value?.profile.sub
  if (key && userId && NAV_KEYS.has(key)) {
    rememberLastTab(userId, key)
  }
})
