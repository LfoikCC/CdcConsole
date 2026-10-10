package com.cdcplatform.service.delivery;

import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.DeliveryBatch;

import java.util.List;

public interface TargetBatchWriter extends AutoCloseable {
    BatchWriteResult write(DeliveryBatch batch, List<ChangeEvent> events) throws Exception;
    @Override default void close() throws Exception {}
}
