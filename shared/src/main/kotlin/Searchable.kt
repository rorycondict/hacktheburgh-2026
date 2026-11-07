package com.example

/**
 * Represents an entity that can be filtered by a search query.
 */
interface Searchable {
    fun matches(query: String): Boolean
}
