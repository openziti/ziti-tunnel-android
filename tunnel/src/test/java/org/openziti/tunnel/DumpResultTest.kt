/*
 * Copyright (c) 2026 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DumpResultTest {
    private fun parse(text: String) = DumpResult.from(Json.parseToJsonElement(text))

    // the data of a ZitiDump response since ziti-tunnel-sdk-c v1.22.1: the dumps of all identities by identifier
    @Test
    fun readsDumpOfIdentity() {
        val res = parse("""{"Files":[],"Dumps":{"a.json":"Config Source:\ta.json\nController: https://ctrl:1280"}}""")

        assertEquals("Config Source:\ta.json\nController: https://ctrl:1280", res.dumps["a.json"])
        assertEquals(emptyList<String>(), res.files)
    }

    // what the tunneler actually sends on Android: no Files key at all while nothing was written to disk
    @Test
    fun readsDumpWithoutFiles() {
        val res = parse("""{"Dumps":{"a.json":"first"}}""")

        assertEquals("first", res.dumps["a.json"])
        assertEquals(emptyList<String>(), res.files)
    }

    // an identity that is not loaded gets an empty map, not an error
    @Test
    fun readsEmptyDumps() {
        assertNull(parse("""{"Dumps":{}}""").dumps["a.json"])
    }

    @Test
    fun keepsIdentitiesApart() {
        val res = parse("""{"Files":[],"Dumps":{"a.json":"first","b.json":"second"}}""")

        assertEquals("first", res.dumps["a.json"])
        assertEquals("second", res.dumps["b.json"])
    }

    @Test
    fun unknownIdentityHasNoDump() {
        val res = parse("""{"Files":[],"Dumps":{"a.json":"first"}}""")

        assertNull(res.dumps["missing.json"])
    }

    // a later SDK may add fields, which must not make the feedback bundle lose the dump
    @Test
    fun ignoresUnknownFields() {
        val res = parse("""{"Files":["/x/a.ziti"],"Dumps":{"a.json":"first"},"Extra":1}""")

        assertEquals("first", res.dumps["a.json"])
        assertEquals(listOf("/x/a.ziti"), res.files)
    }

    // the format before v1.22.1 was the dumps by identifier at the top level of the data, and has no dumps in the new sense
    @Test
    fun oldFormatHasNoDumps() {
        val res = parse("""{"a.json":"first"}""")

        assertNull(res.dumps["a.json"])
    }
}
