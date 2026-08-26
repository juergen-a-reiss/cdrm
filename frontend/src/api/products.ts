// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

import { http } from './http'
import type { ProductRequest, ProductResponse } from './types'

export const productsApi = {
  list: () => http.get<ProductResponse[]>('/products'),
  get: (id: string) => http.get<ProductResponse>(`/products/${id}`),
  create: (request: ProductRequest) => http.post<ProductResponse>('/products', request),
  update: (id: string, request: ProductRequest) => http.put<ProductResponse>(`/products/${id}`, request),
  remove: (id: string) => http.delete(`/products/${id}`),
}
