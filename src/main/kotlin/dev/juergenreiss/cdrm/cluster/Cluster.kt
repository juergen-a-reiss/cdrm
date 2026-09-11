// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.cluster

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URL
import java.time.Instant
import java.util.*

@Entity
@Table(name = "cluster")
@EntityListeners(AuditingEntityListener::class)
class Cluster(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column
    var description: String? = null,

    @Column(name = "cluster_type", nullable = false)
    @Enumerated(EnumType.STRING)
    var clusterType: ClusterType,

    @Column(nullable = false, unique = true)
    var url: URL,

    @Column(name = "k8s_namespaces")
    var k8sNamespaces: String? = null,

    @Convert(converter = K8sGitopsConfigConverter::class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "k8s_gitops_config", columnDefinition = "jsonb")
    var k8sGitOpsConfig: K8sGitopsConfig? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @LastModifiedDate
    @Column(nullable = false)
    var modifiedAt: Instant? = null,

    @Column(nullable = false, updatable = false)
    var createdBy: UUID,

    @Column(nullable = false)
    var modifiedBy: UUID,
)

open class K8sGitopsConfig(
    var useGitOps: Boolean = true,
    // The default repo to commit to — must match (by exact URL) one of the repos
    // configured via cdrm.gitops.repositories (see GitOpsRepositoryConfig), which is
    // where credentials for it actually live; this column only ever stores the URL
    // itself. A namespace can override this via its own gitRepo (see
    // K8sNamespaceGitopsConfig) when null there, this one applies.
    var gitRepo: String,
    // The default branch to commit to. A namespace can override this via its own
    // gitBranch (see K8sNamespaceGitopsConfig) when null there, this one applies.
    var gitBranch: String = "main",
    var namespaces: MutableMap<String, K8sNamespaceGitopsConfig> = mutableMapOf(),
)

// SIMPLE: fileExpression/yamlExpression, substituted with {namespace}/{workload} and
// set to the new release's image — exactly one file, one YAML key, one value per
// deploy. TEMPLATE: templateScript computes an arbitrary list of (branch, file, YAML
// key, value) edits instead — see GitOpsTemplateEngine for what it can reference and
// must return.
enum class GitOpsNamespaceMode { SIMPLE, TEMPLATE }

open class K8sNamespaceGitopsConfig(
    var namespace: String,
    var useGitOps: Boolean = true,
    var mode: GitOpsNamespaceMode = GitOpsNamespaceMode.SIMPLE,
    var fileExpression: String?,
    var yamlExpression: String?,
    // Null means "use the cluster-wide K8sGitopsConfig.gitBranch" — only set this to
    // commit this namespace's changes to a different branch. Only consulted for
    // mode=SIMPLE — a TEMPLATE script returns its own branch per edit instead.
    var gitBranch: String? = null,
    // Null means "use the cluster-wide K8sGitopsConfig.gitRepo" — only set this to
    // commit this namespace's changes to a different repo entirely (see
    // GitOpsResolver). Same "must match a configured repository" rule as the
    // cluster-wide one above. Applies to both modes — the template still commits to
    // this one resolved repo, it just can't also switch repos per edit.
    var gitRepo: String? = null,
    // GraalVM JS source, wrapped and invoked as a function body (so a plain top-level
    // `return [...]` works) — only used, and only required, when mode=TEMPLATE.
    var templateScript: String? = null,
)

// Serializes K8sGitopsConfig to/from the jsonb k8s_gitops_config column. Does the
// object<->JSON conversion itself (via the project's own Jackson 3 ObjectMapper, with the
// Kotlin module registered so the constructor-based classes above round-trip correctly)
// rather than relying on Hibernate's auto-detected format mapper, which would otherwise
// fall back to a plain, Kotlin-unaware Jackson 2 ObjectMapper pulled in transitively by
// Hibernate itself — @JdbcTypeCode(SqlTypes.JSON) on the field then only has to bind the
// resulting JSON string to the jsonb column, not serialize it.
@Converter
class K8sGitopsConfigConverter : AttributeConverter<K8sGitopsConfig?, String?> {
    private val objectMapper = jacksonObjectMapper()

    override fun convertToDatabaseColumn(attribute: K8sGitopsConfig?): String? =
        attribute?.let { objectMapper.writeValueAsString(it) }

    override fun convertToEntityAttribute(dbData: String?): K8sGitopsConfig? =
        dbData?.let { objectMapper.readValue(it, K8sGitopsConfig::class.java) }
}

