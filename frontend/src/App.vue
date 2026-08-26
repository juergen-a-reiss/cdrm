<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { authenticatedUser, isAuthenticated, login, logout } from './auth/authService'

const navItems = [
  { title: 'Stages', to: '/stages', icon: 'mdi-stairs' },
  { title: 'Products', to: '/products', icon: 'mdi-package-variant-closed' },
  { title: 'Workloads', to: '/workloads', icon: 'mdi-rocket-launch' },
  { title: 'Releases', to: '/releases', icon: 'mdi-tag' },
]
</script>

<template>
  <v-app>
    <v-navigation-drawer v-if="isAuthenticated" permanent>
      <v-list nav>
        <v-list-item
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          :prepend-icon="item.icon"
          :title="item.title"
        />
      </v-list>
    </v-navigation-drawer>

    <v-app-bar title="Continuous Delivery Release Management">
      <template #prepend>
        <v-avatar size="32" rounded="lg" class="ml-2">
          <v-img src="/favicon.svg" alt="cdrm" />
        </v-avatar>
      </template>
      <template #append>
        <v-btn v-if="!isAuthenticated" @click="login">Log in</v-btn>
        <template v-else>
          <span class="mr-4">{{ authenticatedUser?.profile.preferred_username }}</span>
          <v-btn @click="logout">Log out</v-btn>
        </template>
      </template>
    </v-app-bar>

    <v-main>
      <v-container v-if="!isAuthenticated">
        <p>Log in to continue.</p>
      </v-container>
      <v-container v-else fluid>
        <router-view />
      </v-container>
    </v-main>
  </v-app>
</template>
