package app.aaps.core.data.notifications

/** Owner-scoped pending audio requests. Callers confine access to the playback thread. */
class AlarmPlaybackQueue<T>(private val priorityOwner: String) {
    private val requests = linkedMapOf<String, T>()
    fun put(owner: String, request: T) { requests[owner] = request }
    fun remove(owner: String) { requests.remove(owner) }
    fun selected(): Pair<String, T>? {
        val owner = if (requests.containsKey(priorityOwner)) priorityOwner else requests.keys.firstOrNull() ?: return null
        return owner to requests.getValue(owner)
    }
}
