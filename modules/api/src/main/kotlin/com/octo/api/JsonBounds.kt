package com.octo.api

import com.fasterxml.jackson.databind.ObjectMapper

/** Bound on any single caller-supplied JSON-object field (#342) — request metadata, not a blob store. */
const val MAX_JSON_OBJECT_BYTES = 32_768

/**
 * True when [value] is absent, or a JSON object whose serialized form fits [MAX_JSON_OBJECT_BYTES]. A bare
 * scalar or array is never a legitimate value for these fields, and an unbounded one is a storage-abuse lever.
 */
fun ObjectMapper.isBoundedObject(value: Any?): Boolean =
    value == null || (value is Map<*, *> && writeValueAsBytes(value).size <= MAX_JSON_OBJECT_BYTES)
