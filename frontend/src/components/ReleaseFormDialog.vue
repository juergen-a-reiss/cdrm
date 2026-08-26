<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { workloadsApi } from '../api/workloads'
import { releasesApi } from '../api/releases'
import type { WorkloadResponse, ReleaseResponse } from '../api/types'

const props = defineProps<{
  modelValue: boolean
  release: ReleaseResponse | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const binaryUrl = ref('')
const description = ref('')
const workloadId = ref<string | null>(null)
const workloads = ref<WorkloadResponse[]>([])
const saving = ref(false)
const error = ref<string | null>(null)

watch(
  () => [props.modelValue, props.release] as const,
  async ([open, release]) => {
    if (!open) return
    error.value = null
    binaryUrl.value = release?.binaryUrl ?? ''
    description.value = release?.description ?? ''
    workloadId.value = release?.workloadId ?? null
    try {
      workloads.value = await workloadsApi.list()
    } catch (e) {
      error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to load form data'
    }
  },
  { immediate: true },
)

function close() {
  emit('update:modelValue', false)
}

async function save() {
  if (!workloadId.value) {
    error.value = 'Workload is required'
    return
  }
  saving.value = true
  error.value = null
  try {
    const request = { binaryUrl: binaryUrl.value, description: description.value || null, workloadId: workloadId.value }
    if (props.release) {
      await releasesApi.update(props.release.id, request)
    } else {
      await releasesApi.create(request)
    }
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to save release'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="480" @update:model-value="emit('update:modelValue', $event)">
    <v-card :title="release ? 'Edit release' : 'Add release'">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-text-field
          v-model="binaryUrl"
          label="Binary URL"
          placeholder="https://registry.example.com/app:1.0.0"
          required
          autofocus
          :disabled="!!release"
          :hint="release ? 'Cannot be changed after creation' : undefined"
          persistent-hint
        />
        <v-select
          v-model="workloadId"
          :items="workloads"
          item-title="name"
          item-value="id"
          label="Workload"
          required
        />
        <v-textarea v-model="description" label="Description" />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="close">Cancel</v-btn>
        <v-btn color="primary" :loading="saving" @click="save">Save</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
