package com.cdcplatform.model.dev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 一条数据流的图定义。
 *
 * <p>坐标是节点左上角在世界坐标里的像素值；画布渲染与坐标完全解耦，所以后端不需要知道缩放或平移。
 * 节点配置保持 {@code Map<String,Object>}：四类节点的字段差异很大，用联合类型反而更啰嗦。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DevGraph(List<DevNode> nodes, List<DevEdge> edges) {

    public DevGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static DevGraph empty() {
        return new DevGraph(List.of(), List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DevNode(String id, String type, Integer x, Integer y, String name, Map<String, Object> config) {

        public int xOrZero() {
            return x == null ? 0 : x;
        }

        public int yOrZero() {
            return y == null ? 0 : y;
        }

        public Map<String, Object> configOrEmpty() {
            return config == null ? Map.of() : config;
        }

        /** 展示名：配置里的表名/算子名优先，其次节点名，最后退回 id。 */
        public String displayName() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            Object table = configOrEmpty().get("table");
            if (table instanceof String text && !text.isBlank()) {
                return text;
            }
            return id;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DevEdge(String from, String to) {
    }
}
