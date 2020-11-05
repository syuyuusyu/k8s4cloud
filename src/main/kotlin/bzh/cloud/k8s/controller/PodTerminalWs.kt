package bzh.cloud.k8s.controller

import bzh.cloud.k8s.config.ClientUtil
import io.kubernetes.client.Exec
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.io.FileReader
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


@Component
class PodTerminalWs(
        val intervalScheduler:Scheduler
) :WebSocketHandler {

    companion object{
        private val log: Logger = LoggerFactory.getLogger(PodTerminalWs::class.java)
        private val sessions = Collections.synchronizedList(ArrayList<String>())
    }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val sessionId = session.id
        if (sessions.add(sessionId)) {
            log.info("Starting WebSocket Session [{}]", sessionId)

            val query= session.handshakeInfo.uri.query
            val parameterMap = HashMap<String,String>()
            Regex("(?:\\&?)([^=]+)\\=([^&]+)").findAll(query).iterator().forEach {
                parameterMap[it.groups[1]!!.value] = it.groups[2]!!.value
            }
            val namespace = parameterMap["ns"]
            val podName = parameterMap["name"]
            val containerName = parameterMap["container"];
            val client = ClientUtil.apiClient()
            val exec = Exec(client)
            val proc = exec.exec(namespace, podName, arrayOf("sh"), containerName,true, true);
            val input=proc.inputStream
            val output = proc.outputStream

            val outFlux= Flux.interval(Duration.ofMillis(100),intervalScheduler)
                    .map {input.available()}.filter { it>0 }
                    .map {
                        val byteArray = ByteArray(it)
                        input.read(byteArray)
                        log.debug("write to terminal:{}",String(byteArray))
                        session.textMessage(String(byteArray))
                    }

            session.receive().doFinally { sig ->
                log.info("Terminating WebSocket Session (client side) sig: [{}], [{}]", sig.name, sessionId)
                input.close()
                output.close()
                proc.destroy()
                session.close()
                sessions.remove(sessionId)
            }.subscribe { inMsg ->
                val bytes = ByteArray(inMsg.payload.readableByteCount())
                inMsg.payload.read(bytes)
                log.debug("Received message from client [{}]: {} ", sessionId,String(bytes) )
                output.write( bytes)
            }

            return session.send(outFlux)
        }
        return Mono.empty()
    }
}