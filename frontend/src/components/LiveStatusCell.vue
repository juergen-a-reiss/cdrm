<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { workloadsApi } from '../api/workloads'
import type { LiveStatusResponse } from '../api/types'
import { formatUptime } from '../utils/formatDuration'

const props = defineProps<{
  workloadId: string
  stageId: string
  // Whether this cell's containing stage tab is the currently active one — the live
  // read only ever fires once this is true, so opening the product detail view never
  // fires one Kubernetes API call per workload across every stage at once, only for
  // whichever tab is actually visible (per the async-after-base-data requirement).
  active: boolean
}>()

const loading = ref(false)
const error = ref<string | null>(null)
const result = ref<LiveStatusResponse | null>(null)
const fetched = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    result.value = await workloadsApi.liveStatus(props.workloadId, props.stageId)
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load live status'
  } finally {
    loading.value = false
    fetched.value = true
  }
}

watch(
  () => props.active,
  (active) => {
    if (active && !fetched.value) load()
  },
  { immediate: true },
)
</script>

<template>
  <v-progress-circular v-if="loading" indeterminate size="16" width="2" />
  <v-tooltip v-else-if="error" :text="error">
    <template #activator="{ props: tooltipProps }">
      <v-chip v-bind="tooltipProps" color="error" size="small" variant="flat" prepend-icon="mdi-alert" @click="load">
        Unreachable
      </v-chip>
    </template>
  </v-tooltip>
  <template v-else-if="result">
    <span v-if="result.state === 'NOT_APPLICABLE'">—</span>
    <v-chip v-else-if="result.state === 'NOT_FOUND'" color="grey" size="small" variant="flat">Not deployed</v-chip>
    <div v-else-if="result.state === 'OK'" class="text-caption">
      <div>{{ result.image ?? '—' }}</div>
      <div>
        <span :class="{ 'text-error': result.readyReplicas !== result.desiredReplicas }">
          {{ result.readyReplicas }}/{{ result.desiredReplicas }} ready
        </span>
        <span v-if="result.totalRestartCount" class="text-error ml-1">· {{ result.totalRestartCount }} restart(s)</span>
      </div>
      <div class="text-medium-emphasis">up {{ formatUptime(result.uptimeSince) }}</div>
    </div>
  </template>
</template>
