package com.rhn.mock.protocol.wxpay;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付 v2 的 XML 编解码。
 *
 * <p>根元素必须是 {@code <xml>}：WxJava 的 {@code BaseWxPayResult.toMap()} 用 XPath {@code /xml/*}
 * 取"元素名-文本"、{@code getXmlValue} 用 {@code /xml/<field>//text()} 读值，根名不匹配会直接解析为空。
 * 因此这里统一用 {@code <xml>} 信封，值一律以 CDATA 输出，避免业务文本里的特殊字符破坏报文。
 * 解析时禁用 DOCTYPE 与外部实体，避免 XXE。
 */
public final class WxpayXml {
    private WxpayXml() {
    }

    /** 按给定顺序输出 {@code <xml><k><![CDATA[v]]></k>…</xml>}。 */
    public static String build(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder("<xml>");
        fields.forEach((key, value) -> builder.append('<').append(key).append("><![CDATA[")
                .append(value == null ? "" : value).append("]]></").append(key).append('>'));
        return builder.append("</xml>").toString();
    }

    /** 解析 {@code <xml>} 根下的直接子元素为有序 map；无法解析时返回空 map。 */
    public static Map<String, String> parse(String xml) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (xml == null || xml.isBlank()) return fields;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList children = document.getDocumentElement().getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node node = children.item(index);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    fields.put(element.getNodeName(), element.getTextContent());
                }
            }
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
        return fields;
    }
}
