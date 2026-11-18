// Minimal compile-time stand-in. Fingerprint JSON parsing is deliberately not tested here.
package org.json
class JSONObject {
    fun has(key: String): Boolean = false
    fun optString(key: String, fallback: String): String = fallback
}
class JSONArray(input: String) {
    fun length(): Int = 0
    fun optJSONObject(index: Int): JSONObject? = null
}
