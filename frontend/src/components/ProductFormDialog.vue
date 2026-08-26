<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { productsApi } from '../api/products'
import { stagesApi } from '../api/stages'
import type { ProductResponse, StageResponse } from '../api/types'

const props = defineProps<{
  modelValue: boolean
  product: ProductResponse | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const name = ref('')
const description = ref('')
// stageId -> cron text as typed; '' means "not configured for this stage".
const stageCrons = ref<Record<string, string>>({})
const scheduledStages = ref<StageResponse[]>([])
const saving = ref(false)
const error = ref<string | null>(null)

watch(
  () => [props.modelValue, props.product] as const,
  async ([open, product]) => {
    if (!open) return
    error.value = null
    name.value = product?.name ?? ''
    description.value = product?.description ?? ''
    try {
      const stages = await stagesApi.list()
      scheduledStages.value = stages.filter((stage) => stage.deploymentPolicy === 'SCHEDULED')
      const crons: Record<string, string> = {}
      for (const entry of product?.stageDeploymentCrons ?? []) {
        crons[entry.stageId] = entry.deploymentCron
      }
      stageCrons.value = crons
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
  saving.value = true
  error.value = null
  try {
    const stageDeploymentCrons = scheduledStages.value
      .map((stage) => ({ stageId: stage.id, deploymentCron: (stageCrons.value[stage.id] ?? '').trim() }))
      .filter((entry) => entry.deploymentCron.length > 0)
    const request = { name: name.value, description: description.value || null, stageDeploymentCrons }
    if (props.product) {
      await productsApi.update(props.product.id, request)
    } else {
      await productsApi.create(request)
    }
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to save product'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="480" @update:model-value="emit('update:modelValue', $event)">
    <v-card :title="product ? 'Edit product' : 'Add product'">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-text-field v-model="name" label="Name" required autofocus />
        <v-textarea v-model="description" label="Description" />
        <template v-if="scheduledStages.length > 0">
          <p class="text-subtitle-2 mb-2">Deployment times</p>
          <p class="text-caption mb-2">
            Cron expression per stage — only stages using the "At configured time" policy. Leave blank to leave
            that stage unconfigured (releases can't be promoted into it until a time is set).
          </p>
          <v-text-field
            v-for="stage in scheduledStages"
            :key="stage.id"
            v-model="stageCrons[stage.id]"
            :label="`${stage.name} cron`"
            placeholder="0 0 2 * * *"
          />
        </template>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="close">Cancel</v-btn>
        <v-btn color="primary" :loading="saving" @click="save">Save</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
