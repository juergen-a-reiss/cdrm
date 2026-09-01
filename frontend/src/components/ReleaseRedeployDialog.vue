<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { releasesApi } from '../api/releases'
import type { ReleaseResponse } from '../api/types'

const props = defineProps<{
  modelValue: boolean
  release: ReleaseResponse | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const stageId = ref<string | null>(null)
const saving = ref(false)
const error = ref<string | null>(null)

watch(
  () => [props.modelValue, props.release] as const,
  ([open, release]) => {
    if (open) {
      // currentStage isn't necessarily a valid target (only included in
      // redeployableStages when this release is head there) — default to the
      // first eligible stage instead, so the initial selection is always one
      // Vuetify can actually resolve a title for.
      stageId.value = release?.redeployableStages[0]?.id ?? null
      error.value = null
    }
  },
  { immediate: true },
)

function close() {
  emit('update:modelValue', false)
}

async function save() {
  if (!props.release || !stageId.value) return
  saving.value = true
  error.value = null
  try {
    await releasesApi.redeploy(props.release.id, { stageId: stageId.value })
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to redeploy release'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="420" @update:model-value="emit('update:modelValue', $event)">
    <v-card title="Redeploy release">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <p v-if="release" class="text-body-2 mb-4">
          Redeploy <strong>{{ release.image }}</strong> to:
        </p>
        <v-select
          v-model="stageId"
          :items="release?.redeployableStages ?? []"
          item-title="name"
          item-value="id"
          label="Target stage"
          hint="The release's own stage (if it's head there) or any earlier stage"
          persistent-hint
          required
        />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="close">Cancel</v-btn>
        <v-btn color="primary" :loading="saving" :disabled="!stageId" @click="save">Redeploy</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
