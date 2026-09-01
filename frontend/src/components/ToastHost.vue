<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { useToast } from '../composables/useToast'

const { message, visible } = useToast()
</script>

<template>
  <v-snackbar v-model="visible" location="bottom right" :timeout="10000" color="warning" variant="elevated">
    {{ message }}
  </v-snackbar>
</template>
