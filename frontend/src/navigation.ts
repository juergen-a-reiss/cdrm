// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

export interface NavItem {
  // Must match a key in the backend's MenuEntries (config package) and the route `name`
  // below it points to — that's what /menu-visibility and HomeView's redirect match
  // against, and what the router's afterEach hook records as the "last tab" per user.
  key: string
  title: string
  to: string
  icon: string
}

// Order here is the fallback order HomeView redirects to when the user has no
// remembered last tab, or it's no longer visible to them: the first entry the caller's
// menu-visibility whitelist allows.
export const NAV_ITEMS: NavItem[] = [
  { key: 'clusters', title: 'Clusters', to: '/clusters', icon: 'mdi-server-network' },
  { key: 'stages', title: 'Stages', to: '/stages', icon: 'mdi-stairs' },
  { key: 'products', title: 'Products', to: '/products', icon: 'mdi-package-variant-closed' },
  { key: 'workloads', title: 'Workloads', to: '/workloads', icon: 'mdi-rocket-launch' },
  { key: 'releases', title: 'Releases', to: '/releases', icon: 'mdi-tag' },
  { key: 'release-history', title: 'Release History', to: '/release-history', icon: 'mdi-chart-bar' },
  { key: 'configuration', title: 'Configuration', to: '/configuration', icon: 'mdi-cog' },
]
