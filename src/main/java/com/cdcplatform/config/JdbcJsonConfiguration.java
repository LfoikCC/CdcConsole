package com.cdcplatform.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.*;
import java.io.IOException;
import java.sql.Clob;

@Configuration
public class JdbcJsonConfiguration {
    @Bean com.fasterxml.jackson.databind.Module jdbcTextModule() {
        var module = new SimpleModule();
        module.addSerializer(Clob.class, new JsonSerializer<Clob>() {
            @Override public void serialize(Clob value, JsonGenerator output, SerializerProvider provider) throws IOException {
                try { output.writeString(value.getSubString(1, Math.toIntExact(value.length()))); }
                catch (java.sql.SQLException e) { throw new IOException(e); }
            }
        });
        return module;
    }
}
