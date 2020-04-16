package bzh.cloud.k8s.controller

import bzh.cloud.k8s.expansion.*
import bzh.cloud.k8s.service.RegistryService

import org.apache.commons.lang.RandomStringUtils
import org.apache.commons.lang.StringUtils
import org.openapitools.client.api.DefaultApi
import org.openapitools.client.model.Catalog
import org.openapitools.client.model.Tags
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.Part
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.server.*

import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import java.io.File
import java.nio.file.NoSuchFileException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashSet


@RestController
@RequestMapping("/registry")
class RegistryController(
        val localRegistryApi: DefaultApi,
        val registryService: RegistryService
        //val fileService: FileService

) {

    @Value("\${self.tempFileDir}")
    lateinit var tempFileDir: String
    @Value("\${self.officalRegistryUrl}")
    lateinit var officalRegistryUrl:String
    @Value("\${self.registryUrl}")
    lateinit var registryUrl:String



    companion object {
        private val log: Logger = LoggerFactory.getLogger(RegistryController::class.java)
    }

    @GetMapping("/test",produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun test():Mono<String>{
        return Mono.just("aaa").doOnSuccess { log.info("cccc") }.map {
        log.info("bbb")
        "bbb"}
    }


    @GetMapping("/search")
    fun search(@RequestParam(required=false) url:String?,@RequestParam keyword:String,@RequestParam page:Int,@RequestParam pagesize:Int):Mono<Map<String,Any>>{
        var queryUrl = officalRegistryUrl
        if(url!=null){
            queryUrl = url
        }
        return registryService.search(queryUrl,keyword,page,pagesize)
    }

    @GetMapping("/catalog")
    fun catalog():Catalog{
        return localRegistryApi.catalogGet(null)
    }
    private fun doname(name: String,url: String?,username: String?):Pair<String,String>{
        var queryName = name
        var queryUrl = officalRegistryUrl
        if(url!=null) {
            queryUrl = url
        }
        if(username!=null) queryName = "$username/$name"
        return Pair(queryUrl,queryName)
    }
    @GetMapping("/tagList/{name}")
    fun tagList(@PathVariable name: String,@RequestParam(required=false) url:String?,@RequestParam(required=false) username:String?):Mono<Tags>{
        val (queryUrl,queryName) = doname(name,url,username)
        try{
            val a =  localRegistryApi.nameTagsListGet(name,null,null)
            return Mono.just(a)
        }catch (e:Exception){
            e.printStackTrace()
            return  Mono.empty()
        }
    }


    @GetMapping("/dockhub/tagList/{name}")
    fun dockhubtagList(@PathVariable name: String,@RequestParam(required=false) url:String?,@RequestParam(required=false) username:String?):Mono<Tags>{
        val (queryUrl,queryName) = doname(name,url,username)
        return registryService.tagList(queryUrl,queryName)
    }

    @GetMapping("/dockhub/{name}/mountImage/{tag}")
    fun mountImage(@PathVariable name: String,@PathVariable tag:String,@RequestParam(required=false) url:String?,@RequestParam(required=false) username:String?):Mono<DownloadInfo>{
        val (queryUrl,queryName) = doname(name,url,username)
        return registryService.startDownloadOrMount(queryUrl,queryName,tag,SessionProgress.Operation.MOUNT)
    }

    @GetMapping("/dockhub/{name}/startdownload/{tag}")
    fun dockhubstartDownload(@PathVariable name: String, @PathVariable tag: String,@RequestParam(required=false) url:String?,@RequestParam(required=false) username:String?): Mono<DownloadInfo> {
        val (queryUrl,queryName) = doname(name,url,username)
        return registryService.startDownloadOrMount(queryUrl, queryName, tag,SessionProgress.Operation.DOWNLOAD)
    }

    @GetMapping("/dockhub/downloaddetail/{session}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun dockhubdownloaddetail(@PathVariable session: String): Flux<ProcessDetail> = registryService.processdetail(session)

    @GetMapping("/dockhub/downloadimg/{session}", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun dockhubdownloadimg(@PathVariable session: String, response: ServerHttpResponse): Mono<Resource> = registryService.downloadimg( session, response)

    @GetMapping("/{name}/startdownload/{tag}")
    fun startDownload(@PathVariable name: String, @PathVariable tag: String): Mono<DownloadInfo> = registryService.startDownloadOrMount(registryUrl, name, tag,SessionProgress.Operation.DOWNLOAD)

    @GetMapping("/downloaddetail/{session}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun downloaddetail(@PathVariable session: String): Flux<ProcessDetail> = registryService.processdetail(session)

    @GetMapping("/downloadimg/{session}", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun downloadimg(@PathVariable session: String, response: ServerHttpResponse): Mono<Resource> = registryService.downloadimg( session, response)

    @Bean
    fun upload(): RouterFunction<ServerResponse> {
        return RouterFunctions.route(RequestPredicates.POST("/registry/upload").and(RequestPredicates.accept(MediaType.MULTIPART_FORM_DATA)),
                HandlerFunction<ServerResponse> { request ->
                    request.body(BodyExtractors.toMultipartData()).flatMap { parts ->
                        fun err(msg:String):Mono<ServerResponse> = ServerResponse.status(HttpStatus.OK)
                                .body(BodyInserters.fromObject(mapOf("success" to false,"msg" to msg)))
                        val session = RandomStringUtils.randomAlphanumeric(8)
                        val map: Map<String, Part> = parts.toSingleValueMap()
                        val filePart: FilePart = map["file"]!! as FilePart
                        filePart.transferTo(File("$tempFileDir/$session.tar"))
                        try{
                            registryService.decompressUploadFile(session)
                        }catch (e:Exception){
                            e.printStackTrace()
                            return@flatMap err("解压上传文件出错!${e}")
                        }

                        val repositoriesExists = registryService.repositoriesExist(session)
                        val nop = request.queryParam("name")
                        val top = request.queryParam("tag")
                        if(!repositoriesExists && (!nop.isPresent || !top.isPresent)){
                            registryService.clearFile(session)
                            err("上传文件中没有名称和版本信息，必须指定")
                        }else{
                            try{
                                registryService.upload(session,nop.orElse(""),top.orElse(""),null)
                            }catch (e:NoSuchFileException){
                                e.printStackTrace()
                                return@flatMap err("上传文件出错,文件内容缺失${e}")
                            }catch (e:Exception){
                                e.printStackTrace()
                                return@flatMap err("上传文件出错,${e}")
                            }
                            ServerResponse.accepted().body(BodyInserters.fromObject(mapOf(
                                    "success" to true,
                                    "session" to session,
                                    "msg" to "文件已上传到服务器，正在上传到镜像仓库"
                            )))
                        }
                    }
                })
    }

    @GetMapping("/uploaddetail/{session}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun uploaddetail(@PathVariable session: String): Flux<ProcessDetail> = registryService.processdetail(session)

    @GetMapping("/processdetail/{session}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun processdetailx(@PathVariable session: String): Flux<ProcessDetail> = registryService.processdetail(session)

    @DeleteMapping("/{name}/deleteimg/{tag}")
    fun delete(@PathVariable name: String, @PathVariable tag: String):Mono<Void>{
        registryService.delete(name,tag)
        return Mono.empty()
    }



}
