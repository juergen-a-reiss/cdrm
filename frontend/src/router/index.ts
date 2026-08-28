// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/stages' },
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
    { path: '/:pathMatch(.*)*', redirect: '/stages' },
  ],
})
