// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.product

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/products")
class ProductController(private val service: ProductService) {
    @GetMapping
    fun findAll(): List<ProductResponse> = service.findAll()

    @GetMapping("/{id}")
    fun findById(@PathVariable id: UUID): ProductResponse = service.findById(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: ProductRequest): ProductResponse = service.create(request)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: ProductRequest): ProductResponse = service.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)
}