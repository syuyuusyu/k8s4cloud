package bzh.cloud.k8s.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.boot.json.YamlJsonParser;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;



public class JsonUtil {
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final ObjectMapper yamlMapper =  new ObjectMapper(new YAMLFactory());
	public static String beanToJson(Object obj) {
		StringWriter writer = new StringWriter();

		try {
			JsonGenerator gen = new JsonFactory().createJsonGenerator(writer);
			//objectMapper.disable(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES);
			objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
			objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

			objectMapper.writeValue(gen, obj);
			gen.close();
			writer.flush();
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return writer.toString();
	}
	public static <T> T jsonToBean(String json, Class<T> clazz) {
		try {
			return objectMapper.readValue(json, clazz);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	public static <T> T jsonToBean(String json, TypeReference<T> clazz) {
		try {
			return objectMapper.readValue(json, clazz);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static String yamlToJson(String yamlStr){
		//yamlMapper.findAndRegisterModules();
		try{
			Object obj = yamlMapper.readValue(yamlStr,Object.class);
			return beanToJson(obj);
		} catch (Exception e){
			e.printStackTrace();
			return null;
		}
	}

	public static String jsonToYaml(String jsonStr) throws JsonProcessingException {
		try{
			JsonNode jsonNodeTree = objectMapper.readTree(jsonStr);
			YAMLMapper mapper = new YAMLMapper();
			mapper.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
			String str =  mapper.writeValueAsString(jsonNodeTree);
			System.out.println("str = " + str);
			str= str.replaceFirst("---\n","");
			return str;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static <T> T maptoBean(Map<String,Object> map,Class<T> clazz){
		return jsonToBean(beanToJson(map),clazz);
	}

}
