package com.cdcplatform.service.reconciliation;

import org.springframework.stereotype.Component;

@Component
public class StarRocksReconciliationAdapter extends MySqlReconciliationAdapter {
    @Override public String id() { return "STARROCKS"; }
}
