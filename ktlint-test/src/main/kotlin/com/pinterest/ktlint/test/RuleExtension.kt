package com.pinterest.ktlint.test

import com.pinterest.ktlint.core.KtLint
import com.pinterest.ktlint.core.LintError
import com.pinterest.ktlint.core.Rule
import com.pinterest.ktlint.core.RuleSet
import java.util.ArrayList
import org.assertj.core.api.Assertions
import org.assertj.core.util.diff.DiffUtils.diff
import org.assertj.core.util.diff.DiffUtils.generateUnifiedDiff

public fun Rule.lint(
    text: String,
    userData: Map<String, String> = emptyMap(),
    script: Boolean = false
): List<LintError> = lint(null, text, userData, script)

public fun Rule.lint(
    lintedFilePath: String?,
    text: String,
    userData: Map<String, String> = emptyMap(),
    script: Boolean = false
): List<LintError> {
    val res = ArrayList<LintError>()
    val debug = debugAST()
    KtLint.lint(
        KtLint.Params(
            fileName = lintedFilePath,
            text = text,
            ruleSets = (if (debug) listOf(RuleSet("debug", DumpAST())) else emptyList()) +
                listOf(RuleSet("standard", this@lint)),
            userData = userData,
            script = script,
            cb = { e, _ ->
                if (debug) {
                    System.err.println("^^ lint error")
                }
                res.add(e)
            }
        )
    )
    return res
}

public fun Rule.format(
    text: String,
    userData: Map<String, String> = emptyMap(),
    cb: (e: LintError, corrected: Boolean) -> Unit = { _, _ -> },
    script: Boolean = false
): String = format(null, text, userData, cb, script)

public fun Rule.format(
    lintedFilePath: String?,
    text: String,
    userData: Map<String, String> = emptyMap(),
    cb: (e: LintError, corrected: Boolean) -> Unit = { _, _ -> },
    script: Boolean = false
): String = KtLint.format(
    KtLint.Params(
        fileName = lintedFilePath,
        text = text,
        ruleSets = (if (debugAST()) listOf(RuleSet("debug", DumpAST())) else emptyList()) +
            listOf(RuleSet("standard", this@format)),
        userData = userData,
        script = script,
        cb = cb
    )
)

public fun Rule.diffFileLint(
    path: String,
    userData: Map<String, String> = emptyMap()
): String {
    val resourceText = getResourceAsText(path).replace("\r\n", "\n")
    val dividerIndex = resourceText.lastIndexOf("\n// expect\n")
    if (dividerIndex == -1) {
        throw RuntimeException("$path must contain '// expect' line")
    }
    val input = resourceText.substring(0, dividerIndex)
    val expected = resourceText.substring(dividerIndex + 1).split('\n').mapNotNull { line ->
        if (line.isBlank() || line == "// expect") {
            null
        } else {
            line.trimMargin("// ").split(':', limit = 3).let { expectation ->
                if (expectation.size != 3) {
                    throw RuntimeException("$path expectation must be a triple <line>:<column>:<message>")
                    // " (<message> is not allowed to contain \":\")")
                }
                val message = expectation[2]
                val detail = message.removeSuffix(" (cannot be auto-corrected)")
                LintError(expectation[0].toInt(), expectation[1].toInt(), id, detail, message == detail)
            }
        }
    }
    val actual = lint(input, userData, script = true)
    val str = { err: LintError ->
        val ruleId = if (err.ruleId != id) " (${err.ruleId})" else ""
        val correctionStatus = if (!err.canBeAutoCorrected) " (cannot be auto-corrected)" else ""
        "${err.line}:${err.col}:${err.detail}$ruleId$correctionStatus"
    }
    val diff =
        generateUnifiedDiff(
            "expected",
            "actual",
            expected.map(str),
            diff(expected.map(str), actual.map(str)),
            expected.size + actual.size
        ).joinToString("\n")
    return diff.ifEmpty { "" }
}

public fun Rule.diffFileFormat(
    srcPath: String,
    expectedPath: String,
    userData: Map<String, String> = emptyMap()
): String {
    val actual = format(getResourceAsText(srcPath), userData, script = true).split('\n')
    val expected = getResourceAsText(expectedPath).split('\n')
    val diff =
        generateUnifiedDiff(expectedPath, "output", expected, diff(expected, actual), expected.size + actual.size)
            .joinToString("\n")
    return diff.ifEmpty { "" }
}

/**
 * Alternative to [diffFileFormat]. Depending on your personal favor it might be more insightful whenever a test is
 * failing. Currently it is offered as utility so it can be used during development.
 *
 * To be used as:
 *
 *     @Test
 *     fun testFormatRawStringTrimIndent() {
 *         IndentationRule().assertThatFileFormat(
 *             "spec/indent/format-raw-string-trim-indent.kt.spec",
 *             "spec/indent/format-raw-string-trim-indent-expected.kt.spec"
 *         )
 *     }
 */
public fun Rule.assertThatFileFormat(
    srcPath: String,
    expectedPath: String,
    userData: Map<String, String> = emptyMap()
) {
    val actual = format(getResourceAsText(srcPath), userData, script = true).split('\n')
    val expected = getResourceAsText(expectedPath).split('\n')
    Assertions.assertThat(actual).isEqualTo(expected)
}

private fun getResourceAsText(path: String) =
    (ClassLoader.getSystemClassLoader().getResourceAsStream(path) ?: throw RuntimeException("$path not found"))
        .bufferedReader()
        .readText()
