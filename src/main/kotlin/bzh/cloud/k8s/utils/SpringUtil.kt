package ind.syu.kubeIsp.utils

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Component


@Component
object SpringUtil : ApplicationContextAware{

    private val LOG = LoggerFactory.getLogger(SpringUtil::class.java)

    lateinit var context:ApplicationContext

    override fun setApplicationContext(ctx: ApplicationContext) {
        
        ctx.beanDefinitionNames.forEach (LOG::info)

        context=ctx
    }

    fun getBean(name:String) :Any? = context.getBean(name)


}