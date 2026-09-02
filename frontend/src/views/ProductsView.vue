<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import ResourceTable from '../components/ResourceTable.vue'
import ProductFormDialog from '../components/ProductFormDialog.vue'
import ProductFilterBar from '../components/ProductFilterBar.vue'
import { useResourceList } from '../composables/useResourceList'
import { useProductFilter } from '../composables/useProductFilter'
import { productsApi } from '../api/products'
import { ApiError } from '../api/http'
import type { ProductResponse } from '../api/types'
import { canManageProducts } from '../auth/roles'
import { formatDateTime } from '../utils/formatDateTime'

interface DeploymentTimeRow {
  stageName: string
  cron: string
  nextDeploymentDisplay: string
}

interface ProductRow extends ProductResponse {
  deploymentTimes: DeploymentTimeRow[]
}

const { items, loading, error, reload } = useResourceList(productsApi.list)
const { matches } = useProductFilter()

const rows = computed<ProductRow[]>(() =>
  items.value
    .filter((product) => matches(product.id))
    .map((product) => ({
      ...product,
      deploymentTimes: product.stageDeploymentCrons.map((entry) => ({
        stageName: entry.stageName,
        cron: entry.deploymentCron,
        nextDeploymentDisplay: entry.nextDeploymentAt ? formatDateTime(entry.nextDeploymentAt) : 'Unknown',
      })),
    })),
)

const headers = computed<DataTableHeader<ProductRow>[]>(() => {
  const base: DataTableHeader<ProductRow>[] = [
    { title: 'Name', key: 'name' },
    { title: 'Deployment Times', key: 'deploymentTimes', sortable: false },
    { title: 'Description', key: 'description' },
  ]
  if (canManageProducts.value) {
    base.push({ title: 'Actions', key: 'actions', sortable: false, width: 120 })
  }
  return base
})

const dialogOpen = ref(false)
const editingProduct = ref<ProductResponse | null>(null)
const deleteError = ref<string | null>(null)

function openCreate() {
  editingProduct.value = null
  dialogOpen.value = true
}

function openEdit(product: ProductResponse) {
  editingProduct.value = product
  dialogOpen.value = true
}

async function removeProduct(product: ProductResponse) {
  if (!confirm(`Delete product "${product.name}"?`)) {
    return
  }
  deleteError.value = null
  try {
    await productsApi.remove(product.id)
    await reload()
  } catch (e) {
    deleteError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to delete product'
  }
}
</script>

<template>
  <v-alert v-if="deleteError" type="error" :text="deleteError" class="mb-4" />
  <div class="d-flex flex-wrap ga-2 align-center mb-4">
    <ProductFilterBar />
  </div>
  <ResourceTable :headers="headers" :items="rows" :loading="loading" :error="error">
    <template v-if="canManageProducts" #top>
      <v-toolbar flat>
        <v-toolbar-title>Products</v-toolbar-title>
        <v-spacer />
        <v-btn color="primary" prepend-icon="mdi-plus" @click="openCreate">Add product</v-btn>
      </v-toolbar>
    </template>
    <template #item.deploymentTimes="{ item }">
      <div v-if="item.deploymentTimes.length === 0">—</div>
      <div v-for="entry in item.deploymentTimes" :key="entry.stageName" class="text-caption">
        <strong>{{ entry.stageName }}</strong>: <code>{{ entry.cron }}</code> — next: {{ entry.nextDeploymentDisplay }}
      </div>
    </template>
    <template v-if="canManageProducts" #item.actions="{ item }">
      <v-icon icon="mdi-pencil" size="small" class="mr-2" @click="openEdit(item)" />
      <v-icon icon="mdi-delete" size="small" @click="removeProduct(item)" />
    </template>
  </ResourceTable>

  <ProductFormDialog v-model="dialogOpen" :product="editingProduct" @saved="reload" />
</template>
