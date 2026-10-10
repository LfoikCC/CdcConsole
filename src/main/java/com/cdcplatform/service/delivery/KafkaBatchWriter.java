package com.cdcplatform.service.delivery;

import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.DeliveryBatch;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.List;
import java.util.Properties;

public class KafkaBatchWriter implements TargetBatchWriter {
    private final TableMapping mapping;
    private final DeliveryPayloadMapper mapper;
    private final ObjectMapper json;
    private final KafkaProducer<String, String> producer;

    public KafkaBatchWriter(TargetConfig target, TableMapping mapping, DeliveryPayloadMapper mapper, ObjectMapper json) {
        this.mapping = mapping; this.mapper = mapper; this.json = json;
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, target.config().get("bootstrapServers"));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all"); properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        this.producer = new KafkaProducer<>(properties);
    }

    @Override public BatchWriteResult write(DeliveryBatch batch, List<ChangeEvent> events) throws Exception {
        for (var event : events) producer.send(new ProducerRecord<>(mapping.targetTable(), event.id().toString(),
                json.writeValueAsString(mapper.transform(event, mapping)))).get();
        producer.flush();
        return new BatchWriteResult(events.size(), events.size());
    }

    @Override public void close() { producer.close(); }
}
