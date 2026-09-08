// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

// One row per Keycloak subject that has ever acted while user.id.storage's display
// format needed more than the bare UUID (see CurrentActorResolver, which is what writes
// these — never any other write path) — captured from the JWT's given_name/family_name/
// email claims. Absent entirely when the format is plain UUID (the default) or the
// storage mode is NONE: nothing here is ever written unless a devops user has explicitly
// asked for name/email display.
@Entity
@Table(name = "known_user")
class KnownUser(
    @Id
    @Column(name = "id")
    val id: UUID,

    @Column(name = "first_name")
    var firstName: String?,

    @Column(name = "last_name")
    var lastName: String?,

    @Column(name = "email")
    var email: String?,
)
