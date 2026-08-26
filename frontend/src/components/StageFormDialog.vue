<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { stagesApi } from '../api/stages'
import type { DeploymentPolicy, StageResponse } from '../api/types'

const props = defineProps<{
  modelValue: boolean
  stage: StageResponse | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const policyOptions: { title: string; value: DeploymentPolicy }[] = [
  { title: 'Immediate', value: 'IMMEDIATE' },
  { title: 'At configured time', value: 'SCHEDULED' },
]

const name = ref('')
const description = ref('')
const order = ref(0)
const deploymentPolicy = ref<DeploymentPolicy>('IMMEDIATE')
const kubernetesContext = ref('')
const namespacePrefix = ref('')
const saving = ref(false)
const error = ref<string | null>(null)

watch(
  () => [props.modelValue, props.stage] as const,
  ([open, stage]) => {
    if (open) {
      name.value = stage?.name ?? ''
      description.value = stage?.description ?? ''
      order.value = stage?.order ?? 0
      deploymentPolicy.value = stage?.deploymentPolicy ?? 'IMMEDIATE'
      kubernetesContext.value = stage?.kubernetesContext ?? ''
      namespacePrefix.value = stage?.namespacePrefix ?? ''
      error.value = null
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
    const request = {
      name: name.value,
      description: description.value || null,
      order: order.value,
      deploymentPolicy: deploymentPolicy.value,
      kubernetesContext: kubernetesContext.value.trim() || null,
      namespacePrefix: namespacePrefix.value.trim() || null,
    }
    if (props.stage) {
      await stagesApi.update(props.stage.id, request)
    } else {
      await stagesApi.create(request)
    }
    emit('saved')
    close()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Failed to save stage'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="480" @update:model-value="emit('update:modelValue', $event)">
    <v-card :title="stage ? 'Edit stage' : 'Add stage'">
      <v-card-text>
        <v-alert v-if="error" type="error" :text="error" class="mb-4" />
        <v-text-field v-model="name" label="Name" required autofocus />
        <v-text-field v-model.number="order" label="Order" type="number" required />
        <v-textarea v-model="description" label="Description" />
        <v-select v-model="deploymentPolicy" :items="policyOptions" label="Deployment Policy" required />
        <v-text-field
          v-model="kubernetesContext"
          label="Kubernetes Context"
          placeholder="e.g. prod-cluster"
          hint="Kubeconfig context to deploy into — required for stages receiving kubernetes-managed workloads"
          persistent-hint
        />
        <v-text-field
          v-model="namespacePrefix"
          label="Namespace Prefix"
          placeholder="e.g. dev-"
          hint="Prepended to a workload's namespace — only needed if this stage shares a cluster with other stages"
          persistent-hint
          class="mt-4"
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
