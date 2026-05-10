package com.cloudinfra.common.core.serializer;

import com.cloudinfra.common.core.annotation.Sensitive;
import com.cloudinfra.common.core.enums.SensitiveStrategy;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;
import java.util.Objects;

/**
 * 脱敏序列化器
 */
public class SensitiveSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private SensitiveStrategy strategy;

    public SensitiveSerializer() {}

    public SensitiveSerializer(SensitiveStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        switch (strategy) {
            case PHONE:
                gen.writeString(value.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2"));
                break;
            case ID_CARD:
                gen.writeString(value.replaceAll("(\\d{6})\\d{8}(\\w{4})", "$1********$2"));
                break;
            case NAME:
                gen.writeString(value.replaceAll("([\\u4e00-\\u9fa5]{1})(.*)", "$1*"));
                break;
            default:
                gen.writeString(value);
        }
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) throws JsonMappingException {
        Sensitive annotation = property.getAnnotation(Sensitive.class);
        if (Objects.nonNull(annotation)) {
            return new SensitiveSerializer(annotation.strategy());
        }
        return prov.findValueSerializer(property.getType(), property);
    }
}
