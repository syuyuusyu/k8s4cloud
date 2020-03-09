package bzh.cloud.k8s.controller

import bzh.cloud.k8s.expansion.*
import org.apache.commons.lang.RandomStringUtils
import org.openapitools.client.api.DefaultApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ZeroCopyHttpOutputMessage
import org.springframework.http.codec.multipart.Part
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.server.*

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.File
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


@RestController
@RequestMapping("/registry")
class RegistryController(
        val dockerHubAuthApi: DefaultApi,
        val dockerHubApi: DefaultApi,
        val localRegistryApi: DefaultApi
        //val fileService: FileService

) {
    init {
        log.info(localRegistryApi.apiClient.basePath)
        log.info(dockerHubAuthApi.apiClient.basePath)
        log.info(dockerHubApi.apiClient.basePath)
    }

    @Value("\${self.authRegistryUrl}")
    lateinit var authRegistryUrl: String
    @Value("\${self.tempFileDir}")
    lateinit var tempFileDir: String


    val authService = "registry.docker.io";


    companion object {
        private val log: Logger = LoggerFactory.getLogger(RegistryController::class.java)
        private val downloadSessions = Collections.synchronizedList(ArrayList<String>())
        private val fixedThreadPool: Executor = Executors.newFixedThreadPool(100) { r ->
            val t = Thread(r)
            t.isDaemon = true
            t
        }
        public val downloadListenerMap = Collections.synchronizedMap(HashMap<String, SessionProgress>())
    }

    private fun scope(repository: String = "library", img: String) = "repository:$repository/$img:pull"


    @GetMapping("/dockhub/{name}/startdownload/{tag}")
    fun dockhubstartDownload(@PathVariable name: String, @PathVariable tag: String): Mono<DownloadInfo> {
        val name = name.replace("-", "/")
        val session = RandomStringUtils.randomAlphanumeric(8)
        val token = dockerHubAuthApi.tokenGet(authService, "repository:$name:pull").token
        log.info(token)
        val result = dockerHubApi.getManifests(name, tag, token!!)


        return Mono.just(result).doOnSuccess {
            log.info("doOnSuccess")
            dockerHubApi.createPullFile(name, tag, it.config!!.digest!!, session, token, it.layers!!.map { fest -> fest.digest!! })
            fixedThreadPool.execute {
                it.layers!!.stream().parallel().forEach {
                    dockerHubApi.createLayer(name, it.digest!!, session, token)
                }
            }
        }.map {
            var a = it.layers!!
            DownloadInfo().apply {
                sessionId = session
                //TODO
                detailUrl = "/registry/downloaddetail/$session"
                success = true
                msg = "start download"
                it.layers!!.forEach { m -> digests.add(m.digest!!) }
            }
        }
    }

    @GetMapping("/dockhub/downloaddetail/{session}", produces = [MediaType.APPLICATION_STREAM_JSON_VALUE])
    fun dockhubdownloaddetail(@PathVariable session: String): Flux<DownLoadDetail> {
        val process = downloadListenerMap.get(session)!!
        return Flux.create<DownLoadDetail>(process::initSink)
    }

    @GetMapping("/dockhub/downloadimg/{session}", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun dockhubdownloadimg(@PathVariable session: String, response: ServerHttpResponse): Mono<Void> {
        val flag = dockerHubApi.compressDownFile(session)
        if (!flag) {
            response.statusCode = HttpStatus.NO_CONTENT
            return Mono.empty()
        }
        val zeroCopyResponse = response as ZeroCopyHttpOutputMessage
        //val process = downloadListenerMap.get(session)!!

        val tar: File = File("${dockerHubApi.tempFileDir}$session.tar")
        return zeroCopyResponse.writeWith(tar, 0, tar.length()).doOnSuccess {
            downloadListenerMap.remove(session)
            dockerHubApi.clearFile(session)

        }
    }

    @GetMapping("/{name}/startdownload/{tag}")
    fun startDownload(@PathVariable name: String, @PathVariable tag: String): Mono<DownloadInfo> {
        val name = name.replace("-", "/")
        val session = RandomStringUtils.randomAlphanumeric(8)
//        val token = authApi.tokenGet(authService,"repository:$name:pull").token
//        log.info(token)
        val result = localRegistryApi.getManifests(name, tag, null)


        return Mono.just(result).doOnSuccess {
            log.info("doOnSuccess")
            localRegistryApi.createPullFile(name, tag, it.config!!.digest!!, session, null, it.layers!!.map { fest -> fest.digest!! })
            fixedThreadPool.execute {
                it.layers!!.stream().parallel().forEach {
                    localRegistryApi.createLayer(name, it.digest!!, session, null)
                }
            }
        }.map {
            var a = it.layers!!
            DownloadInfo().apply {
                sessionId = session
                //TODO
                detailUrl = "/registry/downloaddetail/$session"
                success = true
                msg = "start download"
                it.layers!!.forEach { m -> digests.add(m.digest!!) }
            }
        }
    }

    @GetMapping("/downloaddetail/{session}", produces = [MediaType.APPLICATION_STREAM_JSON_VALUE])
    fun downloaddetail(@PathVariable session: String): Flux<DownLoadDetail> {
        val process = downloadListenerMap.get(session)!!
        log.info("layer size:{}", process.map.size)
        return Flux.create<DownLoadDetail>(process::initSink)
    }

    @GetMapping("/downloadimg/{session}", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun downloadimg(@PathVariable session: String, response: ServerHttpResponse): Mono<Void> {
        val flag = localRegistryApi.compressDownFile(session)
        if (!flag) {
            response.statusCode = HttpStatus.NO_CONTENT
            return Mono.empty()
        }
        val zeroCopyResponse = response as ZeroCopyHttpOutputMessage
        //val process = downloadListenerMap.get(session)!!

        val tar: File = File("${localRegistryApi.tempFileDir}$session.tar")
        return zeroCopyResponse.writeWith(tar, 0, tar.length()).doOnSuccess {
            downloadListenerMap.remove(session)
            localRegistryApi.clearFile(session)
        }
    }




    @Bean
    fun upload(): RouterFunction<ServerResponse> {
        return RouterFunctions.route(RequestPredicates.POST("/registry/upload").and(RequestPredicates.accept(MediaType.MULTIPART_FORM_DATA)),
                HandlerFunction<ServerResponse> { request ->
                    request.body(BodyExtractors.toMultipartData()).flatMap { parts ->
                        val session = RandomStringUtils.randomAlphanumeric(8)
                        val map: Map<String, Part> = parts.toSingleValueMap()
                        val filePart: FilePart = map["file"]!! as FilePart
                        // Note cast to "FilePart" above

                        // Save file to disk - in this example, in the "tmp" folder of a *nix system
                        //val fileName = filePart.filename()
                        filePart.transferTo(File("$tempFileDir/$session.tar"))
                        ServerResponse.accepted().body(BodyInserters.fromObject("OK")).doOnSuccess {
                            localRegistryApi.uploadFile(session)
                        }
                    }
                })
    }

}
