package bzh.cloud.k8s.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration
@EnableScheduling
class ScheduleService {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ScheduleService::class.java)
    }

    //@Scheduled(fixedRate = 1000)
    fun test(){
        log.info("test log")
    }

}