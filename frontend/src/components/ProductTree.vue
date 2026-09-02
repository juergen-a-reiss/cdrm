<!--
  Required Notice: Copyright Dr. Juergen A. Reiss
  Licensed under the terms in the LICENSE file at the repository root.
-->

<script setup lang="ts">
import { computed } from 'vue'
import type { ProductResponse } from '../api/types'

const props = defineProps<{
  items: ProductResponse[]
  canManage: boolean
}>()

const emit = defineEmits<{
  edit: [product: ProductResponse]
  delete: [product: ProductResponse]
}>()

interface TreeNode {
  id: string
  title: string
  product: ProductResponse
  children?: TreeNode[]
}

// Always built from the full, unfiltered product list (see ProductsView) — the product
// filter only narrows the Table tab, so the tree never shows a product with its ancestor
// groups missing.
const treeItems = computed<TreeNode[]>(() => {
  const byParent = new Map<string | null, ProductResponse[]>()
  for (const product of props.items) {
    const key = product.productGroupId ?? null
    const bucket = byParent.get(key)
    if (bucket) bucket.push(product)
    else byParent.set(key, [product])
  }

  function buildChildren(parentId: string | null, ancestry: Set<string>): TreeNode[] {
    return (byParent.get(parentId) ?? [])
      .slice()
      .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
      .map((product) => ({
        id: product.id,
        title: product.name,
        product,
        // ancestry guards against a stray cycle in bad/legacy data hanging the browser —
        // the backend rejects cycles going forward, but this keeps the tree safe either way.
        children:
          product.isGroup && !ancestry.has(product.id)
            ? buildChildren(product.id, new Set(ancestry).add(product.id))
            : undefined,
      }))
  }

  return buildChildren(null, new Set())
})
</script>

<template>
  <v-treeview :items="treeItems" item-title="title" item-value="id" open-all>
    <template #prepend="{ item }">
      <v-icon :icon="item.product.isGroup ? 'mdi-folder-outline' : 'mdi-package-variant-closed'" />
    </template>
    <template v-if="canManage" #append="{ item }">
      <v-icon icon="mdi-pencil" size="small" class="mr-2" @click.stop="emit('edit', item.product)" />
      <v-icon icon="mdi-delete" size="small" @click.stop="emit('delete', item.product)" />
    </template>
  </v-treeview>
</template>
