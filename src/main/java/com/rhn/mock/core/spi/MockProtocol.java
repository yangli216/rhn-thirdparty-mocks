package com.rhn.mock.core.spi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 协议级 Mock 插件：每个三方协议一个 {@code @Component}。
 *
 * <p>只服务管理端点（枚举协议、reset、配置视图）；HTTP 路由不经过本接口，
 * 由各协议自带的 {@code @RestController} 用 Spring 原生路径映射承担。
 */
public interface MockProtocol {
    /** 协议标识：{@code "chs"} / {@code "alipay"} / {@code "wxpay"}。 */
    String id();

    /** 清空本协议内存态并复位运行时可改配置。 */
    void reset();

    /** 本协议运行时可改配置视图。 */
    ObjectNode configView();

    /** 按补丁做部分更新，返回更新后的配置视图。 */
    ObjectNode applyConfig(JsonNode patch);
}
