<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { workloadsApi } from '../api/workloads'
import { productsApi } from '../api/products'
import { stagesApi } from '../api/stages'
import { clustersApi } from '../api/clusters'
import type { WorkloadResponse, ProductResponse, KubernetesKind, StageResponse, ClusterResponse } from '../api/types'

const props = defineProps<{
  modelValue: boolean
  workload: WorkloadResponse | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const kindOptions: { title: string; value: KubernetesKind }[] = [
  { title: 'Workload', value: 'DEPLOYMENT' },
  { title: 'StatefulSet', value: 'STATEFUL_SET' },
]

const name = ref('')
const description = ref('')
const productId = ref<string | null>(null)
const kubernetes = ref(false)
const kubernetesKind = ref<KubernetesKind | null>(null)
const kubernetesNameSpace = ref('')
const stageIds = ref<string[]>([])
const products = ref<ProductResponse[]>([])
const stages = ref<StageResponse[]>([])
const clusters = ref<ClusterResponse[]>([])
const saving = ref(false)
const error = ref<string | null>(null)

// The base namespace a workload configures, before each stage's own
// namespacePrefix is prepended at deploy time — so candidates come from every
// stage's linked clusters' namespaces, with that stage's prefix stripped back off.
const namespaceOptions = computed(() => {
  const result = new Set<string>()
  for (const stage of stages.value) {
    const prefix = stage.namespacePrefix ?? ''
    for (const clusterInfo of stage.clusters) {
      const cluster = clusters.value.find((c) => c.id === clusterInfo.id)
      const namespaces = cluster?.k8sNamespaces?.split(',').map((ns) => ns.trim()).filter((ns) => ns.length > 0) ?? []
      for (const ns of namespaces) {
        if (!prefix) {
          result.add(ns)
        } else if (ns.startsWith(prefix) && ns.length > prefix.length) {
          result.add(ns.slice(prefix.length))
        }
      }
    }
  }
  return [...result].sort((a, b) => a.localeCompare(b))
})

watch(
  () => [props.modelValue, props.workload] as const,
  async ([open, workload]) => {
    if (!open) return
    error.value = null
    name.value = workload?.name ?? ''
    description.value = workload?.description ?? ''
    productId.value = workload?.productId ?? null
    kubernetes.value = workload?.kubernetes ?? false
    kubernetesKind.value = workload?.kubernetesKind ?? null
    kubernetesNameSpace.value = workload?.kubernetesNameSpace ?? ''
    stageIds.value = workload?.stages.map((stage) => stage.id) ?? []
    try {
      const [productList, stageList, clusterList] = await Promise.all([
        productsApi.list(),
        stagesApi.list(),
        clustersApi.list(),
      ])
      products.value = productList
      stages.value = stageList
      clusters.value = clusterList
    } catch (e) {
      error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load form data'
    }
  },
  { immediate: true },
)

watch(kubernetes, (isKubernetes) => {
  if (!isKubernetes) {
    kubernetesKind.value = null
    kubernetesNameSpace.value = ''
  }
})

function close() {
  emit('update:modelValue', false)
}

async function save() {
  if (!productId.value) {
    error.value = 'Product is required'
    return
  }
  if (kubernetes.value && !kubernetesKind.value) {
    error.value = 'Kind is required for Kubernetes workloads'
    return
  }
  if (kubernetes.value && !kubernetesNameSpace.value.trim()) {
    error.value = 'Namespace is required for Kubernetes workloads'
    return
  }
  saving.value = true
  error.value = null
  try {
    if (props.workload) {
      await workloadsApi.update(props.workload.id, {
        name: name.value,
        productId: productId.value,
        description: description.value || null,
        kubernetes: kubernetes.value,
        kubernetesKind: kubernetesKind.value,
        kubernetesNameSpace: kubernetes.value ? kubernetesNameSpace.value.trim() : null,
        stageIds: stageIds.value,
      })
    } else {
      await workloadsApi.create({
        name: name.value,
        productId: productId.value,
        description: description.value || null,
        kubernetes: kubernetes.value,
        kubernetesKind: kubernetesKind.value,
        kubernetesNameSpace: kubernetes.value ? kubernetesNameSpace.value.trim() : null,
      })
    }
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to save workload'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="560" @update:model-value="emit('update:modelValue', $event)">
    <v-card :title="workload ? 'Edit workload' : 'Add workload'">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-text-field v-model="name" label="Name" required autofocus />
        <v-select v-model="productId" :items="products" item-title="name" item-value="id" label="Product" required />
        <v-textarea v-model="description" label="Description" />
        <v-switch v-model="kubernetes" label="Kubernetes" color="primary" />
        <v-select
          v-if="kubernetes"
          v-model="kubernetesKind"
          :items="kindOptions"
          label="Kind"
          required
        />
        <v-select
          v-if="kubernetes"
          v-model="kubernetesNameSpace"
          :items="namespaceOptions"
          label="Namespace"
          required
        />
        <v-select
          v-if="workload"
          v-model="stageIds"
          :items="stages"
          item-title="name"
          item-value="id"
          label="Stages"
          multiple
          chips
          hint="A new workload always links to every stage. Change which stages this one applies to here."
          persistent-hint
        />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="close">Cancel</v-btn>
        <v-btn color="primary" :loading="saving" @click="save">Save</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
