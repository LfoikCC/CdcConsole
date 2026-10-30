package com.cdcplatform.service;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CaptureProgressServiceTest {
    @Test void requiresDurableProgressBeyondFenceAndHandlesBinlogRollover(){
        assertThat(CaptureProgressService.reached(Map.of("file","mysql-bin.000009","pos",900),"MYSQL:mysql-bin.000010:100")).isFalse();
        assertThat(CaptureProgressService.reached(Map.of("file","mysql-bin.000010","pos",99),"MYSQL:mysql-bin.000010:100")).isFalse();
        assertThat(CaptureProgressService.reached(Map.of("file","mysql-bin.000010","pos",100),"MYSQL:mysql-bin.000010:100")).isTrue();
        assertThat(CaptureProgressService.reached(Map.of("file","other.000011","pos",100),"MYSQL:mysql-bin.000010:100")).isFalse();
        assertThat(CaptureProgressService.reached(Map.of("file","mysql-bin.000011","pos",100,"snapshot",true),"MYSQL:mysql-bin.000010:100")).isFalse();
    }
    @Test void comparesSqlServerLsnAndPollingRoundsWithoutPretendingTheyAreBinlogs(){
        assertThat(CaptureProgressService.reached(Map.of("commit_lsn","00000027:00000758:0003"),"SQLSERVER:00000027000007580002")).isTrue();
        assertThat(CaptureProgressService.reached(Map.of("commit_lsn","00000027:00000758:0001"),"SQLSERVER:00000027000007580002")).isFalse();
        assertThat(CaptureProgressService.reached(Map.of("poll_started_at",1000),"POLL:1001")).isFalse();
        assertThat(CaptureProgressService.reached(Map.of("poll_started_at",1001),"POLL:1001")).isTrue();
    }
}
