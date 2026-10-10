package com.cdcplatform.model;

import java.util.List;

public record DeliveryBatchPage(List<DeliveryBatch> items, long total, int page, int size, int totalPages) {}
