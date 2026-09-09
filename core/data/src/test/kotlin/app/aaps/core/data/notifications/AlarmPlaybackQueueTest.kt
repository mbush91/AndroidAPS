package app.aaps.core.data.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AlarmPlaybackQueueTest {
    @Test fun `full screen preempts internal and stopping it resumes newest pending request`() {
        val queue = AlarmPlaybackQueue<Int>("fullscreen")
        queue.put("internal", 1)
        queue.put("fullscreen", 2)
        queue.put("internal", 3)
        assertThat(queue.selected()).isEqualTo("fullscreen" to 2)
        queue.remove("fullscreen")
        assertThat(queue.selected()).isEqualTo("internal" to 3)
    }

    @Test fun `dismissing glucose while fullscreen is active does not stop fullscreen or resurrect glucose`() {
        val queue = AlarmPlaybackQueue<Int>("fullscreen")
        queue.put("internal", 1)
        queue.put("fullscreen", 2)
        queue.remove("internal")
        assertThat(queue.selected()).isEqualTo("fullscreen" to 2)
        queue.remove("fullscreen")
        assertThat(queue.selected()).isNull()
    }

    @Test fun `global mute removes both requests regardless of stop order`() {
        for (order in listOf(listOf("internal", "fullscreen"), listOf("fullscreen", "internal"))) {
            val queue = AlarmPlaybackQueue<Int>("fullscreen")
            queue.put("internal", 1)
            queue.put("fullscreen", 2)
            order.forEach(queue::remove)
            assertThat(queue.selected()).isNull()
        }
    }
}
