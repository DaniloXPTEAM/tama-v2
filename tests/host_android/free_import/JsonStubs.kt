// In-memory JSON stand-in; tests build values directly and do not test JSON parsing.
package org.json
class JSONObject {
    private val values = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun opt(key: String): Any? = values[key]
    fun has(key: String): Boolean = values.containsKey(key)
    fun keys(): Iterator<String> = values.keys.iterator()
    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray
    companion object { val NULL = Any() }
}
class JSONArray {
    private val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values.add(value); return this }
    fun length(): Int = values.size
    fun opt(index: Int): Any? = values.getOrNull(index)
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
}
