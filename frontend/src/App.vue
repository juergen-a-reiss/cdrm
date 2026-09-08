<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDisplay, useTheme } from 'vuetify'
import { isAuthenticated, login, logout } from './auth/authService'
import { useMenuVisibility } from './composables/useMenuVisibility'
import { NAV_ITEMS } from './navigation'
import ToastHost from './components/ToastHost.vue'
import UserProfileMenu from './components/UserProfileMenu.vue'
import { THEME_STORAGE_KEY } from './plugins/vuetify'

const theme = useTheme()

function toggleTheme() {
  const next = theme.global.current.value.dark ? 'light' : 'dark'
  theme.change(next)
  localStorage.setItem(THEME_STORAGE_KEY, next)
}

const { visibleKeys } = useMenuVisibility()

// Below the mobile breakpoint the drawer becomes a temporary overlay (closed by
// default, toggled by the nav icon) instead of permanently reserving screen width —
// there's no room for both a fixed drawer and the actual page content on a phone.
const { mobile } = useDisplay()

// Audit is desktop-only regardless of the role's menu-visibility whitelist — it's a
// dense server-paginated table, not something worth cramming onto a phone screen.
const navItems = computed(() =>
  NAV_ITEMS.filter((item) => visibleKeys.value.has(item.key) && !(item.key === 'audit' && mobile.value)),
)
const drawerOpen = ref(!mobile.value)
watch(mobile, (isMobile) => {
  drawerOpen.value = !isMobile
})

function closeDrawerOnMobileNav() {
  if (mobile.value) {
    drawerOpen.value = false
  }
}
</script>

<template>
  <v-app>
    <v-navigation-drawer v-if="isAuthenticated" v-model="drawerOpen" :permanent="!mobile" :temporary="mobile">
      <v-list nav>
        <v-list-item
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          :prepend-icon="item.icon"
          :title="item.title"
          @click="closeDrawerOnMobileNav"
        />
      </v-list>
    </v-navigation-drawer>

    <v-app-bar title="Continuous Delivery Release Management">
      <template #prepend>
        <v-app-bar-nav-icon v-if="isAuthenticated && mobile" @click="drawerOpen = !drawerOpen" />
        <span class="brand-logo ml-2" aria-hidden="true" />
      </template>
      <template #append>
        <v-btn
          :icon="theme.global.current.value.dark ? 'mdi-weather-sunny' : 'mdi-weather-night'"
          variant="text"
          :title="theme.global.current.value.dark ? 'Switch to light mode' : 'Switch to dark mode'"
          @click="toggleTheme"
        />
        <v-btn v-if="!isAuthenticated" @click="login">Log in</v-btn>
        <template v-else>
          <UserProfileMenu v-if="!mobile" />
          <v-btn @click="logout">Log out</v-btn>
        </template>
      </template>
    </v-app-bar>

    <v-main>
      <v-container v-if="!isAuthenticated">
        <p>Log in to continue.</p>
        <p>This is the cdrm (Continuous Delivery Release Management) application. It will automate and govern all your deployments to kubernetes (k8s) and proxmox. 
          Further, it allows you to get metrics and statistics about deployments, rollbacks or redeploys for all your applications and products.</p>
      </v-container>
      <v-container v-else fluid>
        <router-view />
      </v-container>
    </v-main>

    <ToastHost />
  </v-app>
</template>
