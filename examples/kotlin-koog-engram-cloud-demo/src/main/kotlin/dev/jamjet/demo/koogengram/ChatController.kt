package dev.jamjet.demo.koogengram

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ChatController(private val agent: MemoryAgent) {

    @PostMapping("/chat")
    fun chat(@RequestParam session: String, @RequestBody message: String): ChatResponse =
        runBlocking {
            val reply = agent.chat(session, message)
            ChatResponse(session = session, reply = reply)
        }

    data class ChatResponse(val session: String, val reply: String)
}
