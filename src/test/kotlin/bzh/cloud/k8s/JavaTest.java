package bzh.cloud.k8s;

import bzh.cloud.k8s.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.List;

public class JavaTest {

    @Test
    public void testjson(){
        String s = "[['sdsdsd',1,2],['efefef',2,4]]";
        List<List<Object>>  list = JsonUtil.jsonToBean(s, new TypeReference<List<List<Object>>>() {});
    }
}
