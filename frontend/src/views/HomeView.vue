<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { authenticatedUser, isAuthenticated } from '../auth/authService'
import { useMenuVisibility } from '../composables/useMenuVisibility'
import { NAV_ITEMS } from '../navigation'
import { getLastTab } from '../utils/lastTab'

const router = useRouter()
const { visibleKeys, loaded } = useMenuVisibility()

// Never rendered while logged out — App.vue shows the login prompt instead of
// <router-view> until isAuthenticated — so this only ever has to resolve the
// authenticated case, once menu-visibility has loaded.
watch(
  [isAuthenticated, loaded, visibleKeys],
  ([authenticated, isLoaded]) => {
    if (!authenticated || !isLoaded) return
    const userId = authenticatedUser.value?.profile.sub
    const lastTab = userId ? getLastTab(userId) : null
    const targetKey =
      lastTab && visibleKeys.value.has(lastTab) ? lastTab : NAV_ITEMS.find((item) => visibleKeys.value.has(item.key))?.key
    const target = NAV_ITEMS.find((item) => item.key === targetKey)
    if (target) {
      router.replace(target.to)
    }
  },
  { immediate: true },
)

const noVisibleTabs = computed(() => loaded.value && !NAV_ITEMS.some((item) => visibleKeys.value.has(item.key)))
</script>

<template>
  <v-container v-if="noVisibleTabs">
    <p>No menu entries are configured for your role. Contact a cdrm-devops user.</p>
  </v-container>
</template>
