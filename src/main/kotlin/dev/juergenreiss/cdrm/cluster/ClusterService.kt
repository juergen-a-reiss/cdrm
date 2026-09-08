// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.cluster

import dev.juergenreiss.cdrm.audit.AuditEntityType
import dev.juergenreiss.cdrm.audit.AuditRecorder
import dev.juergenreiss.cdrm.common.SortSpec
import dev.juergenreiss.cdrm.common.sortedBySpec
import dev.juergenreiss.cdrm.security.CurrentActorResolver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
@Transactional(readOnly = true)
class ClusterService(
    private val repository: ClusterRepository,
    private val currentActorResolver: CurrentActorResolver,
    private val auditRecorder: AuditRecorder,
) {

    private val log = LoggerFactory.getLogger(ClusterService::class.java)

    private val defaultSort = SortSpec("name", descending = false)
    private val sortComparators: Map<String, Comparator<ClusterResponse>> = mapOf(
        "name" to compareBy { it.name },
        "clusterType" to compareBy { it.clusterType },
        "url" to compareBy { it.url.toString() },
        "description" to compareBy(nullsFirst()) { it.description },
    )

    fun findAll(sort: String? = null): List<ClusterResponse> =
        repository.findAll().map { it.toResponse() }.sortedBySpec(SortSpec.parse(sort, defaultSort), sortComparators)

    fun findById(id: UUID): ClusterResponse =
        repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }.toResponse()

    @Transactional
    fun create(request: ClusterRequests): ClusterResponse {
        val userId = currentUserId()
        val saved = repository.save(
            Cluster(
                name = request.name,
                description = request.description,
                clusterType = request.clusterType,
                url = request.url,
                k8sNamespaces = request.k8sNamespaces,
                k8sGitOpsConfig = request.k8sGitOpsConfig,
                createdBy = userId,
                modifiedBy = userId,
            )
        )
        val response = saved.toResponse()
        auditRecorder.recordCreate(AuditEntityType.CLUSTER, saved.id!!.toString(), saved.name, null, response, userId)
        log.info("Created Cluster {} ('{}') by user {}", saved.id, saved.name, userId)
        return response
    }

    @Transactional
    fun update(id: UUID, request: ClusterRequests): ClusterResponse {
        val cluster = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val before = cluster.toResponse()
        cluster.name = request.name
        cluster.description = request.description
        cluster.clusterType = request.clusterType
        cluster.url = request.url
        cluster.k8sNamespaces = request.k8sNamespaces
        cluster.k8sGitOpsConfig = request.k8sGitOpsConfig
        cluster.modifiedBy = currentUserId()
        val saved = repository.save(cluster)
        val after = saved.toResponse()
        auditRecorder.recordUpdate(AuditEntityType.CLUSTER, saved.id!!.toString(), saved.name, null, before, after, saved.modifiedBy)
        log.info("Updated cluster {} ('{}') by user {}", saved.id, saved.name, saved.modifiedBy)
        return after
    }

    @Transactional
    fun delete(id: UUID) {
        val cluster = repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val before = cluster.toResponse()
        val userId = currentUserId()
        try {
            repository.deleteById(id)
            repository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cluster is still linked to one or more stages")
        }
        auditRecorder.recordDelete(AuditEntityType.CLUSTER, id.toString(), before.name, null, before, userId)
        log.info("Deleted cluster {} by user {}", id, userId)
    }

    private fun currentUserId(): UUID = currentActorResolver.resolve()

    private fun Cluster.toResponse() = ClusterResponse(
        id = id!!,
        name = name,
        description = description,
        clusterType = clusterType,
        url = url,
        k8sNamespaces = k8sNamespaces,
        k8sGitOpsConfig = k8sGitOpsConfig,
        createdAt = createdAt!!,
        modifiedAt = modifiedAt!!,
        createdBy = createdBy,
        modifiedBy = modifiedBy,
    )
}
