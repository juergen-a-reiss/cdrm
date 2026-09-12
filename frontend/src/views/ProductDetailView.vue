<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { productsApi } from '../api/products'
import type { ProductDeploymentOverviewResponse } from '../api/types'
import LiveStatusCell from '../components/LiveStatusCell.vue'
import { formatDeploymentStatus, gitOpsStatusDisplay, kubernetesStatusDisplay } from '../utils/releaseHistoryStatus'

const props = defineProps<{ name: string }>()

const overview = ref<ProductDeploymentOverviewResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
// Which stage tab is active — LiveStatusCell only fetches for the active tab's rows
// (see its own comment), so switching tabs never fires live-status calls for every
// stage's workloads at once, only the one currently visible.
const activeStageId = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    // The route only carries the product's name (see router/index.ts), so resolve it to
    // an id before hitting the UUID-keyed deployment-overview endpoint.
    const products = await productsApi.list()
    const product = products.find((p) => p.name === props.name)
    if (!product) {
      error.value = `Product "${props.name}" not found`
      overview.value = null
      return
    }
    overview.value = await productsApi.deploymentOverview(product.id)
    if (!overview.value.stages.some((stage) => stage.stageId === activeStageId.value)) {
      activeStageId.value = overview.value.stages[0]?.stageId ?? null
    }
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load product'
  } finally {
    loading.value = false
  }
}

watch(() => props.name, load, { immediate: true })

const headers = [
  { title: 'Workload', key: 'workloadName' },
  { title: 'Namespace', key: 'namespace' },
  { title: 'Last release', key: 'lastRelease', sortable: false },
  { title: 'Status', key: 'status', sortable: false },
  { title: 'Live status', key: 'liveStatus', sortable: false },
]
</script>

<template>
  <v-alert v-if="error" type="error" :text="error" class="mb-4" />
  <v-progress-linear v-if="loading" indeterminate class="mb-4" />

  <template v-if="overview">
    <h1 class="text-h5 mb-4">{{ overview.productName }}</h1>

    <div v-if="overview.stages.length === 0" class="text-body-2 text-medium-emphasis">
      This product has no workloads linked to any stage yet.
    </div>

    <template v-else>
      <v-tabs v-model="activeStageId">
        <v-tab v-for="stage in overview.stages" :key="stage.stageId" :value="stage.stageId">
          {{ stage.stageName }}
          <span class="text-caption text-medium-emphasis ml-1">({{ stage.pipeline }})</span>
        </v-tab>
      </v-tabs>

      <v-window v-model="activeStageId">
        <v-window-item v-for="stage in overview.stages" :key="stage.stageId" :value="stage.stageId">
          <div class="text-caption text-medium-emphasis my-2">
            Pipeline: {{ stage.pipeline }} — Cluster: {{ stage.kubernetesContext ?? '(none configured)' }}
          </div>

          <v-data-table :headers="headers" :items="stage.workloads" item-value="workloadId" density="comfortable">
            <template #item.namespace="{ item }">{{ item.namespace ?? '—' }}</template>
            <template #item.lastRelease="{ item }">
              <div v-if="item.latestRelease">
                <div>{{ item.latestRelease.image }}</div>
                <div class="text-caption text-medium-emphasis">
                  {{ item.latestRelease.action }} — {{ formatDeploymentStatus(item.latestRelease) }}
                </div>
              </div>
              <span v-else>—</span>
            </template>
            <template #item.status="{ item }">
              <template v-if="item.latestRelease">
                <v-chip
                  v-if="gitOpsStatusDisplay(item.latestRelease.gitOpsStatus)"
                  :color="gitOpsStatusDisplay(item.latestRelease.gitOpsStatus)!.color"
                  size="small"
                  variant="flat"
                  class="mr-1"
                >
                  {{ gitOpsStatusDisplay(item.latestRelease.gitOpsStatus)!.label }}
                </v-chip>
                <v-chip
                  v-if="kubernetesStatusDisplay(item.latestRelease.kubernetesStatus)"
                  :color="kubernetesStatusDisplay(item.latestRelease.kubernetesStatus)!.color"
                  size="small"
                  variant="flat"
                >
                  {{ kubernetesStatusDisplay(item.latestRelease.kubernetesStatus)!.label }}
                </v-chip>
              </template>
              <span v-else>—</span>
            </template>
            <template #item.liveStatus="{ item }">
              <LiveStatusCell
                v-if="item.kubernetes"
                :workload-id="item.workloadId"
                :stage-id="stage.stageId"
                :active="activeStageId === stage.stageId"
              />
              <span v-else>—</span>
            </template>
          </v-data-table>
        </v-window-item>
      </v-window>
    </template>
  </template>
</template>
