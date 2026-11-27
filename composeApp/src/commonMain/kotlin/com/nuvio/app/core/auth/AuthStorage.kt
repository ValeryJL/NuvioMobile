package com.nuvio.app.core.auth

internal expect object AuthStorage {
    fun loadAnonymousUserId(): String?
    fun saveAnonymousUserId(userId: String)
    fun clearAnonymousUserId()
    fun loadFlag(key: String): Boolean
    fun saveFlag(key: String, value: Boolean)
    fun loadString(key: String): String?
    fun saveString(key: String, value: String)
    fun remove(key: String)
}
