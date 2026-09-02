// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { ref } from 'vue'
import { useIdFilter } from './useIdFilter'
import { productsApi } from '../api/products'
import type { ProductResponse } from '../api/types'

// Module-level so every consumer (ProductFilterBar, Products/Workloads/Releases views, ...)
// shares one fetch instead of each triggering its own — mirrors the per-key registry in
// useIdFilter. Not built on useResourceList: that composable calls onMounted internally,
// which only works from inside a component's setup(), not at module scope here.
const products = ref<ProductResponse[]>([])
let loaded = false

async function ensureLoaded() {
  if (loaded) return
  loaded = true
  try {
    products.value = await productsApi.list()
  } catch {
    loaded = false // allow a later call to retry
  }
}

export function useProductFilter() {
  const { selectedIds, clear } = useIdFilter('cdrm.productFilter')
  ensureLoaded()

  // A selected product group stands in for all of its member products — selecting the
  // group is a shorthand for selecting everything under it, since a group itself is never
  // a real product on a workload/release.
  function matches(productId: string): boolean {
    if (selectedIds.value.length === 0) return true
    if (selectedIds.value.includes(productId)) return true
    const product = products.value.find((p) => p.id === productId)
    return product?.productGroupId != null && selectedIds.value.includes(product.productGroupId)
  }

  return { selectedProductIds: selectedIds, clear, matches, products }
}
