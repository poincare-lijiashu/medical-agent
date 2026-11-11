package com.medassist.fhir.web;

import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 检索结果 → FHIR searchset Bundle。
 *
 * <p>分页语义与 HAPI/主流服务端一致：{@code _count} + {@code _offset}，
 * 且只有"还有下一页"时才给 {@code next} 链接——不要给一个永远返回空页的链接，
 * 对端会一直翻到底。
 */
@Component
public class FhirBundleFactory {

    public Bundle search(String resourceType, List<? extends Resource> resources,
                         long total, int count, int offset,
                         FhirCall call, HttpServletRequest request) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.getMeta().setLastUpdatedElement(new InstantType(Instant.now().toString()));
        bundle.setTotal((int) Math.min(total, Integer.MAX_VALUE));

        bundle.addLink().setRelation("self")
                .setUrl(pageUrl(resourceType, call, request, count, offset));
        if ((long) offset + resources.size() < total) {
            bundle.addLink().setRelation("next")
                    .setUrl(pageUrl(resourceType, call, request, count, offset + count));
        }

        for (Resource resource : resources) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setFullUrl(call.baseUrl() + "/" + resourceType + "/"
                    + resource.getIdElement().getIdPart());
            entry.setResource(resource);
            entry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }

    /** 保留原始检索条件，只替换分页参数（_count 已按服务端上限收敛后的生效值）。 */
    private String pageUrl(String resourceType, FhirCall call, HttpServletRequest request,
                           int count, int offset) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(call.baseUrl() + "/" + resourceType);
        request.getParameterMap().forEach((name, values) -> {
            if ("_count".equals(name) || "_offset".equals(name)) {
                return;
            }
            for (String value : values) {
                builder.queryParam(name, value);
            }
        });
        builder.queryParam("_count", count);
        if (offset > 0) {
            builder.queryParam("_offset", offset);
        }
        return builder.build().encode().toUriString();
    }
}
