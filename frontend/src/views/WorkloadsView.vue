<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import ResourceTable from '../components/ResourceTable.vue'
import type { SortByItem } from '../components/ResourceTable.vue'
import WorkloadFormDialog from '../components/WorkloadFormDialog.vue'
import ProductFilterBar from '../components/ProductFilterBar.vue'
import PipelineFilterBar from '../components/PipelineFilterBar.vue'
import { useResourceList } from '../composables/useResourceList'
import { useProductFilter } from '../composables/useProductFilter'
import { usePipelineFilter } from '../composables/usePipelineFilter'
import { usePersistedRef } from '../composables/usePersistedRef'
import { workloadsApi } from '../api/workloads'
import { productsApi } from '../api/products'
import { ApiError } from '../api/http'
import type { WorkloadResponse } from '../api/types'
import { canManageWorkloads } from '../auth/roles'
import { sortParam } from '../utils/sortParam'

const KIND_LABELS: Record<string, string> = {
  DEPLOYMENT: 'Workload',
  STATEFUL_SET: 'StatefulSet',
}

interface WorkloadRow {
  id: string
  name: string
  description: string | null
  pipeline: string
  productName: string
  target: string
  stageCount: number
  raw: WorkloadResponse
}

const sortBy = usePersistedRef<SortByItem[]>('cdrm.sort.workloads', [{ key: 'name', order: 'asc' }])
const { items, loading, error, reload } = useResourceList(() => workloadsApi.list(sortParam(sortBy.value)))
watch(sortBy, reload, { deep: true })
const { items: products } = useResourceList(productsApi.list)
const { matches } = useProductFilter()
const { matches: matchesPipeline } = usePipelineFilter()

const productNameById = computed(() => new Map(products.value.map((product) => [product.id, product.name])))

const rows = computed<WorkloadRow[]>(() =>
  items.value
    .filter((workload) => matches(workload.productId) && matchesPipeline(workload.pipeline))
    .map((workload) => ({
      id: workload.id,
      name: workload.name,
      description: workload.description,
      pipeline: workload.pipeline,
      productName: productNameById.value.get(workload.productId) ?? workload.productId,
      target: workload.kubernetes
        ? `Kubernetes · ${workload.kubernetesKind ? KIND_LABELS[workload.kubernetesKind] : 'Unknown'}${workload.kubernetesNameSpace ? ` (${workload.kubernetesNameSpace})` : ''}`
        : 'Computer',
      stageCount: workload.stages.length,
      raw: workload,
    })),
)

const headers = computed<DataTableHeader<WorkloadRow>[]>(() => {
  const base: DataTableHeader<WorkloadRow>[] = [
    { title: 'Name', key: 'name' },
    { title: 'Pipeline', key: 'pipeline' },
    { title: 'Product', key: 'productName' },
    { title: 'Target', key: 'target', sortable: false },
    { title: 'Stages', key: 'stageCount', width: 100, sortable: false },
    { title: 'Description', key: 'description' },
  ]
  if (canManageWorkloads.value) {
    base.push({ title: 'Actions', key: 'actions', sortable: false, width: 120 })
  }
  return base
})

const dialogOpen = ref(false)
const editingWorkload = ref<WorkloadResponse | null>(null)
const deleteError = ref<string | null>(null)

function openCreate() {
  editingWorkload.value = null
  dialogOpen.value = true
}

function openEdit(workload: WorkloadResponse) {
  editingWorkload.value = workload
  dialogOpen.value = true
}

async function removeWorkload(workload: WorkloadResponse) {
  if (!confirm(`Delete workload "${workload.name}"?`)) {
    return
  }
  deleteError.value = null
  try {
    await workloadsApi.remove(workload.id)
    await reload()
  } catch (e) {
    deleteError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to delete workload'
  }
}
</script>

<template>
  <v-alert v-if="deleteError" type="error" :text="deleteError" class="mb-4" />
  <div class="d-flex flex-wrap ga-2 align-center mb-4">
    <PipelineFilterBar />
    <ProductFilterBar />
  </div>
  <ResourceTable :headers="headers" :items="rows" :loading="loading" :error="error" v-model:sort-by="sortBy">
    <template v-if="canManageWorkloads" #top>
      <v-toolbar flat>
        <v-toolbar-title>Workloads</v-toolbar-title>
        <v-spacer />
        <v-btn color="primary" prepend-icon="mdi-plus" @click="openCreate">Add workload</v-btn>
      </v-toolbar>
    </template>
    <template v-if="canManageWorkloads" #item.actions="{ item }">
      <v-icon icon="mdi-pencil" size="small" class="mr-2" @click="openEdit(item.raw)" />
      <v-icon icon="mdi-delete" size="small" @click="removeWorkload(item.raw)" />
    </template>
  </ResourceTable>

  <WorkloadFormDialog v-model="dialogOpen" :workload="editingWorkload" @saved="reload" />
</template>
