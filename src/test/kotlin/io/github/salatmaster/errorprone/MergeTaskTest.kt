package io.github.salatmaster.errorprone

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MergeTaskTest {

    private val old = mapOf("A.java" to "a1", "B.java" to "b1", "C.java" to "c1")

    @Test
    fun `a full compile replaces everything the task said before`() {
        val merged = mergeTask(old, mapOf("B.java" to "b2"), CompileOutcome.FULL) { false }

        assertThat(merged).isEqualTo(mapOf("B.java" to "b2"))
    }

    @Test
    fun `a task that compiled nothing keeps what it had`() {
        val merged = mergeTask(old, emptyMap(), CompileOutcome.NONE) { true }

        assertThat(merged).isEqualTo(old)
    }

    @Test
    fun `a partial compile replaces reported files and drops only stale unreported ones`() {
        // C changed since its diagnostics were recorded, so it was recompiled and is now clean;
        // A did not change and was not recompiled, so what it had still holds.
        val merged = mergeTask(old, mapOf("B.java" to "b2"), CompileOutcome.PARTIAL) { it == "c1" }

        assertThat(merged).isEqualTo(mapOf("A.java" to "a1", "B.java" to "b2"))
    }

    @Test
    fun `a failed compile adds what it reported and keeps the rest, changed or not`() {
        // Error Prone says nothing once javac finds an error, so C's silence proves nothing.
        val merged = mergeTask(old, mapOf("B.java" to "b2"), CompileOutcome.FAILED) { it == "c1" }

        assertThat(merged).isEqualTo(mapOf("A.java" to "a1", "B.java" to "b2", "C.java" to "c1"))
    }
}
