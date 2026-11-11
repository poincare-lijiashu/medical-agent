package com.medassist.fhir.web;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * FHIR 媒体类型协商与序列化。
 *
 * <p>刻意把「资源模型」与「HTTP 细节」分开：Controller 只声明返回什么资源，
 * 由本类决定 {@code application/fhir+json} 还是 {@code application/fhir+xml}。
 * 序列化交给 HAPI，避免手写 JSON 把必填字段、枚举与日期格式写错。
 *
 * <p>协商规则（与主流 FHIR 服务端一致）：
 * <ol>
 *   <li>{@code _format} 优先于 {@code Accept}；非法值直接 400，不静默回退；</li>
 *   <li>{@code Accept} 同时含 json/xml 或 {@code *&#47;*} 时默认 JSON；</li>
 *   <li>其余情况按 {@code Accept} 里出现的 xml 判定，避免浏览器默认头导致 406。</li>
 * </ol>
 */
@Component
public class FhirFormats {

    public static final String JSON_MEDIA = "application/fhir+json";
    public static final String XML_MEDIA = "application/fhir+xml";

    private final FhirContext context = FhirContext.forR4();
    private final IParser jsonParser = context.newJsonParser();
    private final IParser xmlParser = context.newXmlParser();

    public enum Format {
        JSON(JSON_MEDIA),
        XML(XML_MEDIA);

        private final String mediaType;

        Format(String mediaType) {
            this.mediaType = mediaType;
        }

        public String mediaType() {
            return mediaType;
        }
    }

    public Format negotiate(String formatParam, String acceptHeader) {
        if (formatParam != null && !formatParam.isBlank()) {
            String value = formatParam.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "json", "application/fhir+json", "application/json" -> Format.JSON;
                case "xml", "application/fhir+xml", "application/xml", "text/xml" -> Format.XML;
                default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "不支持的 _format：" + formatParam + "（仅支持 json / xml）");
            };
        }
        if (acceptHeader == null || acceptHeader.isBlank() || acceptHeader.contains("*/*")) {
            return Format.JSON;
        }
        String accept = acceptHeader.toLowerCase(Locale.ROOT);
        boolean xml = accept.contains("xml");
        boolean json = accept.contains("json");
        return xml && !json ? Format.XML : Format.JSON;
    }

    public String encode(IBaseResource resource, Format format) {
        return format == Format.XML
                ? xmlParser.encodeResourceToString(resource)
                : jsonParser.encodeResourceToString(resource);
    }

    public ResponseEntity<String> response(IBaseResource resource, Format format) {
        return response(resource, format, HttpStatus.OK);
    }

    public ResponseEntity<String> response(IBaseResource resource, Format format, HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(format.mediaType() + ";charset=UTF-8"))
                // PHI 不进共享缓存/浏览器缓存
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(encode(resource, format));
    }
}
