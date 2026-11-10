/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.integ

/** Outcome of one device test, from the status code of its `INSTRUMENTATION_STATUS` record. */
enum class TestStatus { OK, FAILURE, ERROR, IGNORED, ASSUMPTION_FAILURE }

data class DeviceTestResult(val className: String, val test: String, val status: TestStatus, val stack: String?)

private const val STATUS = "INSTRUMENTATION_STATUS: "
private const val STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
private const val CODE_START = 1

/**
 * Parses the raw output of `am instrument -w -r`: `INSTRUMENTATION_STATUS: key=value` lines, where a value continues
 * over the following lines until the next `INSTRUMENTATION_` line, closed by `INSTRUMENTATION_STATUS_CODE: n`.
 * Returns one result per finished test; the "test started" record (code 1) adds none.
 */
fun parseInstrumentation(raw: String): List<DeviceTestResult> {
    val results = mutableListOf<DeviceTestResult>()
    var fields = mutableMapOf<String, String>()
    var lastKey: String? = null

    for (line in raw.lines()) {
        when {
            line.startsWith(STATUS_CODE) -> {
                val code = line.removePrefix(STATUS_CODE).trim().toIntOrNull()
                if (code != CODE_START) results += result(fields, code)
                fields = mutableMapOf()
                lastKey = null
            }
            line.startsWith(STATUS) -> {
                val field = line.removePrefix(STATUS)
                val key = field.substringBefore('=')
                fields[key] = field.substringAfter('=', "")
                lastKey = key
            }
            line.startsWith("INSTRUMENTATION_") -> lastKey = null
            lastKey != null -> fields[lastKey] = fields.getValue(lastKey) + "\n" + line
        }
    }
    return results
}

private fun result(fields: Map<String, String>, code: Int?): DeviceTestResult {
    val status = when (code) {
        0 -> TestStatus.OK
        -1 -> TestStatus.ERROR
        -2 -> TestStatus.FAILURE
        -3 -> TestStatus.IGNORED
        -4 -> TestStatus.ASSUMPTION_FAILURE
        else -> TestStatus.ERROR // never let a record we do not understand look like a pass
    }
    // an instrumentation-level failure (say, no such runner) has no stack, only an `Error=` field saying what went wrong
    val stack = (fields["stack"] ?: fields["Error"])?.trimEnd()?.ifEmpty { null }
        ?: if (code in 0 downTo -4) null else "unknown instrumentation status code: $code"
    return DeviceTestResult(fields["class"].orEmpty(), fields["test"].orEmpty(), status, stack)
}

/**
 * Fails unless the device ran at least one test and all of them passed. A run that executed nothing (wrong class or
 * method, crashed process) must never look like a pass.
 */
fun List<DeviceTestResult>.requirePassed(className: String, method: String) {
    val name = "$className#$method"
    if (isEmpty()) {
        throw AssertionError(
            "no test results for $name: the device run executed no tests " +
                "(wrong class or method name, or the instrumentation process crashed)"
        )
    }
    for (result in this) {
        val detail = result.stack?.let { "\n$it" }.orEmpty()
        when (result.status) {
            TestStatus.OK -> {}
            TestStatus.ASSUMPTION_FAILURE -> throw AssertionError(
                "$name was skipped on the device (assumption failure): " +
                    "the instrumentation arguments the test needs were not passed$detail"
            )
            else -> {
                // a failure of the instrumentation itself has no test to name
                val label = if (result.className.isEmpty() && result.test.isEmpty()) "instrumentation" else "${result.className}#${result.test}"
                throw AssertionError("$label ${result.status} on the device$detail")
            }
        }
    }
}
