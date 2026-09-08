// Required Notice: Copyright Dr. Juergen A. Reiss
// Licensed under the terms in the LICENSE file at the repository root.

package dev.juergenreiss.cdrm.testsupport

import org.mockito.Mockito

// Verifying a call to a Kotlin-declared collaborator (e.g. AuditRecorder) whose
// parameters include non-null types runs into a real Kotlin/Mockito interop pitfall:
// Mockito's matchers (eq(), any(), isNull(), ArgumentCaptor.capture()) all return null
// at runtime as an implementation detail (Mockito matches via the registered matcher
// object, not this return value — harmless for Java callers), but Kotlin inserts a
// runtime null-check on any argument passed into a non-null-declared parameter and
// throws ("eq(...) must not be null", "capture(...) must not be null", etc.) — even
// though the verification itself would have passed. This sidesteps the whole class of
// problem: it inspects an already-recorded invocation's raw arguments directly, with no
// Mockito matcher (and so no such check) involved at all. Assumes the named method was
// called on this mock exactly once.
fun singleInvocationArgs(mock: Any, methodName: String): List<Any?> =
    Mockito.mockingDetails(mock).invocations.single { it.method.name == methodName }.arguments.toList()
