<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
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
const isGroup = ref(false)
const productGroupId = ref<string | null>(null)
// stageId -> cron text as typed; '' means "not configured for this stage".
const stageCrons = ref<Record<string, string>>({})
const scheduledStages = ref<StageResponse[]>([])
const allProducts = ref<ProductResponse[]>([])
const saving = ref(false)
const error = ref<string | null>(null)

const dialogTitle = computed(() => {
  if (props.product) return isGroup.value ? 'Edit product group' : 'Edit product'
  return isGroup.value ? 'Add product group' : 'Add product'
})

// A group can belong to another group (nesting is allowed) but never to itself.
const groupOptions = computed(() =>
  allProducts.value
    .filter((p) => p.isGroup && p.id !== props.product?.id)
    .map((p) => ({ title: p.name, value: p.id }))
    .sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' })),
)

watch(
  () => [props.modelValue, props.product] as const,
  async ([open, product]) => {
    if (!open) return
    error.value = null
    name.value = product?.name ?? ''
    description.value = product?.description ?? ''
    isGroup.value = product?.isGroup ?? false
    productGroupId.value = product?.productGroupId ?? null
    try {
      const [stages, products] = await Promise.all([stagesApi.list(), productsApi.list()])
      scheduledStages.value = stages.filter((stage) => stage.deploymentPolicy === 'SCHEDULED')
      allProducts.value = products
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
    const stageDeploymentCrons = isGroup.value
      ? []
      : scheduledStages.value
          .map((stage) => ({ stageId: stage.id, deploymentCron: (stageCrons.value[stage.id] ?? '').trim() }))
          .filter((entry) => entry.deploymentCron.length > 0)
    const request = {
      name: name.value,
      description: description.value || null,
      isGroup: isGroup.value,
      productGroupId: productGroupId.value,
      stageDeploymentCrons,
    }
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
    <v-card :title="dialogTitle">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-switch v-model="isGroup" label="This is a product group" color="primary" hide-details class="mb-2" />
        <v-text-field v-model="name" label="Name" required autofocus />
        <v-textarea v-model="description" label="Description" />
        <v-select
          v-model="productGroupId"
          :items="groupOptions"
          label="Parent group"
          clearable
          hide-details
          class="mb-4"
        />
        <template v-if="!isGroup && scheduledStages.length > 0">
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
