package com.deekshith.droidserve

import org.junit.Assert.*
import org.junit.Test

class ApiJsonThumbTest {

    @Test fun thumbUrlEmittedOnlyWhenPresent() {
        val rows = listOf(
            ApiJson.Row("pic.jpg", false, 10L, 0L, "image/jpeg", thumbUrl = "/thumb/pic.jpg?w=320"),
            ApiJson.Row("notes.txt", false, 5L, 0L, "text/plain")
        )
        val json = ApiJson.list("", rows, "")
        assertTrue(json.contains("\"thumbUrl\":\"/thumb/pic.jpg?w=320\""))
        val txtObj = json.substringAfter("notes.txt").substringBefore('}')
        assertFalse("text row carries no thumbUrl", txtObj.contains("thumbUrl"))
    }
}
