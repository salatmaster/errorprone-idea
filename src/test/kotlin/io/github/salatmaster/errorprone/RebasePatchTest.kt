package io.github.salatmaster.errorprone

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RebasePatchTest {

    private val patchDir = Path.of("/tmp/fixes/_compileJava-1a2b")
    private val project = Path.of("/work/shop")

    // As Error Prone writes it: every path relative to the directory the patch is in.
    private val patch = """
        --- ../../../work/shop/src/main/java/Customer.java
        +++ ../../../work/shop/src/main/java/Customer.java
        @@ -46,5 +46,5 @@
           }

        -  public String toString() {
        +  @Override public String toString() {
             return name;
           }
        --- ../../../work/shop/src/main/java/Money.java
        +++ ../../../work/shop/src/main/java/Money.java
        @@ -1,3 +1,4 @@
         package shop;

        +import java.util.Locale;
         import java.math.BigDecimal;
    """.trimIndent()

    @Test
    fun `rewrites every path relative to the project`() {
        val rebased = rebasePatch(patch, patchDir, project)

        assertThat(rebased.lines().filter { it.startsWith("--- ") || it.startsWith("+++ ") }).containsExactly(
            "--- src/main/java/Customer.java",
            "+++ src/main/java/Customer.java",
            "--- src/main/java/Money.java",
            "+++ src/main/java/Money.java",
        )
        assertThat(rebased).contains("+  @Override public String toString() {", "+import java.util.Locale;")
    }

    @Test
    fun `keeps only the files asked for`() {
        val rebased = rebasePatch(patch, patchDir, project) { it.fileName.toString() == "Money.java" }

        assertThat(rebased).startsWith("--- src/main/java/Money.java\n+++ src/main/java/Money.java\n@@ -1,3 +1,4 @@")
        assertThat(rebased).doesNotContain("Customer", "@Override")
    }

    @Test
    fun `is empty when no file is kept`() {
        assertThat(rebasePatch(patch, patchDir, project) { false }).isEmpty()
    }
}
