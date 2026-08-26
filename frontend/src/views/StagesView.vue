<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DataTableHeader } from 'vuetify/lib/components/VDataTable/types.js'
import ResourceTable from '../components/ResourceTable.vue'
import StageFormDialog from '../components/StageFormDialog.vue'
import { useResourceList } from '../composables/useResourceList'
import { stagesApi } from '../api/stages'
import { ApiError } from '../api/http'
import type { StageResponse } from '../api/types'
import { isAdmin } from '../auth/roles'

const { items, loading, error, reload } = useResourceList(stagesApi.list)

const POLICY_LABELS: Record<string, string> = {
  IMMEDIATE: 'Immediate',
  SCHEDULED: 'At configured time',
}

const headers = computed<DataTableHeader<StageResponse>[]>(() => {
  const base: DataTableHeader<StageResponse>[] = [
    { title: 'Order', key: 'order', width: 100 },
    { title: 'Name', key: 'name' },
    { title: 'Deployment Policy', key: 'deploymentPolicy', value: (item) => POLICY_LABELS[item.deploymentPolicy] },
    { title: 'Kubernetes Context', key: 'kubernetesContext', value: (item) => item.kubernetesContext ?? '—' },
    { title: 'Namespace Prefix', key: 'namespacePrefix', value: (item) => item.namespacePrefix ?? '—' },
    { title: 'Description', key: 'description' },
  ]
  if (isAdmin.value) {
    base.push({ title: 'Actions', key: 'actions', sortable: false, width: 120 })
  }
  return base
})

const dialogOpen = ref(false)
const editingStage = ref<StageResponse | null>(null)
const deleteError = ref<string | null>(null)

function openCreate() {
  editingStage.value = null
  dialogOpen.value = true
}

function openEdit(stage: StageResponse) {
  editingStage.value = stage
  dialogOpen.value = true
}

async function removeStage(stage: StageResponse) {
  if (!confirm(`Delete stage "${stage.name}"?`)) {
    return
  }
  deleteError.value = null
  try {
    await stagesApi.remove(stage.id)
    await reload()
  } catch (e) {
    deleteError.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to delete stage'
  }
}
</script>

<template>
  <v-alert v-if="deleteError" type="error" :text="deleteError" class="mb-4" />
  <ResourceTable :headers="headers" :items="items" :loading="loading" :error="error">
    <template v-if="isAdmin" #top>
      <v-toolbar flat>
        <v-toolbar-title>Stages</v-toolbar-title>
        <v-spacer />
        <v-btn color="primary" prepend-icon="mdi-plus" @click="openCreate">Add stage</v-btn>
      </v-toolbar>
    </template>
    <template v-if="isAdmin" #item.actions="{ item }">
      <v-icon icon="mdi-pencil" size="small" class="mr-2" @click="openEdit(item)" />
      <v-icon icon="mdi-delete" size="small" @click="removeStage(item)" />
    </template>
  </ResourceTable>

  <StageFormDialog v-model="dialogOpen" :stage="editingStage" @saved="reload" />
</template>
