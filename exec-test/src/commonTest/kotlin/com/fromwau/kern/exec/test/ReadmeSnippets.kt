package com.fromwau.kern.exec.test

import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.Starter
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals

fun gitBranch(starter: Starter): String? {
    val child = (starter.start(Command(listOf("git", "branch", "--show-current"))) as? Result.Success)?.value
        ?: return null
    val text = StringBuilder()
    while (true) {
        when (val event = child.next() ?: return text.toString().trim()) {
            is ChildEvent.Output -> text.append(event.text)
            else -> Unit
        }
    }
}

class ReadmeSnippets {
    @Test
    fun `the branch is what git wrote`() {
        val starter = FakeStarter {
            FakeRun.Starts(
                listOf(
                    ChildEvent.Output("main\n", ChildStream.Stdout),
                    ChildEvent.Exited(0),
                    ChildEvent.OutputClosed,
                ),
            )
        }

        assertEquals("main", gitBranch(starter))
        assertEquals(listOf("git", "branch", "--show-current"), starter.started.single().argv)
    }
}
